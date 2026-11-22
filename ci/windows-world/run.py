"""Boot a private, disposable Windows game and exercise the installed recognizer through Java Sound."""
from pathlib import Path
import os
import subprocess
import shutil
import sys

if os.getenv("GITHUB_ACTIONS") != "true" or os.name != "nt":
    raise SystemExit("Hosted Windows only")
root = Path(os.environ["MOBSEAR_SMOKE_DIR"])
server = root / "server"
client = root / "smokeIntegrated"
server.mkdir(exist_ok=True)
(client / "saves").mkdir(parents=True, exist_ok=True)
(server / "eula.txt").write_text("eula=true\n")
(server / "server.properties").write_text(
    "server-ip=127.0.0.1\nserver-port=0\nonline-mode=false\nlevel-type=minecraft:flat\nlevel-name=HearFixture\n"
    "gamemode=survival\ndifficulty=normal\nlevel-seed=123\ngenerate-structures=false\nview-distance=3\nsimulation-distance=3\n")
(client / "options.txt").write_text(
    "version:3955\nonboardAccessibility:false\nskipMultiplayerWarning:true\npauseOnLostFocus:false\ntutorialStep:none\n"
    "narrator:0\nrenderDistance:5\nsimulationDistance:5\nenableVsync:false\nmaxFps:30\ngraphicsMode:0\nsoundCategory_master:0.0\nguiScale:2\n")
subprocess.run(["cmd", "/d", "/c", "gradlew.bat", "--no-daemon", "--max-workers=2", "-PvoiceMultiplayer",
                "classes", "prepareSmokeServerRun", "prepareSmokeIntegratedRun", "createSmokeServerLaunchScript",
                "createSmokeIntegratedLaunchScript"], check=True, timeout=900)

def run_game(name, marker, stop_server=False):
    options = list(Path("build").rglob(name+".cmd")) + list(Path("build").rglob(name+".bat"))
    if len(options) != 1:
        raise RuntimeError(f"Expected one launch script for {name}: {options}")
    process = subprocess.Popen(["cmd", "/d", "/c", str(options[0])], stdin=subprocess.PIPE,
                               stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, errors="replace")
    try:
        for line in process.stdout:
            if any(key in line for key in ("MOBSEAR_", "ERROR", "Exception", "Caused by", "OpenGL", "Starting integrated")):
                print(line.strip()[:800], flush=True)
            if "MOBSEAR_SMOKE_FAIL" in line:
                break
            if marker in line:
                if stop_server:
                    process.stdin.write("stop\n")
                    process.stdin.flush()
                    process.communicate(timeout=90)
                    if process.returncode: raise RuntimeError("Seed server failed to stop cleanly")
                return
        raise RuntimeError("Game exited before "+marker)
    finally:
        if process.poll() is None:
            subprocess.run(["taskkill", "/PID", str(process.pid), "/T", "/F"], capture_output=True)

run_game("runSmokeServer", "Done (", stop_server=True)
assert (server / "HearFixture/level.dat").is_file()
shutil.move(str(server/"HearFixture"), str(client/"saves/HearFixture"))
run_game("runSmokeIntegrated", "MOBSEAR_INTEGRATED_PASS")
