#!/usr/bin/env bash
set -euo pipefail
[[ ${GITHUB_ACTIONS:-} == true && -n ${DISPLAY:-} ]] || {
  echo "Run on the hosted Linux runner under Xvfb." >&2
  exit 2
}
export MOBSEAR_SMOKE_DIR
MOBSEAR_SMOKE_DIR=$(mktemp -d "${RUNNER_TEMP}/mobsear-multiplayer.XXXXXX")
export LIBGL_ALWAYS_SOFTWARE=true LP_NUM_THREADS=1 ALSOFT_DRIVERS=null
mkdir -p "$MOBSEAR_SMOKE_DIR"/{server,smokeSpeaker,smokeListener}
cat > "$MOBSEAR_SMOKE_DIR/server/server.properties" <<'CONFIG'
server-ip=127.0.0.1
server-port=25576
online-mode=false
enforce-secure-profile=false
level-type=minecraft:flat
generate-structures=false
gamemode=creative
difficulty=peaceful
view-distance=5
simulation-distance=3
spawn-protection=0
max-players=2
sync-chunk-writes=false
CONFIG
echo 'eula=true' > "$MOBSEAR_SMOKE_DIR/server/eula.txt"
mkdir -p "$MOBSEAR_SMOKE_DIR/server/config"
echo 'hordeEnabled = true' > "$MOBSEAR_SMOKE_DIR/server/config/mobsear-server.toml"
for role in smokeSpeaker smokeListener; do
  mkdir -p "$MOBSEAR_SMOKE_DIR/$role"/{tmp,natives}
  cat > "$MOBSEAR_SMOKE_DIR/$role/options.txt" <<'OPTIONS'
version:3955
onboardAccessibility:false
skipMultiplayerWarning:true
renderDistance:5
simulationDistance:5
enableVsync:false
maxFps:20
graphicsMode:0
soundCategory_master:0.0
guiScale:2
tutorialStep:none
pauseOnLostFocus:false
OPTIONS
done

if [[ ${MOBSEAR_PUBLIC_MENU_STACK:-} == 1 ]]; then
  python3 ci/multiplayer/stage_menu_stack.py "$MOBSEAR_SMOKE_DIR/smokeListener/mods"
  mkdir -p "$MOBSEAR_SMOKE_DIR/smokeListener/config"
  # Exercise the menu/render stack with the standard pipeline and the mod's default scene.
  echo 'enableShaders=false' > "$MOBSEAR_SMOKE_DIR/smokeListener/config/iris.properties"
fi

bash gradlew --no-daemon --max-workers=2 -PvoiceMultiplayer \
  classes prepareSmokeServerRun prepareSmokeSpeakerRun prepareSmokeListenerRun \
  createSmokeServerLaunchScript createSmokeSpeakerLaunchScript createSmokeListenerLaunchScript

