# HUD Fix, Scale Expansion & Position Calibration (Joystick)

This plan fixes a crash on Android 14, expands the HUD scaling range, and introduces a "Calibration Pad" (Joystick) for precise HUD alignment.

## User Review Required

> [!IMPORTANT]
> I've identified a crash in `OverlayService` caused by Android 14's strict broadcast receiver requirements. I will fix this as part of the update.

## Proposed Changes

### [app](file:///C:/Dev/DeXOverlayPoC/app)

#### [MODIFY] [OverlayService.kt](file:///C:/Dev/DeXOverlayPoC/app/src/main/java/com/example/dexoverlay/OverlayService.kt)
- **Fix Crash**: Update `registerReceiver` to include the `RECEIVER_NOT_EXPORTED` flag (required for API 34).
- **Scale Update**: Change `hudScale` range to `coerceIn(0.25f, 1.5f)`.
- **Custom Offsets**: Retrieve `KEY_X_OFFSET` and `KEY_Y_OFFSET` from SharedPreferences.
- **Dynamic Positioning**: Apply these offsets to the HUD's layout parameters.

#### [MODIFY] [MainActivity.kt](file:///C:/Dev/DeXOverlayPoC/app/src/main/java/com/example/dexoverlay/MainActivity.kt)
- **Scale SeekBar**: Update to handle the `0.25 -> 1.50` range.
- **NEW QUICKHACK #3: HUD ALIGNMENT CALIBRATOR**:
    - Add a touch-sensitive "Calibration Pad" that acts like a joystick.
    - Swiping on the pad will adjust the HUD's X/Y position in real-time.
    - Add a "RESET" button to return to default offsets (40px).

## Verification Plan

### Automated Tests
- Build and deploy to ensure the service no longer crashes on startup.

### Manual Verification
- **HUD Visibility**: Confirm the HUD appears in the corner (fixing the current issue).
- **Scale**: Verify the HUD can be scaled down to 0.25x.
- **Joystick**: Use the "Calibration Pad" to move the HUD around and confirm it responds to touch.
