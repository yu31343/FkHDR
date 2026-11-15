package io.github.fkhdr.xp

import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * 反射工具：替代 legacy 的 XposedHelpers。
 *
 * 语义保持「按名字找 + 自动匹配参数」：沿类继承链找方法/字段，忽略访问修饰符，
 * 参数按装箱类型 + 数值拓宽匹配（int→float、float→double 等）。
 */
object XpHelpers {
    fun findClass(name: String, classLoader: ClassLoader): Class<*> =
        Class.forName(name, false, classLoader)

    fun callMethod(obj: Any?, methodName: String, vararg args: Any?): Any? {
        val target = obj ?: throw NullPointerException("callMethod on null: " + methodName)
        val m = findMethod(target.javaClass, methodName, args)
            ?: throw NoSuchMethodException(methodName + " on " + target.javaClass.name)
        return m.invoke(target, *args)
    }

    fun callStaticMethod(cls: Class<*>, methodName: String, vararg args: Any?): Any? {
        val m = findMethod(cls, methodName, args)
            ?: throw NoSuchMethodException(methodName + " on " + cls.name)
        return m.invoke(null, *args)
    }

    fun getObjectField(obj: Any?, name: String): Any? = fieldOf(requireObj(obj, name).javaClass, name).get(obj)

    fun setObjectField(obj: Any?, name: String, value: Any?) {
        fieldOf(requireObj(obj, name).javaClass, name).set(obj, value)
    }

    fun getBooleanField(obj: Any?, name: String): Boolean = fieldOf(requireObj(obj, name).javaClass, name).getBoolean(obj)

    fun setBooleanField(obj: Any?, name: String, value: Boolean) {
        fieldOf(requireObj(obj, name).javaClass, name).setBoolean(obj, value)
    }

    fun getIntField(obj: Any?, name: String): Int = fieldOf(requireObj(obj, name).javaClass, name).getInt(obj)

    fun setIntField(obj: Any?, name: String, value: Int) {
        fieldOf(requireObj(obj, name).javaClass, name).setInt(obj, value)
    }

    fun getFloatField(obj: Any?, name: String): Float = fieldOf(requireObj(obj, name).javaClass, name).getFloat(obj)

    fun setFloatField(obj: Any?, name: String, value: Float) {
        fieldOf(requireObj(obj, name).javaClass, name).setFloat(obj, value)
    }

    fun findField(cls: Class<*>, name: String): Field? =
        try {
            fieldOf(cls, name)
        } catch (_: Throwable) {
            null
        }

    private fun requireObj(obj: Any?, name: String): Any =
        obj ?: throw NullPointerException("field " + name + " on null receiver")

    private fun fieldOf(cls: Class<*>, name: String): Field {
        var c: Class<*>? = cls
        while (c != null && c != Any::class.java) {
            for (f in c.declaredFields) {
                if (f.name == name) {
                    f.isAccessible = true
                    return f
                }
            }
            c = c.superclass
        }
        throw NoSuchFieldException(name + " on " + cls.name)
    }

    private fun findMethod(cls: Class<*>, name: String, args: Array<out Any?>): Method? {
        var fallback: Method? = null
        var c: Class<*>? = cls
        while (c != null && c != Any::class.java) {
            for (m in c.declaredMethods) {
                if (m.name != name || m.parameterTypes.size != args.size) continue
                if (exactTypes(m.parameterTypes, args)) {
                    m.isAccessible = true
                    return m
                }
                if (fallback == null && compatibleTypes(m.parameterTypes, args)) fallback = m
            }
            c = c.superclass
        }
        fallback?.let { it.isAccessible = true }
        return fallback
    }

    private fun exactTypes(params: Array<Class<*>>, args: Array<out Any?>): Boolean {
        for (i in params.indices) {
            val a = args[i]
            if (a == null) {
                if (params[i].isPrimitive) return false
            } else if (box(params[i]) != a.javaClass) {
                return false
            }
        }
        return true
    }

    private fun compatibleTypes(params: Array<Class<*>>, args: Array<out Any?>): Boolean {
        for (i in params.indices) {
            val a = args[i]
            val p = params[i]
            if (a == null) {
                if (p.isPrimitive) return false
                continue
            }
            val boxed = box(p)
            if (boxed.isInstance(a)) continue
            if (!p.isPrimitive) return false
            // 数值拓宽：int→float/double/long，float→double 等
            if (a is Number) continue
            return false
        }
        return true
    }

    private fun box(t: Class<*>): Class<*> = when (t) {
        Boolean::class.javaPrimitiveType -> Boolean::class.javaObjectType
        Byte::class.javaPrimitiveType -> Byte::class.javaObjectType
        Char::class.javaPrimitiveType -> Char::class.javaObjectType
        Short::class.javaPrimitiveType -> Short::class.javaObjectType
        Int::class.javaPrimitiveType -> Int::class.javaObjectType
        Long::class.javaPrimitiveType -> Long::class.javaObjectType
        Float::class.javaPrimitiveType -> Float::class.javaObjectType
        Double::class.javaPrimitiveType -> Double::class.javaObjectType
        else -> t
    }
}
