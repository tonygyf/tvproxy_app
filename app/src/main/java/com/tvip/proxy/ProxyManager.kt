package com.tvip.proxy

import android.content.Context
import android.provider.Settings
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.model.Proxy
import com.github.kr328.clash.core.model.ProxySort
import java.io.File

object ProxyManager {

    const val LOCAL_PORT = 7890

    @Volatile
    private var httpStarted = false

    private fun configFile(context: Context): File =
        context.filesDir.resolve("profile.yaml")

    /**
     * 拉取订阅 -> 加载配置 -> 启动本地HTTP代理 -> 写入系统全局代理
     * 任何一步失败，都会自动清空系统代理设置（安全网），不让电视被卡死在无效代理上。
     * 这是个挂起函数，请在协程里调用（比如 ProxyService 里的 CoroutineScope）。
     */
    suspend fun start(context: Context, subscriptionUrl: String, onStatus: (String) -> Unit) {
        try {
            onStatus("正在拉取订阅...")
            Clash.fetchAndValid(configFile(context), subscriptionUrl, true) { status ->
                onStatus("拉取中: ${status.action}")
            }.await()

            onStatus("正在加载配置...")
            Clash.load(configFile(context)).await()

            onStatus("正在启动本地代理...")
            val error = Clash.startHttp("127.0.0.1:$LOCAL_PORT")
            if (error != null) {
                throw IllegalStateException("内核启动本地代理失败: $error")
            }
            httpStarted = true

            onStatus("正在写入系统代理设置...")
            setSystemProxy(context, "127.0.0.1", LOCAL_PORT)

            onStatus("已开启")
        } catch (e: Exception) {
            // 安全网：任何一步出错，立刻清掉系统代理，让电视至少能直连，
            // 不会因为代理指向一个没有东西在监听的端口而彻底断网。
            onStatus("出错了: ${e.message}，已自动清除系统代理设置以保证电视能直连")
            stop(context)
            throw e
        }
    }

    fun stop(context: Context) {
        if (httpStarted) {
            try {
                Clash.stopHttp()
            } catch (_: Exception) {
                // 内核本来就没起来也没关系，继续往下清系统设置
            }
            httpStarted = false
        }
        clearSystemProxy(context)
    }

    fun isRunning(): Boolean = httpStarted

    /**
     * 返回第一个可选择的节点组名称，以及该组下的所有节点。
     * 大多数订阅生成的配置只有一个主选择器分组，够用了；
     * 如果你的订阅有多层分组嵌套，后续可以在这里扩展成多级列表。
     */
    fun listNodes(): Pair<String, List<Proxy>>? {
        val groupNames = Clash.queryGroupNames(true)
        val groupName = groupNames.firstOrNull() ?: return null
        val group = Clash.queryGroup(groupName, ProxySort.Default)
        return groupName to group.proxies
    }

    fun currentNode(): String? {
        val groupNames = Clash.queryGroupNames(true)
        val groupName = groupNames.firstOrNull() ?: return null
        return Clash.queryGroup(groupName, ProxySort.Default).now
    }

    fun selectNode(groupName: String, nodeName: String): Boolean {
        return Clash.patchSelector(groupName, nodeName)
    }

    fun setSystemProxy(context: Context, host: String, port: Int) {
        Settings.Global.putString(
            context.contentResolver,
            Settings.Global.HTTP_PROXY,
            "$host:$port"
        )
    }

    fun clearSystemProxy(context: Context) {
        Settings.Global.putString(
            context.contentResolver,
            Settings.Global.HTTP_PROXY,
            ":0"
        )
    }
}
