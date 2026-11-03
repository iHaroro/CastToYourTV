# Design

## 架构总览

接收端为单进程 Android 前台服务，内部并行运行两条协议引擎（DLNA 与 AirPlay），通过统一回调拉起播放器 Activity。

```
手机/iPhone
   │
   ├─ DLNA/UPnP ──► DlnaRenderer ──┐
   │                                │──► CastSession.Callback ──► PlayerActivity（视频）
   ├─ AirPlay 镜像 ─► RtspListener ─┤                           MirrorActivity（镜像）
   └─ AirPlay 音频 ─► RtspListener ─┘                           AudioPlayerActivity（音乐）
```

## 关键决策

### 1. 前台服务 + 双协议引擎
用一个前台 Service 同时持有 DLNA 和 AirPlay 两套监听器，统一生命周期，避免多服务竞争。
持有 `WifiManager.MulticastLock`（SSDP/mDNS 广播）和 `PARTIAL_WAKE_LOCK`（防休眠）。

### 2. 播放器按内容类型分 Activity
- `PlayerActivity`：DLNA 视频，用系统播放器（ExoPlayer 类）+ 比例切换
- `MirrorActivity`：AirPlay 镜像，SurfaceView + MediaCodec 硬解 H.264
- `AudioPlayerActivity`：AirPlay 音乐，纯 UI（封面/曲名/时长/音量/设备信息）

### 3. AirPlay 安全层纯 Java 实现
- `Playfair`：FairPlay ekey 解密（移植自 UxPlay `lib/playfair`），用于镜像视频流密钥派生
- `AirPlayCrypto`：X25519/Ed25519 配对握手，AES-128-CTR（镜像）/AES-128-CBC（音频）解密
- 表数据预生成到 `assets/playfair_tables.bin`，避免运行时解析大数组

### 4. 音频解码
- ALAC：纯 Java 解码器（移植自 Apple 官方参考实现），Android 5.1+ 无内置 ALAC
- AAC-LC/AAC-ELD：走 `MediaCodec` 硬解
- PCM：直接换字节序后喂 `AudioTrack`

### 5. 自动拉起依赖悬浮窗权限
Android 10+ 后台拉起 Activity 需要 `SYSTEM_ALERT_WINDOW` 权限，首页检测未授权时弹窗引导用户开启。

### 6. 幂等处理重复 SETUP
iPhone 会反复重发相同参数的 SETUP，用流参数（ct/spf）去重，避免重建导致的爆音和元数据闪烁。

## 数据流

### 镜像流程
```
iPhone ──mDNS 发现──► 7000/RTSP ──pair-verify──► fp-setup ──► SETUP(type=110)
                                                          │
                                                          ▼
                                              TCP 数据通道（AES-128-CTR 解密 H.264）
                                                          │
                                                          ▼
                                              MirrorActivity（MediaCodec 渲染）
```

### 音乐流程
```
iPhone ──mDNS 发现──► 7000/RTSP ──pair-verify──► SETUP(type=96, ALAC/AAC)
                                                          │
                                                          ▼
                                              UDP RTP 数据通道（AES-128-CBC 解密）
                                                          │
                                                          ▼
                                              AudioReceiver ──► AudioRenderer ──► AudioTrack
                                                          │
                                                          ▼
                                              AudioPlayerActivity（元数据展示）
```
