package com.casttoyourtv.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 开机后自动拉起接收服务 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            CastService.start(context);
        }
    }
}
