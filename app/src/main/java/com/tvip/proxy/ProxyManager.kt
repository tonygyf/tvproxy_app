package com.tvip.proxy

import android.app.ActivityManager
import android.content.Context
import android.content.pm.ApplicationInfo
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
     * 拉取订阅并保存为配置
     */
    suspend fun importConfig(context: Context, subscriptionUrl: String, onStatus: (String) -> Unit) {
        onStatus("正在拉取订阅...")
        Clash.fetchAndValid(configFile(context), subscriptionUrl, true) { status ->
            onStatus("拉取中: ${status.action}")
        }.await()
        onStatus("拉取成功，节点已更新")
    }

    /**
     * 加载配置 -> 启动本地HTTP代理 -> 写入系统全局代理
     * 任何一步失败，都会自动清空系统代理设置（安全网），不让电视被卡死在无效代理上。
     * 这是个挂起函数，请在协程里调用（比如 ProxyService 里的 CoroutineScope）。
     */
    suspend fun start(context: Context, onStatus: (String) -> Unit) {
        try {
            val file = configFile(context)
            if (!file.exists()) {
                throw IllegalStateException("未找到配置，请先导入节点信息")
            }

            onStatus("正在加载配置...")
            Clash.load(file).await()

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
        // 最优先：不管内核那边接下来会不会出问题，先把系统代理设置清掉，
        // 这样哪怕后面 stopHttp() 崩了，也只是这一次没关成功，
        // 不会出现全电视所有App都没网这种大范围故障。
        clearSystemProxy(context)
        if (httpStarted) {
            try {
                Clash.stopHttp()
            } catch (_: Exception) {
                // 内核本来就没起来也没关系，继续往下清系统设置
            }
            httpStarted = false
        }
        clearSystemProxy(context)
        killCachedAppProcesses(context)
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
        val resolver = context.contentResolver
        Settings.Global.putString(resolver, Settings.Global.HTTP_PROXY, "$host:$port")
        Settings.Global.putString(resolver, "global_http_proxy_host", host)
        Settings.Global.putString(resolver, "global_http_proxy_port", port.toString())
        Settings.Global.putString(resolver, "global_http_proxy_exclusion_list", "")
        Settings.Global.putString(resolver, "global_proxy_pac_url", "")
    }

    fun clearSystemProxy(context: Context) {
        val resolver = context.contentResolver
        try {
            Settings.Global.putString(resolver, Settings.Global.HTTP_PROXY, null)
            Settings.Global.putString(resolver, "global_http_proxy_host", null)
            Settings.Global.putString(resolver, "global_http_proxy_port", null)
            Settings.Global.putString(resolver, "global_http_proxy_exclusion_list", null)
            Settings.Global.putString(resolver, "global_proxy_pac_url", null)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    /**
     * 关代理时顺手清一遍后台App进程，效果等价于"重启"——
     * 因为系统的 PROXY_CHANGE_ACTION 广播只有系统自己能发，
     * 我们的App没权限通知其他App"代理变了"，
     * 只能靠让它们的进程重新拉起，才会重新读取当前真实的代理状态。
     */
    private fun killCachedAppProcesses(context: Context) {
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val pm = context.packageManager
            for (appInfo in pm.getInstalledApplications(0)) {
                if (appInfo.packageName == context.packageName) continue
                // 跳过没被改过的系统原生App，避免误杀系统关键进程
                val isUntouchedSystemApp =
                    (appInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0) &&
                            (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP == 0)
                if (isUntouchedSystemApp) continue

                am.killBackgroundProcesses(appInfo.packageName)
            }
        } catch (e: Exception) {
            // 清理失败不影响主流程，代理设置本身已经清除干净了
        }
    }
}
