==============================================================
 TOOLS - MumlaO / DoorPi
==============================================================

FILE PRESENTI
-------------
  proxy_start.bat     Avvia il proxy per lo stream video
  camera_emulator.bat Switcha la camera sull'endpoint proxy
  camera_reale.bat    Switcha la camera sull'endpoint reale


==============================================================
 COME TESTARE LO STREAM VIDEO SULL'EMULATORE
==============================================================

PREREQUISITI:
  - PC sulla stessa rete del DoorPi (192.168.1.x)
  - Emulatore Android avviato in Android Studio

  NOTA: tutti i bat si auto-elevano a Amministratore al doppio click
  tramite UAC (apparira' il popup di conferma Windows). Non serve
  "tasto destro -> Esegui come amministratore".

PASSI:

  1. Avvia il proxy (una volta sola, rimane attivo)
     - Doppio click su proxy_start.bat
     - Conferma il popup UAC
     - Lascia la finestra aperta

  2. Switcha la camera sull'endpoint emulatore
     - Doppio click su camera_emulator.bat
     - L'app si riavvia automaticamente

  3. Testa l'app sull'emulatore
     - Lo stream viene instradato: emulatore -> PC -> DoorPi

  4. Prima di installare sul Nexus 7 reale
     - Doppio click su camera_reale.bat
     - Oppure cambia manualmente in Settings -> Camera Endpoit

  5. Per fermare il proxy
     - Nella finestra del proxy_start.bat, premi un tasto


==============================================================
 ENDPOINT CAMERA
==============================================================

  Emulatore:    http://10.0.2.2:8081/?action=stream
  Nexus 7:      http://192.168.1.54:8081/?action=stream

  (modificabile anche da app: Menu -> Settings -> Camera Endpoit)


==============================================================
 INSTALLAZIONE APP SULL'EMULATORE (manuale)
==============================================================

  cd C:\Lavori\Android\DoorPhoneAndroidApp
  .\gradlew assembleDebug
  adb install -r app\build\outputs\apk\debug\doorphone_emulatore.apk
  adb shell am start -n "com.doorphone/com.doorphone.app.MumlaActivity"

==============================================================
