package com.casttoyourtv.receiver;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.util.Set;

/** 主界面：显示设备名与等待状态，启动接收服务 */
public class MainActivity extends AppCompatActivity {

    private LinearLayout castCard;
    private TextView tvActiveCast;
    private Button btnResumeCast;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int selfCheckAttemptsLeft;

    private final Runnable selfCheckRefresh = new Runnable() {
        @Override
        public void run() {
            if (isFinishing()) return;
            refreshSelfCheck();
            boolean ready = CastService.isDlnaRunning() && CastService.isAirplayRunning();
            if (!ready && selfCheckAttemptsLeft-- > 0) {
                handler.postDelayed(this, 700);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        TextView tvStatus = findViewById(R.id.tvStatus);
        TextView tvHint = findViewById(R.id.tvHint);
        castCard = findViewById(R.id.castCard);
        tvActiveCast = findViewById(R.id.tvActiveCast);
        btnResumeCast = findViewById(R.id.btnResumeCast);
        btnResumeCast.setOnClickListener(v -> {
            if (!CastService.openActivePlayer(this)) {
                Toast.makeText(this, "投屏已断开，请在手机上重新投屏", Toast.LENGTH_SHORT).show();
            }
        });

        String deviceName = CastService.getDeviceName();
        tvHint.setText("在手机/电脑的投屏或 AirPlay 列表中选择「" + deviceName + "」");

        CastService.start(this);
        tvStatus.setText("接收服务运行中 · AirPlay + DLNA");
        refreshSelfCheck();
        scheduleSelfCheckRefresh();
        refreshActiveCast();
        if (isLauncherIntent(getIntent())) {
            getWindow().getDecorView().post(this::tryRouteToActivePlayer);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshSelfCheck();
        scheduleSelfCheckRefresh();
        refreshActiveCast();
        checkOverlayPermission();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(selfCheckRefresh);
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        refreshActiveCast();
        if (isLauncherIntent(intent)) {
            getWindow().getDecorView().post(this::tryRouteToActivePlayer);
        }
    }

    private boolean isLauncherIntent(Intent intent) {
        if (intent == null || !Intent.ACTION_MAIN.equals(intent.getAction())) return false;
        Set<String> categories = intent.getCategories();
        return categories != null && (categories.contains(Intent.CATEGORY_LAUNCHER)
                || categories.contains(Intent.CATEGORY_LEANBACK_LAUNCHER));
    }

    private void tryRouteToActivePlayer() {
        if (isFinishing()) return;
        CastService.openActivePlayer(this);
    }

    /** 服务刚启动时自检可能早于引擎就绪，短暂重试避免首页一直显示未启动。 */
    private void scheduleSelfCheckRefresh() {
        handler.removeCallbacks(selfCheckRefresh);
        selfCheckAttemptsLeft = 6;
        handler.postDelayed(selfCheckRefresh, 600);
    }

    /** 正在投屏时在主界面显示返回入口；没有可恢复页面时只显示断开提示。 */
    private void refreshActiveCast() {
        CastService.Snapshot snapshot = CastService.getSnapshot();
        if (snapshot.mode == CastMode.NONE) {
            castCard.setVisibility(View.GONE);
            return;
        }

        String mode;
        switch (snapshot.mode) {
            case VIDEO:
                mode = "视频";
                break;
            case MIRROR:
                mode = "屏幕镜像";
                break;
            case AUDIO:
                mode = "音乐";
                break;
            default:
                mode = "投屏";
                break;
        }

        StringBuilder text = new StringBuilder("正在投屏 · ").append(mode);
        if (snapshot.title != null && !snapshot.title.isEmpty()) {
            text.append("\n").append(snapshot.title);
        }
        if (snapshot.subtitle != null && !snapshot.subtitle.isEmpty()) {
            text.append("\n").append(snapshot.subtitle);
        }
        if (!snapshot.resumable) {
            text.append("\n请在手机上重新投屏");
        }
        tvActiveCast.setText(text.toString());
        btnResumeCast.setVisibility(snapshot.resumable ? View.VISIBLE : View.GONE);
        castCard.setVisibility(View.VISIBLE);
    }

    /** 服务自检：DLNA/AirPlay 引擎是否就绪、悬浮窗权限是否已授权 */
    private void refreshSelfCheck() {
        TextView tvDlna = findViewById(R.id.tvDlnaStatus);
        TextView tvAirplay = findViewById(R.id.tvAirplayStatus);
        TextView tvOverlay = findViewById(R.id.tvOverlayStatus);

        boolean dlna = CastService.isDlnaRunning();
        boolean airplay = CastService.isAirplayRunning();
        tvDlna.setText("DLNA 接收：" + (dlna ? "✓ 正常" : "✗ 未启动"));
        tvDlna.setTextColor(dlna ? 0xFF4CAF50 : 0xFFF44336);
        tvAirplay.setText("AirPlay 接收：" + (airplay ? "✓ 正常" : "✗ 未启动"));
        tvAirplay.setTextColor(airplay ? 0xFF4CAF50 : 0xFFF44336);

        boolean overlay = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                || Settings.canDrawOverlays(this);
        tvOverlay.setText("悬浮窗权限：" + (overlay ? "✓ 已授权" : "✗ 未授权（影响后台自动弹出）"));
        tvOverlay.setTextColor(overlay ? 0xFF4CAF50 : 0xFFF44336);
    }

    /** Android 10+ 后台拉起播放页需要悬浮窗权限，未授权时弹窗引导（只需授权一次） */
    private void checkOverlayPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || Settings.canDrawOverlays(this)) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("需要一项授权")
                .setMessage("为了在不打开 App 时也能自动弹出投屏画面，请允许「显示在其他应用上层」。（只需设置一次）")
                .setPositiveButton("去设置", (d, w) -> {
                    Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + getPackageName()));
                    startActivity(i);
                })
                .setNegativeButton("以后再说", null)
                .show();
    }
}
