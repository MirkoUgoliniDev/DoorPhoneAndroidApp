# DoorPhone — Client Mumble con controllo DoorPi

**DoorPhone** (package `com.doorphone`) è un'applicazione Android che combina un client **Mumble** (VoIP) con il controllo di un videocitofono intelligente basato su **DoorPi** (Raspberry Pi).

> **Nota sul nome:** il progetto nasceva come **MumlaO** (fork di Mumla). È stato rinominato in **DoorPhone** con la migrazione del package da `se.lublin.mumla` a `com.doorphone`. In questo documento "DoorPhone" e il vecchio "MumlaO" indicano la stessa app; eventuali riferimenti residui a `mumlaO/` riguardano la vecchia cartella ormai dismessa.

È un fork personalizzato di [Mumla](https://gitlab.com/quite/mumla) (GPL v3), esteso con:
- Streaming video **RTSP / H.264** dalla telecamera DoorPi (libreria alexvas/rtsp-client-android)
- Controllo dispositivi via **HTTP REST** (porta, luci, tablet, reboot)
- Comportamento **kiosk** per tablet fisso (Nexus 7 2012)
- **Configurazione remota** automatica dal Raspberry Pi (JSON via HTTP)

> **Regola**: questo file va aggiornato ad ogni modifica significativa al codice.

---

## Indice

1. [Requisiti](#1-requisiti)
2. [Architettura del sistema](#2-architettura-del-sistema)
3. [Configurazione remota — RaspberryConfigFetcher](#3-configurazione-remota--raspberryconfigfetcher)
4. [Foreground Service — DoorPhoneService](#4-foreground-service--doorphoneservice)
5. [Riconnessione WiFi e Doze mode](#5-riconnessione-wifi-e-doze-mode)
6. [Streaming video RTSP](#6-streaming-video-rtsp)
7. [Server DoorPi — HTTP REST](#7-server-doorpi--http-rest)
8. [Suoneria (SoundPool)](#8-suoneria-soundpool)
9. [Flusso di una chiamata citofono](#9-flusso-di-una-chiamata-citofono)
10. [Modalità kiosk](#10-modalità-kiosk)
11. [Indicatore stato connessione](#11-indicatore-stato-connessione)
12. [Configurazione manuale Settings](#12-configurazione-manuale-settings)
13. [Configurazione Raspberry Pi (lato server)](#13-configurazione-raspberry-pi-lato-server)
14. [Build e installazione](#14-build-e-installazione)
15. [Testing sull'emulatore](#15-testing-sullemulatore)
16. [Struttura del progetto](#16-struttura-del-progetto)
17. [Dipendenze](#17-dipendenze)
18. [Bug noti e fix applicati](#18-bug-noti-e-fix-applicati)
19. [Note di sicurezza](#19-note-di-sicurezza)
20. [Licenza](#20-licenza)

---

## 1. Requisiti

### Dispositivo target
| Parametro | Valore |
|-----------|--------|
| Dispositivo | **Nexus 7 2012** (grouper) |
| Android | **7.1.2** (API 25) |
| Root | **Sì** — necessario per reboot, screen lock, luminosità e ora di sistema |
| ABI | `armeabi-v7a` |

### Ambiente di sviluppo
| Strumento | Versione |
|-----------|---------|
| Android Studio | 2023.x o superiore |
| JDK | 17 |
| compileSdk | 35 |
| targetSdk | 25 |
| minSdk | 24 |
| Kotlin | 2.0.21 |
| NDK | 29.0.14206865 |

---

## 2. Architettura del sistema

Il sistema è composto da un **Raspberry Pi** (DoorPi) che funge da centrale citofonica e da un **tablet Android** (MumlaO) che funge da terminale di controllo. I due dispositivi comunicano attraverso **tre canali indipendenti**:

```
┌──────────────────────────────────────────────────────────────────────┐
│                    TABLET ANDROID (Nexus 7)                          │
│                                                                      │
│  ┌────────────────────────────────────────────────────────────────┐  │
│  │                     VideoVLCActivity                           │  │
│  │                                                                │  │
│  │  ┌──────────────────┐  ┌──────────────┐  ┌─────────────────┐  │  │
│  │  │  RtspSurfaceView │  │ DoorPhoneService │  │CommandSubmitter │  │  │
│  │  │  RTSP H.264/265  │  │ Mumble VoIP  │  │  HTTP REST      │  │  │
│  │  └────────┬─────────┘  └──────┬───────┘  └───────┬─────────┘  │  │
│  └───────────┼───────────────────┼──────────────────┼────────────┘  │
└──────────────┼───────────────────┼──────────────────┼───────────────┘
               │                   │                  │
               │ RTSP TCP :554     │ Mumble TCP/TLS   │ HTTP REST
               │                   │ :64738           │ :8080
               │                   │                  │
               ▼                   ▼                  ▼
┌──────────────────────────────────────────────────────────────────────┐
│                  RASPBERRY PI — 192.168.1.54                         │
│                                                                      │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────────────────┐   │
│  │  IP Camera   │  │    Murmur    │  │         DoorPi           │   │
│  │  RTSP :554   │  │  (Mumble     │  │  (HTTP REST :8080        │   │
│  │  192.168.1.  │  │   server)    │  │   + controllo hardware)  │   │
│  │  124         │  │              │  │                          │   │
│  └──────────────┘  └──────────────┘  └──────────────────────────┘   │
└──────────────────────────────────────────────────────────────────────┘
```

### Layer applicativi Android

| Layer | Classi principali |
|-------|------------------|
| **UI** | `VideoVLCActivity`, `DoorPhoneActivity`, `Preferences` |
| **Service** | `DoorPhoneService` (foreground, connessione Mumble) |
| **Config** | `RaspberryConfigFetcher` (fetch JSON config dal Raspberry) |
| **Network** | `CommandSubmitter` (HTTP REST), libreria `Humla` (protocollo Mumble) |
| **Video** | `RtspSurfaceView` (libreria alexvas, MediaCodec nativo) |
| **Audio** | `Sounds` (SoundPool: suoneria e beep) |
| **Data** | `MumlaSQLiteDatabase`, `Settings` |

---

## 3. Configurazione remota — RaspberryConfigFetcher

All'avvio e ogni volta che l'app torna in foreground, `RaspberryConfigFetcher` esegue una chiamata HTTP GET all'URL configurato in Settings e applica automaticamente tutti i parametri ricevuti.

### Flusso configurazione

```
App entra in foreground (MyApp.onActivityResumed)
        │
        ▼
RaspberryConfigFetcher.fetch(configUrl, prefs)
        │
        ▼  HTTP GET (asincrono, timeout 10s)
        │
   ┌────┴──────────────────────────────────────────────────────────────┐
   │  JSON dal Raspberry Pi (esempio completo)                         │
   │  {                                                                │
   │    "kiosk": true,              ← modalità kiosk                  │
   │    "hide_status_bar": true,    ← nasconde status bar             │
   │    "miclevel": 140,            ← boost microfono (0-200, 100=neutro) │
   │    "speakerlevel": 100,        ← volume altoparlante (0-100%)    │
   │    "screenbrightnesslevel": 100, ← luminosità display (0-100%)  │
   │    "timezone": "Europe/Rome",  ← fuso orario                    │
   │    "server_time": 1778307446,  ← epoch Unix (int, non stringa!) │
   │    "mumbleserver": {           ← server Mumble                  │
   │      "server": "192.168.1.54",                                   │
   │      "port": "64738",                                            │
   │      "username": "Doorpi",                                       │
   │      "password": "..."                                           │
   │    },                                                             │
   │    "doorpi": {                 ← controllo DoorPi               │
   │      "host": "192.168.1.54",                                     │
   │      "api_port": "8080",                                         │
   │      "unlock_command": "unlockdoor"                              │
   │    },                                                             │
   │    "camera": {                 ← telecamera                     │
   │      "video": { "enabled": true, "endpoint": "rtsp://..." },     │
   │      "snapshot": { "enabled": true, "endpoint": "http://..." },  │
   │      "username": "...", "password": "..."                        │
   │    }                                                              │
   │  }                                                                │
   └────┬──────────────────────────────────────────────────────────────┘
        │
        ▼  Parsing e applicazione (main thread)
        │
        ├── kiosk / hide_status_bar  → SharedPreferences (immediato)
        ├── miclevel                 → PREF_AMPLITUDE_BOOST
        ├── speakerlevel             → AudioManager (STREAM_MUSIC + STREAM_VOICE_CALL)
        ├── screenbrightnesslevel    ─┐
        ├── timezone                  ├── Thread "TimeSync" → su -c ...
        └── server_time             ─┘
        ├── mumbleserver            → host, porta, password Mumble
        ├── doorpi                  → host, api_port, unlock_command
        └── camera                  → endpoint RTSP, credenziali
```

### Parametri JSON supportati

### URL endpoint

```
GET http://192.168.1.54:8080/config/?p=<piano>
```

Il parametro `?p=` (es. `p1`, `p2`, `p4`) viene aggiunto automaticamente dall'app in base al piano configurato. Non includerlo nell'URL nelle Settings: verrebbe ignorato e sostituito.

### Parametri JSON supportati

| Campo JSON | Tipo | Range / Note | Applicazione |
|------------|------|-------------|--------------|
| `kiosk` o `kiosk_mode` | bool | — | Modalità kiosk (Back disabilitato, auto-foreground) |
| `hide_status_bar` | bool | — | Nasconde status bar + navigation bar (immersive sticky) |
| `miclevel` | int | 0-200, 100=neutro | Boost microfono → `PREF_AMPLITUDE_BOOST` |
| `speakerlevel` | int | 0-100% | Volume altoparlante su STREAM_MUSIC e STREAM_VOICE_CALL |
| `screenbrightnesslevel` | int | 0-100% → 0-255 | Luminosità display via `su -c settings put system screen_brightness` |
| `timezone` | string | es. `Europe/Rome` | Fuso orario via `su -c settings put global time_zone` + broadcast |
| `server_time` | int | Epoch Unix | Ora di sistema via `su -c date @<epoch>` |
| `mumbleserver` | object | — | Host (`server`/`host`/`ip`), porta, username, password |
| `doorpi` | object | — | Host (`host`/`ip`), api_port, unlock_command |
| `apk` | object | — | **Ignorato da MumlaO** — usato da app updater separata |
| `camera` | object | — | Endpoint RTSP/snapshot, username, password |

> Tutti i campi sono opzionali. Se assenti, i valori in SharedPreferences rimangono invariati. Se il fetch fallisce (WiFi non ancora disponibile al boot), l'app usa i valori cached dall'avvio precedente.

### Dettaglio `server_time`

| Formato | Esempio | Note |
|---------|---------|------|
| **int** (consigliato) | `1778307446` | Epoch Unix UTC — zero ambiguità |
| string ISO 8601 UTC (legacy) | `"2026-05-08T09:24:27"` | Accettato ma verboso — deve essere UTC |
| string locale (errato) | `"2026-05-08T11:24:27"` | **NON usare**: causa errore di +1/+2 ore |

```python
# CORRETTO
import time
"server_time": int(time.time())
```

### Dettaglio `camera`

Priorità: se `video.enabled = true` → RTSP; altrimenti se `snapshot.enabled = true` → MJPEG/HTTP. Se nessuno è abilitato, le preferenze camera non vengono aggiornate.

### Esempio implementazione Python (Flask)

```python
from flask import Flask, request, jsonify
import time

app = Flask(__name__)

@app.route("/config/")
def config():
    piano = request.args.get("p", "p1").lower()
    rtsp_map = {
        "p1": "rtsp://192.168.1.124:554/Preview_01_sub",
        "p4": "rtsp://192.168.1.124:554/Preview_01_sub",
    }
    return jsonify({
        "server_time":          int(time.time()),
        "kiosk":                True,
        "hide_status_bar":      True,
        "miclevel":             140,
        "speakerlevel":         100,
        "screenbrightnesslevel": 100,
        "timezone":             "Europe/Rome",
        "mumbleserver": {"server": "192.168.1.54", "port": "64738",
                         "username": "Doorpi", "password": "..."},
        "doorpi":       {"host": "192.168.1.54", "api_port": "8080",
                         "unlock_command": "unlockdoor"},
        "camera": {
            "video":    {"enabled": True,  "endpoint": rtsp_map.get(piano, rtsp_map["p1"])},
            "snapshot": {"enabled": False, "endpoint": ""},
            "username": "doorpiuser", "password": "..."
        }
    })
```

### Test con curl

```bash
# Risposta per piano 4
curl -s "http://192.168.1.54:8080/config/?p=p4" | python3 -m json.tool

# Verifica server_time (diff deve essere < 5s)
curl -s "http://192.168.1.54:8080/config/?p=p1" | python3 -c \
  "import sys,json,time; d=json.load(sys.stdin); \
   print(f'server_time diff: {d[\"server_time\"]-int(time.time())}s')"
```

---

## 4. Foreground Service — DoorPhoneService

### Perché serve un foreground service

Android uccide i servizi in background quando il dispositivo entra in **Doze mode** (display spento). Per un'app kiosk che deve ricevere il campanello 24/7, `DoorPhoneService` gira come **foreground service** con notifica persistente, che Android non può terminare.

### Ciclo di vita

```
DoorPhoneActivity.onCreate()
        │
        └── startForegroundService(DoorPhoneService)
                │
                ▼
        DoorPhoneService.onCreate()
        ├── startForeground() → notifica "Connessione in corso..."
        ├── registerObserver(mObserver)
        └── registerNetworkCallback()     ← Doze fix
                │
                ▼
        DoorPhoneActivity.onServiceConnected()
        └── Connect()  ← solo se stato DISCONNECTED
                │
                ▼
        HumlaService.connect() → TCP/TLS → Murmur
                │
                ▼
        mObserver.onConnected()
        └── notifica aggiornata → "Connesso a DoorPi / Servizio Mumble attivo"
                │
                ▼
        mObserver.onUserConnected(user)
        ├── is_connected = true
        └── UpdateClientStatus("Connected") → LocalBroadcast → ActionBar verde
```

### Notifica foreground

| Stato | Titolo | Testo |
|-------|--------|-------|
| `onCreate()` | DoorPhone | Connessione in corso... |
| `onConnected()` | DoorPhone — Connesso a DoorPi | Servizio Mumble attivo |
| `onDisconnected()` | DoorPhone | In riconnessione... |

### Flag `mDoorCallActive`

`DoorPhoneService` mantiene `private volatile boolean mDoorCallActive` per tracciare se una chiamata DoorPi è in corso. È `volatile` perché `onDisconnected()` gira sul thread di rete Humla mentre `openCall()`/`closeCall()` girano sul main thread.

| Evento | `mDoorCallActive` |
|--------|-------------------|
| `openCall()` | → `true` |
| `closeCall()` | → `false` |
| `onDisconnected()` | → `false` (reset thread-safe) |

Questo flag è letto da `VideoVLCActivity.onServiceConnected()` per evitare di silenziare Mumble durante la ricreazione dell'Activity in kiosk=false.

---

## 5. Riconnessione WiFi e Doze mode

### Il problema di `CONNECTIVITY_ACTION`

La libreria Humla usa il broadcast `CONNECTIVITY_ACTION` per rilevare il ritorno della rete. In **Doze mode** (display spento da più di qualche minuto) questo broadcast è **bloccato** dal sistema → il servizio rimane bloccato in stato "reconnecting" indefinitamente.

### La soluzione: `NetworkCallback`

`DoorPhoneService` registra un `ConnectivityManager.NetworkCallback` che funziona anche in Doze mode:

```
WiFi cade
    │
    ▼
HumlaService → stato CONNECTION_LOST
Registra CONNECTIVITY_ACTION receiver ← (bloccato in Doze mode)
    │
    ▼
Dispositivo entra in Doze mode
    │
WiFi torna
    │
    ├── CONNECTIVITY_ACTION ─────── [BLOCCATO — non arriva]
    │
    └── NetworkCallback.onAvailable()  [FUNZIONA anche in Doze]
            │
            ▼  post() sul main thread (thread-safety)
            │
            ├── getTargetServer() == null? → skip (boot race condition)
            │
            ├── stato == CONNECTING o CONNECTED? → skip (già in connessione)
            │
            ├── cancelReconnect()    ← cancella vecchio tentativo bloccato
            └── connect()            ← nuova connessione immediata
```

### Garanzie implementate

| Garanzia | Meccanismo |
|----------|------------|
| Thread-safety | `getConnectionState()` letto sul main thread, non sul callback thread |
| No doppio connect | Guard su stato: se già `CONNECTING`, il secondo `onAvailable()` viene ignorato |
| No NPE al boot | Guard `getTargetServer() == null`: protegge dalla finestra tra `onCreate()` e la prima `Connect()` |
| No falso trigger al boot | `mFirstNetworkCallback` skippa il primo `onAvailable()` sintetico se WiFi era già disponibile |

### Connessione al boot — keyguard + schermo spento (fix 2026-06-27)

La connessione Mumble è avviata **solo** da `DoorPhoneActivity.onServiceConnected() → Connect()`, che richiede l'Activity **RESUMED**. Dopo un reboot (il kiosk riavvia ~2×/giorno) il device si presenta su **keyguard con schermo spento**: `DoorPhoneActivity`, pur essendo il launcher, va in `onResume → onPause` in ~55 ms dietro il keyguard e non resta mai in foreground → il `DoorPhoneService` nasce ma non riceve mai l'ordine di connettersi (e il `NetworkCallback` non può supplire perché `getTargetServer()` è ancora `null`). Risultato: **app scollegata finché un umano non sblocca lo schermo**.

**Fix:** in `DoorPhoneActivity.onCreate()` vengono aggiunti i window flags
`FLAG_SHOW_WHEN_LOCKED | FLAG_DISMISS_KEYGUARD | FLAG_TURN_SCREEN_ON | FLAG_KEEP_SCREEN_ON`
(gli stessi già usati da `VideoVLCActivity`). Il router emerge sopra il lockscreen e accende lo schermo per la finestra di boot/connect → resta RESUMED → la connessione parte da sola.

Verificato con reboot ripetuti (`docs/reboot_test.sh`): **baseline senza fix 3/3 blocco; con fix 10/10 connesso in 6-8 s**, senza interazione. Dettaglio completo in **`docs/fix-blocco-boot-2026-06-27.md`**.

> **Assunzione:** `DISMISS_KEYGUARD` sblocca solo un keyguard **non sicuro** (senza PIN/pattern), come sul kiosk. Con un PIN configurato il boot-connect tornerebbe a bloccarsi.

---

## 6. Streaming video RTSP

### Pipeline video (alexvas/rtsp-client-android)

```
IP Camera Reolink (RTSP TCP :554)
        │
        ▼
RtspSurfaceView (libreria alexvas)
├── Connessione TCP RTSP
├── Demux RTP
├── Decode H.264/H.265 via MediaCodec (hardware)
└── Render su Surface nativa
        │
        ▼  setVideoRotation(degrees)
        │
SurfaceView nel layout di VideoVLCActivity
(rotazione applicata da SurfaceFlinger a livello native window)
```

### API utilizzata in VideoVLCActivity

```java
mRtspSurfaceView.init(uri, username, password, userAgent, null);
mRtspSurfaceView.setVideoRotation(degrees);      // 0 / 90 / 180 / 270
mRtspSurfaceView.start(true, false, false);      // video=true, audio=false, recording=false
if (mRtspSurfaceView.isStarted()) mRtspSurfaceView.stop();
mRtspSurfaceView.setStatusListener(listener);
```

> **Kotlin/Java interop**: i metodi Kotlin con parametri default NON generano overload Java automaticamente senza `@JvmOverloads`. Specificare sempre tutti i parametri esplicitamente.

### Ciclo di vita stream

| Evento Activity | Azione stream |
|-----------------|--------------|
| `onResume()` | `startRtspStream()` |
| `onPause()` | `stopRtspStream()` — **solo qui**, non in onStop/onDestroy |
| `onStop()` | nessuna azione (Surface già invalidata, stop in onPause sufficiente) |

> **Attenzione**: chiamare `stopRtspStream()` in `onStop()` o `onDestroy()` causa spam di errori `cancelBuffer: BufferQueue has no connected producer` su SurfaceFlinger perché la Surface è già stata rilasciata da Android.

### Warning noto su Nexus 7

```
Video stream rotation is not supported by this Android device
(Nexus 7 - grouper, codec: 'OMX.Nvidia.h264.decode')
```
Il codec non supporta `KEY_ROTATION` via `MediaFormat`, ma SurfaceFlinger applica comunque la rotazione a livello di native window. Il video si vede correttamente; il warning è rumoroso ma non causa malfunzionamenti.

---

## 7. Server DoorPi — HTTP REST

MumlaO comunica con DoorPi tramite `CommandSubmitter` (chiamate HTTP GET asincrone, timeout 30s, callback `PostDataCallback`).

### Endpoint principali

#### Sblocco porta
```
GET http://192.168.1.54:8080/?command=unlockdoor
```
Risposta: `{ "unlock_door": "PIANO4" }`

#### Stato dispositivi
```
GET http://192.168.1.54:8080/?command=getalldevicestatus
```
Risposta: `{ "device1": 1, "device3": 0, "power_tablet": 1 }`

#### Controllo dispositivi
```
GET http://192.168.1.54:8080/?command=setdevicestatus&D=<device>&S=<on|off>
```

#### Reboot Raspberry Pi
```
GET http://192.168.1.54:8080/?command=reboot_raspberry
```

### Gestione anti-rimbalzo

| Evento | Effetto UI |
|--------|-----------|
| Click su Sblocca | Pulsante disabilitato (grigio) immediatamente |
| Risposta JSON con `unlock_door` | Pulsante verde per 10 secondi |
| Errore HTTP o JSON inatteso | Pulsante riabilitato dopo 5 secondi |
| `closeCall()` | Pulsante ripristinato allo stato neutro |

---

## 8. Suoneria (SoundPool)

La suoneria usa `android.media.SoundPool` tramite la classe singleton `Sounds`.

### Bug fix applicati

**Bug 1 — ID sample hardcoded**: `SoundPool.load()` restituisce un ID assegnato dal sistema, non necessariamente `1` o `2`. Usare i letterali causava riproduzioni sbagliate o silenziose. Fix: usare sempre `mySounds.getSound1()` / `mySounds.getSound2()`.

**Bug 2 — Race condition al cold start**: `SoundPool.play()` chiamato prima di `onLoadComplete` ritornava `0` (sample non pronto) e il primo ring non suonava. Fix: flag `volatile boolean mLoaded` che blocca `playSound()` finché entrambi i sample non sono pronti.

**Bug 3 — Volume cacheato**: il volume veniva calcolato una volta nel costruttore. Se al momento dell'istanza lo stream era muto, tutti i `play` successivi erano silenziosi. Fix: metodo `currentVolume()` che legge il volume corrente ad ogni riproduzione.

### Comportamento risultante

- Primo ring con app appena avviata: il primo tick può essere saltato (logcat: `samples not loaded yet`); il loop ogni 5s riprova automaticamente.
- Variazioni live del volume hanno effetto immediato sul tick successivo.
- `STREAM_MUSIC` usato per la suoneria → controllato dallo stesso `speakerlevel` del JSON.

---

## 9. Flusso di una chiamata citofono

```
Visitatore preme campanello
        │
        ▼
DoorPi invia messaggio Mumble: "cmd-ring"
        │
        ▼
DoorPhoneService.onMessageLogged() → Ring()
├── WakeLock ACQUIRE_CAUSES_WAKEUP (3s) → display acceso
├── LocalBroadcast "ring" → VideoVLCActivity
└── LocalBroadcast "ring" retry +1500ms (sicurezza Activity non ancora pronta)
        │
        ▼
VideoVLCActivity.messageReceiver riceve "ring"
├── FLAG_TURN_SCREEN_ON → finestra svegliata
├── Sounds.playSound(doorbell) — si ripete ogni 5s
├── bringActivityForeground() — app in primo piano
├── Mostra pulsante ACCETTA
└── Avvia timeout 50s (chiusura automatica se nessuno risponde)
        │
        ▼ Utente clicca ACCETTA
        │
        ├── Cancella timeout ring (50s)
        ├── DoorPhoneService.openCall()
        │       ├── mDoorCallActive = true
        │       └── setSelfMuteDeafState(false, false) → microfono attivo
        ├── sendMessage("cmd-accept-call") → DoorPi
        ├── Mostra pulsante SBLOCCA PORTA
        └── Avvia timeout 20s (chiusura se nessuno sblocca)
                │
                ▼ Utente clicca SBLOCCA
                │
                ├── CommandSubmitter → GET /?command=unlockdoor
                ├── DoorPi → { "unlock_door": "PIANO4" }
                ├── FAB verde per 10 secondi
                └── closeCall():
                        ├── mDoorCallActive = false
                        ├── setSelfMuteDeafState(true, true) → microfono spento
                        ├── sendMessage("cmd-close-call") → DoorPi
                        ├── Rimuove FLAG_TURN_SCREEN_ON
                        └── turnOffScreen() → display spento
```

### Meccanismo ACK

DoorPi risponde ai comandi con messaggi Mumble di conferma (`ack-accept-call`, `ack-close-call`). Se l'ACK non arriva entro il timeout, l'app chiude la chiamata in modo sicuro per evitare stalli.

---

## 10. Modalità kiosk

Controllata da due flag JSON indipendenti:

```
kiosk=false, hide_status_bar=false
    → Modalità manutenzione: Back funziona, barre di sistema visibili

kiosk=true, hide_status_bar=false
    → Kiosk leggero: Back disabilitato, barre visibili, ActionBar mostra stato

kiosk=true, hide_status_bar=true
    → Kiosk completo (produzione): schermo pieno, pallino stato in alto a sinistra
```

### Comportamento `kiosk=false` (stabilizzato)

Con `kiosk=false` il Back è visibile. Premendolo:

```
Back premuto su VideoVLCActivity
        │
        ▼
onStop() → unbindService() + mNeedsRebind = true
        │
        ▼
DoorPhoneActivity.onResume() → bind → onServiceConnected()
→ stato CONNECTED → openVideoStream() → nuova VideoVLCActivity
        │
        ▼
VideoVLCActivity.onResume()
└── mNeedsRebind == true → bindService() → onServiceConnected()
        │
        └── guard: !ring && !mService.isDoorCallActive()
                    → closeCall() solo se NON c'è chiamata attiva
```

---

## 11. Indicatore stato connessione

Con `kiosk=true` + `hide_status_bar=true` l'ActionBar è nascosta. Un cerchio colorato in alto a sinistra sostituisce l'indicazione di stato:

```
┌──────────────────────────────────────────┐
│ ●P4                                      │
│      [video RTSP]                        │
│      [pulsanti chiamata]                 │
└──────────────────────────────────────────┘
```

| Colore | Significato |
|--------|-------------|
| **Verde** `#15ff00` | Android connesso al server Mumble (TCP/TLS up) |
| **Rosso** `#ff0033` | Disconnesso o in riconnessione |

> **Definizione scelta (opzione A)**: il pallino rappresenta lo stato della connessione TCP/TLS tra
> l'app Android e il server Murmur. Non indica se il Raspberry Pi (talkkonnect) è nel canale.

### Quando viene aggiornato

| Momento | Meccanismo | Risultato |
|---------|-----------|-----------|
| Binding al servizio | `mService.isConnected()` in `onServiceConnected()` | Verde/Rosso immediato |
| TCP Mumble stabilita | `onConnected()` → `UpdateClientStatus("Connected")` | **Verde immediato** |
| TCP Mumble persa | `onDisconnected()` → `UpdateClientStatus("Disconnected")` | **Rosso immediato** |
| Kiosk mode on/off | `updateConnectionStatusOverlay()` diretta | Visibile/nascosto |

### Flusso completo — due connessioni indipendenti

Il sistema gestisce due livelli di connessione distinti:

```
LIVELLO 1 — RETE (WiFi)
    Gestito da: ConnectivityManager.NetworkCallback (DoorPhoneService)
    Evento chiave: onAvailable(Network)

LIVELLO 2 — MUMBLE (TCP/TLS)
    Gestito da: HumlaService + HumlaObserver (DoorPhoneService)
    Evento chiave: onConnected() / onDisconnected()
```

#### Scenario A — WiFi cade e torna

```
WiFi cade
    │
    ▼  TCP Mumble si interrompe
    │
    ▼  HumlaObserver.onDisconnected()
    │   ├── UpdateClientStatus("Disconnected") → LocalBroadcast
    │   ├── is_connected = false
    │   └── mDoorCallActive = false
    │
    ▼  messageReceiver riceve "Disconnected"
    │   └── mConnectionOk = false → pallino ROSSO
    │
    ▼  Dispositivo in Doze mode (display spento)
    │   CONNECTIVITY_ACTION è bloccato dal sistema [PROBLEMA]
    │
    ▼  WiFi torna
    │
    ├── CONNECTIVITY_ACTION ─── [BLOCCATO in Doze — non arriva]
    │
    └── NetworkCallback.onAvailable() ─── [FUNZIONA in Doze]
            │
            ▼  post() sul main thread
            │
            ├── getTargetServer() == null? → skip
            ├── stato != DISCONNECTED/CONNECTION_LOST? → skip
            ├── cancelReconnect()
            └── connect()  → nuova connessione TCP/TLS
                    │
                    ▼  HumlaObserver.onConnected()
                    │   ├── is_connected = true
                    │   └── UpdateClientStatus("Connected") → LocalBroadcast
                    │
                    ▼  messageReceiver riceve "Connected"
                    └── mConnectionOk = true → pallino VERDE ✓
```

#### Scenario B — Server Mumble cade (WiFi ancora up)

```
Server Murmur si spegne / crash
    │
    ▼  TCP si interrompe
    │
    ▼  HumlaObserver.onDisconnected()
    │   └── UpdateClientStatus("Disconnected") → pallino ROSSO
    │
    ▼  HumlaService entra in loop di retry automatico
    │   (backoff interno alla libreria Humla)
    │
    ▼  [contemporaneamente] Raspberry Pi (talkkonnect):
    │   OnDisconnect() → exponential backoff (5s→10s→20s→40s→max 60s)
    │   → ReConnect() — indipendente dall'Android
    │
    ▼  Server Murmur torna up
    │
    ▼  HumlaService riconnette
    │
    ▼  HumlaObserver.onConnected()
    │   └── UpdateClientStatus("Connected") → pallino VERDE ✓
    │
    ▼  [in parallelo] Raspberry Pi riconnette con il suo timer
        → entra nel canale (indipendente dal pallino)
```

#### Scenario C — App aperta, servizio già connesso

```
VideoVLCActivity.onCreate()
    │
    ▼  bindService(DoorPhoneService)
    │
    ▼  onServiceConnected()
    │   ├── mService.isConnected()  ← stato corrente HumlaService
    │   ├── mConnectionOk = true/false
    │   └── updateConnectionStatusOverlay()  → colore immediato
```

### Fix applicato — ritardo eliminato

**Problema originale**: `UpdateClientStatus("Connected")` era chiamato solo in `onUserConnected()`,
che scatta dopo la sync completa della lista utenti (secondi dopo il TCP connect). Il pallino
restava rosso per tutta la fase di sync anche se la connessione era già attiva.

**Fix**: aggiunta la chiamata a `UpdateClientStatus("Connected")` direttamente in `onConnected()`
(che scatta non appena il TCP/TLS con Murmur è stabilito):

```java
// DoorPhoneService.java — HumlaObserver.onConnected()
@Override
public void onConnected() {
    showForegroundNotification(...);
    is_connected = true;
    UpdateClientStatus("Connected");   // ← aggiunto: pallino verde immediato
}
```

### Visibilità del pallino

Il cerchio è visibile **solo** con `kiosk=true` AND `hide_status_bar=true`.
Con qualsiasi altra combinazione è `View.GONE` e lo stato è mostrato dall'ActionBar.

---

## 12. Configurazione manuale Settings

Accessibile da **Menu (⋮) → Settings** (password default: `mouse`).

### Sezione DoorPi

| Campo | Chiave preference | Default | Descrizione |
|-------|-------------------|---------|-------------|
| Piano | `doorpi_piano` | `PIANO1` | Identificativo unità (es. P4) |
| Host IP | `doorpi_ip` | `192.168.1.54` | IP Raspberry Pi |
| Mumble port | `doorpi_port` | `64738` | Porta server Murmur |
| API Port | `doorpi_api_port` | `8080` | Porta HTTP REST DoorPi |
| Password Mumble | `door_pi_password` | — | Password server Mumble |
| Camera Endpoint | `camera_endpoint` | — | URL RTSP stream |
| Rotazione video | `video_rotation` | `270` | 0° / 90° / 180° / 270° |
| Unlock cmd | `cmd_unlock` | `unlockdoor` | Comando sblocco porta |
| Config URL | `raspberry_config_url` | — | URL JSON configurazione remota |

> I parametri ricevuti via JSON sovrascrivono quelli manuali ad ogni avvio.

---

## 13. Configurazione Raspberry Pi (lato server)

### Fix `server_time`: formato epoch Unix obbligatorio

Se l'ora sul tablet è sempre **2 ore indietro**, la causa è il formato di `server_time` nel server Python del Raspberry.

```python
# ERRATO — causa errore di 2 ore (ora locale senza fuso orario)
"server_time": datetime.now().strftime("%Y-%m-%dT%H:%M:%S")

# CORRETTO — epoch Unix assoluto, zero ambiguità
import time
"server_time": int(time.time())
```

Dopo la modifica riavviare il server:
```bash
sudo systemctl restart doorpi
# Verifica (deve essere un numero intero):
curl -s "http://localhost:8080/config/" | python3 -m json.tool | grep server_time
```

### Fix sessione Mumble appesa (`Kicked from another device`)

Quando l'app viene chiusa bruscamente, la sessione TCP resta aperta sul server fino al timeout. Al riavvio, Murmur kicca la sessione stantia e la nuova connessione riceve un `UserRemove` che Humla interpreta come kick.

**Fix lato server** — ridurre il timeout in `/etc/mumble-server.ini`:
```ini
timeout=15
```

```bash
sudo systemctl restart mumble-server
sudo systemctl status mumble-server   # deve essere active (running)
```

Con `timeout=15`, la sessione stantia viene rimossa in 15 secondi. Il riavvio dell'app impiega più tempo → la sessione è già pulita → nessun conflitto.

### Parametri murmur.ini utili

| Parametro | Valore consigliato | Significato |
|-----------|--------------------|-------------|
| `timeout` | `15` | Secondi prima di considerare offline un client non responsivo |
| `port` | `64738` | Porta TCP/UDP (default) |
| `bandwidth` | `72000` | Banda max per utente in bit/s |
| `users` | `10` | Utenti connessi simultaneamente |
| `allowhtml` | `true` | Permette HTML nei messaggi (usato da DoorPi) |

### Monitoraggio Murmur in tempo reale

```bash
sudo journalctl -u mumble-server -f
```

---

## 14. Build e installazione

### Build debug (dispositivo fisico)
```powershell
.\gradlew assembleDebug
```
APK: `app/build/outputs/apk/debug/doorphone.apk`

> Non ci sono product flavor: un'unica variante `debug`, splittata per ABI tramite
> `output.outputFileName` in `app/build.gradle`.

### Installazione su Nexus 7 (USB)
```powershell
adb install -r app\build\outputs\apk\debug\doorphone.apk
adb shell am start -n "com.doorphone/com.doorphone.app.DoorPhoneActivity"
```

### Output per ABI (nessun flavor)
| ABI | APK | Target |
|-----|-----|--------|
| `armeabi-v7a` | `doorphone.apk` | Nexus 7 2012 (Tegra 3), device reale |
| `x86_64` | `doorphone_emulatore.apk` | Emulatore Android Studio (API 29) |

---

## 15. Testing sull'emulatore

### Emulatore consigliato
- **Device**: Nexus 7 (7 pollici)
- **System Image**: API 29 x86_64 (le immagini API 25 x86_64 non esistono)

### Setup proxy per lo stream RTSP

```
Emulatore → 10.0.2.2:554 → PC localhost:554 → 192.168.1.124:554
```

#### Script nella cartella `tools/`

```
tools\proxy_start.bat      ← avvia proxy (come Admin)
tools\camera_emulator.bat  ← switch endpoint → emulatore
tools\camera_reale.bat     ← ripristina endpoint → DoorPi reale
```

### Nota: `NoRouteToHostException` su emulatore

L'emulatore non raggiunge `192.168.1.54` senza proxy. Se l'endpoint è impostato sull'IP reale, lo stream RTSP fallisce con:
```
java.net.NoRouteToHostException: No route to host
```
Soluzione: avviare il proxy e usare `tools\camera_emulator.bat`.

---

## 16. Struttura del progetto

```
DoorPhoneAndroidApp/
├── app/
│   ├── build.gradle                     Dipendenze, SDK, split ABI
│   ├── docs/
│   │   ├── call_protocol_android.md     Protocollo chiamata dettagliato
│   │   └── bugfix_todo.md               Lista bug e fix applicati
│   └── src/main/
│       ├── AndroidManifest.xml
│       └── java/com/doorphone/
│           ├── app/
│           │   ├── DoorPhoneActivity.java        Launcher, primo binding servizio
│           │   ├── MyApp.java                Application class, fetch config
│           │   └── RaspberryConfigFetcher.java  Fetch + apply JSON config
│           ├── ui/
│           │   └── VideoVLCActivity.java     Schermata principale citofono
│           ├── service/
│           │   ├── DoorPhoneService.java         Foreground service Mumble
│           │   └── IDoorPhoneService.java        Interfaccia pubblica servizio
│           ├── util/
│           │   ├── CommandSubmitter.java     HTTP REST verso DoorPi
│           │   └── Sounds.java               SoundPool (suoneria, beep)
│           ├── callbacks/
│           │   └── PostDataCallback.java     Callback HTTP async
│           └── preference/
│               └── Preferences.java          Activity Settings
├── libraries/
│   ├── humla/                           Protocollo Mumble (locale, GPL)
│   └── rtsp-client/                    Streaming RTSP H.264 (alexvas, locale)
├── tools/
│   ├── proxy_start.bat
│   ├── camera_emulator.bat
│   └── camera_reale.bat
├── set_config_raspberry.md              Contratto JSON endpoint configurazione
├── Raspberry.md                         Config lato server Raspberry Pi
└── local.properties                     Percorsi SDK/NDK (non in VCS)
```

---

## 17. Dipendenze

| Libreria | Versione | Uso |
|---------|---------|-----|
| `:libraries:rtsp-client` | locale (alexvas) | Streaming video RTSP H.264/H.265 (MediaCodec) |
| `:libraries:humla` | locale | Protocollo Mumble (TLS, Opus) |
| `com.loopj.android:android-async-http` | 1.4.9 | Chiamate HTTP async a DoorPi |
| `com.google.guava:guava` | 24.1-jre | Hashing password preferenze |
| `androidx.media3:media3-exoplayer` | 1.9.0 | Dipendenza transitiva rtsp-client |
| `androidx.camera:camera-core` | 1.5.2 | Dipendenza transitiva rtsp-client |

---

## 18. Bug noti e fix applicati

### Fix applicati (in ordine cronologico)

| # | Problema | Fix | File |
|---|---------|-----|------|
| 1 | LibVLC instabile su Nexus 7 con RTSP H.264 | Sostituito con alexvas/rtsp-client-android (MediaCodec nativo) | `VideoVLCActivity`, `build.gradle` |
| 2 | `cancelBuffer` spam SurfaceFlinger | `stopRtspStream()` solo in `onPause()`, rimosso da `onStop()`/`onDestroy()` | `VideoVLCActivity` |
| 3 | NPE in `sendMessage()` durante riconnessione | Guard `isConnectionEstablished()` prima di accedere a `user_in_chat` | `DoorPhoneService` |
| 4 | `user_in_chat` con session ID stantii dopo disconnessione brusca | `user_in_chat.clear()` in `onConnectionDisconnected()` | `DoorPhoneService` |
| 5 | `openCall()` non implementato | `setSelfMuteDeafState(false, false)` + `mDoorCallActive = true` | `DoorPhoneService` |
| 6 | Foreground service non avviato subito | `startForeground()` spostato in `onCreate()` | `DoorPhoneService` |
| 7 | Notifica "Connesso" anche durante interruzione rete | Aggiornamento notifica in `onDisconnected()` → "In riconnessione..." | `DoorPhoneService` |
| 8 | Suoneria silenziosa (ID sample hardcoded) | Usare `getSound1()`/`getSound2()` da `Sounds` | `VideoVLCActivity`, `Sounds` |
| 9 | Primo ring non suona (race condition SoundPool) | Flag `mLoaded`, `playSound()` aspetta `onLoadComplete` | `Sounds` |
| 10 | Volume suoneria cacheato al costruttore | Metodo `currentVolume()` legge volume in tempo reale | `Sounds` |
| 11 | `CONNECTIVITY_ACTION` bloccato in Doze mode | `NetworkCallback` (funziona in Doze) sostituisce il broadcast | `DoorPhoneService` |
| 12 | `closeCall()` durante ricreazione Activity (kiosk=false) | Guard `mDoorCallActive` in `onServiceConnected()` | `VideoVLCActivity`, `DoorPhoneService` |
| 13 | Mancato rebind dopo Home press | Flag `mNeedsRebind` + rebind in `onResume()` | `VideoVLCActivity` |
| 14 | Pallino rosso per minuti anche se già connesso | `mService.isConnected()` in `onServiceConnected()` → update immediato | `VideoVLCActivity` |
| 15 | `NetworkCallback.onAvailable()` su thread sbagliato (stale read) | Tutto spostato nel `post()` al main thread | `DoorPhoneService` |
| 16 | NPE se `onAvailable()` scatta prima di `Connect()` | Guard `getTargetServer() == null` | `DoorPhoneService` |
| 17 | `server_time` come stringa causa errore di 2 ore | Parsato come epoch int; fix lato server (vedi sezione 13) | `RaspberryConfigFetcher` |
| 18 | Pallino stato resta rosso per secondi anche dopo riconnessione | `UpdateClientStatus("Connected")` spostato in `onConnected()` (TCP up) invece di aspettare `onUserConnected()` (sync utenti). Verde immediato su WiFi-return e Mumble-server-return | `DoorPhoneService` |
| 19 | Robustezza riconnessione kiosk 24/7 | Watchdog ping TCP (timeout 30s) per connessioni half-open, `setSoTimeout(40s)` sul read loop + propagazione errori di scrittura, backoff esponenziale+jitter (10/20/40s, cap 60s), `START_STICKY`, WifiLock `FULL_HIGH_PERF` | `HumlaConnection`, `HumlaTCP`, `HumlaService`, `DoorPhoneService` — commit `c046d14` |
| 20 | Doppia connessione Mumble → "Kicked from another device" (fix client-side definitivo) | `connect()` non aveva guard e non chiudeva la `mConnection` precedente → socket TCP orfane con lo stesso certificato → il server scalciava i duplicati → disconnessione "fantasma" ogni ~39s (soTimeout). Fix: guard in `connect()` (ignora se CONNECTING/CONNECTED), chiusura connessione precedente, watchdog reso affidabile (`mLastReceivedTCPPing` volatile, try/catch nel ping runnable) | `HumlaService`, `HumlaConnection` — commit `5fab811` |
| 21 | Schermata bianca al ritorno su `DoorPhoneActivity` (catch-up) | Il router con layout vuoto restava visibile quando il servizio era già CONNECTED al ri-foreground; il ramo CONNECTED ora riporta sempre avanti `VideoVLCActivity` (`REORDER_TO_FRONT|SINGLE_TOP`) | `DoorPhoneActivity` — commit `4d7fa74` |
| 22 | Security hardening + best-practice cleanup (da review agenti) | Vari interventi di sicurezza e robustezza del ciclo di vita | commit `dce2bb7` |
| 23 | Blocco al boot: Mumble non si connette dopo reboot | Su keyguard a schermo spento il router non resta RESUMED → connect mai avviato. Fix: window flags `SHOW_WHEN_LOCKED \| DISMISS_KEYGUARD \| TURN_SCREEN_ON \| KEEP_SCREEN_ON` su `onCreate`. Incluso anche l'anti-blocco della modale "Connessione a …" (`dismissConnectingDialog()` + dismiss nel ramo CONNECTED). Verificato 10/10 reboot. Vedi `docs/fix-blocco-boot-2026-06-27.md` | `DoorPhoneActivity` — commit `1a5f978` |

### Bug noti non ancora risolti

| # | Problema | Impatto | Note |
|---|---------|---------|------|
| A | Warning rotazione codec Nexus 7 | Basso — video si vede correttamente | `OMX.Nvidia.h264.decode` non supporta `KEY_ROTATION` via MediaFormat; SurfaceFlinger applica comunque la rotazione |

> Il vecchio bug **B "Kicked from another device"** è stato **risolto lato client** (fix #20, commit `5fab811`) oltre al palliativo lato server (`timeout=15` in murmur.ini, vedi §13).

---

## 19. Note di sicurezza

> Le comunicazioni HTTP con DoorPi avvengono su rete locale non cifrata — non esporre la porta 8080 su Internet.

- La connessione Mumble è cifrata **TLS** con codec Opus
- La password DoorPi è configurabile via JSON remoto o nelle Settings
- La password del menu Settings (`mouse`) è in `VideoVLCActivity.java`
- Il dispositivo deve essere registrato come **Device Admin** per il blocco schermo automatico
- I comandi `su` (luminosità, ora, timezone, reboot) richiedono il root del dispositivo

---

## 20. Licenza

GPL v3 — fork di [Mumla](https://gitlab.com/quite/mumla) / [Plumble](https://github.com/Morlunk/Plumble).
