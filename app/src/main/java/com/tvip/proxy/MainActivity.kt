package com.tvip.proxy

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.tvip.proxy.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var autoRetestJob: Job? = null
    private var activeTestJob: Job? = null

    private var baselineOffIp: String? = null
    private var proxyOnIp: String? = null
    private var proxyOffRecoveryIp: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.textGrantCommand.text =
            "adb shell pm grant $packageName android.permission.WRITE_SECURE_SETTINGS"

        binding.editSubUrl.setText(SettingsStore.getSubscriptionUrl(this) ?: "")

        binding.btnImport.setOnClickListener {
            val url = binding.editSubUrl.text.toString().trim()
            if (url.isEmpty()) {
                Toast.makeText(this, "请先输入订阅地址", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            SettingsStore.setSubscriptionUrl(this, url)

            val intent = Intent(this, ProxyService::class.java).apply {
                action = ProxyService.ACTION_IMPORT
            }
            startForegroundService(intent)
        }

        binding.btnConnect.setOnClickListener {
            val intent = Intent(this, ProxyService::class.java).apply {
                action = ProxyService.ACTION_START
            }
            startForegroundService(intent)
        }

        binding.btnDisconnect.setOnClickListener {
            val intent = Intent(this, ProxyService::class.java).apply {
                action = ProxyService.ACTION_STOP
            }
            startForegroundService(intent)
        }

        binding.btnRefreshNodes.setOnClickListener {
            refreshNodeList()
        }

        binding.cardDomestic.setOnClickListener { testNetwork() }
        binding.cardForeign.setOnClickListener { testNetwork() }
        binding.cardCf.setOnClickListener { testNetwork() }
        binding.cardConn.setOnClickListener { testNetwork() }

        binding.btnConnect.requestFocus()
        testNetwork()
    }

    override fun onResume() {
        super.onResume()
        updateStatusText(ProxyService.lastStatus)
        ProxyService.statusListener = { status ->
            runOnUiThread {
                updateStatusText(status)
                if (status == "已开启" || status == "已关闭") {
                    scheduleRetest()
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        ProxyService.statusListener = null
        autoRetestJob?.cancel()
    }

    private fun refreshNodeList() {
        val result = ProxyManager.listNodes()
        binding.nodeListContainer.removeAllViews()

        if (result == null) {
            Toast.makeText(this, "还没有可用节点，先确认代理已连接成功", Toast.LENGTH_SHORT).show()
            return
        }

        val (groupName, nodes) = result
        val current = ProxyManager.currentNode()

        for (node in nodes) {
            val button = Button(this).apply {
                text = if (node.name == current) "● ${node.name}（当前使用）" else node.name
                gravity = Gravity.START
                setTextColor(android.graphics.Color.WHITE)
                setBackgroundResource(R.drawable.tv_button_bg)
                val marginParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 8, 0, 8)
                }
                layoutParams = marginParams
                setPadding(32, 24, 32, 24)
                setOnClickListener {
                    val ok = ProxyManager.selectNode(groupName, node.name)
                    if (ok) {
                        SettingsStore.setSelectedNode(this@MainActivity, node.name)
                        Toast.makeText(this@MainActivity, "已切换到 ${node.name}", Toast.LENGTH_SHORT)
                            .show()
                        refreshNodeList()
                        scheduleRetest()
                    } else {
                        Toast.makeText(this@MainActivity, "切换失败", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            binding.nodeListContainer.addView(button)
        }
    }

    private fun updateStatusText(status: String) {
        binding.textStatus.text = "状态：$status"
    }

    private fun scheduleRetest(delayMs: Long = 1200L) {
        autoRetestJob?.cancel()
        autoRetestJob = lifecycleScope.launch {
            delay(delayMs)
            testNetwork()
        }
    }

    private fun testNetwork() {
        activeTestJob?.cancel()

        binding.tvDomesticIp.text = "测试中..."
        binding.tvDomesticLoc.text = "-"
        binding.tvForeignIp.text = "测试中..."
        binding.tvForeignLoc.text = "-"
        binding.tvCfIp.text = "测试中..."
        binding.tvCfLoc.text = "-"
        binding.tvConnStatus.text = "测试中"
        binding.tvConnDetail.text = "-"
        binding.tvProxySummary.text = "Proxy 状态：测试中..."
        binding.tvDomesticTests.text = "国内测试：测试中..."
        binding.tvForeignTests.text = "国外测试：测试中..."
        binding.tvProxyDiagnosis.text = "Proxy 诊断：测试中..."

        activeTestJob = lifecycleScope.launch(Dispatchers.IO) {
            val proxySnapshot = NetworkDiagnostics.readProxySnapshot(this@MainActivity)
            val cfDeferred = async { NetworkDiagnostics.fetchCloudflareTrace(this@MainActivity) }
            val domesticJobs = NetworkDiagnostics.domesticEndpoints.map { endpoint ->
                async { NetworkDiagnostics.testEndpoint(this@MainActivity, endpoint) }
            }
            val foreignJobs = NetworkDiagnostics.foreignEndpoints.map { endpoint ->
                async { NetworkDiagnostics.testEndpoint(this@MainActivity, endpoint) }
            }

            val cfTrace = cfDeferred.await()
            val geoInfo = NetworkDiagnostics.fetchGeoInfo(this@MainActivity, cfTrace?.ip)
            val domesticResults = domesticJobs.awaitAll()
            val foreignResults = foreignJobs.awaitAll()
            val exitIp = cfTrace?.ip?.takeIf { it.isNotBlank() } ?: geoInfo?.ip?.takeIf { it.isNotBlank() }

            recordProxyHistory(proxySnapshot.enabled, exitIp)

            withContext(Dispatchers.Main) {
                binding.tvDomesticIp.text = geoInfo?.ip?.ifBlank { "获取失败" } ?: "获取失败"
                binding.tvDomesticLoc.text = formatDomesticLocation(geoInfo)
                updateFlag(binding.ivDomesticFlag, geoInfo?.countryCode)

                binding.tvForeignIp.text = exitIp ?: "获取失败"
                binding.tvForeignLoc.text = formatForeignLocation(cfTrace, geoInfo)
                updateFlag(binding.ivForeignFlag, cfTrace?.countryCode ?: geoInfo?.countryCode)

                binding.tvCfIp.text = cfTrace?.ip?.ifBlank { "获取失败" } ?: "获取失败"
                binding.tvCfLoc.text = formatCloudflareLocation(cfTrace)
                updateFlag(binding.ivCfFlag, cfTrace?.countryCode)

                val domesticOk = domesticResults.count { it.status == NetworkDiagnostics.ProbeStatus.SUCCESS }
                val foreignOk = foreignResults.count { it.status == NetworkDiagnostics.ProbeStatus.SUCCESS }
                binding.tvConnStatus.text = "国内 $domesticOk/4 · 国外 $foreignOk/5"
                binding.tvConnDetail.text = "更新于 ${formatTimestamp(System.currentTimeMillis())}"
                binding.ivConnFlag.setImageDrawable(null)

                binding.tvProxySummary.text = buildProxySummary(proxySnapshot, cfTrace, geoInfo, exitIp)
                binding.tvDomesticTests.text = buildProbeBlock("国内测试", domesticResults)
                binding.tvForeignTests.text = buildProbeBlock("国外测试", foreignResults)
                binding.tvProxyDiagnosis.text = buildProxyDiagnosis(proxySnapshot.enabled, exitIp)
            }
        }
    }

    private fun recordProxyHistory(proxyEnabled: Boolean, exitIp: String?) {
        if (exitIp.isNullOrBlank()) return
        if (proxyEnabled) {
            proxyOnIp = exitIp
        } else if (proxyOnIp.isNullOrBlank()) {
            baselineOffIp = exitIp
        } else {
            proxyOffRecoveryIp = exitIp
        }
    }

    private fun buildProxySummary(
        proxySnapshot: NetworkDiagnostics.ProxySnapshot,
        cfTrace: NetworkDiagnostics.CloudflareTrace?,
        geoInfo: NetworkDiagnostics.GeoInfo?,
        exitIp: String?
    ): String {
        val baseline = baselineOffIp ?: "-"
        val changed = when {
            baselineOffIp.isNullOrBlank() || exitIp.isNullOrBlank() -> "待比较"
            baselineOffIp == exitIp -> "否"
            else -> "是"
        }

        return buildString {
            append("Proxy 状态：")
            append(if (proxySnapshot.enabled) "已启用" else "未启用")
            append('\n')
            append("系统设置：${proxySnapshot.displayTarget()}")
            append('\n')
            append("原始出口 IP：$baseline")
            append('\n')
            append("当前出口 IP：${exitIp ?: "获取失败"}")
            append('\n')
            append("出口是否变化：$changed")
            append('\n')
            append("国家/城市：${formatGeoLine(geoInfo)}")
            append('\n')
            append("运营商/ASN：${formatCarrierLine(geoInfo)}")
            append('\n')
            append("Cloudflare POP：${cfTrace?.colo?.ifBlank { "-" } ?: "-"}")
            append('\n')
            append("Trace 协议：HTTP ${cfTrace?.httpProtocol?.ifBlank { "-" } ?: "-"} / TLS ${cfTrace?.tls?.ifBlank { "-" } ?: "-"}")
            append('\n')
            append("更新于：${formatTimestamp(System.currentTimeMillis())}")
        }
    }

    private fun buildProbeBlock(
        title: String,
        results: List<NetworkDiagnostics.ProbeResult>
    ): String {
        return buildString {
            append(title)
            append("：")
            append('\n')
            results.forEachIndexed { index, result ->
                if (index > 0) append('\n')
                append("● ${result.name}  ${formatProbeResult(result)}")
            }
        }
    }

    private fun buildProxyDiagnosis(proxyEnabled: Boolean, exitIp: String?): String {
        val conclusion = when {
            baselineOffIp != null &&
                proxyOnIp != null &&
                proxyOffRecoveryIp != null &&
                baselineOffIp != proxyOnIp &&
                proxyOffRecoveryIp == baselineOffIp -> "Proxy 工作正常"

            baselineOffIp != null &&
                proxyOnIp != null &&
                proxyOffRecoveryIp != null &&
                baselineOffIp != proxyOnIp &&
                proxyOffRecoveryIp == proxyOnIp -> "代理已关闭，但测试请求仍命中旧连接或旧缓存"

            baselineOffIp != null &&
                proxyOnIp != null &&
                baselineOffIp == proxyOnIp -> "当前测试请求没有检测到出口变化"

            baselineOffIp != null &&
                proxyOnIp != null &&
                proxyOffRecoveryIp != null &&
                proxyOffRecoveryIp != baselineOffIp -> "关闭代理后出口没有回到基线，可能存在 DNS 缓存、系统代理残留或外部网络变化"

            exitIp.isNullOrBlank() -> "当前出口 IP 获取失败，暂时无法下结论"
            else -> "已记录当前阶段，继续执行 OFF -> ON -> OFF 可得到完整结论"
        }

        return buildString {
            append("Proxy 诊断：")
            append('\n')
            append("当前阶段：")
            append(if (proxyEnabled) "ON" else "OFF")
            append('\n')
            append("OFF：${baselineOffIp ?: "-"}")
            append('\n')
            append("ON：${proxyOnIp ?: "-"}")
            append('\n')
            append("OFF 恢复：${proxyOffRecoveryIp ?: "-"}")
            append('\n')
            append("结论：$conclusion")
        }
    }

    private fun formatProbeResult(result: NetworkDiagnostics.ProbeResult): String {
        return when (result.status) {
            NetworkDiagnostics.ProbeStatus.SUCCESS ->
                "${result.latencyMs ?: "-"} ms (${result.detail})"

            NetworkDiagnostics.ProbeStatus.TIMEOUT ->
                "超时 ${result.latencyMs ?: "-"} ms"

            else -> "${result.status.label} (${result.detail})"
        }
    }

    private fun formatDomesticLocation(geoInfo: NetworkDiagnostics.GeoInfo?): String {
        if (geoInfo == null) return "未知位置"
        return buildList {
            add(formatGeoLine(geoInfo))
            val carrierLine = formatCarrierLine(geoInfo)
            if (carrierLine != "-") add(carrierLine)
        }.joinToString(" · ")
    }

    private fun formatForeignLocation(
        cfTrace: NetworkDiagnostics.CloudflareTrace?,
        geoInfo: NetworkDiagnostics.GeoInfo?
    ): String {
        return buildList {
            val country = cfTrace?.countryName?.ifBlank { cfTrace.countryCode } ?: geoInfo?.countryName
            if (!country.isNullOrBlank()) add(country)
            if (!cfTrace?.colo.isNullOrBlank()) add(cfTrace?.colo.orEmpty())
            if (!geoInfo?.city.isNullOrBlank()) add(geoInfo?.city.orEmpty())
        }.joinToString(" · ").ifBlank { "未知位置" }
    }

    private fun formatCloudflareLocation(cfTrace: NetworkDiagnostics.CloudflareTrace?): String {
        if (cfTrace == null) return "无法访问"
        return buildList {
            add(cfTrace.countryName.ifBlank { cfTrace.countryCode })
            if (cfTrace.colo.isNotBlank()) add(cfTrace.colo)
        }.joinToString(" · ").ifBlank { "未知位置" }
    }

    private fun formatGeoLine(geoInfo: NetworkDiagnostics.GeoInfo?): String {
        if (geoInfo == null) return "-"
        return buildList {
            if (geoInfo.countryName.isNotBlank()) add(geoInfo.countryName)
            if (geoInfo.region.isNotBlank()) add(geoInfo.region)
            if (geoInfo.city.isNotBlank()) add(geoInfo.city)
        }.joinToString(" · ").ifBlank { "-" }
    }

    private fun formatCarrierLine(geoInfo: NetworkDiagnostics.GeoInfo?): String {
        if (geoInfo == null) return "-"
        return buildList {
            if (geoInfo.isp.isNotBlank()) add(geoInfo.isp)
            if (geoInfo.asn.isNotBlank()) add(geoInfo.asn)
        }.joinToString(" · ").ifBlank { "-" }
    }

    private fun updateFlag(imageView: ImageView, countryCode: String?) {
        if (countryCode.isNullOrBlank()) {
            imageView.setImageDrawable(null)
            return
        }

        Glide.with(this)
            .load("https://flagcdn.com/w320/${countryCode.lowercase(Locale.ROOT)}.png")
            .transition(DrawableTransitionOptions.withCrossFade())
            .into(imageView)
    }

    private fun formatTimestamp(timestamp: Long): String {
        return SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
    }
}
