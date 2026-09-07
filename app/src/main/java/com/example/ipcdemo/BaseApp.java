package com.example.ipcdemo;

import android.app.Application;
import android.util.Log;

import com.meituan.android.walle.WalleChannelReader;

public class BaseApp extends Application {
    private static final String TAG = "BaseApp";

    private String channel;

    @Override
    public void onCreate() {
        super.onCreate();
        channel = WalleChannelReader.getChannel(getApplicationContext());
        Log.i(TAG, "walle channel: " + channel);
    }

    public String getChannel() {
        return channel;
    }
}
