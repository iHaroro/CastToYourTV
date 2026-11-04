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
- **投屏中返回**：按电视 Home 后再打开 App，会回到当前视频/镜像/音乐播放页；
  主界面同时显示“正在投屏”卡片和“返回播放器”按钮
- **结束当前投屏**：播放页按返回键弹出控制面板，可选择“继续观看”或“结束投屏”；
  结束只断开当前会话，DLNA/AirPlay 接收服务继续运行，电视仍可被发现

## 技术要点

- **AirPlay**：mDNS 广播（`_raop._tcp`/`_airplay._tcp`）、RTSP 握手、
  pair-verify（X25519/Ed25519）、FairPlay fp-setup 与 ekey 解密
  （Playfair 纯 Java 移植，参考 UxPlay `lib/playfair`）、
  镜像流 AES-128-CTR、音频流 AES-128-CBC
- **播放**：MediaCodec 硬解 H.264（镜像）、AudioTrack + 纯 Java ALAC 解码器（音乐）
- **DLNA**：SSDP 广播 + DMR（AVTransport/RenderingControl）

## 投屏中的返回与结束

- 视频、镜像、音乐播放页使用独立任务栈，电视 Home 键不会销毁正在播放的页面。
- 从电视 Launcher 重新打开 App 时，会优先回到当前播放页。
- 播放页按返回键打开控制面板：
  - `继续观看`：关闭面板，播放/镜像继续。
  - `结束投屏`：停止当前视频/镜像/音频并返回主界面；接收服务不退出。
- 镜像页或镜像流已经被系统回收时，主界面会提示重新投屏，不会恢复一个失效的镜像页。
- DLNA 是手机向电视推送的单向协议，电视端会立即停止并断开媒体；手机上的投屏浮层可能延迟消失。

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
