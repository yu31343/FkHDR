package io.github.fkoscreen.player;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.MediaFormat;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.util.Locale;

/**
 * 极简 HDR 验证播放器（MediaCodec 直出 SurfaceView）。
 *
 * 用途：验证 LSPosed 模块的 HDR 内容识别链路。
 * 刻意 **不调用** window.setColorMode(COLOR_MODE_HDR)，模拟普通第三方播放器。
 */
public class MainActivity extends Activity
        implements PlayerEngine.Listener, SurfaceHolder.Callback {

    private static final String TAG = "HdrProbe";
    private static final String SDR_PATH = "/sdcard/Movies/videotest.mp4";
    private static final String HDR_PATH = "/sdcard/Movies/hdr10_test.mp4";

    private FrameLayout root;
    private SurfaceView surfaceView;
    private TextView statusView;
    private TextView titleView;

    private PlayerEngine engine;
    private MediaSession session;
    private Surface surface;
    private String pendingPath;

    private boolean halfMode = false;

    private String currentFile = "(none)";
    private String engineState = "IDLE";
    private long frames = 0;
    private long ptsUs = 0;
    private String videoInDesc = "(no decoder yet)";
    private String videoInRaw = "(no decoder yet)";
    private String videoOutDesc = "-";
    private String codecName = "-";
    private String audioInDesc = "-";
    private String lastError = "";

    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_FULLSCREEN);

        buildUi();
        engine = new PlayerEngine(this);
        setupMediaSession();
        requestStoragePermissionIfNeeded();
        applyHalfMode(halfMode);
        refreshStatus();
    }

    // ── UI ──────────────────────────────────────────────────
    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        surfaceView = new SurfaceView(this);
        surfaceView.getHolder().addCallback(this);
        root.addView(surfaceView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 半透明 HUD，方便截图取证
        LinearLayout hud = new LinearLayout(this);
        hud.setOrientation(LinearLayout.VERTICAL);
        hud.setBackgroundColor(0xD9000000);
        hud.setPadding(dp(8), dp(6), dp(8), dp(8));

        titleView = new TextView(this);
        titleView.setText("HDR Probe Player\n（HdrProbe / MediaCodec -> SurfaceView）");
        titleView.setTextColor(Color.parseColor("#FFCC66"));
        titleView.setTextSize(11f);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        hud.addView(titleView);

        statusView = new TextView(this);
        statusView.setTextColor(Color.parseColor("#B8FFB8"));
        statusView.setTextSize(9.5f);
        statusView.setTypeface(Typeface.MONOSPACE);
        statusView.setTextIsSelectable(true);
        ScrollView sc = new ScrollView(this);
        LinearLayout.LayoutParams scLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(132));
        scLp.topMargin = dp(2);
        sc.addView(statusView);
        hud.addView(sc, scLp);

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setPadding(0, dp(4), 0, 0);
        Button btnSdr = makeButton("播 SDR", row1);
        Button btnHdr = makeButton("播 HDR10", row1);
        Button btnStop = makeButton("停止", row1);
        hud.addView(row1);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setGravity(Gravity.CENTER_VERTICAL);
        row2.setPadding(0, dp(4), 0, 0);
        Switch sw = new Switch(this);
        sw.setText("半屏模式（SurfaceView 只占左半边）");
        sw.setTextColor(Color.WHITE);
        sw.setTextSize(11f);
        sw.setChecked(halfMode);
        sw.setPadding(dp(4), dp(4), dp(4), dp(4));
        sw.setOnCheckedChangeListener((buttonView, isChecked) -> {
            halfMode = isChecked;
            applyHalfMode(halfMode);
            refreshStatus();
            Log.i(TAG, "half mode = " + halfMode);
        });
        row2.addView(sw, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        hud.addView(row2);

        FrameLayout.LayoutParams hudLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hudLp.gravity = Gravity.BOTTOM;
        root.addView(hud, hudLp);

        setContentView(root);

        btnSdr.setOnClickListener(v -> playFile(SDR_PATH));
        btnHdr.setOnClickListener(v -> playFile(HDR_PATH));
        btnStop.setOnClickListener(v -> doStop());
    }

    private Button makeButton(String text, LinearLayout parent) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(13f);
        b.setAllCaps(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, dp(46), 1f);
        lp.setMargins(dp(2), 0, dp(2), 0);
        parent.addView(b, lp);
        return b;
    }

    private void applyHalfMode(boolean half) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                half ? getResources().getDisplayMetrics().widthPixels / 2
                        : ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
        lp.gravity = half ? (Gravity.TOP | Gravity.START) : Gravity.FILL;
        surfaceView.setLayoutParams(lp);
        Log.i(TAG, "layout: " + (half ? "HALF(surface=" + lp.width + "px)" : "FULL"));
    }

    // ── MediaSession（模块靠它认「前台应用在放媒体」）────────
    private void setupMediaSession() {
        try {
            session = new MediaSession(this, "HdrProbePlayer");
            session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                    | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
            session.setCallback(new MediaSession.Callback() {
                @Override
                public void onPlay() {
                    Log.i(TAG, "session onPlay");
                }

                @Override
                public void onPause() {
                    Log.i(TAG, "session onPause");
                }

                @Override
                public void onStop() {
                    Log.i(TAG, "session onStop");
                    ui.post(MainActivity.this::doStop);
                }
            });
            session.setActive(true);
            updateSessionState(PlaybackState.STATE_NONE);
            Log.i(TAG, "MediaSession active (tag=HdrProbePlayer)");
        } catch (Throwable t) {
            Log.e(TAG, "MediaSession setup failed: " + t, t);
        }
    }

    private void updateSessionState(int state) {
        if (session == null) return;
        try {
            PlaybackState.Builder pb = new PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                            | PlaybackState.ACTION_STOP | PlaybackState.ACTION_PLAY_PAUSE);
            pb.setState(state, state == PlaybackState.STATE_PLAYING ? ptsUs : 0L, 1.0f);
            session.setPlaybackState(pb.build());
            Log.i(TAG, "session playbackState -> " + state);
        } catch (Throwable t) {
            Log.w(TAG, "setPlaybackState failed: " + t);
        }
    }

    // ── 播放控制 ────────────────────────────────────────────
    private void playFile(String path) {
        currentFile = path;
        frames = 0;
        ptsUs = 0;
        videoInDesc = "(preparing...)";
        videoInRaw = "(preparing...)";
        codecName = "-";
        videoOutDesc = "-";
        audioInDesc = "-";
        lastError = "";
        refreshStatus();

        // 停止状态下 SurfaceView 被隐藏过，先恢复
        if (surfaceView.getVisibility() != View.VISIBLE) {
            surfaceView.setVisibility(View.VISIBLE);
        }
        if (surface != null && surface.isValid()) {
            startEngine(surface, path);
        } else {
            pendingPath = path;
            engineState = "WAIT_SURFACE";
            refreshStatus();
        }
    }

    private void startEngine(Surface s, String path) {
        engine.start(s, path);
        updateSessionState(PlaybackState.STATE_PLAYING);
        refreshStatus();
    }

    private void doStop() {
        engine.stop();
        updateSessionState(PlaybackState.STATE_STOPPED);
        // 清屏：把 SurfaceView 图层撤掉，避免上一帧的 HDR 静态元数据残留在 SF 图层里
        surfaceView.setVisibility(View.INVISIBLE);
        engineState = "STOPPED";
        refreshStatus();
    }

    // ── SurfaceHolder.Callback ─────────────────────────────
    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        Log.i(TAG, "surfaceCreated " + holder.getSurfaceFrame());
        surface = holder.getSurface();
        if (pendingPath != null) {
            String p = pendingPath;
            pendingPath = null;
            startEngine(surface, p);
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        Log.i(TAG, "surfaceChanged " + width + "x" + height);
        surface = holder.getSurface();
        refreshStatus();
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        Log.i(TAG, "surfaceDestroyed");
        surface = null;
    }

    // ── PlayerEngine.Listener ──────────────────────────────
    @Override
    public void onState(String state) {
        engineState = state;
        ui.post(this::refreshStatus);
    }

    @Override
    public void onVideoInputFormat(MediaFormat f) {
        videoInDesc = describe(f);
        videoInRaw = f == null ? "null" : f.toString();
        ui.post(this::refreshStatus);
    }

    @Override
    public void onVideoCodec(String name) {
        codecName = name;
        ui.post(this::refreshStatus);
    }

    @Override
    public void onVideoOutputFormat(MediaFormat f) {
        videoOutDesc = describe(f);
        ui.post(this::refreshStatus);
    }

    @Override
    public void onAudioInputFormat(MediaFormat f) {
        audioInDesc = describe(f);
        ui.post(this::refreshStatus);
    }

    @Override
    public void onProgress(long frameCount, long lastPts) {
        frames = frameCount;
        ptsUs = lastPts;
        ui.post(this::refreshStatus);
    }

    @Override
    public void onError(String msg) {
        lastError = msg;
        ui.post(this::refreshStatus);
    }

    // ── 状态文本 ────────────────────────────────────────────
    private void refreshStatus() {
        StringBuilder sb = new StringBuilder();
        sb.append("FILE : ").append(currentFile).append('\n');
        sb.append("STATE: ").append(engineState)
                .append("   frames=").append(frames)
                .append("   pts=").append(ptsUs / 1000).append("ms\n");
        sb.append("CODEC: ").append(codecName).append('\n');
        sb.append("MODE : ").append(halfMode ? "HALF(left half)" : "FULL")
                .append("   surface=").append(surface != null ? "ok" : "null").append('\n');
        sb.append("DECODE(IN) : ").append(videoInDesc).append('\n');
        sb.append("DECODE(OUT): ").append(videoOutDesc).append('\n');
        sb.append("AUDIO      : ").append(audioInDesc).append('\n');
        sb.append("RAW FORMAT : ").append(videoInRaw).append('\n');
        if (!lastError.isEmpty()) sb.append("ERROR: ").append(lastError);
        statusView.setText(sb.toString());
    }

    /** 把 MediaFormat 关键字段打成一行：mime / profile / level / 尺寸 / transfer / standard / hdr-static-info */
    private static Integer i(MediaFormat f, String k) {
        try {
            return f.containsKey(k) ? f.getInteger(k) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Long lng(MediaFormat f, String k) {
        try {
            return f.containsKey(k) ? f.getLong(k) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Float flt(MediaFormat f, String k) {
        try {
            return f.containsKey(k) ? f.getFloat(k) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String describe(MediaFormat f) {
        if (f == null) return "null";
        StringBuilder sb = new StringBuilder();
        String mime = "?";
        try {
            mime = f.containsKey(MediaFormat.KEY_MIME) ? f.getString(MediaFormat.KEY_MIME) : "?";
        } catch (Throwable ignored) {
        }
        sb.append(mime);

        Integer w = i(f, MediaFormat.KEY_WIDTH);
        Integer h = i(f, MediaFormat.KEY_HEIGHT);
        if (w != null && h != null) sb.append("  ").append(w).append('x').append(h);

        Integer prof = i(f, MediaFormat.KEY_PROFILE);
        if (prof != null) {
            sb.append("  profile=").append(prof).append('(').append(profileName(mime, prof)).append(')');
        }
        Integer level = i(f, MediaFormat.KEY_LEVEL);
        if (level != null) sb.append("  level=").append(level);

        Integer trc = i(f, MediaFormat.KEY_COLOR_TRANSFER);
        if (trc != null) sb.append("  color-transfer=").append(trc).append('(').append(transferName(trc)).append(')');
        Integer std = i(f, MediaFormat.KEY_COLOR_STANDARD);
        if (std != null) sb.append("  color-standard=").append(std).append('(').append(standardName(std)).append(')');
        Integer range = i(f, MediaFormat.KEY_COLOR_RANGE);
        if (range != null) sb.append("  range=").append(range == MediaFormat.COLOR_RANGE_FULL ? "FULL" : "LIMITED");
        Integer prim = i(f, "color-primaries");
        if (prim != null) sb.append("  primaries=").append(prim);

        try {
            if (f.containsKey(MediaFormat.KEY_HDR_STATIC_INFO)) {
                java.nio.ByteBuffer bb = f.getByteBuffer(MediaFormat.KEY_HDR_STATIC_INFO);
                sb.append("  hdr-static-info=").append(bb == null ? "null" : bb.remaining() + "B");
            } else {
                sb.append("  hdr-static-info=absent");
            }
        } catch (Throwable t) {
            sb.append("  hdr-static-info=err");
        }

        Float fps = flt(f, MediaFormat.KEY_FRAME_RATE);
        if (fps != null) sb.append(String.format(Locale.US, "  fps=%.2f", fps));
        Long dur = lng(f, MediaFormat.KEY_DURATION);
        if (dur != null) sb.append("  dur=").append(dur / 1000).append("ms");
        Integer sr = i(f, MediaFormat.KEY_SAMPLE_RATE);
        if (sr != null) sb.append("  sr=").append(sr);
        Integer ch = i(f, MediaFormat.KEY_CHANNEL_COUNT);
        if (ch != null) sb.append("  ch=").append(ch);
        return sb.toString();
    }

    private static String profileName(String mime, int p) {
        if (mime == null) return "?";
        if (mime.startsWith("video/hevc")) {
            switch (p) {
                case 1: return "Main";
                case 2: return "Main10";
                case 4: return "MainStill";
                case 4096: return "Main10HDR10";        // HEVCProfileMain10HDR10
                case 8192: return "Main10HDR10Plus";    // HEVCProfileMain10HDR10Plus
                default: return "hevc#" + p;
            }
        }
        if (mime.startsWith("video/avc")) {
            switch (p) {
                case 1: return "Baseline";
                case 2: return "Main";
                case 4: return "Extended";
                case 8: return "High";
                default: return "avc#" + p;
            }
        }
        if (mime.startsWith("video/av01")) return "av1#" + p;
        return String.valueOf(p);
    }

    private static String transferName(int t) {
        if (t == MediaFormat.COLOR_TRANSFER_SDR_VIDEO) return "SDR_VIDEO";
        if (t == MediaFormat.COLOR_TRANSFER_ST2084) return "ST2084/PQ = HDR10";
        if (t == MediaFormat.COLOR_TRANSFER_HLG) return "HLG";
        if (t == MediaFormat.COLOR_TRANSFER_LINEAR) return "LINEAR";
        return "?";
    }

    private static String standardName(int s) {
        if (s == MediaFormat.COLOR_STANDARD_BT709) return "BT709";
        if (s == MediaFormat.COLOR_STANDARD_BT601_NTSC) return "BT601_NTSC";
        if (s == MediaFormat.COLOR_STANDARD_BT601_PAL) return "BT601_PAL";
        if (s == MediaFormat.COLOR_STANDARD_BT2020) return "BT2020";
        return "?";
    }

    // ── 权限 ────────────────────────────────────────────────
    private void requestStoragePermissionIfNeeded() {
        String perm = (Build.VERSION.SDK_INT >= 33)
                ? Manifest.permission.READ_MEDIA_VIDEO
                : Manifest.permission.READ_EXTERNAL_STORAGE;
        if (checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "permission " + perm + " not granted, requesting");
            try {
                requestPermissions(new String[]{perm}, 1001);
            } catch (Throwable t) {
                Log.w(TAG, "requestPermissions failed: " + t);
            }
        } else {
            Log.i(TAG, "permission " + perm + " granted");
        }
    }

    @Override
    protected void onDestroy() {
        try {
            engine.stop();
        } catch (Throwable ignored) {
        }
        if (session != null) {
            try {
                session.setActive(false);
                session.release();
            } catch (Throwable ignored) {
            }
            session = null;
        }
        super.onDestroy();
    }
}