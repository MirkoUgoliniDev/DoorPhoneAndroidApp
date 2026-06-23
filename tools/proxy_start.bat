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
:: proxy_start.bat
:: Avvia il port proxy per testare lo stream video della camera
:: DoorPi sull'emulatore Android.
::
:: Funzionamento:
::   L'emulatore non puo' raggiungere direttamente 192.168.1.54.
::   Questo script crea un tunnel:
::   Emulatore (10.0.2.2:8081) --> PC localhost:8081 --> 192.168.1.54:8081
::
:: Requisiti:
::   - Eseguire come Amministratore
::   - PC sulla stessa rete del DoorPi (192.168.1.x)
::   - Emulatore avviato
:: ============================================================

echo.
echo  *** PROXY STREAM CAMERA DOORPI ***
echo  Emulatore ^-^> PC ^-^> 192.168.1.54:8081
echo.

:: Rimuovi proxy precedente se esiste
netsh interface portproxy delete v4tov4 listenport=8081 listenaddress=127.0.0.1 >nul 2>&1

:: Aggiungi nuovo proxy
netsh interface portproxy add v4tov4 listenport=8081 listenaddress=0.0.0.0 connectport=8081 connectaddress=192.168.1.54

if %ERRORLEVEL% NEQ 0 (
    echo.
    echo  ERRORE: eseguire come Amministratore!
    echo  Tasto destro sul file ^> "Esegui come amministratore"
    pause
    exit /b 1
)

echo  Proxy attivo. Configurazione:
netsh interface portproxy show all

echo.
echo  Endpoint da usare nell'emulatore:
echo  http://10.0.2.2:8081/?action=stream
echo.
echo  Premi un tasto per fermare il proxy e uscire...
pause >nul

:: Rimuovi proxy alla chiusura
netsh interface portproxy delete v4tov4 listenport=8081 listenaddress=0.0.0.0
echo  Proxy rimosso.
