# Design

## Context

实机日志（2026-11-16）：Home 后重新打开，解码器重建，222 帧入队 0 帧输出，
watchdog 连续重启仍黑屏。iPhone 镜像流只在开始时发 IDR，后续全部为 P 帧，
因此任何“重建解码器”的路径在流中段都会因为缺少参考帧而无法出画。

唯一可靠的方案是：解码器从流开始到结束只创建一次，全程持续消化码流。

## Goals / Non-Goals

**Goals：**

- Home → 重新打开，镜像立即恢复显示，无需 iPhone 重发关键帧。
- 后台期间解码链路继续消费帧，`MirrorSession` 队列不堆积。
- 解码器不支持动态切 Surface 时安全回退，不崩溃。

**Non-Goals：**

- 不做后台预览/画中画。
- 不修改 AirPlay 协议交互，不请求发送端补发关键帧。
- 不处理 Activity 被系统杀死后的恢复（进程级重启）。

## Decisions

### 解码线程只随会话启停，不随 Surface 启停

`surfaceCreated`：
- 解码线程未运行 → 以当前 Surface 创建解码器并启动线程；
- 解码线程已运行 → 记录 `pendingSurface`，由解码线程在循环里执行切换。

`surfaceDestroyed`：
- 只置 `rendering=false`（停靠模式），线程继续 queue input / drain output，
  输出统一 `releaseOutputBuffer(idx, false)`，不触碰已销毁的 Surface。

### 回台用 `setOutputSurface()` 热切换

Android 5.1+ 的 `MediaCodec.setOutputSurface()` 允许在解码器运行中替换输出
Surface（电视为 Android 10）。回台时解码线程执行 `codec.setOutputSurface(newSurface)`
后继续渲染。解码器内部参考帧不受影响，下一帧 P 帧即可正常显示。

### 回退策略

`setOutputSurface()` 抛异常（部分厂商解码器不支持）时：

- 记录 `setOutputSurface failed`；
- 释放旧解码器，用新 Surface 重建（即当前行为）；
- 后果仍是黑屏直到下一个 IDR，但行为不劣于现状，且日志可定位。

## Risks / Trade-offs

- [停靠期间解码输出被丢弃] → 参考链由解码器内部维护，丢弃渲染不影响解码状态。
- [厂商解码器不支持动态切 Surface] → 回退路径 + 日志。
- [后台线程持续消耗 CPU] → 码流必须持续消化以维持参考链，这是方案成立的前提；
  画面为静止内容时 iPhone 本身发包极少，开销可忽略。

## Migration Plan

覆盖安装即可，无数据迁移；回滚安装旧版 APK。

## Open Questions

None blocking implementation.
