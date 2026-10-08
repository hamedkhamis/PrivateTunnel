package hev.htproxy

/** JNI bridge to hev-socks5-tunnel. Class/package name must stay exactly like this. */
class TProxyService {
    companion object {
        init { System.loadLibrary("hev-socks5-tunnel") }
    }

    private external fun TProxyStartService(configPath: String, fd: Int)
    private external fun TProxyStopService()
    private external fun TProxyGetStats(): LongArray?

    fun start(configPath: String, fd: Int) = TProxyStartService(configPath, fd)
    fun stop() = TProxyStopService()
    fun stats(): LongArray? = TProxyGetStats()
}
