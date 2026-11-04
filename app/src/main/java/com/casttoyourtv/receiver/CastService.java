package com.casttoyourtv.receiver;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;

import androidx.annotation.Nullable;

import com.casttoyourtv.receiver.airplay.AirPlayServer;
import com.casttoyourtv.receiver.airplay.AudioSession;
import com.casttoyourtv.receiver.airplay.MirrorSession;
import com.casttoyourtv.receiver.dlna.DlnaRenderer;
import com.casttoyourtv.receiver.player.AudioPlayerActivity;
import com.casttoyourtv.receiver.player.MirrorActivity;
import com.casttoyourtv.receiver.player.PlayerActivity;

/**
 * 投屏接收前台服务：
 *  - 启动 DLNA(DMR) 与 AirPlay(RAOP) 两个接收引擎
 *  - 持有组播锁 + 唤醒锁，保证局域网内可被手机发现
 */
public class CastService extends Service implements CastSession.Callback {

    public static final String CHANNEL_ID = "cast_service";
    private static final int NOTIFY_ID = 1;
    private static final String TAG = "CastService";

    private DlnaRenderer dlna;
    private AirPlayServer airplay;
    private WifiManager.MulticastLock multicastLock;
    private PowerManager.WakeLock wakeLock;
    private volatile CastMode activeMode = CastMode.NONE;
    private volatile String activeVideoUrl;
    private volatile String activeVideoTitle;

    public static void start(Context ctx) {
        Intent i = new Intent(ctx, CastService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(i);
        } else {
            ctx.startService(i);
        }
    }

    public static String getDeviceName() {
        // 可被手机侧看到的设备名，可后续做成设置项
        return "CastToYourTV-" + Build.MODEL;
    }

    /** 当前运行的服务实例（供主界面自检状态用），服务未启动时为 null */
    private static volatile CastService instance;

    public static boolean isDlnaRunning() {
        CastService s = instance;
        return s != null && s.dlna != null;
    }

    public static boolean isAirplayRunning() {
        CastService s = instance;
        return s != null && s.airplay != null;
    }

    public static CastMode getActiveMode() {
        CastService s = instance;
        return s != null ? s.activeMode : CastMode.NONE;
    }

    private void setActiveMode(CastMode mode) {
        CastMode previous = activeMode;
        activeMode = mode;
        if (previous != mode) {
            Log.i(TAG, "active cast mode: " + previous + " -> " + mode);
        }
    }

    /** 主界面展示用的会话快照，线程安全且不暴露服务实例。 */
    public static final class Snapshot {
        public final CastMode mode;
        public final String title;
        public final String subtitle;
        public final boolean resumable;

        Snapshot(CastMode mode, String title, String subtitle, boolean resumable) {
            this.mode = mode;
            this.title = title;
            this.subtitle = subtitle;
            this.resumable = resumable;
        }
    }

    public static Snapshot getSnapshot() {
        CastService s = instance;
        if (s == null || s.activeMode == CastMode.NONE) {
            return new Snapshot(CastMode.NONE, null, null, false);
        }
        switch (s.activeMode) {
            case VIDEO:
                String videoTitle = s.activeVideoTitle;
                if (videoTitle == null || videoTitle.isEmpty()) videoTitle = "视频投屏";
                boolean videoResumable = PlayerActivity.isAlive()
                        || (s.activeVideoUrl != null && !s.activeVideoUrl.isEmpty());
                return new Snapshot(CastMode.VIDEO, videoTitle, "DLNA 视频", videoResumable);
            case MIRROR:
                boolean mirrorResumable = MirrorActivity.isAlive();
                return new Snapshot(CastMode.MIRROR, "屏幕镜像",
                        mirrorResumable ? "AirPlay 屏幕镜像" : "镜像已断开", mirrorResumable);
            case AUDIO:
                AudioSession audio = AudioSession.get();
                String title = audio.getTitle();
                if (title == null || title.isEmpty()) title = "AirPlay 音乐";
                String artist = audio.getArtist();
                if (artist == null || artist.isEmpty()) artist = "AirPlay 音频";
                return new Snapshot(CastMode.AUDIO, title, artist, audio.isPlaying());
            default:
                return new Snapshot(CastMode.NONE, null, null, false);
        }
    }

