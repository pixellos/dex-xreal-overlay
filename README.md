# DeX & XREAL Floating Clock + Maps Overlay 👓🕒

A lightweight native Android application and system overlay service designed for **Samsung DeX on XREAL 1s smart glasses** (and external displays). It renders an always-on-top, non-intrusive digital clock and semi-transparent Google Maps widget directly over full-screen video streams.

---

## ✨ Features

- **XREAL Micro-OLED True-Black Shader**: Projects pure black (`#000000`) as 100% transparent in AR glasses, producing a floating digital HUD readout in your Field of View (FOV).
- **Samsung DeX Multi-Display Support**: Uses Android `DisplayManager` to target the secondary DeX / XREAL display context (`displayId != DEFAULT_DISPLAY`).
- **Touch Pass-Through (`FLAG_NOT_TOUCHABLE`)**: Click or tap right through the floating clock to interact with underlying video player controls on DeX.
- **Interactive Local Browser Preview**: Includes `index.html` to instantly simulate and test the XREAL overlay layout on any browser.
- **Automated GitHub CI/CD Pipeline**: Automatically builds and attaches `app-debug.apk` to GitHub Releases on tag push (`v*`).

---

## 🛠️ Project Architecture & Stack

- **Language**: Kotlin 1.9.22
- **UI & Layout**: Native Android Views & `WindowManager` (`TYPE_APPLICATION_OVERLAY`)
- **Maps Engine**: Google Maps SDK for Android (`com.google.android.gms:play-services-maps`)
- **Min SDK**: 26 (Android 8.0+) | **Target SDK**: 34 (Android 14)

---

## 🚀 Building & Running Locally

### Option 1: Quick Install via Pre-built APK
Download the compiled `app-debug.apk` directly from the [Releases](../../releases) tab.

### Option 2: Build in Android Studio
1. Clone the repository:
   ```bash
   git clone https://github.com/pixellos/dex-xreal-overlay.git
   ```
2. Open the project in **Android Studio**.
3. Ensure `local.properties` specifies your Android SDK directory:
   ```properties
   sdk.dir=F:/Android/Sdk
   ```
4. Click **Run ▶** to deploy to your phone or `DeX_Emulator`.

---

## 📦 Automated Release Pipeline (CI/CD)

Whenever a version tag is pushed (e.g. `v1.0.0`), GitHub Actions automatically:
1. Sets up JDK 17 (Temurin).
2. Compiles `app-debug.apk` using `./gradlew assembleDebug`.
3. Creates a new **GitHub Release** and attaches `app-debug.apk` as an asset.

---

## 📄 License
MIT License
