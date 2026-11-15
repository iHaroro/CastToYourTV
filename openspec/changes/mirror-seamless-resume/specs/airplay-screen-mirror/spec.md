# Spec Delta

## Purpose

接收 iPhone/iPad 的 AirPlay 屏幕镜像，在电视上实时渲染设备屏幕内容，并保证镜像页后台恢复时立即出画。

## ADDED Requirements

### Requirement: 镜像后台恢复不黑屏
当镜像进行中用户离开镜像页（按电视 Home）后再返回时，系统 SHALL 在不重建解码器、不要求发送端重发关键帧的情况下立即恢复画面显示。

#### Scenario: Home 后重新打开
- **WHEN** iPhone 屏幕镜像进行中，用户按电视 Home 离开镜像页
- **AND** 随后从电视桌面重新打开应用
- **THEN** 应用直接回到镜像页
- **AND** 画面立即恢复显示，不出现黑屏等待

#### Scenario: 多次进出后台
- **WHEN** 用户在镜像期间多次按 Home 并重新打开应用
- **THEN** 每次返回都立即恢复画面
- **AND** 镜像会话保持不断开
