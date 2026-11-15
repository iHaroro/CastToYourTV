# Tasks

## 1. 分类逻辑实现

- [x] 1.1 在 `RtspListener` 的 SETUP `streams` 处理中先收集本 SETUP 的流类型；验证同一 SETUP 含 110 与 96 时能识别为镜像会话（logcat 输出伴音分类结果）。
- [x] 1.2 在 type 96 分支实现镜像伴音判定 `usingScreen || ct == 8`：照常创建 `AudioRenderer/AudioReceiver`，但不调用 `AudioSession.onStart()` 与 `callback.onAudioStart()`；验证镜像 SETUP 日志显示伴音已建立且无音乐模式激活日志。
- [x] 1.3 验证纯音乐投屏（`usingScreen=false` 且非 ct=8）仍输出 `active cast mode: NONE -> AUDIO` 并打开音乐页。

## 2. 实机回归验证

- [x] 2.1 iPhone 屏幕镜像（播放有声视频）：画面与伴音正常，首页卡片/通知栏为“屏幕镜像”，不出现音乐页；logcat 模式保持 MIRROR。
- [x] 2.2 镜像期间 Home → 重新打开 App：回到镜像页且画面继续（沿用 active-cast 行为）。
- [x] 2.3 镜像结束后立即发起 Apple Music 投屏：正常进入音乐页，元数据与封面刷新正常。
- [x] 2.4 音乐投屏结束后立即发起屏幕镜像：正常进入镜像页且伴音正常。
- [x] 2.5 iPhone 端停止镜像：镜像页关闭、模式回 NONE、接收服务仍可被再次发现。

## 3. 构建与规格

- [x] 3.1 运行 `gradle :app:assembleDebug :app:assembleRelease` 并确认 BUILD SUCCESSFUL。
- [x] 3.2 运行 `openspec validate mirror-audio-mode-classification --strict` 并确认通过。

## Workflow follow-up

- 全部任务完成后归档该变更。
