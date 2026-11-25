"""Two production NeoForge clients play on one loopback-only, disposable Mic server."""
import hashlib
import os
from pathlib import Path
import queue
import re
import shutil
import subprocess
import sys
import threading
import time
import uuid
from zipfile import ZipFile

from minecraft_launcher_lib import command, mod_loader, utils

if os.name != "posix" or os.getenv("RUNNER_ENVIRONMENT") != "github-hosted" or os.getenv("GITHUB_ACTIONS") != "true":
    raise SystemExit("Disposable GitHub-hosted Linux required")
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/"fullpack"))
from stage import OLD, public_json, read_url

ROOT = Path(os.environ["RUNNER_TEMP"])
CLIENT = ROOT / "join-client"
SERVER = ROOT / "join-server"
LAUNCHER = ROOT / "join-launcher"
PACK = os.environ["JOIN_PACK"]
VERSION = "21.1.249"
PORT = 25565


def sha(path):
    with path.open("rb") as f:
        return hashlib.file_digest(f,"sha256").hexdigest()


def stage():
    candidate, = Path("dist").glob("*.jar")
    assert sha(candidate) == os.environ["RAW_SHA"]
    observer = Path("build/pack-probe/ci-mic-join-observer.jar")
    with ZipFile(observer) as jar:
        assert all(k.startswith("dev/hearcheck/") for k in jar.namelist() if k.endswith(".class"))
    old = CLIENT/"mods"/"mobsear-0.3.1.jar"
    assert sha(old) == "850e97492f40e7dc206dcc56decaab81c0c4bf129f063dffd90a638b2d414174"
    old.rename(ROOT/"reference-0.3.1.jar")
    mods = sorted((CLIENT/"mods").glob("*.jar"))
    assert len(mods) == (365 if PACK=="public-pack" else 2)
    servermods = SERVER/"mods"
    servermods.mkdir(parents=True)
    if PACK=="public-pack":
        inventory = public_json("profile.json")
        avoid = {r["name"] for r in inventory["mods"] if r["side"]=="unsupported"} - {
                    "SimplyTooltips-neoforge-0.1.5.jar","cobblemon_party_extras-neoforge-1.8.15.jar"}
        avoid.update(public_json("server-exclusions.json"))
    else:
        avoid = set()
    for mod in mods:
        if mod.name not in avoid:
            os.link(mod,servermods/mod.name)
    for name in ("config","defaultconfigs","resourcepacks","datapacks"):
        folder=CLIENT/name
        if folder.is_dir():
            shutil.copytree(folder,SERVER/name,copy_function=shutil.copyfile if name.endswith("config") or name=="defaultconfigs" else os.link)
    print("JOIN_INVENTORY",PACK,"common client mods",len(mods),"dedicated pack dependencies",len(list(servermods.glob("*.jar"))),
          "unchanged common content digest",hashlib.sha256("".join(m.name+":"+sha(m)+"\n" for m in mods).encode()).hexdigest(),flush=True)
    shutil.copyfile(candidate,servermods/"mobsear-candidate.jar")
    for directory in (servermods,CLIENT/"mods"):
        shutil.copyfile(observer,directory/observer.name)
    (CLIENT/"config").mkdir(exist_ok=True)
    (CLIENT/"config/mobsear-client.properties").write_text("micMuted=true\ndeafened=true\nlocalMobCalls=false\nvoiceActivation=false\n")
    (CLIENT/"options.txt").write_text("version:3955\nonboardAccessibility:false\npauseOnLostFocus:false\n"
        "tutorialStep:none\nnarrator:0\nrenderDistance:3\nsimulationDistance:3\nenableVsync:false\nmaxFps:20\n"
        "graphicsMode:0\nsoundCategory_master:0.0\nguiScale:2\n")
    return candidate