    /**
     * 把当前投屏页带回前台。播放页已经存在时只切回，不重新加载媒体。
     * @return true 表示已经发起切换
     */
    public static boolean openActivePlayer(Context ctx) {
        CastService s = instance;
        if (s == null) return false;

        Intent i;
        switch (s.activeMode) {
            case VIDEO:
                if (PlayerActivity.isAlive()) {
                    i = new Intent(ctx, PlayerActivity.class);
                } else if (s.activeVideoUrl != null && !s.activeVideoUrl.isEmpty()) {
                    i = new Intent(ctx, PlayerActivity.class);
                    i.putExtra(PlayerActivity.EXTRA_URL, s.activeVideoUrl);
                    i.putExtra(PlayerActivity.EXTRA_TITLE, s.activeVideoTitle);
                } else {
                    return false;
                }
                break;
            case MIRROR:
                if (!MirrorActivity.isAlive()) return false;
                i = new Intent(ctx, MirrorActivity.class);
                break;
            case AUDIO:
                if (!AudioSession.get().isPlaying()) return false;
                i = new Intent(ctx, AudioPlayerActivity.class);
                break;
            default:
                return false;
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try {
            ctx.startActivity(i);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "openActivePlayer failed", e);
            return false;
        }
    }

    /**
     * 由播放页发起：结束当前会话，但保留 DLNA/AirPlay 接收服务。
     * caller 由调用方自己 finish，避免服务提前结束正在处理按钮的 Activity。
     */
    public static void stopActiveCast(Activity caller) {
        CastService s = instance;
        if (s == null) {
            finishOtherPlayers(caller);
            return;
        }
        s.stopActiveCastInternal(caller);
    }

    private static void finishOtherPlayers(Activity caller) {
        if (!(caller instanceof PlayerActivity)) PlayerActivity.finishInstance();
        if (!(caller instanceof MirrorActivity)) MirrorActivity.finishInstance();
        if (!(caller instanceof AudioPlayerActivity)) AudioPlayerActivity.finishInstance();
    }

    private void stopActiveCastInternal(Activity caller) {
        setActiveMode(CastMode.NONE);
        activeVideoUrl = null;
        activeVideoTitle = null;

        if (airplay != null) airplay.stopActiveSessions();
        AudioSession.get().onStop();
        MirrorSession.get().endSession();
        finishOtherPlayers(caller);
        updateNotification("投屏接收服务运行中");
        Log.i(TAG, "active cast stopped by viewer, receivers stay running");
    }

    /** 视频自然播放结束时调用，避免主界面残留“正在投屏”。 */
    public static void notifyVideoEnded() {
        CastService s = instance;
        if (s != null) s.clearMode(CastMode.VIDEO);
    }

    /** 镜像流断开但页面还在时调用。 */
    public static void notifyMirrorEnded() {
        CastService s = instance;
        if (s != null) s.clearMode(CastMode.MIRROR);
    }

    private boolean clearMode(CastMode expected) {
        if (activeMode != expected) return false;
        setActiveMode(CastMode.NONE);
        activeVideoUrl = null;
        activeVideoTitle = null;
        updateNotification("投屏接收服务运行中");
        return true;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        acquireLocks();
        startForegroundWithNotification();

        // 启动 DLNA 渲染器（安卓/相册/视频 App 投放）
        dlna = new DlnaRenderer(this, getDeviceName(), this);
        dlna.start();

        // 启动 AirPlay/RAOP（iPhone 投放）
        airplay = new AirPlayServer(this, getDeviceName(), this);
        airplay.start();
    }

