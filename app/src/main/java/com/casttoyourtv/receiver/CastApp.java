package com.casttoyourtv.receiver;

import android.app.Application;
import android.content.Intent;

/** 应用入口：启动常驻接收服务 */
public class CastApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        CastService.start(this);
    }
}
