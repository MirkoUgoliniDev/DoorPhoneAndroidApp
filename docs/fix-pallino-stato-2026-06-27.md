# Fix pallino di stato ROSSO con Mumble connesso (overlay kiosk)

**Data:** 2026-06-27
**Branch:** `fix-catlog`
**File modificato:** `app/src/main/java/com/doorphone/ui/VideoVLCActivity.java`
**Correlato:** [fix-blocco-boot-2026-06-27.md](./fix-blocco-boot-2026-06-27.md) (stesso filone, il boot-block ha esposto questo bug)

---

## 1. Sintomo

In produzione su un tablet **P1** (`kiosk=true`, `hide_status_bar=true`) il **pallino di stato** in alto a sinistra — che dovrebbe essere **verde** quando l'app è connessa a Mumble — resta **rosso**. Il **video RTSP** e il **fetch config** funzionano regolarmente (sono canali indipendenti: RTSP :554, HTTP :8080, Mumble :64738).

Sul banco (tablet **P4**, schermo sempre acceso) il bug **non** si riproduce: pallino sempre verde.

---

## 2. Come funziona il colore del pallino

Il colore dipende solo dal flag `mConnectionOk` (`VideoVLCActivity`):

- **verde** se `mConnectionOk == true`, **rosso** se `false` (parte da `false`);
- diventa `true` per **due** vie soltanto:
  1. **broadcast** `LocalBroadcast("my-message", status="Connected")` emesso da `DoorPhoneService.UpdateClientStatus()` (da `HumlaObserver.onConnected()` ecc.), ricevuto da `messageReceiver` — registrato in `onStart`, **deregistrato in `onStop`**;
  2. **catch-up** `mConnectionOk = mService.isConnected()` in `VideoVLCActivity.onServiceConnected()` — eseguito solo al **bind** (`onCreate`) e al **rebind** in `onResume` (quando `mNeedsRebind` è stato settato in `onStop`).

---

## 3. Causa radice

Il `LocalBroadcast` **non è sticky**: chi si registra dopo l'emissione non lo vede mai. Con il fix boot-block la connessione Mumble completa **molto presto**, mentre in primo piano c'è ancora il *router* `DoorPhoneActivity` e **`VideoVLCActivity` non esiste ancora** → il broadcast `"Connected"` è **perso**. Lo stesso accade se l'activity è `stopped` (schermo spento kiosk, receiver deregistrato).

Resta quindi **solo** il catch-up `onServiceConnected.isConnected()`. Ma questo scatta solo su bind/rebind: **una `onResume` SENZA `onStop` precedente** (es. `onNewIntent` da `openVideoStream` con `REORDER_TO_FRONT|SINGLE_TOP`, ritorno da dialog/menu) **non** rifà il bind e **non** rilegge `isConnected()`. E una connessione **già stabile non emette mai un nuovo `onConnected()`**.

> **Conseguenza:** se nell'unico istante di lettura `isConnected()` era `false` (su P1: connessione più lenta / ancora in `CONNECTING` / `onResume` senza rebind), `mConnectionOk` resta `false` e **nessun evento successivo lo riallinea** → **pallino rosso permanente pur essendo CONNESSO**.

Perché P1 sì e P4 no: P1 è kiosk full (overlay visibile) con continui cicli schermo-spento 24/7; P4 da banco ha schermo sempre acceso, foreground stabile, receiver sempre registrato e connessione sempre su al bind.

### Prova empirica (12 reboot, device P4)

Con log diagnostici (`overlay stato — connectionOk=…`, `onServiceConnected — isConnected=…`, `status broadcast ricevuto`):

| Metrica | Risultato su 12 cicli |
|---|---|
| Pallino finale | 12/12 **VERDE** |
| `onServiceConnected.isConnected` | 12/12 **true** |
| **`status broadcast` ricevuti da VideoVLCActivity** | **0 / 12** |

Il dato chiave è **`statusBroadcastRicevuti = 0`**: il broadcast non arriva **mai**. Il pallino è verde **solo** grazie all'unica lettura del catch-up. **Zero ridondanza** → una sola lettura `false` = rosso che non si ripara.

---

## 4. Il fix

Rendere il pallino **query-driven**: riallinearlo dalla verità del servizio a **ogni** `onResume`, eliminando la dipendenza dal broadcast perso. In `VideoVLCActivity.onResume()`, dopo il blocco `mNeedsRebind`:

```java
// Riallineo del pallino di stato dalla verita' del servizio a ogni foreground.
// Il broadcast "status=Connected" NON e' sticky: se emesso mentre l'activity non
// esisteva ancora (boot) o era stopped (schermo spento), e' perso per sempre. Una
// connessione gia' stabile non emette piu' onConnected(), quindi un mConnectionOk
// stale resterebbe indefinitamente. Copriamo le onResume SENZA rebind; sul rebind
// isBound e' ancora false e ci pensa onServiceConnected.
if (isBound && mService != null) {
    mConnectionOk = mService.isConnected();
    updateConnectionStatusOverlay();
}
```

Più 3 log diagnostici (mantenuti) su: colore finale dell'overlay, `isConnected` letto in `onServiceConnected`, ricezione del broadcast `status`.

### Verifica

Re-test col fix (10 reboot, device P4): **10/10 VERDE, 0 regressioni** (`BUG(conn+rosso)=0`). Il fix legge e ridipinge soltanto: nessun `connect()`/`bindService` aggiuntivo (guard `isBound`), nessun impatto su schermata bianca o doppia connessione.

> **Nota operativa:** il fix garantisce l'invariante *"se connesso → verde"*. Se dopo il deploy il **P1 resta rosso**, allora Mumble è **davvero** scollegato su quel tablet → indagare lato connessione: porta 64738 raggiungibile dalla rete del P1? un **altro client Mumble già loggato con lo stesso username** (che per l'app **è il piano**, non `mumbleserver.username`)?

---

## 5. Come rieseguire il test

```bash
# da repo root, device collegato e APK installato (kiosk + hide_status_bar attivi)
bash docs/reboot_dot_test.sh 12
# riepilogo in /tmp/dot_summary.txt
```
