# Design

## Context

见 `proposal.md` 的缺陷描述。当前 `RtspListener` 对 SETUP `streams` 数组逐条处理：
type 110 建立 `MirrorReceiver` 并回调 `onMirrorStart()`；type 96 建立音频链路并回调
`onAudioStart()`。iPhone 屏幕镜像的两条流可能出现在同一 SETUP 中，且顺序不保证，
因此后处理的 type 96 会把已激活的 MIRROR 模式覆盖成 AUDIO。

`CastService.onAudioStart()` 是音乐投屏的唯一入口，它做三件事：设置 `CastMode.AUDIO`、
关闭 `MirrorActivity`、打开 `AudioPlayerActivity`。所以缺陷的可见表现是镜像画面被音乐页替换。

## Goals / Non-Goals

**Goals：**

- 镜像伴音（`usingScreen=true` 或 `ct=8`）只建立音频播放链路，不激活音乐投屏模式。
- 同一 SETUP 内 110 与 96 并存时，模式判定与数组顺序无关。
- 镜像会话内的音乐元数据不被伴音 SETUP 清空。
- 纯音乐投屏路径完全不变。

**Non-Goals：**

- 不实现镜像伴音音量独立调节 UI。
- 不改 AirPlay 协议、mDNS 广播与 FairPlay 流程。
- 不处理“镜像与音乐分属两条并发 RTSP 连接”之外的复杂并发场景（现有单会话模型已覆盖常见用法）。

## Decisions

### 判定镜像伴音：`usingScreen || ct == 8`

两个信号任一命中即视为镜像伴音：

- `usingScreen=true`：iPhone 明确声明该音频流服务于屏幕镜像；
- `ct=8`（AAC-ELD）：镜像伴音的低延迟编码，音乐投屏使用 ALAC/AAC-LC 等深缓冲编码。

双信号取“或”而不是单依赖 `usingScreen`：不同 iOS 版本对可选字段的填充不完全一致，
编码类型是更稳定的兜底信号。该判据已在现有代码中用于决定低延迟缓冲
（`boolean lowLatency = usingScreen || ct == 8;`），只是没有用于模式分类，复用同一语义可避免两处判据漂移。

### 镜像伴音不触发 `onAudioStart()`

type 96 且判定为镜像伴音时：

1. 照常创建 `AudioRenderer`（低延迟）与 `AudioReceiver`，保证声音正常；
2. 不调用 `AudioSession.onStart()`，避免清空音乐元数据；
3. 不调用 `callback.onAudioStart()`，避免模式被覆盖、镜像页被关闭。

`onAudioStart()` 的语义收窄为“纯音乐投屏开始”。这是最小改动：协议解析层决定分类，
服务层不感知 `usingScreen` 细节。

### 同一 SETUP 的模式以“是否有镜像视频流”为准

处理 `streams` 数组前先扫描一遍类型集合。若同一 SETUP 同时出现 110 与 96：

- 110 存在 → 该 SETUP 属于屏幕镜像会话，伴音按镜像伴音处理；
- 只有 96 → 按现有音乐投屏流程处理。

这样即使 iPhone 把音频流放在视频流之前，也不会先把模式置成 AUDIO 再被 MIRROR 覆盖
（后者虽然能自愈，但会造成页面闪烁与通知栏文案抖动）。

备选方案：仅依赖回调顺序，让后到的 `onMirrorStart()` 再纠正模式。否决原因：会短暂
打开音乐页再关闭，可见闪烁；且通知栏文案会抖动。

### 断开路径沿用现有清理

TEARDOWN/连接断开时已有 `onMirrorStop()` + `onAudioStop()` 顺序清理。
镜像模式下 `clearMode(AUDIO)` 不命中，不会误关镜像页；伴音链路由
`AirPlayServer.stopActiveSessions()` 与连接级 `finally` 释放。不需要新增状态。

## Risks / Trade-offs

- [某些 iOS 版本音乐投屏也使用 AAC-ELD] → 以 `usingScreen` 为主判据、`ct=8` 为兜底；
  若实测出现误判，优先收紧为仅 `usingScreen`，不引入新状态机。
- [镜像伴音 SETUP 重复重发] → 沿用现有 `p.audioCt/p.audioSpf` 去重复用逻辑，
  不重复建流，也不会重复触发模式回调。
- [镜像结束后立即音乐投屏] → 音乐 SETUP 满足 `usingScreen=false` 且非 ct=8，
  正常走 `onAudioStart()`；旧镜像页由该回调主动关闭。
- [分类错误导致镜像无声] → 伴音链路创建逻辑不变，仅移除模式回调；用 iPhone 实机
  镜像（有声视频）验证。

## Migration Plan

直接覆盖安装新 APK；无数据迁移。回滚为安装上一版本 APK。发送端无需任何操作。

## Open Questions

None blocking implementation.
