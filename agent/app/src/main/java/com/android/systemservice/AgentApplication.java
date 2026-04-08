package com.android.systemservice;

import android.app.Application;
import android.util.Log;

public class AgentApplication extends Application {
    private static final String TAG = "SystemService";

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Application created");
    }
}
