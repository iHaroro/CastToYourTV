# CastToYourTV

运行在 Android TV 上的自研投屏接收器，替代乐播等收费投屏软件。
在 Sony BRAVIA（Android 10）上实测通过。

## 功能

- DLNA 投屏：安卓手机/相册/视频 App 投放，支持快进、暂停、画面比例调整
- AirPlay 屏幕镜像：iPhone 屏幕镜像（FairPlay 解密 + H.264 硬解）
- AirPlay 音乐投放：iPhone 隔空播放音乐（ALAC/AAC 解码），带播放界面（封面、曲名、已播时长、音量）
- 后台常驻：前台服务 + 开机自启，不打开 App 也能被搜索并自动拉起播放页

## 技术要点

- AirPlay：mDNS 广播、RTSP 握手、pair-verify（X25519/Ed25519）、FairPlay fp-setup 与 ekey 解密（Playfair 纯 Java 移植，参考 UxPlay lib/playfair）、镜像流 AES-128-CTR、音频流 AES-128-CBC
- 播放：MediaCodec 硬解 H.264（镜像）、AudioTrack + 纯 Java ALAC 解码器（音乐）
- DLNA：SSDP 广播 + DMR（AVTransport/RenderingControl）

## 参考项目

- UxPlay（AirPlay 协议实现，C）：https://github.com/FDH2/UxPlay
- java-airplay（纯 Java 参考）：https://github.com/serezhka/java-airplay

## 构建

需要 Android SDK 与 JDK 17，local.properties 指向本机 SDK 路径后执行：

    gradle :app:assembleDebug

## 说明

仅供学习研究使用。FairPlay 相关实现来自公开的逆向成果（UxPlay 项目）。
