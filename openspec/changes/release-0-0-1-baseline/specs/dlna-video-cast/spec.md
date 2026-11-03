# Spec Delta

## Purpose

接收安卓手机/平板通过 DLNA/UPnP 投放的视频，在电视上播放，并支持常见播放控制。

## ADDED Requirements

### Requirement: DLNA 设备可被发现
系统 SHALL 通过 SSDP 广播设备服务，使手机投屏列表中能搜索到本设备。

#### Scenario: 手机搜索到电视
- **WHEN** 手机在局域网内打开投屏/投屏助手的设备列表
- **THEN** 能看到「CastToYourTV-<型号>」设备条目

### Requirement: 接收视频地址并播放
系统 SHALL 接受 DLNA SetAVTransportURI 请求中的视频 URL 并自动拉起播放页开始播放。

#### Scenario: 投放成功
- **WHEN** 手机在视频 App 中选择投屏到本设备
- **THEN** 电视在数秒内自动打开播放器并开始播放该视频

### Requirement: 播放控制
系统 SHALL 支持播放、暂停、停止、快进（Seek）控制指令。

#### Scenario: 手机控制播放
- **WHEN** 手机端点击暂停/继续/拖动进度条
- **THEN** 电视端播放器同步执行对应操作

### Requirement: 画面比例调整
系统 SHALL 提供画面比例切换（如原始、拉伸、16:9、4:3），并记住当前选择。

#### Scenario: 调整比例
- **WHEN** 用户在播放页用遥控器切换比例
- **THEN** 画面按所选比例渲染，且黑边处理方式符合选项
