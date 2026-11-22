#!/usr/bin/env bash
set -euo pipefail
[[ ${GITHUB_ACTIONS:-} == true && ${MOBSEAR_INTEGRATED:-} == 1 && -n ${DISPLAY:-} ]]
export MOBSEAR_SMOKE_DIR
export MOBSEAR_NOTICE_PROOF=1
MOBSEAR_SMOKE_DIR=$(mktemp -d "${RUNNER_TEMP}/hearing-integrated.XXXXXX")
export LIBGL_ALWAYS_SOFTWARE=true LP_NUM_THREADS=1 ALSOFT_DRIVERS=null
mkdir -p "$MOBSEAR_SMOKE_DIR"/{server,smokeIntegrated/saves}
printf 'eula=true\n' > "$MOBSEAR_SMOKE_DIR/server/eula.txt"
cat > "$MOBSEAR_SMOKE_DIR/server/server.properties" <<'PROPS'
server-ip=127.0.0.1
server-port=0
online-mode=false
level-type=minecraft:flat
level-seed=123
generate-structures=false
level-name=HearFixture
gamemode=survival
difficulty=normal
view-distance=3
simulation-distance=3
PROPS
cat > "$MOBSEAR_SMOKE_DIR/smokeIntegrated/options.txt" <<'OPTIONS'
version:3955
onboardAccessibility:false
skipMultiplayerWarning:true
pauseOnLostFocus:false
tutorialStep:none
renderDistance:5
simulationDistance:5
enableVsync:false
maxFps:30
graphicsMode:0
soundCategory_master:0.0
guiScale:2
OPTIONS
bash gradlew --no-daemon --max-workers=2 -PvoiceMultiplayer classes prepareSmokeServerRun \
  prepareSmokeIntegratedRun createSmokeServerLaunchScript createSmokeIntegratedLaunchScript
bootstrap=$(find build -name runSmokeServer.sh -print -quit)
client=$(find build -name runSmokeIntegrated.sh -print -quit)
[[ -f "$bootstrap" && -f "$client" ]]
# Generate a save wholly on the runner. Shut this process down before opening that save as single-player.
python3 - "$bootstrap" <<'PY'
import subprocess, sys
with subprocess.Popen(["bash", sys.argv[1]], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                      stderr=subprocess.STDOUT, text=True, errors="replace") as server:
    for line in server.stdout:
        if "Done (" in line:
            server.stdin.write("stop\n")
            server.stdin.flush()
            break
    out, _ = server.communicate(timeout=90)
    if server.returncode:
        raise RuntimeError("Unable to seed disposable world: " + out[-1200:])
PY
test -f "$MOBSEAR_SMOKE_DIR/server/HearFixture/level.dat"
mv "$MOBSEAR_SMOKE_DIR/server/HearFixture" "$MOBSEAR_SMOKE_DIR/smokeIntegrated/saves/"
setsid bash "$client" > "$MOBSEAR_SMOKE_DIR/integrated.console" 2>&1 &
client_pid=$!
cleanup() {
  local status=$?
  trap - EXIT
  if ((status)); then
    if [[ -d "$MOBSEAR_SMOKE_DIR/notices" ]]; then
      mkdir -p build/notice-previews
      cp -R "$MOBSEAR_SMOKE_DIR/notices/." build/notice-previews/
    fi
    grep -E 'MOBSEAR_|Caused by:|Exception:|\[.*ERROR\]' "$MOBSEAR_SMOKE_DIR/integrated.console" | cut -c1-500 | tail -n 65 || true
    tail -n 30 "$MOBSEAR_SMOKE_DIR/integrated.console" | cut -c1-500 || true
  fi
  kill -TERM -- "-$client_pid" 2>/dev/null || true
  wait "$client_pid" 2>/dev/null || true
  exit "$status"
}
trap cleanup EXIT
deadline=$((SECONDS + 300))
while ((SECONDS < deadline)); do
  if grep -Fq 'MOBSEAR_INTEGRATED_PASS' "$MOBSEAR_SMOKE_DIR/integrated.console"; then
    grep -F 'MOBSEAR_INTEGRATED_' "$MOBSEAR_SMOKE_DIR/integrated.console"
    grep -F 'MOBSEAR_NOTICE_FRAME' "$MOBSEAR_SMOKE_DIR/integrated.console"
    mkdir -p build/notice-previews
    for scale in 1 2 3; do
      for hud in hearts armor; do
        cp "$MOBSEAR_SMOKE_DIR/notices/notice-scale-$scale-$hud.png" build/notice-previews/
      done
    done
    exit 0
  fi
  grep -Fq 'MOBSEAR_SMOKE_FAIL' "$MOBSEAR_SMOKE_DIR/integrated.console" && exit 1
  kill -0 "$client_pid" 2>/dev/null || exit 1
  sleep 2
done
exit 1
