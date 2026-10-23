package io.github.fkoscreen.player;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.util.Log;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 极简 MediaCodec 播放器：MediaExtractor -> MediaCodec -> SurfaceView，另带一条 AAC 音轨。
 *
 * 目的：给 LSPosed 模块的 HDR 识别链路造一个「可控、可复现」的播放源。
 * 刻意不做任何 setColorMode(COLOR_MODE_HDR) —— 模拟普通第三方播放器。
 */
public class PlayerEngine {

    public static final String TAG = "HdrProbe";

    public interface Listener {
        void onState(String state);

        void onVideoCodec(String name);

        void onVideoInputFormat(MediaFormat f);

        void onVideoOutputFormat(MediaFormat f);

        void onAudioInputFormat(MediaFormat f);

        void onProgress(long frames, long ptsUs);

        void onError(String msg);
    }

    private final Listener listener;

    private volatile boolean running;
    private Thread vThread;
    private Thread aThread;
    private MediaExtractor vEx;
    private MediaExtractor aEx;
    private MediaCodec vCodec;
    private MediaCodec aCodec;
    private AudioTrack audioTrack;
    private int pcmEncoding = AudioFormat.ENCODING_PCM_16BIT;

    private volatile long frameCount;
    private volatile long lastPtsUs;
    private volatile String state = "IDLE";
    private String path = "";

    public PlayerEngine(Listener l) {
        this.listener = l;
    }

    public String getPath() {
        return path;
    }

    public long getFrameCount() {
        return frameCount;
    }

    public long getLastPtsUs() {
        return lastPtsUs;
    }

    public String getState() {
        return state;
    }

    public synchronized void start(Surface surface, String file) {
        stopInternal(false);
        path = file;
        frameCount = 0;
        lastPtsUs = 0;
        setState("PREPARING");
        try {
            vEx = new MediaExtractor();
            vEx.setDataSource(file);
            int vTrack = findTrack(vEx, "video/");
            if (vTrack < 0) throw new IOException("no video track in " + file);
            vEx.selectTrack(vTrack);
            MediaFormat vf = vEx.getTrackFormat(vTrack);
            Log.i(TAG, "VIDEO INPUT FORMAT = " + vf);
            Log.i(TAG, "VIDEO INPUT raw      = " + vf.toString());
            listener.onVideoInputFormat(vf);

            String mime = vf.getString(MediaFormat.KEY_MIME);
            vCodec = MediaCodec.createDecoderByType(mime);
            vCodec.configure(vf, surface, null, 0);
            vCodec.start();
            Log.i(TAG, "video decoder started, mime=" + mime + " codec=" + vCodec.getName());
            listener.onVideoCodec(vCodec.getName());

            // ── 音轨（HDR 测试片无音轨，SDR 片有 AAC）──
            try {
                aEx = new MediaExtractor();
                aEx.setDataSource(file);
                int aTrack = findTrack(aEx, "audio/");
                if (aTrack >= 0) {
                    aEx.selectTrack(aTrack);
                    MediaFormat af = aEx.getTrackFormat(aTrack);
                    Log.i(TAG, "AUDIO INPUT FORMAT = " + af);
                    listener.onAudioInputFormat(af);
                    aCodec = MediaCodec.createDecoderByType(af.getString(MediaFormat.KEY_MIME));
                    aCodec.configure(af, null, null, 0);
                    aCodec.start();
                } else {
                    Log.i(TAG, "no audio track in source, video only");
                    aEx.release();
                    aEx = null;
                }
            } catch (Throwable t) {
                Log.w(TAG, "audio setup failed: " + t);
                releaseAudio();
            }

            running = true;
            vThread = new Thread(this::videoLoop, "vp-video");
            vThread.start();
            if (aCodec != null) {
                aThread = new Thread(this::audioLoop, "vp-audio");
                aThread.start();
            }
            setState("PLAYING");
        } catch (Throwable t) {
            Log.e(TAG, "start failed: " + t, t);
            listener.onError("start failed: " + t);
            releaseAll();
            setState("ERROR");
        }
    }

    public synchronized void stop() {
        Log.i(TAG, "stop() requested");
        stopInternal(true);
    }

    public synchronized boolean isRunning() {
        return running;
    }

