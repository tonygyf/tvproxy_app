package com.tvip.proxy

import android.content.Context
import android.provider.Settings
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.Locale
import java.util.UUID
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException

object NetworkDiagnostics {

    data class Endpoint(
        val name: String,
        val url: String,
        val expectedCodes: Set<Int> = setOf(200, 204, 301, 302)
    )

    data class ProxySnapshot(
        val enabled: Boolean,
        val host: String,
        val port: Int?,
        val httpProxy: String,
        val globalHost: String,
        val globalPort: String,
        val exclusionList: String,
        val pacUrl: String
    ) {
        fun displayTarget(): String {
            return when {
                host.isNotBlank() && port != null -> "$host:$port"
                httpProxy.isNotBlank() -> httpProxy
                else -> "未设置"
            }
        }

        fun toJavaProxy(): Proxy {
            return if (enabled && host.isNotBlank() && port != null && port > 0) {
                Proxy(Proxy.Type.HTTP, InetSocketAddress(host, port))
            } else {
                Proxy.NO_PROXY
            }
        }
    }

    enum class ProbeStatus(val label: String) {
        SUCCESS("成功"),
        TIMEOUT("超时"),
        DNS_FAILURE("DNS失败"),
        TCP_FAILURE("TCP失败"),
        TLS_FAILURE("TLS失败"),
        HTTP_ERROR("HTTP错误"),
        IO_ERROR("网络错误"),
        UNTESTED("未测试")
    }

    data class ProbeResult(
        val name: String,
        val url: String,
        val status: ProbeStatus,
        val latencyMs: Long?,
        val httpCode: Int?,
        val detail: String,
        val lastUpdated: Long
    )

    data class CloudflareTrace(
        val ip: String,
        val countryCode: String,
        val countryName: String,
        val colo: String,
        val httpProtocol: String,
        val tls: String
    )

    data class GeoInfo(
        val ip: String,
        val countryCode: String,
        val countryName: String,
        val region: String,
        val city: String,
        val isp: String,
        val asn: String,
        val source: String
    )

    val domesticEndpoints = listOf(
        Endpoint("抖音", "https://www.douyin.com/favicon.ico"),
        Endpoint("Bilibili", "https://www.bilibili.com/favicon.ico"),
        Endpoint("微信", "https://res.wx.qq.com/a/wx_fed/assets/res/NTI4MWU5.ico"),
        Endpoint("淘宝", "https://www.taobao.com/favicon.ico")
    )

    val foreignEndpoints = listOf(
        Endpoint("GitHub", "https://github.com/favicon.ico"),
        Endpoint("Telegram", "https://telegram.org/favicon.ico"),
        Endpoint("X.com", "https://x.com/favicon.ico"),
        Endpoint("YouTube", "https://www.youtube.com/generate_204", setOf(204)),
        Endpoint("Google", "https://www.google.com/generate_204", setOf(204))
    )

    fun readProxySnapshot(context: Context): ProxySnapshot {
        val resolver = context.contentResolver
        val httpProxy = Settings.Global.getString(resolver, Settings.Global.HTTP_PROXY).orEmpty()
        val globalHost = Settings.Global.getString(resolver, "global_http_proxy_host").orEmpty()
        val globalPort = Settings.Global.getString(resolver, "global_http_proxy_port").orEmpty()
        val exclusionList =
            Settings.Global.getString(resolver, "global_http_proxy_exclusion_list").orEmpty()
        val pacUrl = Settings.Global.getString(resolver, "global_proxy_pac_url").orEmpty()

        val parsedHost = globalHost.ifBlank { httpProxy.substringBefore(':', "") }
        val parsedPort = globalPort.toIntOrNull() ?: httpProxy.substringAfter(':', "").toIntOrNull()
        val enabled = parsedHost.isNotBlank() && parsedPort != null && parsedPort > 0

        return ProxySnapshot(
            enabled = enabled,
            host = parsedHost,
            port = parsedPort,
            httpProxy = httpProxy,
            globalHost = globalHost,
            globalPort = globalPort,
            exclusionList = exclusionList,
            pacUrl = pacUrl
        )
    }

