# Spec Delta

## Purpose

保证投屏接收服务在电视端常驻运行、开机自启，并能在接收到投放请求时自动拉起对应播放界面。

## ADDED Requirements

### Requirement: 服务常驻
系统 SHALL 以前台服务形式常驻运行，持有网络组播锁与唤醒锁，防止被系统休眠或回收。

#### Scenario: 后台持续被发现
- **WHEN** App 切到后台或电视进入屏保
- **THEN** 投屏服务仍在运行，手机仍能搜索到设备

### Requirement: 开机自启
系统 SHALL 在电视开机完成后自动启动接收服务，无需用户手动打开 App。

#### Scenario: 重启后可用
- **WHEN** 电视断电重启完成
- **THEN** 投屏服务自动启动，手机可直接搜索并投放

### Requirement: 服务自检
系统 SHALL 在首页显示 DLNA/AirPlay 服务运行状态、悬浮窗权限状态，便于用户排查问题。

#### Scenario: 查看服务状态
- **WHEN** 用户打开 App 首页
- **THEN** 能看到「DLNA 接收」「AirPlay 接收」「悬浮窗权限」三项自检结果

### Requirement: 自动拉起播放页
系统 SHALL 在收到投放请求时自动拉起对应播放界面（视频/镜像/音频），无需用户手动打开 App。

#### Scenario: 后台自动播放
- **WHEN** 手机投放视频且 App 未在前台
- **THEN** 电视自动弹出播放器并开始播放

### Requirement: 悬浮窗权限引导
系统 SHALL 在检测到悬浮窗权限未授权时，引导用户前往设置页授权，以保证后台拉起功能可用。

#### Scenario: 权限引导
- **WHEN** 用户打开 App 且未授权悬浮窗权限
- **THEN** 弹出提示框，点击后跳转到系统设置页
