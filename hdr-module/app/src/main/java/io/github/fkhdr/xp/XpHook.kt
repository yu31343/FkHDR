package io.github.fkhdr.xp

import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Member

/**
 * 兼容层：把旧 XposedBridge 的「before/after 回调」模型映射到 libxposed API 102 的拦截器链。
 *
 * 迁移背景：targetApiVersion = 102 的模块不能再引用 de.robv.android.xposed 的任何类
 * （XposedInterface.API_102 的 behavior change 明确写了这一点），所以这里自己实现一套
 * 同形状的最小 API，业务代码只改 import 与类名，hook 语义保持不变。
 */
abstract class XpHook {
    open fun beforeHookedMethod(param: MethodHookParam) {}

    open fun afterHookedMethod(param: MethodHookParam) {}

    /**
     * 单次调用的可变状态，对齐 de.robv.android.xposed.XC_MethodHook.MethodHookParam：
     * thisObject / args / result / throwable / extras 均可读写。
     */
    class MethodHookParam internal constructor(
        val method: Member,
        var thisObject: Any?,
        var args: Array<Any?>,
    ) {
        private var resultValue: Any? = null
        private var throwable: Throwable? = null
        private var extras: HashMap<String, Any?>? = null

        /** true = 仍在 before 阶段：此时给 result 赋值表示「跳过原方法」 */
        internal var inBefore = true

        /** before 阶段给 result 赋值产生的短路标记 */
        internal var returnEarly = false

        /**
         * 对齐 XC_MethodHook.MethodHookParam#getResult/#setResult 的语义：
         * before 阶段赋值 = 短路原方法直接返回该值；after 阶段赋值 = 替换返回值。
         */
        var result: Any?
            get() = resultValue
            set(value) {
                resultValue = value
                if (inBefore) returnEarly = true
            }

        fun hasThrowable(): Boolean = throwable != null

        fun getThrowable(): Throwable? = throwable

        fun setThrowable(t: Throwable?) {
            throwable = t
        }

        fun getObjectExtra(name: String): Any? = extras?.get(name)

        fun setObjectExtra(name: String, value: Any?) {
            val map = extras ?: HashMap<String, Any?>().also { extras = it }
            map[name] = value
        }
    }
}

/** libxposed Hooker 适配器：before → proceed → after。 */
internal class XpHookBridge(private val hook: XpHook) : XposedInterface.Hooker {
    override fun intercept(chain: XposedInterface.Chain): Any? {
        val executable = chain.getExecutable()
        val param = XpHook.MethodHookParam(
            executable,
            chain.getThisObject(),
            chain.getArgs().toTypedArray(),
        )

        try {
            hook.beforeHookedMethod(param)
        } catch (t: Throwable) {
            // 旧 XposedBridge 会吞掉 before 里的异常并继续调用原方法，这里保持一致
            Xp.reportHookError("before", executable, t)
            param.returnEarly = false
        }

        if (param.returnEarly) {
            param.getThrowable()?.let { throw it }
            return param.result
        }

        val result: Any?
        try {
            result = chain.proceed(param.args)
        } catch (t: Throwable) {
            param.inBefore = false
            param.setThrowable(t)
            runAfter(param, executable)
            throw param.getThrowable() ?: t
        }

        param.inBefore = false
        param.result = result
        // 构造器 hook：对象在执行 <init> 时才真正可见，proceed 之后再取一次
        param.thisObject = chain.getThisObject() ?: param.thisObject
        runAfter(param, executable)

        param.getThrowable()?.let { throw it }
        return param.result
    }

    private fun runAfter(param: XpHook.MethodHookParam, executable: Member) {
        try {
            hook.afterHookedMethod(param)
        } catch (t: Throwable) {
            Xp.reportHookError("after", executable, t)
        }
    }
}
