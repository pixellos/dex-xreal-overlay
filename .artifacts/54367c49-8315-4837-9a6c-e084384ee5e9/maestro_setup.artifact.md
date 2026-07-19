# Maestro UI Testing Setup

I have initialized a Maestro testing suite for your Cyberdeck app. These "Flows" allow you to automate the testing of your HUD and calibration tools.

## Created Flows

### 1. [smoke-test.yaml](file:///C:/Dev/DeXOverlayPoC/.maestro/smoke-test.yaml)
- Launches the app.
- Executes and terminates the Cyberpunk HUD.
- Verifies the state change via the UI text and Toast messages.

### 2. [calibration-test.yaml](file:///C:/Dev/DeXOverlayPoC/.maestro/calibration-test.yaml)
- Tests the **System HUD Scale** slider.
- Interacts with the **HUD Alignment Calibrator** (Joystick).
- Verifies that the X/Y offsets update and that the **Reset** button works.

---

## How to Run These Tests

### 1. Install Maestro
If you haven't already, run this command in your terminal:
```powershell
curl -Ls "https://get.maestro.mobile.dev" | bash
```

### 2. Run the Tests
Ensure your Android emulator is running and the app is installed, then run:
```powershell
# Run the smoke test
maestro test .maestro/smoke-test.yaml

# Run the calibration joystick test
maestro test .maestro/calibration-test.yaml
```

### 3. Use Maestro Studio (Visual Mode)
To visually build more tests or debug your joystick pad:
```powershell
maestro studio
```
This will open a web interface where you can see your app live and click elements to generate YAML code.

> [!TIP]
> Maestro is "Black Box"—it doesn't need access to your source code to run, so it's perfect for testing how the HUD overlay interacts with other apps on the device!
