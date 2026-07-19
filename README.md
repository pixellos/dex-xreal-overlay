# DeX & XREAL Cyberpunk HUD Overlay 👓⚡

A lightweight native Android application and system overlay service designed for **Samsung DeX on XREAL 1s smart glasses** (and external displays). It renders an always-on-top, transparent Cyberpunk 2077 HUD with digital clock, 5-segment battery bar, and traced vector directions directly over full-screen content.

---

## ✨ Features

- **XREAL Micro-OLED True-Black Shader**: Projects pure black (`#00000000`) as 100% transparent in AR glasses, producing a floating digital HUD readout in your Field of View (FOV).
- **Cyberpunk 2077 In-Game Quickhack Menu**: Authentic in-game Cyberdeck menu interface with Quickhack skill cards, RAM cost badges, status tags, and customizable HUD scale slider (0.25x to 1.50x).
- **Cyberpunk Traced Directions**: Automatically parses turn-by-turn directions from Google Maps into transparent floating vector arrows (`🠺`, `🠸`, `🠹`, `⤺`) and glowing street text.
- **HUD Alignment Calibrator**: Smooth touch pad and pixel-precise D-Pad alignment controls.
- **Samsung DeX Multi-Display Support**: Uses Android `DisplayManager` to target the secondary DeX / XREAL display context (`displayId != DEFAULT_DISPLAY`).
- **Automated Signed GitHub CI/CD Pipeline**: Automatically compiles signed release APK assets (`Cyberpunk-DeX-HUD-v*.apk`) on tag push (`v*`).

---

## 🛠️ Project Architecture & Stack

- **Language**: Kotlin 1.9.22
- **UI & Layout**: Native Android Views & `WindowManager` (`TYPE_APPLICATION_OVERLAY`)
- **Maps Engine**: Google Maps SDK for Android (`com.google.android.gms:play-services-maps`)
- **Min SDK**: 26 (Android 8.0+) | **Target SDK**: 34 (Android 14)

---

## 🚀 Building & Running Locally

### Option 1: Quick Install via Pre-built Signed APK
Download the compiled signed `Cyberpunk-DeX-HUD-v*.apk` directly from the [Releases](../../releases) tab.

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

Whenever a version tag is pushed (e.g. `v1.8.0`), GitHub Actions automatically:
1. Sets up JDK 17 (Temurin).
2. Compiles signed release APK using `./gradlew assembleRelease`.
3. Creates a new **GitHub Release** and attaches `Cyberpunk-DeX-HUD-v*.apk` as an asset.

---

## 📄 License
MIT License
