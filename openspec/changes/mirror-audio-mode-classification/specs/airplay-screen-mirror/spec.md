# Spec Delta

## Purpose

接收 iPhone/iPad 的 AirPlay 屏幕镜像，在电视上实时渲染设备屏幕内容，并正确区分镜像伴音与独立音乐投屏。

## ADDED Requirements

### Requirement: 镜像伴音不得切换为音乐模式
当音频流属于屏幕镜像（`usingScreen=true` 或使用 AAC-ELD 编码）时，系统 SHALL 正常播放该镜像伴音，但 SHALL NOT 将当前投屏模式切换为音乐、SHALL NOT 打开音乐播放页、SHALL NOT 关闭镜像播放页。

#### Scenario: 同一 SETUP 同时包含镜像视频与伴音
- **WHEN** iPhone 屏幕镜像的 SETUP 同时包含 type 110 镜像视频流与 type 96 镜像伴音流
- **THEN** 电视显示镜像画面并播放伴音
- **AND** 投屏模式保持为屏幕镜像
- **AND** 首页卡片与通知栏显示屏幕镜像而非音乐

#### Scenario: 伴音流先于视频流处理
- **WHEN** 同一 SETUP 中 type 96 镜像伴音流排在 type 110 镜像视频流之前
- **THEN** 模式判定结果仍为屏幕镜像
- **AND** 不出现先打开音乐页再切回镜像页的闪烁

### Requirement: 镜像伴音保留音乐会话状态
系统在处理镜像伴音流时 SHALL NOT 清空或重置音乐投屏的元数据状态。

#### Scenario: 镜像后再次音乐投屏
- **WHEN** 用户先使用 Apple Music 音乐投屏，结束后再发起屏幕镜像（含伴音）
- **THEN** 镜像期间不出现音乐播放页
- **AND** 镜像结束后再次发起音乐投屏时，曲名、艺人、封面正常刷新显示

### Requirement: 纯音乐投屏行为不变
当音频流不属于屏幕镜像（`usingScreen=false` 且非 AAC-ELD 编码）时，系统 SHALL 按现有 AirPlay 音乐投屏流程激活音乐模式并打开音乐播放页。

#### Scenario: Apple Music 投屏
- **WHEN** iPhone 通过 AirPlay 投放音乐且该音频流非镜像伴音
- **THEN** 投屏模式为音乐
- **AND** 音乐播放页正常打开并显示元数据
