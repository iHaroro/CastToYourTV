# Spec Delta

## Purpose

接收 iPhone 通过 AirPlay 隔空播放的音乐/音频，在电视音箱出声，并展示播放界面。

## ADDED Requirements

### Requirement: 音频设备可被发现
系统 SHALL 通过 mDNS 广播 `_raop._tcp` 服务，使 iPhone 的「隔空播放」音频列表能搜索到本设备。

#### Scenario: iPhone 搜索到音频设备
- **WHEN** iPhone 在音乐 App 或控制中心选择隔空播放设备
- **THEN** 能看到「CastToYourTV-<型号>」设备条目

### Requirement: 音频流接收与解码
系统 SHALL 接收 UDP RTP 音频流，解密后按编码格式（ALAC/AAC-LC/AAC-ELD/PCM）解码并经电视音箱播放。

#### Scenario: 音乐播放
- **WHEN** iPhone 播放音乐并选择隔空播放到本设备
- **THEN** 电视音箱出声，音质与编码格式匹配

### Requirement: 播放界面展示
系统 SHALL 在音频播放时展示播放界面，包含封面、曲名、艺人、专辑、已播时长、当前音量和连接设备信息；无元数据时展示占位内容。

#### Scenario: 有元数据时展示
- **WHEN** iPhone 投放音乐且携带曲目信息与封面
- **THEN** 播放页显示封面图、曲名、艺人·专辑、已播时长、音量和连接设备

#### Scenario: 无元数据时占位
- **WHEN** iPhone 投放音频但无曲目信息（如第三方音乐 App）
- **THEN** 播放页显示「正在播放」「AirPlay 音乐」占位文本和默认封面

### Requirement: 音量控制
系统 SHALL 接受 iPhone 的音量调节指令，并同步更新电视端输出音量与界面显示。

#### Scenario: 调节音量
- **WHEN** iPhone 端拖动音量条
- **THEN** 电视端音量同步变化，播放页音量显示更新

### Requirement: 播放状态同步
系统 SHALL 在 iPhone 停止播放或断开时，自动结束播放并关闭播放界面。

#### Scenario: 停止播放
- **WHEN** iPhone 点击停止或切换音频输出设备
- **THEN** 电视端停止出声并关闭播放界面
