"""Boot real isolated Linux game clients with the exact released optional mods."""
from array import array
import csv
import math
import os
from pathlib import Path
import re
import shutil
import signal
import struct
import subprocess
import sys
import tempfile
import time
import wave

from datapack import create
from public_species import stage as stage_public_species


def game_wave_layout(path):
    """Read actual PCM bytes even if a stopped native process left RIFF sizes unfinished."""
    size = path.stat().st_size
    with path.open("rb") as source:
        if source.read(4) != b"RIFF": raise RuntimeError("OpenAL did not write a RIFF game-output file")
        source.read(4)
        if source.read(4) != b"WAVE": raise RuntimeError("Missing game-output WAVE header")
        audio_format = None
        while source.tell()+8 <= size:
            chunk, length = struct.unpack("<4sI",source.read(8))
            start = source.tell()
            if chunk == b"fmt ": audio_format = source.read(min(length,64))
            if chunk == b"data":
                if audio_format is None or len(audio_format)<16: raise RuntimeError("Missing OpenAL PCM format")
                tag, channels, rate, _, block, bits = struct.unpack("<HHIIHH",audio_format[:16])
                pcm = tag == 1 or (tag == 65534 and len(audio_format)>=40
                        and audio_format[24:40] == bytes.fromhex("0100000000001000800000aa00389b71"))
                if not pcm or bits!=16 or block!=channels*2 or channels not in (1,2):
                    raise RuntimeError(f"Unexpected game-output format tag={tag} channels={channels} bits={bits}")
                available = min(size-start,length) if length not in (0,0xffffffff) else size-start
                if available < rate*block: raise RuntimeError("Game-output WAV contains less than one second of PCM")
                return start, available//block, channels, rate, block
            source.seek(start+length+(length&1))
    raise RuntimeError("Missing OpenAL game-output data chunk")


