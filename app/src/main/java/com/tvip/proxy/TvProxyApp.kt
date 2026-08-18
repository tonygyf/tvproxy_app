package com.tvip.proxy

import android.app.Application
import com.github.kr328.clash.common.Global

class TvProxyApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // core 模块（复用自 ClashMetaForAndroid）要求先注册 Application 实例，
        // 否则第一次调用 Clash.* 方法时会因为拿不到 Context 直接崩溃。
        Global.init(this)
    }
}
