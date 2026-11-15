# Tasks

## 1. 诊断指标

- [ ] 1.1 在 `MirrorReceiver` 增加秒级帧到达计数并输出聚合日志；验证镜像期间每秒一条 `mirror stats arrived=N`。
- [ ] 1.2 在 `MirrorActivity` 增加秒级渲染计数与 `MirrorSession` 队列深度、丢帧数输出；验证日志能区分发送端降帧与接收端积压。
- [ ] 1.3 用 iPhone 镜像 3 分钟（含滚动与静止画面），保存日志并计算每秒帧率曲线。

## 1A. 镜像伴音修复

- [x] 1A.1 `AudioRenderer` 镜像低延迟模式 AudioTrack 缓冲提高到 `min*4`，播放前预缓冲 8 帧并在队列清空后重新预缓冲；验证日志无 `underrun, restarting`。
- [x] 1A.2 `AudioReceiver` 按 RTP 环形序号丢弃重复/迟到包，音频接收与渲染线程使用 `THREAD_PRIORITY_URGENT_AUDIO`；验证镜像伴音连续无爆音。
- [x] 1A.3 iPhone 镜像播放有声视频 3 分钟，电视端伴音无卡顿/爆音；音乐投屏回归无异常。

## 2. 协议审计

- [ ] 2.1 对照 UxPlay `raop_ntp.c` 逐字节审计 `NtpTiming` 请求/应答布局与时间戳精度，记录差异清单。
- [ ] 2.2 如发现影响延迟计算的差异，修正 `NtpTiming` 并重测镜像帧率曲线，对比修正前后。
- [ ] 2.3 审计 `RECORD` 应答 `Audio-Latency` 取值对镜像帧率的影响，记录结论。

## 3. 基线对比与结论

- [ ] 3.1 同一 iPhone、同一内容，分别投本应用与电视自带 AirPlay 接收端，录屏统计两者帧率/流畅度差异。
- [ ] 3.2 根据诊断数据与 A/B 结论，判定降帧归因（发送端自适应 / 接收端 / 协议应答），记录到本变更 design.md。
- [x] 3.3 运行 `gradle :app:assembleDebug :app:assembleRelease` 并确认 BUILD SUCCESSFUL。
- [x] 3.4 运行 `openspec validate mirror-smoothness --strict` 并确认通过。

## Workflow follow-up

- 全部任务完成后归档该变更。
