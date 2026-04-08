# Android Root-Level Geolocation C2 Agent with JNI

Companion code for the article **[Building an Android Root-Level Geolocation C2 Agent with JNI](https://www.hunt-benito.com/building-an-android-root-level-geolocation-c2-agent-with-jni)** published on [Hunt-Benito Limited](https://www.hunt-benito.com).

## Overview

A miniature command-and-control infrastructure consisting of:

1. **C2 Server** — Python/Flask application with REST API, SQLite database, AES-256-GCM decryption, and a Leaflet.js live map dashboard.
2. **Android Agent** — System application with a Java core (service lifecycle, C2 communication) and a JNI native library (geolocation via `dumpsys`, AES-256-GCM encryption, process masking, SELinux bypass).
3. **Frontend Dashboard** — Web interface with Leaflet.js plotting agent coordinates on OpenStreetMap in real time.

The agent targets **Android 13–14** (API 33–34), runs as a **privileged system application** with root access, and is invisible to the device owner.

## Project Structure

```
├── c2server/
│   ├── c2server.py           # Flask C2 server
│   ├── requirements.txt      # Python dependencies
│   └── templates/
│       └── dashboard.html    # Leaflet.js map dashboard
├── agent/
│   ├── app/
│   │   ├── build.gradle
│   │   ├── src/main/
│   │   │   ├── AndroidManifest.xml
│   │   │   ├── java/com/android/systemservice/
│   │   │   │   ├── AgentApplication.java
│   │   │   │   ├── AgentService.java
│   │   │   │   ├── BootReceiver.java
│   │   │   │   ├── C2Client.java
│   │   │   │   ├── CommandHandler.java
│   │   │   │   └── NativeBridge.java
│   │   │   ├── cpp/
│   │   │   │   ├── CMakeLists.txt
│   │   │   │   ├── native_crypto.c
│   │   │   │   └── native_stealth.c
│   │   │   └── res/
│   ├── build.gradle
│   ├── settings.gradle
│   └── deploy.sh
└── README.md
```

## Quick Start

### C2 Server

```bash
cd c2server
pip install -r requirements.txt
python c2server.py
# Server starts at https://0.0.0.0:4443
```

### Android Agent

Open the `agent/` directory in Android Studio (with NDK 26.x installed) and build:

```bash
cd agent
./gradlew assembleRelease
```

Or build and deploy in one step:

```bash
bash deploy.sh
```

## Requirements

- **Python 3.10+** with Flask, PyCryptodome, pyOpenSSL
- **Android Studio** with NDK 26.x
- **adb** (Android Debug Bridge)
- A **rooted** Android 13/14 device (physical or emulator)

## Encryption

All C2 traffic is encrypted with **AES-256-GCM** using a pre-shared 256-bit key:

```
Wire format (base64): [Nonce 12B][Tag 16B][Ciphertext]
```

The native library (`libagent.so`) encrypts using BoringSSL's EVP interface (linked against `/system/lib64/libcrypto.so`). The C2 server decrypts using PyCryptodome.

## Ethical Use

This code is for **educational and authorised security testing only**. See the [article](https://www.hunt-benito.com/building-an-android-root-level-geolocation-c2-agent-with-jni) for ethical considerations.