    private void stopInternal(boolean notify) {
        boolean wasRunning = running;
        running = false;
        Thread vt = vThread;
        Thread at = aThread;
        vThread = null;
        aThread = null;
        if (vt != null) {
            try {
                vt.join(2000);
            } catch (InterruptedException ignored) {
            }
        }
        if (at != null) {
            try {
                at.join(2000);
            } catch (InterruptedException ignored) {
            }
        }
        releaseAll();
        if (notify || wasRunning) setState("STOPPED");
    }

    // ── 视频解码循环 ────────────────────────────────────────
    private void videoLoop() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean inputDone = false;
        long clockStartNs = -1L;
        long frames = 0;
        try {
            while (running) {
                if (!inputDone) {
                    int inIdx = vCodec.dequeueInputBuffer(10000);
                    if (inIdx >= 0) {
                        ByteBuffer ib = vCodec.getInputBuffer(inIdx);
                        int size = (ib == null) ? -1 : vEx.readSampleData(ib, 0);
                        if (size < 0) {
                            vCodec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                            Log.i(TAG, "video input EOS queued, frames=" + frames);
                        } else {
                            long pts = vEx.getSampleTime();
                            vCodec.queueInputBuffer(inIdx, 0, size, pts, 0);
                            vEx.advance();
                        }
                    }
                }

                int outIdx = vCodec.dequeueOutputBuffer(info, 10000);
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat of = vCodec.getOutputFormat();
                    Log.i(TAG, "VIDEO OUTPUT FORMAT = " + of);
                    listener.onVideoOutputFormat(of);
                } else if (outIdx == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    // 老 API，忽略
                } else if (outIdx >= 0) {
                    boolean render = info.size > 0;
                    if (render) {
                        // 按 PTS 自校正节奏：睡眠发生在 release 之前，保持真实时长
                        long nowNs = System.nanoTime();
                        long ptsNs = info.presentationTimeUs * 1000L;
                        if (clockStartNs < 0) clockStartNs = nowNs - ptsNs;
                        long waitMs = (clockStartNs + ptsNs - nowNs) / 1000000L;
                        if (waitMs > 1) {
                            if (waitMs > 2000) waitMs = 2000;
                            sleepInterruptible(waitMs);
                            if (!running) {
                                vCodec.releaseOutputBuffer(outIdx, false);
                                break;
                            }
                        }
                        frames++;
                        frameCount = frames;
                        lastPtsUs = info.presentationTimeUs;
                        if (frames % 30 == 0) listener.onProgress(frames, info.presentationTimeUs);
                    }
                    vCodec.releaseOutputBuffer(outIdx, render);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0 && inputDone) {
                        Log.i(TAG, "video EOS reached, total frames=" + frames);
                        setState("ENDED");
                        break;
                    }
                }
            }
        } catch (Throwable t) {
            if (running) {
                Log.e(TAG, "video loop error: " + t, t);
                listener.onError("video loop: " + t);
            }
        }
    }

    // ── 音频解码循环 ────────────────────────────────────────
    private void audioLoop() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean inputDone = false;
        try {
            while (running) {
                if (!inputDone) {
                    int inIdx = aCodec.dequeueInputBuffer(10000);
                    if (inIdx >= 0) {
                        ByteBuffer ib = aCodec.getInputBuffer(inIdx);
                        int size = (ib == null) ? -1 : aEx.readSampleData(ib, 0);
                        if (size < 0) {
                            aCodec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            long pts = aEx.getSampleTime();
                            aCodec.queueInputBuffer(inIdx, 0, size, pts, 0);
                            aEx.advance();
                        }
                    }
                }
                int outIdx = aCodec.dequeueOutputBuffer(info, 10000);
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat of = aCodec.getOutputFormat();
                    Log.i(TAG, "AUDIO OUTPUT FORMAT = " + of);
                    setupAudioTrack(of);
                } else if (outIdx >= 0) {
                    if (info.size > 0 && audioTrack != null) {
                        ByteBuffer ob = aCodec.getOutputBuffer(outIdx);
                        if (ob != null) {
                            ob.position(info.offset);
                            ob.limit(info.offset + info.size);
                            writeAudio(ob);
                        }
                    }
                    aCodec.releaseOutputBuffer(outIdx, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
                }
            }
        } catch (Throwable t) {
            if (running) Log.e(TAG, "audio loop error: " + t, t);
        }
    }

    private void setupAudioTrack(MediaFormat of) {
        try {
            int sr = of.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                    ? of.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44100;
            int ch = of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                    ? of.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 2;
            pcmEncoding = of.containsKey(MediaFormat.KEY_PCM_ENCODING)
                    ? of.getInteger(MediaFormat.KEY_PCM_ENCODING) : AudioFormat.ENCODING_PCM_16BIT;
            int chanMask = (ch >= 2) ? AudioFormat.CHANNEL_OUT_STEREO : AudioFormat.CHANNEL_OUT_MONO;
            int minBuf = AudioTrack.getMinBufferSize(sr, chanMask, pcmEncoding);
            if (minBuf <= 0) minBuf = 16384;
            int bufSize = Math.max(minBuf * 3, 32768);

            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build();
            AudioFormat afmt = new AudioFormat.Builder()
                    .setEncoding(pcmEncoding)
                    .setSampleRate(sr)
                    .setChannelMask(chanMask)
                    .build();
            audioTrack = new AudioTrack.Builder()
                    .setAudioAttributes(attrs)
                    .setAudioFormat(afmt)
                    .setBufferSizeInBytes(bufSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
            audioTrack.play();
            Log.i(TAG, "AudioTrack ready sr=" + sr + " ch=" + ch + " pcm=" + pcmEncoding
                    + " buf=" + bufSize);
        } catch (Throwable t) {
            Log.e(TAG, "AudioTrack setup failed: " + t, t);
            audioTrack = null;
        }
    }

    private void writeAudio(ByteBuffer buf) {
        try {
            if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
                int n = buf.remaining() / 4;
                float[] f = new float[n];
                buf.order(ByteOrder.nativeOrder()).asFloatBuffer().get(f);
                audioTrack.write(f, 0, n, AudioTrack.WRITE_BLOCKING);
            } else {
                int n = buf.remaining();
                byte[] b = new byte[n];
                buf.get(b);
                audioTrack.write(b, 0, n); // 阻塞写 = 天然 A/V 节奏
            }
        } catch (Throwable t) {
            Log.w(TAG, "audio write failed: " + t);
        }
    }

    // ── 工具 ────────────────────────────────────────────────
    private static int findTrack(MediaExtractor ex, String prefix) {
        for (int i = 0; i < ex.getTrackCount(); i++) {
            MediaFormat f = ex.getTrackFormat(i);
            String mime = f.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith(prefix)) return i;
        }
        return -1;
    }

    private void sleepInterruptible(long ms) {
        long end = System.currentTimeMillis() + Math.max(0, ms);
        while (running) {
            long left = end - System.currentTimeMillis();
            if (left <= 0) return;
            try {
                Thread.sleep(Math.min(50L, Math.max(1L, left)));
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private void releaseAll() {
        releaseVideo();
        releaseAudio();
    }

    private void releaseVideo() {
        MediaCodec c = vCodec;
        vCodec = null;
        if (c != null) {
            try {
                c.stop();
            } catch (Throwable ignored) {
            }
            try {
                c.release();
            } catch (Throwable ignored) {
            }
        }
        MediaExtractor e = vEx;
        vEx = null;
        if (e != null) {
            try {
                e.release();
            } catch (Throwable ignored) {
            }
        }
    }

    private void releaseAudio() {
        MediaCodec c = aCodec;
        aCodec = null;
        if (c != null) {
            try {
                c.stop();
            } catch (Throwable ignored) {
            }
            try {
                c.release();
            } catch (Throwable ignored) {
            }
        }
        MediaExtractor e = aEx;
        aEx = null;
        if (e != null) {
            try {
                e.release();
            } catch (Throwable ignored) {
            }
        }
        AudioTrack t = audioTrack;
        audioTrack = null;
        if (t != null) {
            try {
                t.stop();
            } catch (Throwable ignored) {
            }
            try {
                t.release();
            } catch (Throwable ignored) {
            }
        }
    }

    private void setState(String s) {
        state = s;
        Log.i(TAG, "STATE = " + s);
        listener.onState(s);
    }
}
