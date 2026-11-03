# Spec Delta

## Purpose

接收 iPhone/iPad 的 AirPlay 屏幕镜像，在电视上实时渲染设备屏幕内容。

## ADDED Requirements

### Requirement: AirPlay 镜像设备可被发现
系统 SHALL 通过 mDNS 广播 `_airplay._tcp` 与 `_raop._tcp` 服务，使 iPhone 的「屏幕镜像」列表能搜索到本设备。

#### Scenario: iPhone 搜索到镜像设备
- **WHEN** iPhone 打开控制中心进入「屏幕镜像」
- **THEN** 能看到「CastToYourTV-<型号>」设备条目

### Requirement: 免配对连接
系统 SHALL 支持 AirPlay pair-verify 免 PIN 配对，用户无需在电视上输入验证码即可建立连接。

#### Scenario: 无密码连接
- **WHEN** iPhone 在屏幕镜像列表中选择本设备
- **THEN** 直接建立镜像连接，不弹出 PIN 输入框

### Requirement: 镜像画面渲染
系统 SHALL 解密 FairPlay 加密的镜像视频流并实时渲染到电视屏幕。

#### Scenario: 镜像显示
- **WHEN** iPhone 屏幕内容变化
- **THEN** 电视端在合理延迟内同步显示对应画面

### Requirement: 镜像分辨率与帧率
系统 SHALL 根据 iPhone 屏幕分辨率协商镜像参数，并支持常见帧率（30/60fps）。

#### Scenario: 高分辨率镜像
- **WHEN** iPhone 以 1080p 或更高分辨率投屏
- **THEN** 电视端按实际分辨率渲染，画面清晰
