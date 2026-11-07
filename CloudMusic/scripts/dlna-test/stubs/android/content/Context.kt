package android.content

/**
 * JVM 上跑服务端协议测试时用的空壳，只提供 DlnaServer 用到的那几个成员。
 */
open class Context {
    open val applicationContext: Context get() = this
    open fun getSystemService(name: String): Any? = null

    companion object {
        const val WIFI_SERVICE = "wifi"
    }
}