def collect_game_audio(root,role,observer):
    timeline = root/"evidence"/f"{observer}-visit-timeline.tsv"
    with timeline.open(newline="",encoding="utf8") as source:
        events = list(csv.DictReader(source,delimiter="\t"))
    path = root/role/"game-output.wav"
    data, frames, channels, rate, block = game_wave_layout(path)
    def at(event): return max(0,min(frames,(int(event["audioBytes"])-data)//block))
    def pcm(start,end):
        with path.open("rb") as source:
            source.seek(data+start*block)
            return source.read(max(0,end-start)*block)
    def energy(start,end):
        samples = array("h",pcm(start,end))
        if sys.byteorder!="little": samples.byteswap()
        if not samples: return 0.0,0
        return math.sqrt(sum(n*n for n in samples)/len(samples)),max(abs(n) for n in samples)
    offsets = []
    for cycle in (1,2):
        rows = [e for e in events if int(e["cycle"])==cycle]
        def one(name):
            found = [e for e in rows if e["event"]==name]
            if len(found)!=1: raise RuntimeError(f"Missing unique {observer} chew {cycle} {name}")
            return found[0]
        added, removed, cry = one("prop-add"),one("prop-remove"),one("cry")
        if rows.index(added)>=rows.index(removed) or rows.index(removed)>=rows.index(cry):
            raise RuntimeError("Client berry removal and species cry arrived out of order")
        for phase in ("beginning","middle","end","after","bob-1","bob-2"):
            one("frame-"+phase)
            if not (root/"evidence"/f"{observer}-visit-chew-{cycle}-{phase}.png").is_file():
                raise RuntimeError(f"Missing rendered {observer} chew {cycle} {phase}")
        start = max(0,at(added)-rate//4)
        end = min(frames,at(cry)+rate)
        chewing = energy(at(added)+rate//10,at(removed))
        cry_end = min(frames,at(cry)+rate*3//4)
        later_pats = [at(e) for e in events if e["event"]=="pat-cry" and at(e)>at(cry)]
        if later_pats: cry_end=min(cry_end,min(later_pats))
        crying = energy(at(cry),cry_end)
        output = root/"evidence"/f"{observer}-visit-chew-{cycle}.wav"
        with wave.open(str(output),"wb") as target:
            target.setnchannels(channels); target.setsampwidth(2); target.setframerate(rate)
            target.writeframes(pcm(start,end))
        for event in rows:
            offsets.append([output.name,event["event"],f"{(at(event)-start)/rate:.3f}",event["gameTick"],event["detail"]])
        if chewing[0]<=5 or chewing[1]<=50 or crying[0]<=5 or crying[1]<=50:
            raise RuntimeError(f"Silent actual game output for {observer} chew {cycle}: eating={chewing} cry={crying}")
        print(f"MOBSEAR_POKEMON_AUDIO_PASS observer={observer} cycle={cycle} eatingRms={chewing[0]:.1f} "
              f"cryRms={crying[0]:.1f} frames={end-start} rate={rate} source=OpenAL-Wave-File-Writer",flush=True)
    with (root/"evidence"/f"{observer}-visit-audio.tsv").open("w",newline="",encoding="utf8") as target:
        writer=csv.writer(target,delimiter="\t")
        writer.writerow(["file","event","secondsInClip","gameTick","detail"])
        writer.writerows(offsets)


def run(mode):
    if os.environ.get("GITHUB_ACTIONS") != "true" or mode not in {"solo", "multi"}:
        raise SystemExit("Hosted usage: python3 ci/cobblemon/run.py solo|multi")
    root = Path(tempfile.mkdtemp(prefix="pokemon-voice-", dir=os.environ["RUNNER_TEMP"]))
    env = dict(os.environ, MOBSEAR_POKEMON_ROOT=str(root), LIBGL_ALWAYS_SOFTWARE="true",
               LP_NUM_THREADS="1", ALSOFT_DRIVERS="wave")
    roles = ["server", "solo"] if mode == "solo" else ["server", "speaker", "listener"]
    meminfo=Path("/proc/meminfo").read_text().splitlines()
    physical=next(line for line in meminfo if line.startswith("MemTotal:"))
    print(f"Hosted game capacity: {physical.strip()}, G1, server 2800 MiB, solo 4800 MiB, each network client 3200 MiB",flush=True)
    for role in roles:
        for sub in ("tmp", "natives"):
            (root / role / sub).mkdir(parents=True, exist_ok=True)
        if role != "server":
            (root / role / "options.txt").write_text(
                "version:3955\nonboardAccessibility:false\nskipMultiplayerWarning:true\npauseOnLostFocus:false\n"
                "tutorialStep:none\nrenderDistance:5\nsimulationDistance:5\nenableVsync:false\nmaxFps:20\n"
                "graphicsMode:0\nsoundCategory_master:1.0\nsoundCategory_music:0.0\n"
                "soundCategory_ambient:0.0\nsoundCategory_weather:0.0\nguiScale:2\n")
            (root/role/"alsoft.conf").write_text(
                "[general]\ndrivers = wave\nchannels = stereo\nsample-type = int16\nfrequency = 48000\n"
                "period_size = 256\nperiods = 3\n[wave]\nfile = "+str(root/role/"game-output.wav")+"\n")
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
        game_env=dict(env)
        if role != "server":
            observer={"solo":"solo","speaker":"multi-caller","listener":"multi-viewer"}[role]
            game_env.update(ALSOFT_CONF=str(root/role/"alsoft.conf"),
                            MOBSEAR_GAME_AUDIO=str(root/role/"game-output.wav"),MOBSEAR_AV_ROLE=observer)
        with (root / (role+".console")).open("wb") as log:
            processes[role] = subprocess.Popen(command, env=game_env, stdin=subprocess.PIPE, stdout=log,
                                                stderr=subprocess.STDOUT, start_new_session=True)

    def wait(role, marker, seconds):
        end = time.monotonic()+seconds
        while time.monotonic()<end:
            logs = {p: (root/(p+".console")).read_text(errors="replace") for p in processes}
            for name, data in logs.items():
                if "POKEMON_FIXTURE_FAIL" in data or processes[name].poll() is not None:
                    raise RuntimeError("Game fixture stopped in "+name)
                if "Client disconnected with reason:" in data or "lost connection: Timed out" in data:
                    raise RuntimeError("Disconnected from disposable world in "+name+" while waiting for "+marker)
            if marker in logs[role]: return
            time.sleep(2)
        raise RuntimeError("Timeout waiting for "+role+" "+marker)

    fixture_passed=False
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
        caller_log = (root / ("solo.console" if mode == "solo" else "speaker.console")).read_text(errors="replace")
        for transition in ("near-after-new-far", "recognition-off-on", "mute-unmute", "deafen-undeafen"):
            guard = f"MOBSEAR_CALL_REPLY_GUARD {transition} stale-success=false"
            if guard not in caller_log:
                raise RuntimeError(f"Delayed reply was not checked after {transition}")
            print(guard, flush=True)
        landed = (root/"evidence")
        expected = ["pikachu-before", "pikachu-after", "eevee-after", "lopunny-before", "lopunny-after", "look-after", "approach-after", "addon-after",
                    "namespace-after", "near_edge-after", "blocked-after", "repeat_first-after", "repeat_walk-after"]
        expected.extend(["walking_pikachu-after", "walking_eevee-after", "walking_lopunny-after", "interrupted-after"])
        for species in ("pikachu","eevee","lopunny"):
            for state in ("turn","beside"):
                for suffix in ([""] if mode=="solo" else ["-caller","-viewer"]):
                    expected.append(f"walking_{species}-{state}{suffix}")
        if env.get("MOBSEAR_POKEMON_PACK") == "1":
            views = {"solo": "caller"} if mode == "solo" else {"speaker": "caller", "listener": "viewer"}
            for role, observer in views.items():
                observed = (root / f"{role}.console").read_text(errors="replace").splitlines()
                frames = [line for line in observed if f"ENTITY_DEER_RENDER role={observer} " in line]
                if len(frames) != 1:
                    raise RuntimeError(f"Missing independently rendered walking deer for {observer}")
                print(frames[0], flush=True)
                expected.append(f"walking_deer-{observer}-view")
            expected.extend(["pack_form-after", "pack_custom-after", "global_pack-after", "named_cow-before", "named_cow-after",
                             "named_hostile-after", "forest_deer-before", "forest_deer-after", "boss_charging-protected", "naga_combat-protected",
                             "owl_guardian-before", "owl_guardian-after", "owl_offender-protected", "velvet_idle-before",
                             "velvet_idle-after", "velvet_guard-protected", "dungeon_mimic-before", "dungeon_mimic-after",
                             "zombie_call_mimic-before", "zombie_call_mimic-after"])
        for view in expected:
            if not (landed/f"{mode}-{view}.png").is_file(): raise RuntimeError("Missing actual in-game capture " + view)
        fixture_passed=True
    except BaseException:
        for f in sorted(root.glob("*.console")):
            print(f.name, flush=True)
            lines=f.read_text(errors="replace").splitlines()
            selected=[l for l in lines if any(k in l for k in ("POKEMON_", "ENTITY_", "Caused by:", "Exception in thread",
                      "FATAL", "ERROR", "Crash report", "onnection closed", "lost connection", "utOfMemory", "Timed out"))]
            for l in selected[-36:]+lines[-22:]: print(l[:550])
        raise
    finally:
        for proc in processes.values():
            if proc.poll() is None:
                try: os.killpg(proc.pid, signal.SIGTERM)
                except ProcessLookupError: pass
        for proc in processes.values():
            try: proc.wait(timeout=20)
            except subprocess.TimeoutExpired:
                os.killpg(proc.pid,signal.SIGKILL)
                proc.wait(timeout=10)
        audio_errors=[]
        for role in roles:
            if role=="server": continue
            observer={"solo":"solo","speaker":"multi-caller","listener":"multi-viewer"}[role]
            try: collect_game_audio(root,role,observer)
            except Exception as ex:
                audio_errors.append(f"{observer}: {ex}")
                print("MOBSEAR_POKEMON_AUDIO_FAILED",observer,str(ex),flush=True)
        for role in roles:
            config=root/role/"config/connectivity.json"
            if config.is_file():
                raw=config.read_text(errors="replace")
                settings=dict(re.findall(r'"(logintimeout|disconnectTimeout)"\s*:\s*(\d+)',raw))
                print("Hosted Connectivity",role,settings,flush=True)
        dest=Path("build/pokemon-evidence"); dest.mkdir(parents=True,exist_ok=True)
        for p in (root/"evidence").glob("*"):
            if p.suffix in (".png",".tsv",".wav"): shutil.copy2(p,dest/p.name)
        if (root/"catalogs").is_dir():
            shutil.copytree(root/"catalogs",Path("build/pokemon-catalogs"),dirs_exist_ok=True)
        for log in root.glob("*.console"):
            for line in log.read_text(errors="replace").splitlines():
                if any(k in line for k in ("POKEMON_CASE_PASS","POKEMON_VISIT_PASS","POKEMON_REPEAT_PASS","POKEMON_BOXTAIL_","ENTITY_NAME_CASE_PASS","ENTITY_DEER_ROUTE","ENTITY_REGISTRY_PASS","POKEMON_CLIENT_READY","POKEMON_PACK_FORM_","POKEMON_REGISTRY_PASS","MOBSEAR_POKEMON_")): print(line[:450])
        if fixture_passed and audio_errors: raise RuntimeError("Actual game-output evidence failed: "+"; ".join(audio_errors))


if __name__ == "__main__":
    run(sys.argv[1])