pids=()
roles=()
collect_images() {
  local image
  mkdir -p build/state-previews
  for image in "$MOBSEAR_SMOKE_DIR/smokeListener/screenshots/"menu-*.png "$MOBSEAR_SMOKE_DIR/visuals/"*.png; do
    if [[ -f $image ]]; then cp "$image" build/state-previews/; fi
  done
}
cleanup() {
  local status=$?
  trap - EXIT
  if ((status != 0)); then
    collect_images
    for role in "${roles[@]}"; do
      echo "--- $role ---"
      grep -E 'MOBSEAR_SMOKE_FAIL|Caused by:|/FATAL\]|Exception in thread' "$MOBSEAR_SMOKE_DIR/$role.console" \
        | cut -c1-650 | tail -n 14 || true
      local directory=$role crash
      [[ $role != smokeServer ]] || directory=server
      for crash in "$MOBSEAR_SMOKE_DIR/$directory/crash-reports/"*.txt; do
        if [[ -f $crash ]]; then head -n 90 "$crash" | cut -c1-650; fi
      done
      tail -n 70 "$MOBSEAR_SMOKE_DIR/$role.console" || true
    done
  fi
  for pid in "${pids[@]}"; do kill -TERM -- "-$pid" 2>/dev/null || true; done
  sleep 2
  for pid in "${pids[@]}"; do kill -KILL -- "-$pid" 2>/dev/null || true; done
  wait || true
  exit "$status"
}
trap cleanup EXIT
launch() {
  local role=$1 script
  script=$(find build -type f -name "run${role^}.sh" -print -quit)
  [[ -n $script ]] || { echo "Missing launch script for $role" >&2; exit 1; }
  if [[ $role == smokeServer ]]; then
    setsid bash "$script" > "$MOBSEAR_SMOKE_DIR/$role.console" 2>&1 &
  else
    # Each fullscreen GLFW surface has independent focus. Neither can suspend the other's real capture path.
    setsid xvfb-run -a -s '-screen 0 1440x900x24' bash "$script" > "$MOBSEAR_SMOKE_DIR/$role.console" 2>&1 &
  fi
  pids+=("$!")
  roles+=("$role")
}
wait_for() {
  local role=$1 marker=$2 deadline=$((SECONDS + $3))
  while ((SECONDS < deadline)); do
    if grep -Fq 'MOBSEAR_SMOKE_FAIL' "$MOBSEAR_SMOKE_DIR"/*.console; then return 1; fi
    if grep -Fq "$marker" "$MOBSEAR_SMOKE_DIR/$role.console"; then return 0; fi
    for pid in "${pids[@]}"; do kill -0 "$pid" 2>/dev/null || return 1; done
    sleep 2
  done
  echo "Timed out waiting for $role to report $marker" >&2
  return 1
}
launch smokeServer
wait_for smokeServer 'Done (' 240
launch smokeSpeaker
wait_for smokeSpeaker 'MOBSEAR_CLIENT_READY' 200
launch smokeListener
if [[ ${MOBSEAR_PUBLIC_MENU_STACK:-} == 1 ]]; then
  wait_for smokeListener 'MOBSEAR_MENU_PASS' 500
  grep -F 'MOBSEAR_PUBLIC_MENU_STACK' "$MOBSEAR_SMOKE_DIR/smokeListener.console"
  grep -F 'MOBSEAR_MENU_PASS' "$MOBSEAR_SMOKE_DIR/smokeListener.console"
  collect_images
  test -s build/state-previews/menu-gui-scale3.png
  exit 0
fi
wait_for smokeServer 'MOBSEAR_MULTIPLAYER_PASS' 600
grep -Fq 'MOBSEAR_HORDE_WIRE_PASS' "$MOBSEAR_SMOKE_DIR/smokeServer.console"
grep -F 'MOBSEAR_' "$MOBSEAR_SMOKE_DIR/smokeServer.console"
grep -F 'MOBSEAR_MENU_PASS' "$MOBSEAR_SMOKE_DIR/smokeListener.console"
grep -F 'MOBSEAR_PITCH_AUDIO' "$MOBSEAR_SMOKE_DIR/smokeListener.console"
grep -F 'MOBSEAR_TRACKED_HEAD' "$MOBSEAR_SMOKE_DIR/smokeListener.console"
grep -Fq 'MOBSEAR_PITCH_WIRE_PASS' "$MOBSEAR_SMOKE_DIR/smokeServer.console"
mkdir -p build/pitch-previews
for sample in bypass squeaky deep low-end high-end center restored; do
  cp "$MOBSEAR_SMOKE_DIR/pitch-audio/received-$sample.wav" build/pitch-previews/
done
collect_images
snapshots=()
for theme in uwu{,_lotus,_blossom,_petals,_hearts} emo{,_skulls,_thorns,_shards,_eclipse} \
             vanilla{,_ferns,_grove,_jungle,_beach} amethyst{,_nebula,_rings,_void,_stars}; do
  snapshots+=("menu-$theme")
  if [[ $theme == *_* ]]; then
    for frame in {0..7}; do snapshots+=("menu-$theme-frame-$frame"); done
  fi
done
for role in hud nameplate; do
  for family in uwu emo vanilla amethyst; do
    for state in idle muted deafened speaking speaking-next; do snapshots+=("$role-$family-$state"); done
  done
  snapshots+=("$role-effects-reduced" "$role-effects-off" "$role-hidden")
done
snapshots+=(nameplate-out-of-range nameplate-team-visible nameplate-team-hidden status-speaking status-quiet gain-zero device-meter)
snapshots+=(menu-first-open menu-compact menu-voice-changer)
for call in enderman spider mob mobs; do snapshots+=("gaze-$call-before" "gaze-$call-after" "gaze-$call-feedback"); done
snapshots+=(menu-uwu_lotus-reduced menu-uwu_lotus-reduced-check)
for name in "${snapshots[@]}"; do test -s "build/state-previews/$name.png" || { echo "Missing $name"; exit 1; }; done
printf 'MOBSEAR_CAPTURE %s.png\n' "${snapshots[@]}"

# Restore the exact file saved when the tester closed Voice Changer with a preset and custom value.
# The first listener subsequently exercised Reset and the remaining sound checks in the same profile.
listener_pid=${pids[2]}
kill -TERM -- "-$listener_pid" 2>/dev/null || true
wait "$listener_pid" 2>/dev/null || true
unset 'pids[2]'
cp "$MOBSEAR_SMOKE_DIR/pitch-persistence.properties" "$MOBSEAR_SMOKE_DIR/smokeListener/config/mobsear-client.properties"
export MOBSEAR_RESTART_CHECK=1
launch smokeListener
wait_for smokeListener 'MOBSEAR_GAME_RESTART_PASS' 210
grep -F 'MOBSEAR_GAME_RESTART_PASS' "$MOBSEAR_SMOKE_DIR/smokeListener.console"
