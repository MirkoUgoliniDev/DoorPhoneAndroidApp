@echo off
:: ------------------------------------------------------------
:: AUTO-ELEVAZIONE UAC
:: "net session" fallisce se non si e' amministratore (ERRORLEVEL != 0).
:: In quel caso PowerShell rilancia questo stesso script (%~f0 = percorso
:: completo del bat) con il flag -Verb RunAs, che mostra il popup UAC.
:: Il processo originale esce subito; quello elevato prosegue normalmente.
:: ------------------------------------------------------------
net session >nul 2>&1
if %ERRORLEVEL% NEQ 0 (
    powershell -Command "Start-Process '%~f0' -Verb RunAs"
    exit /b
)
:: ============================================================
:: camera_reale.bat
:: Ripristina l'endpoint della camera reale DoorPi
:: sull'emulatore (o dispositivo fisico).
:: ============================================================

set ADB="%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
set PKG=com.doorphone
set PREFS=/data/data/%PKG%/shared_prefs/%PKG%_preferences.xml
set ENDPOINT=http://192.168.1.54:8081/?action=stream

echo.
echo  *** CAMERA: modalita' REALE ***
echo  Endpoint: %ENDPOINT%
echo.

%ADB% shell "run-as %PKG% sed -i 's|name=\"camera_endpoint\">.*</string>|name=\"camera_endpoint\">%ENDPOINT%</string>|g' %PREFS%"
%ADB% shell "run-as %PKG% grep camera_endpoint %PREFS%"

echo.
echo  Riavvio app...
%ADB% shell am force-stop %PKG%
%ADB% shell am start -n "%PKG%/%PKG%.app.MumlaActivity"
echo  Fatto!
