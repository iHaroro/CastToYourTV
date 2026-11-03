# Proposal

## Why

电视上乐播等投屏软件自动更新后转会员收费，需要一个免费、自研、可常驻后台的投屏接收器。
本变更把已实现的 DLNA/AirPlay 镜像/音乐投放、自动拉起播放页、后台服务与开机自启整理为 0.0.1 版本基线需求。

## What Changes

- 建立 0.0.1 版本基线需求，覆盖四条接收链路
- DLNA 视频投放（含快进、暂停、画面比例调整）
- AirPlay 屏幕镜像（iPhone 屏幕镜像，FairPlay 解密 + H.264 硬解）
- AirPlay 音乐投放（ALAC/AAC 解码 + 播放界面，含封面/曲名/已播时长/音量/连接设备）
- 后台服务与开机自启（不打开 App 也能被搜索并自动拉起）

## Capabilities

### New Capabilities

- `dlna-video-cast`: DLNA/UPnP 视频投放接收，含播放控制与画面比例调整
- `airplay-screen-mirror`: AirPlay 屏幕镜像接收与渲染
- `airplay-audio-cast`: AirPlay 音乐/音频投放接收与播放界面
- `receiver-lifecycle`: 后台服务常驻、开机自启、服务自检与自动拉起播放页

### Modified Capabilities

无（0.0.1 为首次建基线）。

## Impact

- 代码：`app/src/main/java/com/casttoyourtv/receiver/`（DLNA、AirPlay、播放器、服务）
- 资源：图标、横幅、界面布局
- 文档：README 补全功能说明、构建步骤、协议参考
