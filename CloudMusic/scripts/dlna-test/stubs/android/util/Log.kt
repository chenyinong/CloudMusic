package android.util

/**
 * JVM 上跑协议测试时用的空壳，编译期不引 android.jar 也能过。
 */
object Log {
    @JvmStatic
    fun v(tag: String, msg: String): Int = 0

    @JvmStatic
    fun d(tag: String, msg: String): Int = 0

    @JvmStatic
    fun i(tag: String, msg: String): Int = 0

    @JvmStatic
    fun w(tag: String, msg: String): Int = 0

    @JvmStatic
    fun e(tag: String, msg: String): Int = 0

    @JvmStatic
    fun e(tag: String, msg: String, tr: Throwable?): Int = 0
}
