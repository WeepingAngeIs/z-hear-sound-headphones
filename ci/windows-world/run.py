"""Boot a private, disposable Windows game and exercise the installed recognizer through Java Sound."""
from pathlib import Path
import os
import subprocess
import shutil
import sys

if os.getenv("GITHUB_ACTIONS") != "true" or os.name != "nt":
    raise SystemExit("Hosted Windows only")
root = Path(os.environ["MOBSEAR_SMOKE_DIR"])
pokemon = os.getenv("MOBSEAR_POKEMON_ACOUSTIC") == "1"
server = root / "server"
client = root / ("solo" if pokemon else "smokeIntegrated")
world = "PokemonFixture" if pokemon else "HearFixture"
server_run, client_run = ("PokemonServer", "PokemonSolo") if pokemon else ("SmokeServer", "SmokeIntegrated")
server.mkdir(exist_ok=True)
(client / "saves").mkdir(parents=True, exist_ok=True)
(server / "eula.txt").write_text("eula=true\n")
(server / "server.properties").write_text(
    f"server-ip=127.0.0.1\nserver-port=0\nonline-mode=false\nlevel-type=minecraft:flat\nlevel-name={world}\n"
    "gamemode=survival\ndifficulty=normal\nlevel-seed=123\ngenerate-structures=false\nview-distance=3\nsimulation-distance=3\n")
(client / "options.txt").write_text(
    "version:3955\nonboardAccessibility:false\nskipMultiplayerWarning:true\npauseOnLostFocus:false\ntutorialStep:none\n"
    "narrator:0\nrenderDistance:5\nsimulationDistance:5\nenableVsync:false\nmaxFps:30\ngraphicsMode:0\nsoundCategory_master:0.0\nguiScale:2\n")
if pokemon:
    (client/"tmp").mkdir(exist_ok=True)
    (client/"natives").mkdir(exist_ok=True)
    subprocess.run([sys.executable, "ci/cobblemon/stage.py", str(server/"mods"), str(client/"mods")], check=True)
    upstream, = (server/"mods").glob("Cobblemon-neoforge-*.jar")
    subprocess.run([sys.executable, "ci/cobblemon/datapack.py", str(upstream), str(server/world/"datapacks")], check=True)
property_name = "-PcobblemonFixture" if pokemon else "-PvoiceMultiplayer"
subprocess.run(["cmd", "/d", "/c", "gradlew.bat", "--no-daemon", "--max-workers=2", property_name,
                "classes", f"prepare{server_run}Run", f"prepare{client_run}Run", f"create{server_run}LaunchScript",
                f"create{client_run}LaunchScript"], check=True, timeout=1200)

def run_game(name, marker, stop_server=False):
    options = list(Path("build").rglob(name+".cmd")) + list(Path("build").rglob(name+".bat"))
    if len(options) != 1:
        raise RuntimeError(f"Expected one launch script for {name}: {options}")
    process = subprocess.Popen(["cmd", "/d", "/c", str(options[0])], stdin=subprocess.PIPE,
                               stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, errors="replace")
    try:
        for line in process.stdout:
            if any(key in line for key in ("MOBSEAR_", "POKEMON_", "ERROR", "Exception", "Caused by", "OpenGL", "Starting integrated")):
                print(line.strip()[:800], flush=True)
            if "MOBSEAR_SMOKE_FAIL" in line or "POKEMON_FIXTURE_FAIL" in line:
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

run_game("run"+server_run, "Done (", stop_server=True)
assert (server/world/"level.dat").is_file()
shutil.move(str(server/world), str(client/"saves"/world))
run_game("run"+client_run, "MOBSEAR_POKEMON_SOLO_PASS" if pokemon else "MOBSEAR_INTEGRATED_PASS")
