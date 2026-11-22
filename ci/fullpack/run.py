"""Boot an official Windows production client with the unchanged public 0.3.1 JAR."""
from collections import deque
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import threading
import time
from zipfile import ZipFile

from minecraft_launcher_lib import command, mod_loader, utils
import requests

if os.getenv("GITHUB_ACTIONS") != "true" or os.getenv("RUNNER_ENVIRONMENT") != "github-hosted" or os.name != "nt":
    raise SystemExit("Disposable GitHub Windows runner required")
root = Path(os.environ["MOBSEAR_SMOKE_DIR"]).resolve()
game = root / "game"
launcher = root / "launcher"
world = "HearPublicPack"
java = str(Path(os.environ["JAVA_HOME"]) / "bin/java.exe")


def checked_response(url):
    response = requests.get(url, timeout=120)
    response.raise_for_status()
    return response


def seed_world():
    listing = checked_response("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json").json()
    version = next(v for v in listing["versions"] if v["id"] == "1.21.1")
    metadata = checked_response(version["url"]).content
    assert hashlib.sha1(metadata).hexdigest() == version["sha1"]
    entry = json.loads(metadata)["downloads"]["server"]
    binary = checked_response(entry["url"]).content
    assert hashlib.sha1(binary).hexdigest() == entry["sha1"]
    seed = root / "seed"
    seed.mkdir()
    (seed / "server.jar").write_bytes(binary)
    (seed / "eula.txt").write_text("eula=true\n")
    (seed / "server.properties").write_text(
        "server-ip=127.0.0.1\nserver-port=0\nonline-mode=false\nlevel-type=minecraft:flat\n"
        f"level-name={world}\ngamemode=survival\ndifficulty=normal\nlevel-seed=123\ngenerate-structures=false\n"
        "view-distance=3\nsimulation-distance=3\n")
    with subprocess.Popen([java,"-Xmx1300m","-jar","server.jar","nogui"],cwd=seed,stdin=subprocess.PIPE,
                          stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,errors="replace") as process:
        timer=threading.Timer(240, process.kill)
        timer.start()
        try:
            for line in process.stdout:
                if "Done (" in line:
                    process.stdin.write("gamerule spawnChunkRadius 0\nstop\n")
                    process.stdin.flush()
                    break
            process.communicate(timeout=80)
            assert process.returncode == 0 and (seed/world/"level.dat").is_file(), "Disposable seed failed"
        finally:
            timer.cancel()
            if process.poll() is None: process.kill()
    (game / "saves").mkdir(exist_ok=True)
    shutil.move(seed/world,game/"saves"/world)
    print("Fresh flat Survival save created by official Minecraft 1.21.1; hosted integrated server opens it", flush=True)


def progress_snapshot(pid):
    # White-list stack frames and heap sizes. Launcher arguments, player data and speech never enter the report.
    jcmd=str(Path(java).with_name("jcmd.exe"))
    for action in ("GC.heap_info","Thread.print"):
        try:
            result=subprocess.run([jcmd,str(pid),action],capture_output=True,text=True,errors="replace",timeout=18)
        except (OSError,subprocess.TimeoutExpired):
            print("ACOUSTIC_BOOT_DIAGNOSTIC unavailable",action,flush=True)
            continue
        if action=="GC.heap_info":
            for line in result.stdout.splitlines():
                if "heap" in line and "used" in line: print("ACOUSTIC_HEAP",line.strip()[:125],flush=True)
            continue
        for block in re.split(r"\r?\n\r?\n",result.stdout):
            first=block.splitlines()[0] if block else ""
            if not first.startswith(('"Server thread"','"Render thread"')): continue
            frames=re.findall(r"(?m)^\s+at ([\w@.$/+:-]+)\(",block)[:24]
            print("ACOUSTIC_BOOT_THREAD",first.split('"')[1]," | ".join(frames),flush=True)


