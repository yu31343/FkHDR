package io.github.fkhdr.xp

import android.util.Log
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Executable
import java.lang.reflect.Member
import java.lang.reflect.Method

/**
 * libxposed API 102 的模块级门面：日志 + hook 安装。
 *
 * 只做两件事：
 *  1) 保存框架在 attachFramework() 之后注入的 XposedInterface；
 *  2) 把 hookAllMethods / hookAllConstructors / hookMethod 翻译成 HookBuilder + Hooker。
 *
 * API 102 模块不允许再调用 de.robv.android.xposed 的 legacy API，所以这里不引用任何 legacy 类。
 */
object Xp {
    private const val LOG_TAG = "FkHDR"

    @Volatile
    private var framework: XposedInterface? = null

    fun attach(iface: XposedInterface) {
        framework = iface
    }

    fun log(msg: String) {
        val f = framework
        if (f == null) {
            Log.i(LOG_TAG, msg)
            return
        }
        try {
            f.log(Log.INFO, LOG_TAG, msg)
        } catch (_: Throwable) {
            Log.i(LOG_TAG, msg)
        }
    }

    internal fun reportHookError(phase: String, executable: Member, t: Throwable) {
        log(LOG_TAG + ": hook " + phase + " failed at " + executable + ": " + t)
    }

    /** 等价于 XposedBridge.hookAllMethods；返回的列表长度就是真正挂上的方法数。 */
    fun hookAllMethods(cls: Class<*>, methodName: String, hook: XpHook): List<XposedInterface.HookHandle> {
        val f = framework ?: return emptyList()
        val handles = ArrayList<XposedInterface.HookHandle>(2)
        for (m in methodsByName(cls, methodName)) {
            try {
                handles.add(install(f, m, hook))
            } catch (t: Throwable) {
                log(LOG_TAG + ": hook " + methodName + " failed: " + t)
            }
        }
        return handles
    }

    /** 等价于 XposedBridge.hookAllConstructors。 */
    fun hookAllConstructors(cls: Class<*>, hook: XpHook): List<XposedInterface.HookHandle> {
        val f = framework ?: return emptyList()
        val handles = ArrayList<XposedInterface.HookHandle>(4)
        for (c in cls.declaredConstructors) {
            try {
                handles.add(install(f, c, hook))
            } catch (t: Throwable) {
                log(LOG_TAG + ": hook constructor of " + cls.name + " failed: " + t)
            }
        }
        return handles
    }

    fun hookMethod(member: Member, hook: XpHook): XposedInterface.HookHandle? {
        val f = framework ?: return null
        return try {
            install(f, member as Executable, hook)
        } catch (t: Throwable) {
            log(LOG_TAG + ": hook " + member + " failed: " + t)
            null
        }
    }

    private fun install(
        f: XposedInterface,
        executable: Executable,
        hook: XpHook,
    ): XposedInterface.HookHandle {
        // id 需要同时满足两件事：
        //   ① 同一个方法上两个不同的钩子必须能共存（否则后装的会原子替换掉先装的）；
        //   ② 同一处源码在 init 重跑时再次安装必须幂等（不能叠两层）。
        // 用 hook 匿名类的名字做后缀：每个 hook 源码点一个稳定类名，跨调用点必然不同。
        val id = "fkhdr:" + executable.declaringClass.name + "#" + executable.name +
            "(" + executable.parameterTypes.joinToString(",") { it.name } + ")@" + hook.javaClass.name
        return f.hook(executable)
            .setId(id)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept(XpHookBridge(hook))
    }

    /**
     * 先查本类，本类没有同名方法再往父类找（对齐 hookAllMethods 的行为，
     * 但不会把被子类 override 的父类方法重复挂一遍）。
     */
    private fun methodsByName(cls: Class<*>, name: String): List<Method> {
        var c: Class<*>? = cls
        while (c != null && c != Any::class.java) {
            val found = c.declaredMethods.filter { it.name == name }
            if (found.isNotEmpty()) return found
            c = c.superclass
        }
        return emptyList()
    }
}