    fun fetchCloudflareTrace(context: Context): CloudflareTrace? {
        var connection: HttpURLConnection? = null
        return try {
            connection = openConnection(
                context,
                "https://www.cloudflare.com/cdn-cgi/trace",
                compactResponse = false
            )
            val code = connection.responseCode
            if (code !in 200..299) return null

            val values = connection.inputStream.bufferedReader().use { reader ->
                reader.lineSequence()
                    .mapNotNull { line ->
                        val index = line.indexOf('=')
                        if (index <= 0) null else line.substring(0, index) to line.substring(index + 1)
                    }
                    .toMap()
            }

            val countryCode = values["loc"].orEmpty()
            CloudflareTrace(
                ip = values["ip"].orEmpty(),
                countryCode = countryCode,
                countryName = countryName(countryCode),
                colo = values["colo"].orEmpty(),
                httpProtocol = values["http"].orEmpty(),
                tls = values["tls"].orEmpty()
            )
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    fun fetchGeoInfo(context: Context, preferredIp: String? = null): GeoInfo? {
        val byteInfo = fetchByteDanceGeo(context)
        val ipApiInfo = fetchIpApiGeo(context)
        val myIpInfo = fetchMyIpGeo(context)

        val ip = firstNonBlank(preferredIp, ipApiInfo?.ip, myIpInfo?.ip, byteInfo?.ip)
        if (ip.isBlank()) return null

        val countryCode = firstNonBlank(
            ipApiInfo?.countryCode,
            myIpInfo?.countryCode,
            if (byteInfo?.country == "中国") "CN" else ""
        )
        val countryName = firstNonBlank(
            ipApiInfo?.countryName,
            myIpInfo?.countryName,
            countryName(countryCode),
            byteInfo?.country
        )
        val region = firstNonBlank(byteInfo?.region, ipApiInfo?.region)
        val city = firstNonBlank(byteInfo?.city, ipApiInfo?.city)
        val isp = firstNonBlank(byteInfo?.isp, ipApiInfo?.isp)
        val asn = firstNonBlank(ipApiInfo?.asn, myIpInfo?.asn)
        val source = buildString {
            if (byteInfo != null) append("字节")
            if (ipApiInfo != null) append(if (isNotEmpty()) "+ip-api" else "ip-api")
            if (myIpInfo != null && ipApiInfo == null) append(if (isNotEmpty()) "+myip" else "myip")
        }.ifBlank { "未知" }

        return GeoInfo(
            ip = ip,
            countryCode = countryCode,
            countryName = countryName,
            region = region,
            city = city,
            isp = isp,
            asn = asn,
            source = source
        )
    }

    fun testEndpoint(context: Context, endpoint: Endpoint): ProbeResult {
        var connection: HttpURLConnection? = null
        val startedAt = System.currentTimeMillis()
        return try {
            connection = openConnection(context, endpoint.url, compactResponse = true)
            val code = connection.responseCode
            val latency = System.currentTimeMillis() - startedAt
            val status = if (code in 200..399 || code in endpoint.expectedCodes) {
                ProbeStatus.SUCCESS
            } else {
                ProbeStatus.HTTP_ERROR
            }

            ProbeResult(
                name = endpoint.name,
                url = endpoint.url,
                status = status,
                latencyMs = latency,
                httpCode = code,
                detail = if (status == ProbeStatus.SUCCESS) "HTTP $code" else "HTTP $code",
                lastUpdated = System.currentTimeMillis()
            )
        } catch (e: Exception) {
            val latency = System.currentTimeMillis() - startedAt
            ProbeResult(
                name = endpoint.name,
                url = endpoint.url,
                status = classifyException(e),
                latencyMs = latency,
                httpCode = null,
                detail = e.message ?: e::class.java.simpleName,
                lastUpdated = System.currentTimeMillis()
            )
        } finally {
            connection?.disconnect()
        }
    }

    private fun openConnection(
        context: Context,
        rawUrl: String,
        compactResponse: Boolean
    ): HttpURLConnection {
        val url = URL(withCacheBuster(rawUrl))
        val proxy = readProxySnapshot(context).toJavaProxy()
        return (url.openConnection(proxy) as HttpURLConnection).apply {
            connectTimeout = 4000
            readTimeout = 4000
            useCaches = false
            defaultUseCaches = false
            instanceFollowRedirects = false
            requestMethod = "GET"
            setRequestProperty("Cache-Control", "no-cache, no-store, max-age=0")
            setRequestProperty("Pragma", "no-cache")
            setRequestProperty("Expires", "0")
            setRequestProperty("Connection", "close")
            setRequestProperty("Accept-Encoding", "identity")
            if (compactResponse) {
                setRequestProperty("Range", "bytes=0-0")
            }
            setRequestProperty("User-Agent", "tvproxy-diagnostics/1.0")
        }
    }

    private fun classifyException(error: Exception): ProbeStatus {
        return when (error) {
            is SocketTimeoutException -> ProbeStatus.TIMEOUT
            is UnknownHostException -> ProbeStatus.DNS_FAILURE
            is ConnectException -> ProbeStatus.TCP_FAILURE
            is SSLHandshakeException, is SSLException -> ProbeStatus.TLS_FAILURE
            is IOException -> ProbeStatus.IO_ERROR
            else -> ProbeStatus.IO_ERROR
        }
    }

    private fun withCacheBuster(rawUrl: String): String {
        val separator = if (rawUrl.contains('?')) '&' else '?'
        return "$rawUrl${separator}t=${System.currentTimeMillis()}&nonce=${UUID.randomUUID()}"
    }

    private fun countryName(code: String): String {
        if (code.isBlank()) return ""
        val locale = Locale("", code)
        return locale.getDisplayCountry(Locale.SIMPLIFIED_CHINESE)
    }

    private fun fetchByteDanceGeo(context: Context): ByteDanceGeoInfo? {
        var connection: HttpURLConnection? = null
        return try {
            connection = openConnection(
                context,
                "https://lf3-zone-xg.byte-tos.com/obj/ies-hotsoon-draft/api/get_ip.json",
                compactResponse = false
            )
            if (connection.responseCode !in 200..299) return null
            val json = connection.inputStream.bufferedReader().use { it.readText() }
            val data = JSONObject(json).optJSONObject("data") ?: return null
            ByteDanceGeoInfo(
                ip = data.optString("ip"),
                country = data.optString("country"),
                region = data.optString("province"),
                city = data.optString("city"),
                isp = firstNonBlank(data.optString("isp"), data.optString("carrier"))
            )
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun fetchIpApiGeo(context: Context): IpApiGeoInfo? {
        var connection: HttpURLConnection? = null
        return try {
            connection = openConnection(
                context,
                "http://ip-api.com/json/?lang=zh-CN",
                compactResponse = false
            )
            if (connection.responseCode !in 200..299) return null
            val json = connection.inputStream.bufferedReader().use { it.readText() }
            val obj = JSONObject(json)
            if (obj.optString("status") != "success") return null
            IpApiGeoInfo(
                ip = obj.optString("query"),
                countryCode = obj.optString("countryCode"),
                countryName = obj.optString("country"),
                region = obj.optString("regionName"),
                city = obj.optString("city"),
                isp = obj.optString("isp"),
                asn = obj.optString("as").substringBefore(' ').ifBlank { obj.optString("as") }
            )
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun fetchMyIpGeo(context: Context): MyIpGeoInfo? {
        var connection: HttpURLConnection? = null
        return try {
            connection = openConnection(
                context,
                "https://api.myip.com",
                compactResponse = false
            )
            if (connection.responseCode !in 200..299) return null
            val json = connection.inputStream.bufferedReader().use { it.readText() }
            val obj = JSONObject(json)
            MyIpGeoInfo(
                ip = obj.optString("ip"),
                countryCode = obj.optString("cc"),
                countryName = obj.optString("country"),
                asn = ""
            )
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun firstNonBlank(vararg values: String?): String {
        return values.firstOrNull { !it.isNullOrBlank() }.orEmpty()
    }

    private data class ByteDanceGeoInfo(
        val ip: String,
        val country: String,
        val region: String,
        val city: String,
        val isp: String
    )

    private data class IpApiGeoInfo(
        val ip: String,
        val countryCode: String,
        val countryName: String,
        val region: String,
        val city: String,
        val isp: String,
        val asn: String
    )

    private data class MyIpGeoInfo(
        val ip: String,
        val countryCode: String,
        val countryName: String,
        val asn: String
    )
}
