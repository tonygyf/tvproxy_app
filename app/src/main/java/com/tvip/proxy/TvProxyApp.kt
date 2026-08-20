package com.tvip.proxy

import android.app.Application
import com.github.kr328.clash.common.Global
import java.io.File
import java.io.FileOutputStream

class TvProxyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        
        extractGeoFiles()

        // core 模块（复用自 ClashMetaForAndroid）要求先注册 Application 实例，
        // 否则第一次调用 Clash.* 方法时会因为拿不到 Context 直接崩溃。
        Global.init(this)
    }

    private fun extractGeoFiles() {
        val targetDir = File(filesDir, "clash")
        if (!targetDir.exists()) targetDir.mkdirs()

        listOf("geoip.metadb", "geosite.dat").forEach { name ->
            val outFile = File(targetDir, name)
            // 如果文件不存在，或者文件小于 1MB（说明之前被内核下载失败生成了空文件/损坏文件），则重新解压
            if (!outFile.exists() || outFile.length() < 1024 * 1024L) {
                try {
                    assets.open(name).use { input ->
                        FileOutputStream(outFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }
}