    private void acquireLocks() {
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm != null) {
            multicastLock = wm.createMulticastLock("cast");
            multicastLock.setReferenceCounted(true);
            multicastLock.acquire();
        }
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "cast:recv");
            wakeLock.acquire();
        }
    }

    private void startForegroundWithNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "投屏接收", NotificationManager.IMPORTANCE_MIN);
            nm.createNotificationChannel(ch);
        }
        Intent pi = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(
                this, 0, pi, PendingIntent.FLAG_IMMUTABLE);
        Notification n = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            n = new Notification.Builder(this, CHANNEL_ID)
                    .setContentTitle("CastToYourTV")
                    .setContentText("投屏接收服务运行中")
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentIntent(pending)
                    .build();
        }
        startForeground(NOTIFY_ID, n);
    }

    // ===== CastSession.Callback：某个协议收到了投放请求 =====
    @Override
    public void onPlayVideo(String url, String title) {
        setActiveMode(CastMode.VIDEO);
        activeVideoUrl = url;
        activeVideoTitle = title;
        MirrorActivity.finishInstance();
        updateNotification("正在投屏 · 视频");
        Intent i = new Intent(this, PlayerActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        i.putExtra(PlayerActivity.EXTRA_URL, url);
        i.putExtra(PlayerActivity.EXTRA_TITLE, title);
        // Android 10+ 后台拉起 Activity 需要悬浮窗权限，否则只会进后台任务栈
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !Settings.canDrawOverlays(this)) {
            Log.w(TAG, "无悬浮窗权限，后台拉起播放页可能被系统拦截");
            updateNotification("需要授权「显示在其他应用上层」才能自动播放，请打开 App 按提示授权");
        }
        try {
            startActivity(i);
        } catch (Exception e) {
            Log.e(TAG, "startActivity failed", e);
        }
    }

    private void updateNotification(String text) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        Intent pi = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 0, pi, PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("CastToYourTV")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentIntent(pending)
                .build();
        nm.notify(NOTIFY_ID, n);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 被系统回收后自动重启服务，保持可被搜索到
        return START_STICKY;
    }

    @Override
    public void onStop() {
        if (clearMode(CastMode.VIDEO)) PlayerActivity.finishInstance();
    }

    @Override
    public void onSeek(long positionMs) {
        PlayerActivity.seekToMs(positionMs);
    }

    @Override
    public void onPause() {
        PlayerActivity.pauseInstance();
    }

    @Override
    public void onResume() {
        PlayerActivity.resumeInstance();
    }

    @Override
    public void onMirrorStart() {
        // 镜像页可能与点播页同时存在，先关点播页
        setActiveMode(CastMode.MIRROR);
        activeVideoUrl = null;
        activeVideoTitle = null;
        PlayerActivity.finishInstance();
        updateNotification("正在投屏 · 屏幕镜像");
        Intent i = new Intent(this, MirrorActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !Settings.canDrawOverlays(this)) {
            Log.w(TAG, "无悬浮窗权限，后台拉起镜像页可能被系统拦截");
        }
        try {
            startActivity(i);
        } catch (Exception e) {
            Log.e(TAG, "startActivity(Mirror) failed", e);
        }
    }

    @Override
    public void onMirrorStop() {
        if (clearMode(CastMode.MIRROR)) MirrorActivity.finishInstance();
    }

    @Override
    public void onAudioStart() {
        // 音频播放页与镜像页互斥，先关镜像页
        setActiveMode(CastMode.AUDIO);
        activeVideoUrl = null;
        activeVideoTitle = null;
        MirrorActivity.finishInstance();
        PlayerActivity.finishInstance();
        updateNotification("正在投屏 · 音乐");
        Intent i = new Intent(this, AudioPlayerActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !Settings.canDrawOverlays(this)) {
            Log.w(TAG, "无悬浮窗权限，后台拉起音频页可能被系统拦截");
        }
        try {
            startActivity(i);
        } catch (Exception e) {
            Log.e(TAG, "startActivity(Audio) failed", e);
        }
    }

    @Override
    public void onAudioStop() {
        if (clearMode(CastMode.AUDIO)) AudioPlayerActivity.finishInstance();
    }

    @Override
    public void onDestroy() {
        setActiveMode(CastMode.NONE);
        activeVideoUrl = null;
        activeVideoTitle = null;
        instance = null;
        if (dlna != null) dlna.stop();
        if (airplay != null) airplay.stop();
        if (multicastLock != null && multicastLock.isHeld()) multicastLock.release();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
