<p align="center">
  <img
    src="https://raw.githubusercontent.com/tonygyf/tvproxy_app/main/app/src/main/ic_launcher-playstore.png"
    alt="TV Proxy"
    width="160"
  />
</p>

<h1 align="center">TV Proxy</h1>

<p align="center">一个面向 Android TV 的全局代理控制工具，支持订阅导入、节点切换、开机恢复，以及可直接在电视端查看的网络状态与站点连通性诊断。</p>

<p align="center">
  <a href="https://github.com/tonygyf/tvproxy_app#平台支持"><img src="https://img.shields.io/badge/platform-Android%20TV%20%7C%20Android-lightgrey" alt="Platform"></a>
  <a href="https://github.com/tonygyf/tvproxy_app#核心能力"><img src="https://img.shields.io/badge/core-Clash%20Core%20%7C%20Mihomo-brightgreen" alt="Core"></a>
  <a href="https://github.com/tonygyf/tvproxy_app#网络诊断"><img src="https://img.shields.io/badge/diagnostics-Cloudflare%20%7C%20Geo%20%7C%20Site%20Ping-blue" alt="Diagnostics"></a>
  <a href="https://github.com/tonygyf/tvproxy_app#首次使用"><img src="https://img.shields.io/badge/permission-ADB%20Grant-orange" alt="Permission"></a>
</p>

<p align="center">
  <a href="https://github.com/tonygyf/tvproxy_app/releases/latest"><img src="https://img.shields.io/github/v/release/tonygyf/tvproxy_app" alt="Latest Release"></a>
  <a href="https://github.com/tonygyf/tvproxy_app/releases"><img src="https://img.shields.io/github/v/release/tonygyf/tvproxy_app?include_prereleases" alt="Pre-release"></a>
  <a href="https://github.com/tonygyf/tvproxy_app#构建"><img src="https://img.shields.io/badge/minSdk-24%2B-6f42c1" alt="minSdk"></a>
  <a href="https://github.com/tonygyf/tvproxy_app/releases"><img src="https://img.shields.io/github/downloads/tonygyf/tvproxy_app/total" alt="Downloads"></a>
  <a href="https://github.com/tonygyf/tvproxy_app/stargazers"><img src="https://img.shields.io/github/stars/tonygyf/tvproxy_app" alt="Stars"></a>
</p>
<p align="center">
  订阅导入 · 节点切换 · Global Proxy · 网络诊断
</p>
<hr>
## 项目简介

TV Proxy 运行在 Android TV / 电视盒子上，核心目标是把电视端代理操作做得更直接：

- 在电视界面中导入订阅并更新节点
- 一键启动或关闭系统全局代理
- 快速切换当前节点，并记住上次选择
- 在首页实时查看出口 IP、地区、Cloudflare POP 和多站点连通性
- 通过开机广播自动恢复上次代理状态

它更偏向“电视端可直接操作的代理控制台”，而不是单纯的配置导入器。

## 平台支持

- Android TV
- Android 7.0+（`minSdk 24`）
- 遥控器 D-pad 操作优先

## 核心能力

| 能力 | 说明 | 状态 |
| --- | --- | --- |
| 订阅导入 | 从订阅链接拉取配置并保存到本地 | ✅ |
| 全局代理开关 | 启动本地 HTTP 代理并写入系统全局代理设置 | ✅ |
| 节点切换 | 自动找出真实节点最多的代理组并切换节点 | ✅ |
| 网络状态 Banner | 首页显示国内 / 国外 / Cloudflare / 墙外测试摘要 | ✅ |
| 网络诊断 | 测试国内外常用站点，展示延迟、HTTP 状态和地区信息 | ✅ |
| 开机恢复 | 开机后自动拉起服务并恢复上次代理状态 | ✅ |
| TV 焦点优化 | 展开详情后支持遥控器逐项选中、内部滚动、返回先收起 | ✅ |

## 网络诊断

首页顶部 Banner 会持续显示几类关键结果：

- 国内出口 IP 与归属地
- 国外出口 IP 与地区信息
- Cloudflare Trace 的 IP / 国家 / POP
- 国内外站点探测成功数

展开详情后，可以继续查看国内外站点的逐项结果，包括：

- 站点名称
- 当前状态
- 详细错误或 HTTP 返回
- 延迟

当前诊断站点覆盖国内外常用服务，适合快速判断“代理是否真的生效”和“墙外链路是否通畅”。

## 首次使用

本项目需要写入系统全局代理设置，因此首次使用前需要执行一次 ADB 授权：

```bash
adb shell pm grant com.tvip.proxy android.permission.WRITE_SECURE_SETTINGS
```

授权完成后，在电视端的推荐使用流程如下：

1. 打开 App，粘贴订阅地址
2. 点击“导入更新节点”
3. 点击“连接”
4. 在节点列表里切换到想使用的节点
5. 观察顶部 Banner 和展开详情中的网络检测结果

## 交互说明

- 点击顶部网络卡片会展开站点详情
- 展开后，遥控器向下会优先进入详情区，而不是直接掉到下方主页面
- 详情区内可以逐项移动焦点，并自动内部滚动
- **展开状态下按返回键，会先收起详情**
- **只有在收起状态下再次按返回键，才会退出 App**

## 构建

环境要求：

- Android Studio / Gradle
- JDK 17
- Android SDK 34

构建 `alphaDebug`：

```powershell
.\gradlew.bat :app:assembleAlphaDebug
```

生成的 APK 通常位于：

```text
app/build/outputs/apk/alpha/debug/
```

## 项目结构

```text
app/       Android TV 前端、服务、启动逻辑、诊断页面
core/      代理内核与相关 native / golang 构建
common/    公共依赖与共享代码
hideapi/   隐藏 API 相关支持
```

## 已知前提与限制

- 依赖 `WRITE_SECURE_SETTINGS`，未授权时无法写入系统全局代理
- 当前走的是系统全局 HTTP 代理链路，部分 App 可能需要重新拉起后才会重新读取代理设置
- 关闭代理时会尝试清理后台 App 进程，以减少旧连接残留
- 仓库根目录当前没有单独声明项目级 `LICENSE` 文件；如果你准备公开分发，建议在发布前补充明确的许可证说明

## 致谢

- [kr328/clash core](https://github.com/kr328/clash) 相关 Android Core 能力
- [MetaCubeX](https://github.com/MetaCubeX) 生态提供的代理内核与 Maven 依赖镜像
