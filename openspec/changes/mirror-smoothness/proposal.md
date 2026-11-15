# Proposal

## Why

用户反馈 iPhone 屏幕镜像存在卡顿。实机日志显示电视端解码链路完全跟得上
（队列不堆积、无丢帧、无解码器重启），但 iPhone 发送帧率在会话约 35 秒后
从 45~50fps 降到 ~33fps，其中一段 5 秒仅 23fps。需要定位该降帧是
iPhone 内容自适应编码的正常行为，还是本接收端的协议应答（NTP 对时、
RECORD 头）导致发送端限速。

## What Changes

- 为镜像链路增加秒级可观测指标：帧到达率、渲染率、队列深度、丢帧数，
  用于区分“发送端少发”和“接收端处理慢”。
- 对齐 UxPlay 审计 AirPlay NTP 对时包格式（请求/应答字节布局与时间戳精度），
  修正可能导致发送端误判延迟的偏差。
- 审计 `RECORD` 应答头（`Audio-Latency`、`Audio-Jack-Status`）对镜像帧率的影响。
- 与电视自带 AirPlay 接收端做同内容 A/B 对比，建立帧率基线。
- 修复镜像伴音卡顿：加大镜像音频缓冲并增加预缓冲、按 RTP 序号丢弃乱序/迟到重传包、
  提高音频线程优先级，消除 AudioTrack underrun。
- 若确认为发送端内容自适应，则只保留诊断能力并记录结论，不做无效优化。

## Capabilities

### New Capabilities

- `airplay-screen-mirror`: AirPlay 屏幕镜像能力，本次补充“卡顿可诊断、
  接收端不引入额外卡顿”的性能与可观测性要求。

### Modified Capabilities

None.

## Impact

- `MirrorReceiver` / `MirrorSession`：帧到达与丢帧计数。
- `MirrorActivity`：渲染率与队列深度指标。
- `AudioRenderer` / `AudioReceiver`：伴音缓冲、预缓冲、RTP 序号去重与线程优先级。
- `NtpTiming`：对时包格式审计与修正。
- `RtspListener`：RECORD 应答头审计。
- 不新增依赖；不影响镜像画面正确性与伴音分类逻辑
  （`mirror-audio-mode-classification` 已另行处理）。
