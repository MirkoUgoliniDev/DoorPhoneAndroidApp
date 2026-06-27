# Fix backoff riconnessione — recupero lento (~42s) al boot

**Data:** 2026-06-27
**Branch:** `fix-catlog`
**File modificato:** `libraries/humla/src/main/java/se/lublin/humla/HumlaService.java`
**Correlati:** [fix-blocco-boot](./fix-blocco-boot-2026-06-27.md), [fix-pallino-stato](./fix-pallino-stato-2026-06-27.md)

---

## 1. Sintomo

Test di **20 reboot** (`docs/loop20.sh`) misurando velocità di connessione Mumble:

| Esito | Cicli | connect (avvio app → CONNECTED) |
|---|---|---|
| Veloce | 17/20 | ~3,7–5,7 s |
| Medio | 2/20 (9, 13) | ~7 s |
| **Lento** | **1/20 (15)** | **~42 s** |

In tutti i casi: connessione **sempre riuscita**, pallino **VERDE**, `piano` corretto, **`kick=0`**. Nessun fallimento permanente. Ma nel caso peggiore il kiosk resta ~40 s su `DoorPhoneActivity` senza video né audio: se qualcuno suona in quella finestra, la chiamata rischia di non essere servita.

## 2. Causa radice (analisi forense dei log)

