package com.casttoyourtv.receiver;

/** 各协议引擎 → 服务 的统一回调 */
public interface CastSession {
    interface Callback {
        /** 收到一个待播放的视频 URL（DLNA/AirPlay 均归一到这里） */
        void onPlayVideo(String url, String title);
        /** 源端请求停止播放 */
        void onStop();
        /** 源端请求快进到指定毫秒位置（直播流忽略） */
        void onSeek(long positionMs);
        /** 源端请求暂停 */
        void onPause();
        /** 源端请求继续播放 */
        void onResume();
        /** AirPlay 屏幕镜像开始（RECORD） */
        void onMirrorStart();
        /** AirPlay 屏幕镜像结束（TEARDOWN/连接断开） */
        void onMirrorStop();
        /** AirPlay 音频播放开始（音乐投放，RECORD 且已有音频流） */
        void onAudioStart();
        /** AirPlay 音频播放结束（TEARDOWN/连接断开） */
        void onAudioStop();
    }
}
