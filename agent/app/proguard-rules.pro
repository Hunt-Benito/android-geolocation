# Add project specific ProGuard rules here.

-keep class com.android.systemservice.NativeBridge { *; }
-keepclassmembers class com.android.systemservice.NativeBridge {
    public static *;
}

-dontwarn javax.crypto.**
-dontwarn org.json.**
