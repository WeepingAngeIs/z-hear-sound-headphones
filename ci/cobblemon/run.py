"""Boot real isolated Linux game clients with the exact released optional mods."""
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import tempfile
import time

from datapack import create
from public_species import stage as stage_public_species


def run(mode):
    if os.environ.get("GITHUB_ACTIONS") != "true" or mode not in {"solo", "multi"}:
        raise SystemExit("Hosted usage: python3 ci/cobblemon/run.py solo|multi")
    root = Path(tempfile.mkdtemp(prefix="pokemon-voice-", dir=os.environ["RUNNER_TEMP"]))
    env = dict(os.environ, MOBSEAR_POKEMON_ROOT=str(root), LIBGL_ALWAYS_SOFTWARE="true",
               LP_NUM_THREADS="1", ALSOFT_DRIVERS="null")
    roles = ["server", "solo"] if mode == "solo" else ["server", "speaker", "listener"]
    for role in roles:
        for sub in ("tmp", "natives"):
            (root / role / sub).mkdir(parents=True, exist_ok=True)
        if role != "server":
            (root / role / "options.txt").write_text(
                "version:3955\nonboardAccessibility:false\nskipMultiplayerWarning:true\npauseOnLostFocus:false\n"
                "tutorialStep:none\nrenderDistance:5\nsimulationDistance:5\nenableVsync:false\nmaxFps:20\n"
                "graphicsMode:0\nsoundCategory_master:0.0\nguiScale:2\n")
    subprocess.run([sys.executable, "ci/cobblemon/stage.py", *[str(root / role / "mods") for role in roles]], check=True, env=env)
    server = root / "server"
    create(next((server/"mods").glob("Cobblemon-*.jar")), server/"PokemonFixture"/"datapacks")
    if env.get("MOBSEAR_POKEMON_PACK") == "1":
        stage_public_species(world_root=root)
    (server/"eula.txt").write_text("eula=true\n")
    (server/"server.properties").write_text(
        "server-ip=127.0.0.1\nserver-port=25579\nonline-mode=false\nenforce-secure-profile=false\n"
        "level-type=minecraft:flat\nlevel-seed=33\nlevel-name=PokemonFixture\ngenerate-structures=false\n"
        "gamemode=survival\ndifficulty=peaceful\nspawn-protection=0\nview-distance=5\nsimulation-distance=3\n")
    tasks = [t for role in roles for t in ("preparePokemon"+role.title()+"Run", "createPokemon"+role.title()+"LaunchScript")]
    subprocess.run(["bash","gradlew","--no-daemon","--max-workers=2","-PcobblemonFixture","classes",*tasks], env=env, check=True)
    processes = {}
    def start(role):
        script = next(Path("build").rglob("runPokemon" + role.title() + ".sh"))
        command = ["bash", str(script)]
        if role != "server":
            command = ["xvfb-run","-a","-s","-screen 0 1024x768x24",*command]
        with (root / (role+".console")).open("wb") as log:
            processes[role] = subprocess.Popen(command, env=env, stdin=subprocess.PIPE, stdout=log,
                                                stderr=subprocess.STDOUT, start_new_session=True)

    def wait(role, marker, seconds):
        end = time.monotonic()+seconds
        while time.monotonic()<end:
            logs = {p: (root/(p+".console")).read_text(errors="replace") for p in processes}
            for name, data in logs.items():
                if "POKEMON_FIXTURE_FAIL" in data or processes[name].poll() is not None:
                    raise RuntimeError("Game fixture stopped in "+name)
            if marker in logs[role]: return
            time.sleep(2)
        raise RuntimeError("Timeout waiting for "+role+" "+marker)

    try:
        start("server")
        wait("server", "Done (", 360)
        if mode == "solo":
            process = processes.pop("server")
            process.stdin.write(b"stop\n"); process.stdin.flush()
            if process.wait(timeout=120): raise RuntimeError("Could not seed solo save")
            dest = root/"solo/saves"; dest.mkdir()
            shutil.move(str(server/"PokemonFixture"), dest/"PokemonFixture")
            start("solo")
            wait("solo", "MOBSEAR_POKEMON_SOLO_PASS", 600)
        else:
            start("speaker")
            wait("speaker", "POKEMON_CLIENT_READY", 360)
            start("listener")
            wait("server", "MOBSEAR_POKEMON_MULTI_PASS", 660)
        landed = (root/"evidence")
        expected = ["pikachu-before", "pikachu-after", "eevee-after", "look-after", "approach-after", "addon-after",
                    "namespace-after", "near_edge-after", "blocked-after", "cooldown_prime-after", "cooldown_expired-after"]
        expected.extend(["walking_pikachu-after", "walking_eevee-after", "interrupted-after"])
        if env.get("MOBSEAR_POKEMON_PACK") == "1":
            expected.extend(["pack_form-after", "pack_custom-after", "global_pack-after", "named_cow-before", "named_cow-after",
                             "named_hostile-after", "forest_deer-before", "forest_deer-after", "boss_charging-protected", "naga_combat-protected",
                             "owl_guardian-before", "owl_guardian-after", "owl_offender-protected", "velvet_idle-before",
                             "velvet_idle-after", "velvet_guard-protected"])
        for view in expected:
            if not (landed/f"{mode}-{view}.png").is_file(): raise RuntimeError("Missing actual in-game capture " + view)
    except BaseException:
        for f in sorted(root.glob("*.console")):
            print(f.name, flush=True)
            lines=f.read_text(errors="replace").splitlines()
            selected=[l for l in lines if any(k in l for k in ("POKEMON_", "Caused by:", "Exception in thread", "FATAL", "ERROR", "Crash report"))]
            for l in selected[-28:]+lines[-9:]: print(l[:450])
        raise
    finally:
        for proc in processes.values():
            if proc.poll() is None: os.killpg(proc.pid, signal.SIGTERM)
        dest=Path("build/pokemon-evidence"); dest.mkdir(parents=True,exist_ok=True)
        for p in (root/"evidence").glob("*.png"): shutil.copy2(p,dest/p.name)
        if (root/"catalogs").is_dir():
            shutil.copytree(root/"catalogs",Path("build/pokemon-catalogs"),dirs_exist_ok=True)
        for log in root.glob("*.console"):
            for line in log.read_text(errors="replace").splitlines():
                if any(k in line for k in ("POKEMON_CASE_PASS","ENTITY_NAME_CASE_PASS","ENTITY_REGISTRY_PASS","POKEMON_CLIENT_READY","POKEMON_PACK_FORM_","POKEMON_REGISTRY_PASS","MOBSEAR_POKEMON_")): print(line[:450])


if __name__ == "__main__":
    run(sys.argv[1])
