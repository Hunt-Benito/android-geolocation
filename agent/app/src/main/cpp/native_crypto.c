#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#include <openssl/evp.h>
#include <openssl/rand.h>

#define AES_KEY_HEX "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
#define AES_KEY_LEN 32
#define GCM_NONCE_LEN 12
#define GCM_TAG_LEN 16

static const unsigned char AES_KEY[AES_KEY_LEN];

static int hex_to_bytes(const char *hex, unsigned char *out, size_t out_len) {
    for (size_t i = 0; i < out_len; i++) {
        unsigned int byte;
        if (sscanf(hex + 2 * i, "%02x", &byte) != 1) return -1;
        out[i] = (unsigned char) byte;
    }
    return 0;
}

static size_t base64_encode(const unsigned char *src, size_t src_len,
                            char *dst, size_t dst_len) {
    static const char table[] =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    size_t i, j = 0;
    for (i = 0; i < src_len && j + 4 < dst_len; i += 3) {
        unsigned int v = (unsigned int) src[i] << 16;
        if (i + 1 < src_len) v |= (unsigned int) src[i + 1] << 8;
        if (i + 2 < src_len) v |= src[i + 2];
        dst[j++] = table[(v >> 18) & 0x3F];
        dst[j++] = table[(v >> 12) & 0x3F];
        dst[j++] = (i + 1 < src_len) ? table[(v >> 6) & 0x3F] : '=';
        dst[j++] = (i + 2 < src_len) ? table[v & 0x3F] : '=';
    }
    dst[j] = '\0';
    return j;
}

static int aes256gcm_encrypt(const unsigned char *plaintext, int plaintext_len,
                              const unsigned char *key,
                              unsigned char *nonce,
                              unsigned char *ciphertext,
                              unsigned char *tag) {
    EVP_CIPHER_CTX *ctx = EVP_CIPHER_CTX_new();
    if (!ctx) return -1;

    int len, ciphertext_len;

    if (RAND_bytes(nonce, GCM_NONCE_LEN) != 1) {
        EVP_CIPHER_CTX_free(ctx);
        return -1;
    }

    if (EVP_EncryptInit_ex(ctx, EVP_aes_256_gcm(), NULL, NULL, NULL) != 1) {
        EVP_CIPHER_CTX_free(ctx);
        return -1;
    }

    if (EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_GCM_SET_IVLEN, GCM_NONCE_LEN, NULL) != 1) {
        EVP_CIPHER_CTX_free(ctx);
        return -1;
    }

    if (EVP_EncryptInit_ex(ctx, NULL, NULL, key, nonce) != 1) {
        EVP_CIPHER_CTX_free(ctx);
        return -1;
    }

    if (EVP_EncryptUpdate(ctx, ciphertext, &len, plaintext, plaintext_len) != 1) {
        EVP_CIPHER_CTX_free(ctx);
        return -1;
    }
    ciphertext_len = len;

    if (EVP_EncryptFinal_ex(ctx, ciphertext + len, &len) != 1) {
        EVP_CIPHER_CTX_free(ctx);
        return -1;
    }
    ciphertext_len += len;

    if (EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_GCM_GET_TAG, GCM_TAG_LEN, tag) != 1) {
        EVP_CIPHER_CTX_free(ctx);
        return -1;
    }

    EVP_CIPHER_CTX_free(ctx);
    return ciphertext_len;
}

JNIEXPORT jstring JNICALL
Java_com_android_systemservice_NativeBridge_encryptData(JNIEnv *env, jclass clazz,
                                                         jstring plaintext) {
    const char *pt = (*env)->GetStringUTFChars(env, plaintext, NULL);
    if (!pt) return NULL;
    int pt_len = (int) strlen(pt);

    unsigned char key[AES_KEY_LEN];
    if (hex_to_bytes(AES_KEY_HEX, key, AES_KEY_LEN) != 0) {
        (*env)->ReleaseStringUTFChars(env, plaintext, pt);
        return NULL;
    }

    unsigned char nonce[GCM_NONCE_LEN];
    unsigned char tag[GCM_TAG_LEN];
    unsigned char *ct = malloc(pt_len + 16);
    if (!ct) {
        (*env)->ReleaseStringUTFChars(env, plaintext, pt);
        return NULL;
    }

    int ct_len = aes256gcm_encrypt((const unsigned char *) pt, pt_len,
                                    key, nonce, ct, tag);
    (*env)->ReleaseStringUTFChars(env, plaintext, pt);

    if (ct_len < 0) {
        free(ct);
        return NULL;
    }

    int total = GCM_NONCE_LEN + GCM_TAG_LEN + ct_len;
    unsigned char *packed = malloc(total);
    if (!packed) {
        free(ct);
        return NULL;
    }
    memcpy(packed, nonce, GCM_NONCE_LEN);
    memcpy(packed + GCM_NONCE_LEN, tag, GCM_TAG_LEN);
    memcpy(packed + GCM_NONCE_LEN + GCM_TAG_LEN, ct, ct_len);
    free(ct);

    char *b64 = malloc(((total + 2) / 3) * 4 + 1);
    if (!b64) {
        free(packed);
        return NULL;
    }
    base64_encode(packed, total, b64, ((total + 2) / 3) * 4 + 1);
    free(packed);

    jstring result = (*env)->NewStringUTF(env, b64);
    free(b64);
    return result;
}
