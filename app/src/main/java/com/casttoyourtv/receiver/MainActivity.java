package com.casttoyourtv.receiver;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

/** 主界面：显示设备名与等待状态，启动接收服务 */
public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        TextView tvStatus = findViewById(R.id.tvStatus);
        TextView tvHint = findViewById(R.id.tvHint);

        String deviceName = CastService.getDeviceName();
        tvHint.setText("在手机/电脑的投屏或 AirPlay 列表中选择「" + deviceName + "」");

        CastService.start(this);
        tvStatus.setText("接收服务运行中 · AirPlay + DLNA");
        refreshSelfCheck();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshSelfCheck();
        checkOverlayPermission();
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
