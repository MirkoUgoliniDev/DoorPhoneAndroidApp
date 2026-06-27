#!/usr/bin/env bash
# Loop reboot: stabilita' + velocita' connessione + stato pallino (con piano).
# Salva il logcat filtrato di ogni ciclo per analisi forense.
CYCLES=${1:-20}
OUT="/c/Users/mirko/AppData/Local/Temp/claude/C--Lavori-Android-DoorPhoneAndroidApp/0d988191-130a-4834-b038-4926457da95f/scratchpad/loop20"
rm -rf "$OUT"; mkdir -p "$OUT"
SUM="$OUT/summary.txt"
: > "$SUM"

ts2ms(){ echo "$1" | awk -F'[:.]' '{print (($1*3600)+($2*60)+$3)*1000+$4}'; }

pass=0; anom=0
echo "=== LOOP $CYCLES — stabilita'+velocita' connessione + pallino ===" | tee -a "$SUM"
for c in $(seq 1 "$CYCLES"); do
  echo "" | tee -a "$SUM"
  echo "---- CICLO $c/$CYCLES ----" | tee -a "$SUM"
  adb reboot; adb wait-for-device
  n=0; until [ "$(adb shell getprop sys.boot_completed 2>/dev/null|tr -d '\r')" = "1" ]||[ $n -ge 60 ]; do sleep 2; n=$((n+1)); done
  conn=""; ov=""; m=0
  until { [ -n "$conn" ]&&[ -n "$ov" ]; }||[ $m -ge 55 ]; do
    L=$(adb logcat -d -v time 2>/dev/null)
    echo "$L"|grep -qE "UpdateClientStatus:Connected" && conn="Y"
    echo "$L"|grep -qE "overlay stato — .*connectionOk=" && ov="Y"
    { [ -z "$conn" ]||[ -z "$ov" ]; } && sleep 2 && m=$((m+1))
  done
  sleep 3
  L=$(adb logcat -d -v time 2>/dev/null)
  echo "$L"|grep -iE "DoorPhoneService|DoorPhoneActivity|VideoVLCActivity|HumlaTCP|UpdateClientStatus|overlay stato|onServiceConnected|status broadcast|kick|another device|Reject|onConnected|onDisconnected|CONNECTION_LOST|UserRemove|Start proc.*doorphone|Reconnect polling|Connection lost" \
    | grep -ivE "powerHAL|VideoDecode|RtspProcessor|OpenGLRenderer|art |linker|Sounds|AsyncHttp|RaspberryConfig" > "$OUT/ciclo_$c.log"
  t_proc=$(echo "$L"|grep -E "Start proc [0-9]+:com.doorphone"|head -1|awk '{print $2}')
  t_conn=$(echo "$L"|grep -E "UpdateClientStatus:Connected"|head -1|awk '{print $2}')
  t_cing=$(echo "$L"|grep -E "HumlaTCP: Connecting"|head -1|awk '{print $2}')
  lat="?"; [ -n "$t_proc" ]&&[ -n "$t_conn" ]&&lat=$(( $(ts2ms $t_conn) - $(ts2ms $t_proc) ))
  tcp="?"; [ -n "$t_cing" ]&&[ -n "$t_conn" ]&&tcp=$(( $(ts2ms $t_conn) - $(ts2ms $t_cing) ))
  connecting=$(echo "$L"|grep -cE "HumlaTCP: Connecting")
  kicked=$(echo "$L"|grep -ciE "kicked|another device|UserRemove|Reject")
  disc=$(echo "$L"|grep -cE "onDisconnected|CONNECTION_LOST")
  recv=$(echo "$L"|grep -cE "status broadcast ricevuto")
  oscon=$(echo "$L"|grep -E "onServiceConnected — isConnected="|tail -1|sed -E 's/.*isConnected=([a-z]+).*/\1/')
  piano=$(echo "$L"|grep -E "overlay stato — piano="|tail -1|sed -E 's/.*piano=([^ ]+) .*/\1/')
  color=$(echo "$L"|grep -E "overlay stato —"|tail -1|grep -oE "VERDE|ROSSO")
  v="OK"
  [ -z "$conn" ] && v="NON_CONNESSO"
  [ "$color" = "ROSSO" ] && v="PALLINO_ROSSO"
  [ "$connecting" -gt 1 ] && v="$v +Connecting=$connecting"
  [ "$kicked" -gt 0 ] && v="$v +kick=$kicked"
  [ "$disc" -gt 0 ] && v="$v +disc=$disc"
  if [ "$v" = "OK" ]; then pass=$((pass+1)); else anom=$((anom+1)); fi
  echo "CICLO $c: $v | piano=$piano pallino=$color | connect=${lat}ms TCPhandshake=${tcp}ms | Connecting=$connecting kick=$kicked disc=$disc statusBcast=$recv onSvcConn.isConn=$oscon boot~$((n*2))s" | tee -a "$SUM"
done
echo "" | tee -a "$SUM"
echo "=== RIEPILOGO: OK=$pass ANOMALIE=$anom su $CYCLES ===" | tee -a "$SUM"
echo "log per ciclo in: $OUT" | tee -a "$SUM"
