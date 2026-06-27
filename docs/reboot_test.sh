#!/usr/bin/env bash
# ============================================================================
# reboot_test.sh — Test automatico del "blocco al boot" (kiosk DoorPhone)
# ----------------------------------------------------------------------------
# Riavvia il device via adb N volte, SENZA mai toccare lo schermo, e verifica
# che l'app si connetta a Mumble da sola entro 90s dopo ogni boot.
#
# Per ogni ciclo registra: esito, tempo di connessione, n. di "HumlaTCP:
# Connecting" (deve essere 1 = nessun doppione), eventuali kick ("another
# device"), se lo schermo si e' spento, se e' arrivato il video, top activity.
#
# Uso:   bash reboot_test.sh [N]      (default N=3)
# Output a video e in /tmp/reboot_summary.txt
#
# Prerequisiti: adb nel PATH, un solo device collegato, APK installato come
# launcher (com.doorphone). Device di riferimento: Nexus 7 (grouper) verso
# server Mumble 192.168.1.54.
# ============================================================================
CYCLES=${1:-3}
SUM=/tmp/reboot_summary.txt
: > "$SUM"
pass=0; block=0; warn=0
echo "=== TEST BLOCCO BOOT — $CYCLES cicli, nessun tocco schermo ===" | tee -a "$SUM"

for c in $(seq 1 "$CYCLES"); do
  echo "" | tee -a "$SUM"
  echo "---- CICLO $c/$CYCLES : reboot ----" | tee -a "$SUM"
  adb reboot
  adb wait-for-device
  # attendi boot_completed (max ~120s)
  n=0
  until [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] || [ $n -ge 60 ]; do sleep 2; n=$((n+1)); done
  bootsec=$((n*2))
  # osserva fino a 90s SENZA toccare lo schermo
  conn=""; m=0
  until [ -n "$conn" ] || [ $m -ge 45 ]; do
    if adb logcat -d -v time 2>/dev/null | grep -qE "updateConnectionState.*state=CONNECTED|UpdateClientStatus:Connected"; then conn="CONNECTED"; fi
    [ -z "$conn" ] && sleep 2 && m=$((m+1))
  done
  obssec=$((m*2))
  L=$(adb logcat -d -v time 2>/dev/null)
  connecting=$(echo "$L" | grep -cE "HumlaTCP: Connecting")
  kicked=$(echo "$L" | grep -ciE "kicked|another device|UserRemove")
  screenoff=$(echo "$L" | grep -cE "onScreenTurnedOff")
  video=$(echo "$L" | grep -cE "VideoVLCActivity.*ON RESUME|startRtspStream")
  top=$(adb shell dumpsys activity activities 2>/dev/null | grep -m1 -iE "mFocusedActivity" | sed -E 's/.*com\.doorphone/com.doorphone/; s/ .*//' | tr -d '\r')
  if [ -z "$conn" ]; then
    block=$((block+1))
    echo "CICLO $c: *** BLOCCO *** non connesso entro ${obssec}s (boot ${bootsec}s) | Connecting=$connecting kick=$kicked screenOff=$screenoff video=$video top=$top" | tee -a "$SUM"
  elif [ "$connecting" -gt 1 ] || [ "$kicked" -gt 0 ]; then
    warn=$((warn+1))
    echo "CICLO $c: ! WARN connesso in ${obssec}s ma Connecting=$connecting kick=$kicked (boot ${bootsec}s) screenOff=$screenoff video=$video top=$top" | tee -a "$SUM"
  else
    pass=$((pass+1))
    echo "CICLO $c: OK CONNESSO in ${obssec}s (boot ${bootsec}s) | Connecting=$connecting kick=$kicked screenOff=$screenoff video=$video top=$top" | tee -a "$SUM"
  fi
done
echo "" | tee -a "$SUM"
echo "=== RIEPILOGO: PASS=$pass  BLOCCO=$block  WARN=$warn  su $CYCLES cicli ===" | tee -a "$SUM"
