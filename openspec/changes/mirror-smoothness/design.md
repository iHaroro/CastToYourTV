# Design

## Context

见 `proposal.md`。已采集的实机数据（2026-11-16，iPhone 镜像 4.5 分钟）：

- 电视端 `queued` 始终等于 `rendered + 1`：解码消费能力充足，队列不堆积；
- 解码器零重启、零 `input buffer too small`、零 `invalid NAL`；
- iPhone 发送帧率：前 35 秒 45~50fps，之后稳定 ~33fps，一段 5 秒仅 23fps；
- 电视 WiFi：5GHz，RSSI -60，链路 702Mbps；
- `/feedback` 空应答与 UxPlay 行为一致，已排除。

## Goals / Non-Goals

**Goals：**

- 任何一次镜像卡顿都能从日志判断瓶颈在发送端还是接收端。
- 接收端解码/渲染链路不引入额外延迟与丢帧。
- NTP 对时应答与 UxPlay 逐字节一致，排除协议层诱因。
- 建立与电视自带 AirPlay 的帧率对比基线。

**Non-Goals：**

- 不承诺 60fps：AirPlay 镜像帧率由 iPhone 按内容自适应决定。
- 不做音视频对齐/时间戳缓冲：当前按到达即播，延迟最小。
- 不改镜像分类逻辑（另行变更）。

## Decisions

### 指标先行，而不是先改协议参数

在 `MirrorReceiver`（到达）与 `MirrorActivity`（渲染）各加一个秒级计数器，
每秒输出：`arrived/rendered/queued/dropped`。判读规则：

```
arrived 低、queued 不涨  → 发送端少发（iPhone 内容自适应或限速）
arrived 高、queued 涨    → 接收端消费慢（解码/渲染瓶颈）
dropped > 0             → 队列溢出丢帧（延迟控制触发）
```

### NTP 对时包逐字节对齐 UxPlay

当前 `NtpTiming` 是最小实现，注释明确省略了时钟偏移计算。若应答字节布局
或时间戳精度与协议预期不符，iPhone 可能高估链路延迟并主动降帧。审计项：

- 请求/应答包长与头部（0x80/0xd2/0xd3）；
- 客户端时间戳回填位置；
- NTP 时间戳的小数部分精度（当前毫秒级，UxPlay 为纳秒级 q32.32）。

### A/B 基线：电视自带 AirPlay

同一 iPhone、同一内容、同一时段分别投本应用与电视自带接收端，
用秒表/录屏对比流畅度。若两者帧率曲线一致，则判定为发送端自适应，
本变更只交付诊断能力与结论记录。

### 镜像伴音卡顿的直接原因与修复

实机日志已出现 `AudioTrack ... disabled due to previous underrun, restarting`。
链路审计发现三个问题：

1. AudioTrack 缓冲仅 `min*1.5`（约 70~140ms），WiFi 抖动即可 underrun；
2. 解码线程收到帧立即播放，没有预缓冲，抖动直接穿透到输出；
3. RTP 序号只做"等于"去重，迟到的重传包与乱序包会被插入播放序列造成爆音。

修复：

- 镜像伴音（低延迟模式）AudioTrack 缓冲提高到 `min*4`（约 250~400ms）；
- 播放前预缓冲 8 帧（AAC-ELD spf=480 时约 86ms），队列清空后重新预缓冲，
  避免连续抖动反复打空；
- 序号按环形距离比较，`diff==0 或 diff>0x7FFF` 判为重复/迟到并丢弃；
- 音频接收与渲染线程提升到 `THREAD_PRIORITY_URGENT_AUDIO`。

镜像模式对延迟的敏感度低于卡顿，250ms 级缓冲换流畅是正确取舍；
音乐投屏路径保持现状（用户已验证无异常）。

## Risks / Trade-offs

- [增加日志可能影响性能] → 每秒仅一条聚合日志，不逐帧打印。
- [NTP 修正可能改变现有可用链路] → 修正后必须完整回归镜像与音乐。
- [A/B 对比主观] → 以录屏逐帧统计为准，不凭体感。

## Migration Plan

诊断日志随版本直接发布；NTP 修正需实机回归后再合入。无数据迁移。

## Open Questions

- iPhone 在静态画面下的降帧幅度是否与内容相关（需 A/B 数据确认）。
