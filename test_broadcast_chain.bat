@echo off
setlocal
set ADB=F:\Android\Sdk\platform-tools\adb.exe
set PKG=com.example.dexoverlay
set APK=C:\Dev\DeXOverlayPoC\app\build\outputs\apk\debug\Cyberpunk-DeX-HUD-v6.23.apk

echo.
echo === STEP 1: Clear logcat ===
%ADB% logcat -c

echo.
echo === STEP 2: Install APK ===
%ADB% install -r -t "%APK%"
if errorlevel 1 ( echo INSTALL FAILED & exit /b 1 )
echo Install OK.

echo.
echo === STEP 3: Grant permissions ===
%ADB% shell appops set %PKG% SYSTEM_ALERT_WINDOW allow
%ADB% shell settings put secure enabled_accessibility_services %PKG%/.HeadCursorAccessibilityService
%ADB% shell settings put secure accessibility_enabled 1
echo Permissions granted.

echo.
echo === STEP 4: Start OverlayService and wait 4s for receivers to register ===
%ADB% shell am startforegroundservice -n %PKG%/.OverlayService
ping -n 5 localhost >nul

echo.
echo === STEP 5: Fire LEFT_CLICK broadcast (simulates Vol Up press) ===
%ADB% shell am broadcast -a com.example.dexoverlay.TRIGGER_ACTION -p %PKG% --es action_name LEFT_CLICK
ping -n 3 localhost >nul

echo.
echo === STEP 6: Fire RIGHT_CLICK broadcast (tests gesture fallback path) ===
%ADB% shell am broadcast -a com.example.dexoverlay.TRIGGER_ACTION -p %PKG% --es action_name RIGHT_CLICK
ping -n 3 localhost >nul

echo.
echo === STEP 7: Dump full HeadCursorService logcat ===
echo --- Chain: OVERLAY -^> RECEIVER_CLICK -^> NODE CLICK / GESTURE ---
%ADB% logcat -d HeadCursorService:D *:S
echo.
echo === ALSO: All log tags for context ===
%ADB% logcat -d -t 80 *:E HeadCursorService:D
echo.
echo === DONE ===
echo.
echo PASS indicators:
echo   "OVERLAY: Dispatching LEFT_CLICK"        ... OverlayService got broadcast
echo   "RECEIVER_CLICK: Executing mapped click" ... accessibility service got click intent
echo   "NODE CLICK: ACTION_CLICK on ..."        ... node tree click attempted
echo   "ACCESSIBILITY GESTURE: Dispatched"      ... gesture fallback fired
endlocal