def run():
    from host_load import measure
    expected = "850e97492f40e7dc206dcc56decaab81c0c4bf129f063dffd90a638b2d414174"
    installed=game/"mods/mobsear-0.3.1.jar"
    assert hashlib.sha256(installed.read_bytes()).hexdigest() == expected
    build=os.environ.get("MOBSEAR_CANDIDATE_RUN","")
    digest=os.environ.get("MOBSEAR_CANDIDATE_SHA256","")
    earlier=os.environ.get("MOBSEAR_EARLIER")=="1"
    if earlier:
        reference=root/"mobsear-0.2.1.jar"
        assert hashlib.sha256(reference.read_bytes()).hexdigest()=="e435bf303a1c63f21882af058aa76b7e84bbba7157ff5efc5b5b2b16e71fc0e5"
        assert not build and not digest
        installed.rename(root/installed.name)
        shutil.copy2(reference,game/"mods"/reference.name)
        print("ACOUSTIC_EARLIER original packaged 0.2.1",flush=True)
    if build or digest:
        assert build.isdigit() and re.fullmatch(r"[0-9a-f]{64}",digest)
        checked,=(root/"candidate").glob("mobsear-*.jar")
        assert hashlib.sha256(checked.read_bytes()).hexdigest()==digest, "Different candidate binary"
        with ZipFile(checked) as jar:
            assert not any("Fixture" in name or "/smoke/" in name or "packprobe" in name for name in jar.namelist())
        installed.rename(root/installed.name)
        shutil.copy2(checked,game/"mods"/checked.name)
        print(f"ACOUSTIC_CANDIDATE canonicalBuild={build} sha256={digest}",flush=True)
    for file in sorted((root/"synth").glob("*.pcm")):
        if file.stem in {"enderman","pikachu","lopunny","enderman-alternate","lopunny-alternate"}:
            print("ACOUSTIC_SAMPLE",file.stem,hashlib.sha256(file.read_bytes()).hexdigest(),flush=True)
    probe = Path("build/pack-probe/ci-acoustic-observer.jar").resolve()
    with ZipFile(probe) as jar:
        allowed_observer = "dev/mobsear/fixture/CalledPokemonVisit.class"
        assert all(not n.endswith(".class") or n.startswith("dev/mobsear/packprobe/") or n == allowed_observer
                   for n in jar.namelist()), "Observer JAR contains a class outside its test sources"
        assert not any(n.startswith(("assets/", "data/")) for n in jar.namelist())
    shutil.copy2(probe,game/"mods"/probe.name)
    native=os.getenv("MOBSEAR_NATIVE_WORLD")=="1"
    if not native:
        seed_world()
    light=os.getenv("MOBSEAR_LIGHT_GRAPHICS")=="1"
    fps=10 if light else 30
    print(f"ACOUSTIC_RENDER hostedSoftware=true fpsLimit={fps}",flush=True)
    (game/"options.txt").write_text("version:3955\nonboardAccessibility:false\npauseOnLostFocus:false\n"
        f"tutorialStep:none\nnarrator:0\nrenderDistance:5\nsimulationDistance:5\nenableVsync:false\nmaxFps:{fps}\n"
        "graphicsMode:0\nsoundCategory_master:0.0\nguiScale:2\n", encoding="utf-8")
    launcher.mkdir(exist_ok=True)
    loader=mod_loader.get_mod_loader("neoforge")
    version=loader.get_installed_version("1.21.1","21.1.249")
    print("Provisioning official production NeoForge 21.1.249 launcher", flush=True)
    loader.install("1.21.1",str(launcher),loader_version="21.1.249",java=java)
    options=utils.generate_test_options()
    options.update(username="AcousticFixture",executablePath=java,gameDirectory=str(game),
                   jvmArguments=["-Xms1G","-Xmx8G","-XX:+UseG1GC","-XX:ActiveProcessorCount=4"],
                   customResolution=True,resolutionWidth="640" if light else "854",resolutionHeight="360" if light else "480")
    if not native: options["quickPlaySingleplayer"]=world
    args=command.get_minecraft_command(version,str(launcher),options)
    if not native and "--quickPlaySingleplayer" not in args:
        args.extend(["--quickPlaySingleplayer",world])
    # Command arguments can contain launcher credentials. Only fixed proof markers are published.
    with subprocess.Popen(args,cwd=game,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,
                          encoding="utf-8",errors="replace") as process:
        recent=deque(maxlen=12)
        stop=threading.Event()
        def monitor():
            start=time.monotonic()
            sampled=set()
            while not stop.wait(8):
                crash=next((game/"crash-reports").glob("crash-*.txt"),None)
                if crash:
                    print("Hosted game created a crash report", flush=True)
                    for line in crash.read_text(encoding="utf-8",errors="replace").splitlines():
                        if any(tag in line for tag in ("Failure message:", "Caused by:", "Exception message:", "Suspected Mod:", "Mod File:")):
                            print(line.strip()[:330],flush=True)
                    process.kill()
                    return
                age=int(time.monotonic()-start)
                for minute in (4,8,14,22,29):
                    if age>=minute*60 and minute not in sampled:
                        sampled.add(minute)
                        progress_snapshot(process.pid)
                if age>1920:
                    print("Hosted client reached the 32 minute in-game deadline",flush=True)
                    process.kill()
                    return
        watcher=threading.Thread(target=monitor,daemon=True)
        watcher.start()
        observations=[]
        helper_pid=0
        try:
            for line in process.stdout:
                if "ACOUSTIC_" in line:
                    message=line[line.index("ACOUSTIC_"):].strip()
                    print(message[:900],flush=True)
                    if message.startswith("ACOUSTIC_CHILD "):
                        helper_pid=int(message.split()[1])
                        measure(process.pid,helper_pid,"created",os.getenv("MOBSEAR_PRIORITY_CONTROL")=="1")
                    elif helper_pid and message.startswith(("ACOUSTIC_STARTUP_STEP","ACOUSTIC_PLAY","ACOUSTIC_RESULT","ACOUSTIC_DESKTOP_EXIT")):
                        measure(process.pid,helper_pid,message.split()[0:2][-1])
                    if message.startswith("ACOUSTIC_RESULT "):
                        observations.append(message)
                    if message.startswith("ACOUSTIC_COMPLETE "):
                        count=2 if earlier else 13 if os.environ.get("MOBSEAR_TEST_FEEDBACK")=="1" else 11 if os.environ.get("MOBSEAR_EXTRA_NAMES")=="1" else 6
                        if os.environ.get("MOBSEAR_TEST_VISIT")=="1":
                            count+=3
                        if os.environ.get("MOBSEAR_TEST_SENTENCES")=="1":
                            count+=7
                        assert len(observations)==count
                        if os.getenv("MOBSEAR_TEST_SENTENCES")=="1":
                            from check_debug import verify
                            verify(game/"logs/latest.log")
                        if os.environ.get("MOBSEAR_INSTALLED_REFERENCE")=="1":
                            assert all(re.search(r'captureFrames=[1-9]\d* serverAudio=true',row) for row in observations)
                            print("ACOUSTIC_REFERENCE installed 0.3.1 observed",message.split(" ",1)[1],flush=True)
                        else:
                            assert message==f"ACOUSTIC_COMPLETE trials={count} failed=0", "One or more acoustic trials failed"
                        return
                    if message.startswith("ACOUSTIC_FATAL "): raise RuntimeError(message[:600])
                elif "POKEMON_VISIT_PASS" in line:
                    print("ACOUSTIC_"+line[line.index("POKEMON_VISIT_PASS"):].strip(),flush=True)
                elif any(x in line for x in ("Starting integrated minecraft server", "Initializing Minecraft", "Loading Minecraft",
                                             "OpenGL Version", "Preparing start region", "Preparing spawn area", "Time elapsed:")):
                    print(line.strip()[:330], flush=True)
                elif any(x in line for x in ("Caused by:", "Exception in thread", "Error occurred during", "Error: Could not", "LoadingFailedException")):
                    recent.append(line.strip()[:400])
            raise RuntimeError("Hosted game exited before the final evidence marker: "+" | ".join(recent))
        finally:
            stop.set()
            if process.poll() is None: process.kill()
            process.wait(timeout=30)


if __name__=="__main__":
    try:
        run()
    except BaseException:
        try:
            if (root/"public-registry.bin").is_file():
                from decoder import diagnose
                mod,=(game/"mods").glob("mobsear-*.jar")
                diagnose(root,mod)
        finally:
            (root/"result.txt").write_text("failure",encoding="ascii")
        raise
    (root/"result.txt").write_text("success",encoding="ascii")
