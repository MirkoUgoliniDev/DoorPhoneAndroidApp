# Fix blocco al boot — Mumla/Mumble non si connette dopo il reboot

**Data:** 2026-06-27
**Branch:** `fix-catlog`
**Commit fix:** `1a5f978` — *fix: elimina blocco al boot (Mumble non si connette dopo reboot)*
**File modificato:** `app/src/main/java/com/doorphone/app/DoorPhoneActivity.java`
**Device di test:** Nexus 7 (grouper, Android 7.1 / API 25), USB → server Mumble `192.168.1.54`

---

## 1. Sintomo

Il kiosk citofono riavvia automaticamente ~2 volte al giorno. Dopo ogni reboot
**la connessione Mumble non partiva**: l'app restava su `DoorPhoneActivity`
scollegata, schermo spento, finché un operatore non svegliava/sbloccava
manualmente lo schermo. Inaccettabile per un dispositivo non presidiato.

A latere era stato segnalato un secondo fastidio: una **modale "Connessione a
192.168.1.54"** che a volte compariva tappando lo schermo.

---

## 2. Causa radice (blocco al boot)

La connessione Mumble è avviata **solo** da
`DoorPhoneActivity.onServiceConnected() → Connect()`, che richiede l'Activity
**RESUMED** (schermo acceso + keyguard tolto).

Al boot il device si presenta su **keyguard con schermo spento**. Sequenza
osservata nei log:

```
DoorPhoneActivity ON CREATE
DoorPhoneActivity ON RESUME
DoorPhoneActivity ON PAUSE        ← dopo ~55 ms (Activity pause timeout)
APP ENTER BACKGROUND
... onScreenTurnedOff()           ← lo schermo si spegne
```

`DoorPhoneActivity` (che è il **launcher**, quindi parte per prima) viene messa
subito in PAUSE dietro il keyguard e non resta mai in foreground → il
`DoorPhoneService` viene creato (`onCreate`) ma **non riceve mai l'ordine di
connettersi**.

Il `NetworkCallback.onAvailable` del Service *potrebbe* chiamare `connect()` da
solo, ma **solo se `getTargetServer() != null`**, e il target server lo imposta
unicamente l'Activity al primo `Connect()`. Al boot quel primo Connect non
avviene mai → `getTargetServer()` resta `null` → **chicken-and-egg**, nessuna
connessione.

**Conferma sperimentale:** svegliando lo schermo e togliendo il keyguard
(`adb shell input keyevent KEYCODE_WAKEUP` + `keyevent 82`), `DoorPhoneActivity`
faceva immediatamente ON RESUME → `onServiceConnected → Connect()` → **connesso
in ~1,3 s**. Quindi il problema è puramente di foreground/keyguard al boot, non
di rete o di server.

---

## 3. Il fix

### 3.1 Fix primario — window flags su `DoorPhoneActivity.onCreate()`

```java
getWindow().addFlags(
        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
        | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
```

