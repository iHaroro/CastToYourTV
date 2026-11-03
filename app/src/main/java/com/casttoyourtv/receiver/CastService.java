package com.casttoyourtv.receiver;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
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
        PlayerActivity.finishInstance();
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
        PlayerActivity.finishInstance();
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
        MirrorActivity.finishInstance();
    }

    @Override
    public void onAudioStart() {
        // 音频播放页与镜像页互斥，先关镜像页
        MirrorActivity.finishInstance();
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
        AudioPlayerActivity.finishInstance();
    }

    @Override
    public void onDestroy() {
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
