# Tasks

## 1. 实现

- [x] 1.1 将 `MirrorActivity` 的解码线程与 `SurfaceView` 生命周期解耦：`surfaceDestroyed` 只停靠渲染，不停止解码；验证 Home 后线程仍持续消费帧（日志有 `frames rendered` 计数继续增长或队列不堆积）。
- [x] 1.2 回台时用 `MediaCodec.setOutputSurface()` 热切换输出 Surface，失败时回退重建解码器并记录日志；验证日志显示切 Surface 成功。
- [x] 1.3 保留镜像结束/页面真正销毁时的解码器释放；验证 TEARDOWN 后无残留解码线程。

## 2. 实机验证

- [x] 2.1 镜像 → Home → 重新打开：立即恢复画面，不黑屏；logcat 无 `decoder stalled`。
- [x] 2.2 镜像期间多次 Home/重开：每次立即恢复，会话不断开。
- [ ] 2.3 镜像 → Home → 重开 → 旋转 iPhone：画面与比例正常。
- [x] 2.4 回归音乐投屏与镜像伴音分类（模式显示正确）。

## 3. 构建与规格

- [x] 3.1 运行 `gradle :app:assembleDebug :app:assembleRelease` 并确认 BUILD SUCCESSFUL。
- [x] 3.2 运行 `openspec validate mirror-seamless-resume --strict` 并确认通过。

## Workflow follow-up

- 全部任务完成后归档该变更。
