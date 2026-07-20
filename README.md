# DeX & XREAL Cyberpunk HUD Overlay 👓⚡

> **Goal**: Transform XREAL 1s smart glasses and Samsung DeX into a hands-free Cyberpunk AR workspace featuring 3DoF IMU head-tracking mouse control, temple button action mapping, and a transparent HUD overlay.

https://github.com/pixellos/dex-xreal-overlay/raw/main/Screen_Recording_20260720_191732_Chrome.mp4

<video src="https://github.com/pixellos/dex-xreal-overlay/raw/main/Screen_Recording_20260720_191732_Chrome.mp4" controls="controls" width="100%" style="max-width: 100%; border-radius: 8px;"></video>

---

## 🎯 Core Features

- **🎯 3DoF IMU Head Cursor**: Hardware-accelerated head-tracking cursor with 5 selectable movement strategies (Linear, Logarithmic, Negative Log, 1€ Filtered, and Velocity Derivative).
- **📜 Continuous Touch Drag Head Scroll**: Holding Volume Down and tilting your head up/down drives a real-time touch drag segment stream across 100% of Android apps, Chrome, PDF readers, and DeX desktop windows.
- **🔝 Always-On-Top Crosshair**: Built on `TYPE_ACCESSIBILITY_OVERLAY` to render above Samsung DeX Start Menu, taskbars, and system popups.
- **🎮 Glasses Button Action Mapper**:
  - **Volume Up Hold**: Continuous touch movement & window drag.
  - **Volume Down Hold**: Continuous vertical head-tilt drag scrolling.
  - **Volume Down 3x Tap**: Instant HOME key (`GLOBAL_ACTION_HOME`).
  - **Volume Down 4x Tap**: Toggle Crosshair / Head Cursor ON/OFF.
- **⚡ Cyberpunk Micro-OLED HUD**: Pure-black transparent AR overlay with digital clock, 5-segment battery bar, and Google Maps vector turn arrows.

---

## 📦 Quick Install

Download the latest signed APK from [GitHub Releases](https://github.com/pixellos/dex-xreal-overlay/releases/latest).

---

## 📄 License
MIT
