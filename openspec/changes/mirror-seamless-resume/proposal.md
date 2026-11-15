# Proposal

## Why

镜像期间按电视 Home 再重新打开 App 会黑屏。根因：iPhone 只在流开始时发一个
IDR 关键帧，之后全是 P 帧；Home 释放解码器后，回台重建解码器时队列里没有
关键帧，H.264 参考链断裂，永远解不出画面，且 iPhone 不会周期性补发 IDR。

## What Changes

- 解码线程与 `SurfaceView` 生命周期解耦：Home/后台只停止向画面输出
  （`releaseOutputBuffer(false)`），不再释放解码器。
- 回到前台时通过 `MediaCodec.setOutputSurface()` 把输出切回新的画面 Surface，
  解码参考链全程不断，恢复立即出画。
- 若设备解码器不支持动态切 Surface，回退到现有“重建解码器”路径并记录日志。
- 镜像页销毁（真正结束投屏）时仍完整释放解码线程。

## Capabilities

### New Capabilities

- `airplay-screen-mirror`: AirPlay 屏幕镜像能力，本次补充“后台恢复不黑屏”的行为要求。

### Modified Capabilities

None.

## Impact

- `MirrorActivity`：`surfaceCreated/surfaceDestroyed` 生命周期与解码线程解耦，
  增加输出 Surface 动态切换与后台“停靠”渲染。
- 不改 `MirrorReceiver/MirrorSession` 协议与队列逻辑。
- 不新增依赖。
