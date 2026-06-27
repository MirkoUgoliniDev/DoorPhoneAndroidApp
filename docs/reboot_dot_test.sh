#!/usr/bin/env bash
# Test pallino-stato al boot: reboot ripetuti, registra COLORE del pallino (VERDE/ROSSO)
# vs stato connessione reale, per scovare il caso "connesso ma pallino rosso".
# Salva la catena filtrata di ogni ciclo in $DIR per analisi successiva.
CYCLES=${1:-12}
DIR=/tmp/dot_logs
rm -rf "$DIR"; mkdir -p "$DIR"
SUM=/tmp/dot_summary.txt
: > "$SUM"
green=0; red=0; bug=0; noconn=0
echo "=== TEST PALLINO AL BOOT — $CYCLES cicli, nessun tocco ===" | tee -a "$SUM"

for c in $(seq 1 "$CYCLES"); do
  echo "" | tee -a "$SUM"
  echo "---- CICLO $c/$CYCLES : reboot ----" | tee -a "$SUM"
  adb reboot
  adb wait-for-device
  n=0
  until [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] || [ $n -ge 60 ]; do sleep 2; n=$((n+1)); done
  # osserva fino a 100s: aspetta che compaia almeno un overlay state DOPO la connessione
  conn=""; ov=""; m=0
  until { [ -n "$conn" ] && [ -n "$ov" ]; } || [ $m -ge 50 ]; do
    L=$(adb logcat -d -v time 2>/dev/null)
    echo "$L" | grep -qE "UpdateClientStatus:Connected|updateConnectionState.*state=CONNECTED" && conn="Y"
    echo "$L" | grep -qE "overlay stato — .*connectionOk=" && ov="Y"
    { [ -z "$conn" ] || [ -z "$ov" ]; } && sleep 2 && m=$((m+1))
  done
  # dai 3s extra per l'ultimo update overlay/catch-up
  sleep 3
  L=$(adb logcat -d -v time 2>/dev/null)
  # salva la catena del ciclo
  echo "$L" | grep -iE "DoorPhoneActivity|DoorPhoneService|VideoVLCActivity|HumlaTCP: Connecting|UpdateClientStatus|overlay stato|onServiceConnected|messageReceiver|status broadcast|kick|Reject|onConnected|Disconnected|UserRemove" \
    | grep -ivE "powerHAL|VideoDecode|RtspProcessor|OpenGLRenderer|art |linker|Sounds|AsyncHttp|RaspberryConfig" > "$DIR/ciclo_$c.log"
  # estrai segnali
  connecting=$(echo "$L" | grep -cE "HumlaTCP: Connecting")
  kicked=$(echo "$L" | grep -ciE "kicked|another device|UserRemove|Reject")
  recv=$(echo "$L" | grep -cE "status broadcast ricevuto")
  oscon=$(echo "$L" | grep -E "onServiceConnected — isConnected=" | tail -1 | sed -E 's/.*isConnected=([a-z]+).*/\1/')
  last_ov=$(echo "$L" | grep -E "overlay stato — .*connectionOk=" | tail -1 | sed -E 's/.*connectionOk=([a-z]+).*\((VERDE|ROSSO)\)/\1=\2/')
  # classifica
  if [ -z "$conn" ]; then
    noconn=$((noconn+1)); verdict="*** NON CONNESSO ***"
  elif echo "$last_ov" | grep -q "ROSSO"; then
    bug=$((bug+1)); verdict="!!! BUG: CONNESSO ma pallino ROSSO !!!"
  elif echo "$last_ov" | grep -q "VERDE"; then
    green=$((green+1)); verdict="OK pallino VERDE"
  else
    red=$((red+1)); verdict="?? overlay non rilevato (last_ov='$last_ov')"
  fi
  echo "CICLO $c: $verdict | overlayFinale=$last_ov onServiceConnected.isConnected=$oscon statusBroadcastRicevuti=$recv Connecting=$connecting kick=$kicked" | tee -a "$SUM"
done
echo "" | tee -a "$SUM"
echo "=== RIEPILOGO: VERDE=$green  BUG(conn+rosso)=$bug  NONconn=$noconn  altro=$red  su $CYCLES ===" | tee -a "$SUM"
echo "Log per ciclo in: $DIR" | tee -a "$SUM"
