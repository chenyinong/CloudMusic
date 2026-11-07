package android.net.wifi

/**
 * JVM 上跑服务端协议测试时用的空壳。
 */
class WifiManager {

    class MulticastLock {
        fun setReferenceCounted(referenceCounted: Boolean) {}
        fun acquire() {}
        fun release() {}
    }

    fun createMulticastLock(tag: String): MulticastLock = MulticastLock()
}
