# 云电视 · Cloud TV

[![Build APK](https://github.com/hongyuray/cloud-tv/actions/workflows/build.yml/badge.svg)](https://github.com/hongyuray/cloud-tv/actions/workflows/build.yml)

一个 200 KB 的 Android 直播播放器，专为电视盒子 / 老电视设计：一个 m3u 订阅就能看电视直播，无广告、无后台、无第三方 SDK。

> 中文名 **云电视**，英文名 **Cloud TV**，包名 `com.dsh.lightlive`

![图标](app/res/drawable/ic_launcher.png)

## 安装

1. 到 [Releases](../../releases) 下载最新的 `CloudTV-x.x.x.apk`（没有 Release 时，去 **Actions → 最近一次 Build APK → Artifacts** 下载）
2. 拷到 U 盘插入电视，用电视自带的「文件管理 / 电视卫士」打开安装
3. 电视提示"未知来源"时，在 **设置 → 安全 → 允许安装未知来源应用** 里放行
4. 打开即用：首次启动会自动加载内置清单（随包携带，离线可用）

要求：Android 5.0 及以上；不含任何 `.so`，ARMv7 / ARM64 / x86 都能装。

## 特性

- **极简**：约 200 KB，纯 Java + 系统 `VideoView`/`MediaPlayer`，零第三方依赖
- **内置已测清单**：随包携带 735 条线路（直播 547 + 点播 159），CCTV1–17 齐全，离线可用
- **多线路**：同名频道的多条 URL 自动合并为"线路"，可按延迟 / 分辨率手动切换，失败自动换线
- **连接可见**：播放时显示频道、线路 x/n、源、10 秒倒计时，超时自动切
- **直播 / 回放标识**：绿色 `● 直播`，琥珀色 `回放 · 时间`
- **手机推送**：电视内建 HTTP 服务 + 二维码，手机扫码即可推送 m3u 地址
- **节目单**：XMLTV 解析，支持 `catchup-source` 回看（需源支持）
- **收藏 / 开机续播 / 只看直播 / 连接超时** 等设置

## 遥控器

| 按键 | 频道栏 | 菜单栏 | 播放中 |
|---|---|---|---|
| ↑ ↓ | 选频道 | 选菜单项 | ↑ 线路 ↓ 节目单 |
| ← → | → 进菜单栏 | ← 回频道栏 | ← → 换台 |
| OK | 播放 | 执行 | 回列表 |
| 返回 | 退出 | 回频道栏 | 回列表 |
| 菜单/信息/红键 | 设置 | — | 设置 |

按键处理不依赖 View 焦点，开机即可操作。

## 截图

| 图标 | 电视横幅 |
|---|---|
| ![icon](app/res/drawable/ic_launcher.png) | ![banner](app/res/drawable/banner.png) |

## 本地构建

不依赖 Gradle，只用 aapt2 + javac + d8 + zipalign + apksigner。

需要 JDK 17、Android build-tools 34、android-34 的 `android.jar`，按下面放置（或用环境变量 `CLOUDTV_JDK` / `CLOUDTV_BT` / `CLOUDTV_ANDROID_JAR` 指定）：

```
sdk/
  jdk/jdk-17.0.2/
  bt/android-14/
  plat/android-34/android.jar
```

```bash
python tools/build_apk.py
```

输出仓库根目录的 `CloudTV-<版本>.apk`。

## 自动构建（GitHub Actions）

`.github/workflows/build.yml` 会在每次 push 到 `main` 时自动打包：

- 推代码 → 自动出 APK，去 **Actions → Build APK → Artifacts** 下载
- 打 tag（如 `git tag v1.9.6 && git push --tags`）→ 自动创建 Release 并附上 APK

## 目录结构

```
app/
  AndroidManifest.xml
  assets/builtin.m3u          内置已测播放列表
  res/values{,en}/strings.xml 中英文应用名
  res/drawable/               图标与电视横幅
  src/com/dsh/lightlive/
    MainActivity.java         界面 / 播放 / 按键 / 面板
    ConfigServer.java         手机推送 HTTP 服务
    QrCode.java               QR 编码器（字节模式 / 纠错 M / 版本 1-15）
    QrView.java               二维码绘制
    EpgStore.java             XMLTV 解析
    M3UParser.java            m3u 解析与多线路聚合
    Channel.java              频道模型
tools/build_apk.py            构建脚本（跨平台）
.github/workflows/build.yml   自动打包
```

## 说明

- 本项目只提供播放器外壳，不含任何内容源。内置清单来自互联网公开聚合，仅供学习研究，请勿用于商业用途。
- 直播源随时可能失效，可在设置里更换 m3u，或用手机推送自己的订阅地址。

## License

MIT