class Running:
    def __init__(self,args,folder,label):
        self.process = subprocess.Popen(args,cwd=folder,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,
                                       text=True,encoding="utf8",errors="replace",bufsize=1)
        self.lines = queue.Queue()
        self.label = label
        self.played = set()
        self.errors = []
        threading.Thread(target=self.read,daemon=True).start()

    def read(self):
        for raw in self.process.stdout:
            for marker in ("JOIN_PROOF","JOIN_PASS","JOIN_REFUSED","JOIN_INCORRECT"):
                if marker in raw:
                    row = raw[raw.index(marker):].strip()[:760]
                    print(self.label,row,flush=True)
                    if row.startswith("JOIN_PROOF server played "): self.played.add(row.split()[3])
                    self.lines.put(row)
                    break
            if "Done (" in raw and "For help" in raw: self.lines.put("READY")
            if any(tag in raw for tag in ("Caused by:","Failure message:","requires ","registry entries","registry data","missing registry","Mod loading error")):
                msg=raw.strip()[:420]
                if msg not in self.errors and len(self.errors)<18: self.errors.append(msg)
        self.lines.put("EXIT")

    def until(self,expected,seconds):
        deadline = time.monotonic()+seconds
        while time.monotonic()<deadline:
            try: row=self.lines.get(timeout=min(15,max(.1,deadline-time.monotonic())))
            except queue.Empty: continue
            if row.startswith(expected): return row
            if row.startswith(("JOIN_REFUSED","JOIN_INCORRECT","EXIT")): break
        raise RuntimeError(f"{self.label} did not reach {expected}. " + " / ".join(self.errors))

    def close(self,graceful=False):
        if self.process.poll() is None:
            if graceful:
                try:
                    self.process.stdin.write("stop\n"); self.process.stdin.flush()
                    self.process.wait(timeout=40); return
                except (OSError,subprocess.TimeoutExpired): pass
            self.process.terminate()
            try: self.process.wait(timeout=15)
            except subprocess.TimeoutExpired: self.process.kill(); self.process.wait()


def main():
    candidate = stage()
    java = str(Path(os.environ["JAVA_HOME"])/"bin/java")
    installer = SERVER/"installer.jar"
    installer.write_bytes(read_url("https://maven.neoforged.net/releases/net/neoforged/neoforge/"+VERSION+"/neoforge-"+VERSION+"-installer.jar"))
    assert sha(installer) == "d88b448eab73cd65bdf1720844a4828262de30a15fc71bd04dd81acc61c5399a"
    with (SERVER/"install.log").open("w") as log:
        subprocess.run([java,"-jar",str(installer),"--installServer"],cwd=SERVER,stdout=log,stderr=subprocess.STDOUT,check=True,timeout=680)
    LAUNCHER.mkdir()
    loader = mod_loader.get_mod_loader("neoforge")
    loader.install("1.21.1",str(LAUNCHER),loader_version=VERSION,java=java)
    client_version=loader.get_installed_version("1.21.1",VERSION)
    (SERVER/"eula.txt").write_text("eula=true\n")
    (SERVER/"server.properties").write_text(f"server-ip=127.0.0.1\nserver-port={PORT}\nonline-mode=false\n"
        "enforce-secure-profile=false\nspawn-protection=0\ngamemode=survival\nlevel-type=minecraft:flat\n"
        "allow-flight=true\ngenerate-structures=false\nview-distance=3\nsimulation-distance=3\nmax-tick-time=0\n")
    server=Running([java,"-Xms512m","-Xmx4608m","-XX:ActiveProcessorCount=3",
                    "@libraries/net/neoforged/neoforge/"+VERSION+"/unix_args.txt","nogui"],SERVER,"server")
    errors=[]
    try:
        server.until("READY",720)
        initial={m.name: sha(m) for m in (CLIENT/"mods").glob("*.jar")}
        for name in ["MicAbsent","MicPresent"]:
            if name=="MicPresent":
                shutil.copyfile(candidate,CLIENT/"mods/mobsear-candidate.jar")
            current={m.name:sha(m) for m in (CLIENT/"mods").glob("*.jar")}
            assert current==(initial if name=="MicAbsent" else initial|{"mobsear-candidate.jar":os.environ["RAW_SHA"]})
            options=utils.generate_test_options()
            options.update(username=name,uuid=str(uuid.UUID(bytes=hashlib.md5(("OfflinePlayer:"+name).encode()).digest(),version=3)),
                           executablePath=java,gameDirectory=str(CLIENT),quickPlayMultiplayer="127.0.0.1:"+str(PORT),
                           jvmArguments=["-Xms512m","-Xmx6800m","-XX:ActiveProcessorCount=4","-XX:+UseG1GC"],
                           customResolution=True,resolutionWidth="854",resolutionHeight="480")
            args=command.get_minecraft_command(client_version,str(LAUNCHER),options)
            if "--quickPlayMultiplayer" not in args: args.extend(["--quickPlayMultiplayer","127.0.0.1:"+str(PORT)])
            client=Running(args,CLIENT,name)
            try:
                client.until("JOIN_PASS "+name,830)
                assert name in server.played, "Server did not confirm the player's vanilla action"
            except (AssertionError,RuntimeError) as exc:
                errors.append(str(exc))
            finally:
                client.close()
        if errors: raise RuntimeError(" / ".join(errors))
        print("JOIN_COMPLETE",PACK,"server and both real clients verified",os.environ["RAW_SHA"],flush=True)
    finally:
        server.close(graceful=True)


if __name__=="__main__":
    main()