Al boot la rete WiFi è spesso **associata ma non ancora L3-ready** (route/DHCP): il primo tentativo TCP Mumble fallisce in pochi ms/secondi (`CONNECTION_LOST` prima dell'handshake TLS). Il recupero dipende da **quale** meccanismo interviene:

- **Recupero veloce (~7 s, cicli 9/13):** il WiFi risultava **DOWN** alla `registerNetworkCallback()` → `mFirstNetworkCallback=false` → il primo `NetworkCallback.onAvailable` reale viene **onorato** e forza subito un reconnect, **bypassando il backoff**.

- **Recupero lento (~42 s, ciclo 15):** il WiFi era già **UP** alla registrazione → il primo `onAvailable` (sintetico) viene **ignorato** by-design (anti doppia-connessione). La rete resta "available" per tutto il tempo → **nessun nuovo `onAvailable`** fa da rescue → il recupero ricade **solo sul backoff esponenziale**:
  ```
  CONNECTION_LOST → attesa ~10s → CONNECTING(2) → CONNECTION_LOST → attesa ~22s → CONNECTING(3) → CONNECTED
  ```
  10 s + 22 s ≈ **32 s di pura attesa** + i tentativi falliti ≈ **~42 s**.

Il backoff (`HumlaService.setReconnecting`, base `RECONNECT_DELAY=10000` → 10/20/40 s, cap 60 s, +jitter ±15%) è **dimensionato bene per il caso "server giù in steady-state"** (anti-hammer + anti-thundering-herd di più kiosk), ma è **troppo grossolano per il transitorio di boot**, dove basterebbe ritentare dopo 1–2 s.

> Nota: una parte delle anomalie può coincidere con un **riavvio del Raspberry** (server Mumble irraggiungibile): in quel caso il recupero lento è atteso e accettabile (il server stesso impiega a ripartire). Questo fix riguarda il transitorio **lato tablet** (rete locale tardiva).

## 3. Il fix (Opzione A — rampa rapida + esponenziale)

In `HumlaService.setReconnecting()` i primi tentativi usano una **rampa rapida**, poi si passa all'esponenziale esistente:

```java
private static final long[] FAST_RECONNECT_DELAYS = {1000, 2000, 4000};
...
long base;
if (mReconnectAttempts < FAST_RECONNECT_DELAYS.length) {
    base = FAST_RECONNECT_DELAYS[mReconnectAttempts];          // 1s, 2s, 4s
} else {
    int exp = mReconnectAttempts - FAST_RECONNECT_DELAYS.length;
    base = mAutoReconnectDelay * (1L << Math.min(exp, 3));     // 10/20/40
    if (base > MAX_RECONNECT_DELAY) base = MAX_RECONNECT_DELAY; // cap 60s
}
// jitter ±15% e postDelayed invariati
```

Sequenza risultante dei ritardi: **1s → 2s → 4s → 10s → 20s → 40s → 60s (cap)**.

`mReconnectAttempts` è azzerato a connessione riuscita (`onConnectionSynchronized`), quindi la rampa è **per-episodio**.

**Effetto:** il worst-case del ciclo 15 passa da 10+22 s di attesa a **1+2 s** → connect totale da **~42 s a ~12–15 s**.

## 4. Perché è a basso rischio (no regressioni)

- **Doppia connessione "kicked from another device" ([fix 5fab811]):** NON reintrodotta. I retry passano sempre da `connect()`, che mantiene il guard "return se CONNECTING/CONNECTED". Nessun percorso concorrente nuovo.
- **Flapping:** i retry scattano solo dopo un `CONNECTION_LOST` reale; il contatore si azzera al successo → rampa per-episodio. Jitter conservato.
- **Server giù in steady-state:** dopo i primi 3 tentativi rapidi (≈7 s totali) il comportamento è **identico a prima** (10/20/40/60 s) → niente martellamento prolungato né thundering-herd.

## 5. Verifica

Test `docs/loop20.sh` (20 reboot) col fix installato: **`OK=20  ANOMALIE=0`**.
- Tutte le connessioni riuscite in **~3,7–6,1 s**, `Connecting=1`, `kick=0`, `disc=0`, pallino **VERDE**, `piano=P4`.
- **Nessuna regressione** rispetto al comportamento nominale.

Nota di onestà sulla copertura: in questa corsa **nessun ciclo è entrato nel path di reconnect** (la rete locale era L3-ready al primo tentativo ogni volta, e il Raspberry non era in reboot), quindi la rampa non è stata esercitata *live*. La correttezza poggia su due fatti:
1. la modifica è un **calcolo di ritardo deterministico** in un solo punto (`setReconnecting`): per `mReconnectAttempts` 0/1/2 il `base` vale ora 1000/2000/4000 ms per costruzione;
2. nel **baseline pre-fix** lo stesso path (ciclo 15) aveva schedulato attese di **~10,4 s** poi **~21,9 s** tra i tentativi (log `loop20` baseline) — esattamente i valori che la rampa sostituisce con 1 s e 2 s.

Worst-case early-boot atteso col fix: da **~42 s a ~12–15 s** (1+2+4 s di rampa + tentativi, invece di 10+22 s).

### Verifica live (server Mumble fermato a mano)

Forzato un `CONNECTION_LOST` con la rete del tablet su, fermando il server (`systemctl stop mumble-server` per ~80 s, poi `start`). Ritardi reali catturati dal log (`Reconnect polling in <delay>ms (attempt N)`):

| Attempt | Ritardo loggato | Atteso |
|--:|--:|--:|
| 1 | 1145 ms | ~1 s (rampa) |
| 2 | 1918 ms | ~2 s (rampa) |
| 3 | 3984 ms | ~4 s (rampa) |
| 4 | 10723 ms | ~10 s (esponenziale) |
| 5 | 22443 ms | ~20 s (esponenziale) |
| 6 | 36892 ms | ~40 s (esponenziale) |

→ al riavvio del servizio: **riconnesso, pallino VERDE**. Sequenza esatta `1→2→4→10→20→40 s` (+jitter ±15%) confermata **live**.

Secondo test (reboot completo del Raspberry): primo retry a **1080 ms (attempt 1)** invece di 10 s; il singolo connect è rimasto pendente finché l'host non è tornato, poi aggancio immediato. In entrambi i test il **pallino** ha seguito lo stato reale (ROSSO durante l'outage, VERDE al recupero).

## 6. Come rieseguire il test

```bash
bash docs/loop20.sh 20   # riepilogo + log per ciclo in scratchpad/loop20/
```
