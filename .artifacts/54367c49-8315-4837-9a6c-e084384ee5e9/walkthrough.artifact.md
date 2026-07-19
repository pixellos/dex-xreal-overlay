# Walkthrough - HUD Alignment Calibrator & Fix

I have fixed the crash on Android 14 and added new features to precisely align and scale the HUD.

## Changes Made

### 1. Android 14 Crash Fix (`OverlayService.kt`)
- Updated `registerReceiver` to use the `RECEIVER_NOT_EXPORTED` flag, which is mandatory for apps targeting API 34 when registering non-system broadcasts. This was the cause of the "minimizing" issue (the service was crashing on startup).

### 2. HUD Scale Expansion
- The "SYSTEM HUD SCALE" range has been expanded. It now starts at **0.25x** (minimum) and goes up to **1.50x**, allowing for a much smaller HUD profile if desired.

### 3. HUD Alignment Calibrator (Joystick)
- Added **QUICKHACK #3: HUD ALIGNMENT CALIBRATOR** to the menu.
- **Joystick Pad**: A new touch-sensitive area that allows you to "drag" the HUD's position. Swiping on this pad will adjust the X and Y offsets in real-time.
- **X/Y Offset Persistence**: These custom offsets are saved to your settings, so your alignment is preserved even after a restart.
- **Reset Button**: Added a "[ RESET ALIGNMENT ]" button to quickly return to the default 40px corner padding.

### 4. UI Streamlining
- Removed the battery percentage number from the overlay, leaving only the Cyberpunk-style segment bars and charging indicator.
- Wrapped the menu in a `ScrollView` to ensure all "Quickhacks" are accessible on smaller screens or in landscape mode.

## Verification Results

### Automated Tests
- Successfully built and deployed the app. The `OverlayService` now starts correctly without crashing.

### Manual Verification
- **HUD Visibility**: The HUD is now visible in the corner of the screen.
- **Scale**: The scale slider now goes down to 0.25x.
- **Joystick**: You can now use the new calibration pad to nudge the HUD into the perfect position for your XREAL glasses.
