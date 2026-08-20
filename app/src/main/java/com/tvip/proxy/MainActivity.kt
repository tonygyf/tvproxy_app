package com.tvip.proxy

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import com.github.kr328.clash.common.compat.startForegroundServiceCompat
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
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.max

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var autoRetestJob: Job? = null
    private var activeTestJob: Job? = null
    private val imageLoadJobs = mutableMapOf<ImageView, Job>()

    private var baselineOffIp: String? = null
    private var proxyOnIp: String? = null
    private var proxyOffRecoveryIp: String? = null
    private var latestProbeCards: List<ProbeCardItem> = emptyList()
    private val detailCardViews = mutableListOf<View>()
    private var hasRetriedInitialDiagnostics = false

    // 当前展开详情的是哪张卡片，null 表示二级 banner 隐藏
    private var selectedCard: String? = null

    private data class ProbeCardItem(
        val name: String,
        val regionLabel: String,
        val iconUrl: String?,
        val statusText: String,
        val detailText: String,
        val latencyText: String,
        val success: Boolean
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.textGrantCommand.text =
            "adb shell pm grant $packageName android.permission.WRITE_SECURE_SETTINGS"
        binding.textVersion.text = "版本 ${resolveVersionName()}"

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
            startForegroundServiceCompat(intent)
        }

        binding.btnConnect.setOnClickListener {
            val intent = Intent(this, ProxyService::class.java).apply {
                action = ProxyService.ACTION_START
            }
            startForegroundServiceCompat(intent)
        }

        binding.btnDisconnect.setOnClickListener {
            val intent = Intent(this, ProxyService::class.java).apply {
                action = ProxyService.ACTION_STOP
            }
            startForegroundServiceCompat(intent)
        }

        binding.btnRefreshNodes.setOnClickListener {
            refreshNodeList()
        }

        binding.cardDomestic.setOnClickListener { onCardClicked("domestic") }
        binding.cardForeign.setOnClickListener { onCardClicked("foreign") }
        binding.cardCf.setOnClickListener { onCardClicked("cf") }
        binding.cardConn.setOnClickListener { onCardClicked("conn") }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (selectedCard != null) {
                    collapseDetailBanner()
                } else {
                    finish()
                }
            }
        })

        latestProbeCards = buildLoadingProbeCards()
        renderProbeCards(latestProbeCards)
        updateDetailNavigationState()
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

    private fun onCardClicked(key: String) {
        if (selectedCard == key) {
            collapseDetailBanner(restoreFocus = false)
            return
        }

        selectedCard = key
        applySelectedDetail()
        if (selectedCard != null) {
            testNetwork()
        }
    }

    private fun collapseDetailBanner(restoreFocus: Boolean = true) {
        val bannerView = resolveSelectedBannerView()
        selectedCard = null
        applySelectedDetail()
        if (restoreFocus) {
            bannerView?.requestFocus()
        }
    }

    private fun applySelectedDetail() {
        val visible = selectedCard != null
        binding.detailBanner.visibility =
            if (visible) View.VISIBLE else View.GONE
        lockMainContentFocus(visible)
        if (visible) {
            binding.tvDetailBannerTitle.text = "网站连接详情"
            binding.tvDetailBannerHint.text = "共 ${latestProbeCards.size} 个站点，含国内/国外"
            renderProbeCards(latestProbeCards)
            binding.detailBannerScroll.post {
                binding.detailBannerScroll.smoothScrollTo(0, 0)
            }
        } else {
            binding.detailBannerScroll.scrollTo(0, 0)
        }
        updateDetailNavigationState()
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
        latestProbeCards = buildLoadingProbeCards()
        renderProbeCards(latestProbeCards)
        applySelectedDetail()

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

                latestProbeCards = buildProbeCards(domesticResults, foreignResults)
                renderProbeCards(latestProbeCards)
                applySelectedDetail()
                maybeRetryInitialDiagnostics(cfTrace, geoInfo, domesticResults, foreignResults)
            }
        }
    }

    private fun maybeRetryInitialDiagnostics(
        cfTrace: NetworkDiagnostics.CloudflareTrace?,
        geoInfo: NetworkDiagnostics.GeoInfo?,
        domesticResults: List<NetworkDiagnostics.ProbeResult>,
        foreignResults: List<NetworkDiagnostics.ProbeResult>
    ) {
        if (hasRetriedInitialDiagnostics) return
        val successCount = (domesticResults + foreignResults).count {
            it.status == NetworkDiagnostics.ProbeStatus.SUCCESS
        }
        val missingExitIp = cfTrace?.ip.isNullOrBlank() && geoInfo?.ip.isNullOrBlank()
        if (successCount > 0 && !missingExitIp) return

        hasRetriedInitialDiagnostics = true
        binding.tvConnDetail.text = "首次检测结果不稳定，正在自动重试..."
        scheduleRetest(1500L)
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

    private fun buildLoadingProbeCards(): List<ProbeCardItem> {
        val domesticCards = NetworkDiagnostics.domesticEndpoints.map { endpoint ->
            ProbeCardItem(
                name = endpoint.name,
                regionLabel = "国内",
                iconUrl = iconUrlForSite(endpoint.name),
                statusText = "测试中",
                detailText = "等待网络请求",
                latencyText = "...",
                success = false
            )
        }
        val foreignCards = NetworkDiagnostics.foreignEndpoints.map { endpoint ->
            ProbeCardItem(
                name = endpoint.name,
                regionLabel = "国外",
                iconUrl = iconUrlForSite(endpoint.name),
                statusText = "测试中",
                detailText = "等待网络请求",
                latencyText = "...",
                success = false
            )
        }
        return domesticCards + foreignCards
    }

    private fun buildProbeCards(
        domesticResults: List<NetworkDiagnostics.ProbeResult>,
        foreignResults: List<NetworkDiagnostics.ProbeResult>
    ): List<ProbeCardItem> {
        return domesticResults.map { result ->
            result.toProbeCardItem("国内")
        } + foreignResults.map { result ->
            result.toProbeCardItem("国外")
        }
    }

    private fun NetworkDiagnostics.ProbeResult.toProbeCardItem(regionLabel: String): ProbeCardItem {
        val success = status == NetworkDiagnostics.ProbeStatus.SUCCESS
        val latencyText = when (status) {
            NetworkDiagnostics.ProbeStatus.SUCCESS -> "${latencyMs ?: "-"} ms"
            NetworkDiagnostics.ProbeStatus.TIMEOUT -> "超时"
            else -> status.label
        }
        val detailText = when (status) {
            NetworkDiagnostics.ProbeStatus.SUCCESS -> detail
            NetworkDiagnostics.ProbeStatus.TIMEOUT -> "请求超时"
            else -> detail.ifBlank { status.label }
        }

        return ProbeCardItem(
            name = name,
            regionLabel = regionLabel,
            iconUrl = iconUrlForSite(name),
            statusText = status.label,
            detailText = detailText,
            latencyText = latencyText,
            success = success
        )
    }

    private fun renderProbeCards(cards: List<ProbeCardItem>) {
        val focusedDetailIndex = detailCardViews.indexOfFirst { it.isFocused }
        binding.detailBannerGrid.removeAllViews()
        detailCardViews.clear()
        cards.forEachIndexed { index, item ->
            val cardView = createProbeCardView(item, index)
            detailCardViews += cardView
            binding.detailBannerGrid.addView(cardView)
        }
        updateDetailNavigationState()
        if (selectedCard != null && focusedDetailIndex in detailCardViews.indices) {
            detailCardViews[focusedDetailIndex].requestFocus()
        }
    }

    private fun createProbeCardView(item: ProbeCardItem, index: Int): LinearLayout {
        val card = LinearLayout(this).apply {
            id = View.generateViewId()
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            setBackgroundResource(R.drawable.tv_detail_item_bg)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = GridLayout.LayoutParams().apply {
                width = 0
                height = GridLayout.LayoutParams.WRAP_CONTENT
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(
                    if (index % 2 == 0) 0 else dp(6),
                    0,
                    if (index % 2 == 0) dp(6) else 0,
                    dp(10)
                )
            }
            setOnFocusChangeListener { view, hasFocus ->
                if (hasFocus) {
                    ensureDetailCardVisible(view)
                }
            }
        }

        val iconView = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
            setImageResource(android.R.drawable.ic_menu_compass)
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = getDrawable(R.drawable.tv_edittext_bg)
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        updateRemoteImage(iconView, item.iconUrl, android.R.drawable.ic_menu_compass)

        val textColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(10)
                marginEnd = dp(10)
            }
        }

        val titleView = TextView(this).apply {
            text = "${item.name} ${item.regionLabel}"
            setTextColor(Color.WHITE)
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

        val subtitleView = TextView(this).apply {
            text = item.detailText
            setTextColor(Color.parseColor("#9E9E9E"))
            textSize = 11f
            maxLines = 1
        }

        val latencyView = TextView(this).apply {
            text = item.latencyText
            setTextColor(if (item.success) Color.parseColor("#FF9800") else Color.parseColor("#B0BEC5"))
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.END
        }

        textColumn.addView(titleView)
        textColumn.addView(subtitleView)
        card.addView(iconView)
        card.addView(textColumn)
        card.addView(latencyView)
        return card
    }

    private fun updateDetailNavigationState() {
        val firstTargetId = if (selectedCard != null && detailCardViews.isNotEmpty()) {
            detailCardViews.first().id
        } else {
            binding.btnImport.id
        }
        listOf(
            binding.cardDomestic,
            binding.cardForeign,
            binding.cardCf,
            binding.cardConn
        ).forEach { card ->
            card.nextFocusDownId = firstTargetId
        }

        if (detailCardViews.isEmpty()) {
            return
        }

        val selectedBannerId = resolveSelectedBannerView()?.id ?: binding.cardDomestic.id
        val columnCount = 2
        detailCardViews.forEachIndexed { index, view ->
            val upIndex = index - columnCount
            val downIndex = index + columnCount
            val isLeftColumn = index % columnCount == 0
            val rightIndex = if (isLeftColumn && index + 1 < detailCardViews.size) index + 1 else index
            val leftIndex = if (isLeftColumn) index else index - 1

            view.nextFocusUpId = if (upIndex >= 0) detailCardViews[upIndex].id else selectedBannerId
            view.nextFocusDownId =
                if (downIndex < detailCardViews.size) detailCardViews[downIndex].id else view.id
            view.nextFocusLeftId = detailCardViews[leftIndex].id
            view.nextFocusRightId = detailCardViews[rightIndex].id
        }
    }

    private fun ensureDetailCardVisible(view: View) {
        binding.detailBannerScroll.post {
            val extraPadding = dp(8)
            val targetTop = max(0, view.top - extraPadding)
            val targetBottom = view.bottom + extraPadding
            val currentTop = binding.detailBannerScroll.scrollY
            val currentBottom = currentTop + binding.detailBannerScroll.height

            when {
                targetTop < currentTop -> binding.detailBannerScroll.smoothScrollTo(0, targetTop)
                targetBottom > currentBottom ->
                    binding.detailBannerScroll.smoothScrollTo(
                        0,
                        max(0, targetBottom - binding.detailBannerScroll.height)
                    )
            }
        }
    }

    private fun lockMainContentFocus(lock: Boolean) {
        binding.mainContentContainer.descendantFocusability =
            if (lock) ViewGroup.FOCUS_BLOCK_DESCENDANTS else ViewGroup.FOCUS_AFTER_DESCENDANTS

        if (lock && isDescendantOf(binding.mainContentContainer, currentFocus)) {
            resolveSelectedBannerView()?.requestFocus()
        }
    }

    private fun resolveSelectedBannerView(): View? {
        return when (selectedCard) {
            "domestic" -> binding.cardDomestic
            "foreign" -> binding.cardForeign
            "cf" -> binding.cardCf
            "conn" -> binding.cardConn
            else -> null
        }
    }

    private fun isDescendantOf(parent: View, child: View?): Boolean {
        var current = child
        while (current != null) {
            if (current === parent) return true
            current = (current.parent as? View)
        }
        return false
    }

    private fun iconUrlForSite(name: String): String? {
        return when (name) {
            "抖音" -> "https://www.douyin.com/favicon.ico"
            "Bilibili" -> "https://www.bilibili.com/favicon.ico"
            "微信" -> "https://res.wx.qq.com/a/wx_fed/assets/res/NTI4MWU5.ico"
            "淘宝" -> "https://www.taobao.com/favicon.ico"
            "GitHub" -> "https://github.com/favicon.ico"
            "Telegram" -> "https://telegram.org/favicon.ico"
            "X.com" -> "https://abs.twimg.com/favicons/twitter.3.ico"
            "YouTube" -> "https://www.youtube.com/favicon.ico"
            "Google" -> "https://www.google.com/favicon.ico"
            else -> null
        }
    }

    private fun updateFlag(imageView: ImageView, countryCode: String?) {
        val url = countryCode?.takeIf { it.isNotBlank() }
            ?.let { "https://flagcdn.com/w320/${it.lowercase(Locale.ROOT)}.png" }
        updateRemoteImage(imageView, url)
    }

    private fun formatTimestamp(timestamp: Long): String {
        return SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
    }

    private fun resolveVersionName(): String {
        return try {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }
            info.versionName ?: "-"
        } catch (_: Exception) {
            "-"
        }
    }

    private fun updateRemoteImage(
        imageView: ImageView,
        imageUrl: String?,
        placeholderResId: Int? = null
    ) {
        imageLoadJobs.remove(imageView)?.cancel()
        imageView.tag = imageUrl

        when {
            placeholderResId != null -> imageView.setImageResource(placeholderResId)
            else -> imageView.setImageDrawable(null)
        }

        if (imageUrl.isNullOrBlank()) {
            return
        }

        imageLoadJobs[imageView] = lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                fetchBitmap(imageUrl)
            }
            if (imageView.tag == imageUrl && bitmap != null) {
                imageView.setImageBitmap(bitmap)
            }
        }
    }

    private fun fetchBitmap(imageUrl: String): Bitmap? {
        val connection = (URL(imageUrl).openConnection() as? HttpURLConnection)
            ?: return null
        return try {
            connection.connectTimeout = 4000
            connection.readTimeout = 4000
            connection.instanceFollowRedirects = true
            connection.inputStream.use(BitmapFactory::decodeStream)
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}
