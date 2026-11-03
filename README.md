# CastToYourTV

运行在 Android TV 上的自研投屏接收器，替代乐播等收费投屏软件。
在 Sony BRAVIA（Android 10）上实测通过。

## 功能

- **DLNA 视频投屏**：安卓手机/相册/视频 App 投放，支持快进、暂停、画面比例调整
- **AirPlay 屏幕镜像**：iPhone 屏幕镜像（FairPlay 解密 + H.264 硬解）
- **AirPlay 音乐投放**：iPhone 隔空播放音乐（ALAC/AAC 解码），带播放界面
  - 封面、曲名、艺人·专辑、已播时长、音量、连接设备信息
  - 第三方 App 不带元数据时显示占位界面
- **后台常驻**：前台服务 + 开机自启，不打开 App 也能被搜索并自动拉起播放页
- **服务自检**：首页显示 DLNA/AirPlay 服务与悬浮窗权限状态

## 技术要点

- **AirPlay**：mDNS 广播（`_raop._tcp`/`_airplay._tcp`）、RTSP 握手、
  pair-verify（X25519/Ed25519）、FairPlay fp-setup 与 ekey 解密
  （Playfair 纯 Java 移植，参考 UxPlay `lib/playfair`）、
  镜像流 AES-128-CTR、音频流 AES-128-CBC
- **播放**：MediaCodec 硬解 H.264（镜像）、AudioTrack + 纯 Java ALAC 解码器（音乐）
- **DLNA**：SSDP 广播 + DMR（AVTransport/RenderingControl）

## 构建

需要 Android SDK 与 JDK 17。`local.properties` 指向本机 SDK 路径后执行：

    gradle :app:assembleDebug

生成的 APK 在 `app/build/outputs/apk/debug/`。

## 项目结构

    app/src/main/java/com/casttoyourtv/receiver/
    ├── airplay/     # AirPlay 协议（发现、配对、解密、镜像/音频流）
    ├── dlna/        # DLNA/UPnP 协议（SSDP、DMR）
    ├── player/      # 播放器界面（视频/镜像/音频）
    └── CastService  # 前台服务（双引擎常驻）

## 需求文档

0.0.1 版本基线需求见 `openspec/changes/release-0-0-1-baseline/`：

- `proposal.md`：变更动机与范围
- `specs/`：四个 capability 的行为规格（DLNA、镜像、音频、生命周期）
- `design.md`：架构与关键决策
- `tasks.md`：验收标准

## 参考项目

- [UxPlay](https://github.com/FDH2/UxPlay)（AirPlay 协议实现，C）
- [java-airplay](https://github.com/serezhka/java-airplay)（纯 Java 参考）

## 说明

仅供学习研究使用。FairPlay 相关实现来自公开的逆向成果（UxPlay 项目）。
