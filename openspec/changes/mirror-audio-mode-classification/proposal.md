# Proposal

## Why

iPhone 屏幕镜像会同时携带视频流（type 110）和镜像伴音流（type 96，`usingScreen=true`，AAC-ELD）。
当前 type 96 分支不区分镜像伴音和音乐投屏，一律回调 `onAudioStart()`，导致镜像页面被关闭、
首页卡片与通知栏显示成“AirPlay 音乐”，镜像被误识别为音频投屏。

## What Changes

- 在 AirPlay SETUP 的 type 96 音频流处理中识别“镜像伴音”：
  `usingScreen=true` 或 `ct=8`（AAC-ELD）时，照常创建低延迟音频接收链路，
  但不触发音乐投屏的会话激活与页面切换。
- 同一个 SETUP 请求同时包含 110（镜像视频）与 96（音频）时，最终投屏模式 SHALL 固定为
  屏幕镜像，不受流处理顺序影响。
- 镜像伴音期间不得清空或覆盖音乐元数据状态（`AudioSession`），音乐投屏的历史信息在镜像结束后仍可由下一次音乐投屏正常刷新。
- 纯音乐投屏（`usingScreen=false` 且非 AAC-ELD）行为保持不变，仍进入 AirPlay 音乐模式。
- 镜像结束（TEARDOWN/连接断开）时，镜像伴音随之停止，不得把模式残留成音频模式。

## Capabilities

### New Capabilities

- `airplay-screen-mirror`: AirPlay 屏幕镜像能力，本次补充“镜像伴音不得切换为音乐模式”的分类行为要求。

### Modified Capabilities

None.

## Impact

- `app/src/main/java/com/casttoyourtv/receiver/airplay/RtspListener.java`：
  type 96 分支增加镜像伴音判定；同一 SETUP 内流类型收集后统一决定激活模式。
- `app/src/main/java/com/casttoyourtv/receiver/CastService.java`：
  `onAudioStart()` 不再被镜像伴音触发；模式切换入口保持现有回调契约。
- 不新增依赖，不改 AirPlay 协议报文，不改 UI 结构。
- 依赖 iPhone 实机回归：镜像（含伴音）、Apple Music 音乐投屏、镜像后立即音乐投屏三条链路。