Sono **gli stessi flag già usati da `VideoVLCActivity`** (righe ~408 e ~952),
quindi pattern già validato sugli stessi tablet. Effetto: al boot il router si
mostra **sopra il lockscreen**, **accende lo schermo** e lo tiene acceso durante
la finestra di connect → l'Activity resta RESUMED → `onServiceConnected →
Connect()` parte → connessione stabilita → `openVideoStream()` porta avanti
`VideoVLCActivity` col video.

### 3.2 Fix secondario — anti-blocco della modale "Connessione a …"

Per evitare che la `ProgressDialog mConnectingDialog` resti appesa a connessione
attiva:

- nuovo helper `dismissConnectingDialog()` → `dismiss()` **e** azzera il
  riferimento (evita dismiss "fantasma" su istanze stantie);
- usato all'ingresso di `updateConnectionState()` e in `onPause()`;
- nel ramo `CONNECTED`: `dismissConnectingDialog()` esplicito (invariante
  "nessuna modale a connessione attiva").

> Nota dalla review: una prima versione aveva una guardia `if (mService.isConnected())
> break;` nel ramo `CONNECTING`, di fatto codice irraggiungibile (`mConnectionState`
> nella libreria Humla è `volatile` e CONNECTED è assegnato solo sul main thread, quindi
> nello stesso giro non può essere CONNECTING e CONNECTED). **Rimossa nella pulizia
> cosmetica** e sostituita con un null-guard reale su `server` (`if (server == null)
> break;`), che chiude anche il potenziale NPE su `server.getHost()`. La protezione
> dell'invariante "nessuna modale a connessione attiva" resta il dismiss all'ingresso di
> `updateConnectionState()` + nel ramo CONNECTED.

---

## 4. Verifica sul campo

Test automatico [`reboot_test.sh`](./reboot_test.sh): riavvia il device N volte,
**senza mai toccare lo schermo**, e verifica la connessione entro 90 s,
registrando: tempo di connessione, n. di `HumlaTCP: Connecting` (deve essere 1),
kick (`another device`), `screenOff`, arrivo del video, top activity.

### Baseline — APK SENZA fix

| Ciclo | Esito | Connecting | Kick | ScreenOff |
|------:|-------|:----------:|:----:|:---------:|
| 1 | 🔴 **BLOCCO** (>90 s) | 0 | 0 | 1 |
| 2 | 🔴 **BLOCCO** (>90 s) | 0 | 0 | 1 |
| 3 | 🔴 **BLOCCO** (>90 s) | 0 | 0 | 1 |

**3/3 blocco** — deterministico.

### Con il fix installato (commit `1a5f978`)

Primo test (3 cicli): **3/3 CONNESSO** in 6–8 s.

Test esteso (10 cicli): **`PASS=10  BLOCCO=0  WARN=0`**.

| Ciclo | Esito | Connect (s) | Connecting | Kick | ScreenOff | Top |
|------:|-------|:-----------:|:----------:|:----:|:---------:|-----|
| 1 | ✅ OK | 6 | 1 | 0 | 0 | VideoVLCActivity |
| 2 | ✅ OK | 6 | 1 | 0 | 0 | VideoVLCActivity |
| 3 | ✅ OK | 8 | 1 | 0 | 0 | VideoVLCActivity |
| 4 | ✅ OK | 8 | 1 | 0 | 0 | VideoVLCActivity |
| 5 | ✅ OK | 6 | 1 | 0 | 0 | VideoVLCActivity |
| 6 | ✅ OK | 8 | 1 | 0 | 0 | VideoVLCActivity |
| 7 | ✅ OK | 8 | 1 | 0 | 0 | VideoVLCActivity |
| 8 | ✅ OK | 6 | 1 | 0 | 0 | VideoVLCActivity |
| 9 | ✅ OK | 8 | 1 | 0 | 0 | VideoVLCActivity |
| 10 | ✅ OK | 6 | 1 | 0 | 0 | VideoVLCActivity |

> **10/10 CONNESSO in 6–8 s, `Connecting=1` (nessun doppione), `kick=0`, schermo
> acceso, video a schermo — senza alcuna interazione.** Nessun blocco, nessun
> warning.
>
> (Nei cicli 1 e 8 il contatore `video` risulta 0 solo perché il check di
> connessione è scattato un istante prima che `startRtspStream` finisse nel log;
> `top=VideoVLCActivity` conferma comunque che il video era in primo piano.)

---

## 5. Code review (agenti)

Due review indipendenti del commit `1a5f978`. **Entrambe approvano**, nessun
bloccante.

**Window flags:**
- nessun conflitto `DoorPhoneActivity ↔ VideoVLCActivity`; il router resta sotto,
  mai duplicato;
- nessuna reintroduzione di doppia-connessione (`5fab811`) né schermata bianca
  (`4d7fa74`); anzi, tenendo il router RESUMED si riduce il bounce bind/unbind;
- API: compileSdk 35 / targetSdk 25 / minSdk 24 — i flag funzionano (su API 25
  non sono nemmeno deprecati); manifest invariato, nessun permesso aggiuntivo.

**Precisazioni (non bloccanti):**
- `FLAG_KEEP_SCREEN_ON` tiene acceso lo schermo **solo durante la finestra di
  boot/connect** (quando il router è top), non 24/7 — è quanto serve; il
  lock-at-idle del video continua a funzionare.
- `FLAG_DISMISS_KEYGUARD` sblocca solo un keyguard **non sicuro** (senza
  PIN/pattern). Sul kiosk il keyguard è non sicuro → ok. **Assunzione da
  rispettare:** se un tablet venisse configurato con PIN, il boot-connect
  tornerebbe a bloccarsi (servirebbe `setShowWhenLocked()` +
  `KeyguardManager.requestDismissKeyguard()`).

**Dialog / connection-state:** invariante "nessuna modale a CONNECTED" regge;
`mConnectionState` è `volatile`, nessuna race reale; nessun NPE (null-guard prima
dello switch). Unica nota: la guardia `isConnected()` nel ramo CONNECTING è dead
code innocuo (vedi §3.2).

---

## 6. Stato e prossimi passi

- [x] Causa radice individuata e provata (baseline 3/3 blocco).
- [x] Fix applicato e committato (`1a5f978`) su `fix-catlog`.
- [x] Verifica 3/3 + review agenti OK.
- [x] Verifica estesa **10/10** (`PASS=10 BLOCCO=0 WARN=0`).
- [x] Pulizia cosmetica: guardia morta `isConnected()` → null-guard su `server`;
      commento `KEEP_SCREEN_ON` corretto; doxygen `onCreate` aggiornato. Ricompilato
      e **ri-verificato 5/5** (`PASS=5 BLOCCO=0 WARN=0`).
- [ ] Commit della pulizia cosmetica + di questi docs.
- [ ] Merge `fix-catlog` → `master` (`--no-ff`) + push, quando validato.

### Come rieseguire il test

```bash
# da C:\Lavori\Android\DoorPhoneAndroidApp, con device collegato e APK installato
bash docs/reboot_test.sh 10
# riepilogo anche in /tmp/reboot_summary.txt
```
