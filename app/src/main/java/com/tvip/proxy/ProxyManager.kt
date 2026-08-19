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
     * 返回包含最多真实节点的代理组，以及该组下的所有真实节点。
     * 解决某些订阅里有“自动选择”、“手动切换”等多层嵌套，导致只显示出子组的问题。
     */
    fun listNodes(): Pair<String, List<Proxy>>? {
        val groupNames = Clash.queryGroupNames(true)
        var maxNodes = -1
        var bestGroup: String? = null
        var bestProxies: List<Proxy>? = null

        for (name in groupNames) {
            val group = Clash.queryGroup(name, ProxySort.Default)
            val nodesCount = group.proxies.count { !it.isGroup }
            if (nodesCount > maxNodes) {
                maxNodes = nodesCount
                bestGroup = name
                bestProxies = group.proxies
            }
        }

        if (bestGroup == null || bestProxies == null) return null
        return bestGroup to bestProxies.filter { !it.isGroup }
    }

    fun currentNode(): String? {
        val groupNames = Clash.queryGroupNames(true)
        var maxNodes = -1
        var bestGroup: String? = null

        for (name in groupNames) {
            val group = Clash.queryGroup(name, ProxySort.Default)
            val nodesCount = group.proxies.count { !it.isGroup }
            if (nodesCount > maxNodes) {
                maxNodes = nodesCount
                bestGroup = name
            }
        }

        if (bestGroup == null) return null
        return Clash.queryGroup(bestGroup, ProxySort.Default).now
    }

    fun selectNode(groupName: String, nodeName: String): Boolean {
        val success = Clash.patchSelector(groupName, nodeName)
        if (success) {
            // 尝试向上级联切换：如果有一个父组（如"节点选择"）包含了当前组（如"手动切换"），
            // 则同时把父组也切换到当前组，确保流量路由正确。
            val allGroups = Clash.queryGroupNames(true)
            for (parentName in allGroups) {
                if (parentName == groupName) continue
                val parentGroup = Clash.queryGroup(parentName, ProxySort.Default)
                if (parentGroup.proxies.any { it.name == groupName }) {
                    Clash.patchSelector(parentName, groupName)
                }
            }
        }
        return success
    }

    fun setSystemProxy(context: Context, host: String, port: Int) {
        Settings.Global.putString(
            context.contentResolver,
            Settings.Global.HTTP_PROXY,
            "$host:$port"
        )
    }

    fun clearSystemProxy(context: Context) {
        val resolver = context.contentResolver
        // 某些系统用 ":0" 清除，某些用 "" 清除，这里两者都尝试以确保彻底
        Settings.Global.putString(resolver, Settings.Global.HTTP_PROXY, ":0")
        Settings.Global.putString(resolver, Settings.Global.HTTP_PROXY, "")
        // 同时清除 host 和 port 以防万一
        Settings.Global.putString(resolver, "global_http_proxy_host", "")
        Settings.Global.putString(resolver, "global_http_proxy_port", "")
    }
}
