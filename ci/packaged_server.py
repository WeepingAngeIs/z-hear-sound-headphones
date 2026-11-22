"""Boot the downloadable mod in a temporary, loopback-only production server."""
import hashlib
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import tempfile
import time
import tomllib
import zipfile


def run_server(folder):
    console = folder / "console.log"
    with console.open("w") as output:
        server = subprocess.Popen(
            ["bash", "run.sh", "nogui"], cwd=folder, stdin=subprocess.PIPE,
            stdout=output, stderr=subprocess.STDOUT, text=True, start_new_session=True,
        )
        try:
            deadline = time.monotonic() + 240
            while time.monotonic() < deadline:
                if server.poll() is not None:
                    raise RuntimeError(f"server exited before readiness ({server.returncode})")
                if "Done (" in console.read_text(errors="replace"):
                    break
                time.sleep(1)
            else:
                raise TimeoutError("server did not become ready")
            server.communicate("stop\n", timeout=90)
            if server.returncode:
                raise RuntimeError(f"shutdown failed ({server.returncode})")
        finally:
            if server.poll() is None:
                os.killpg(server.pid, signal.SIGKILL)
                server.wait()
            for line in console.read_text(errors="replace").splitlines()[-55:]:
                print(line[:400])


def main():
    if os.environ.get("GITHUB_ACTIONS") != "true":
        raise SystemExit("This fixture runs on GitHub-hosted Linux only")
    version, artifact = sys.argv[1], Path(sys.argv[2]).resolve()
    checksums = {
        "21.1.249": "d88b448eab73cd65bdf1720844a4828262de30a15fc71bd04dd81acc61c5399a",
        "21.1.255": "11d67a99db0ded3648b97b1eac06c5db0305899e05f657d360bec9d6741e522f",
    }
    digest = hashlib.sha256(artifact.read_bytes()).hexdigest()
    with zipfile.ZipFile(artifact) as jar:
        meta = tomllib.loads(jar.read("META-INF/neoforge.mods.toml").decode())
        assert not any(name.startswith(("com/cobblemon/", "kotlin/")) for name in jar.namelist())
        ranges = {item["modId"]: item["versionRange"] for item in meta["dependencies"]["mobsear"]}
        assert ranges["neoforge"] == "[21.1.249,21.2)" and ranges["minecraft"] == "[1.21.1]"
    with tempfile.TemporaryDirectory(prefix="voice-packaged-", dir=os.environ["RUNNER_TEMP"]) as temp:
        folder = Path(temp)
        url = f"https://maven.neoforged.net/releases/net/neoforged/neoforge/{version}/neoforge-{version}-installer.jar"
        installer = folder / "installer.jar"
        subprocess.run(["curl", "--fail", "--silent", "--show-error", "--location", "--retry", "3",
                        "--output", installer, url], check=True)
        assert hashlib.sha256(installer.read_bytes()).hexdigest() == checksums[version]
        with (folder / "installer-output.log").open("w") as log:
            install = subprocess.run(["java", "-jar", installer, "--installServer"], cwd=folder,
                                     stdout=log, stderr=subprocess.STDOUT, timeout=650)
        if install.returncode:
            print((folder / "installer-output.log").read_text(errors="replace")[-4000:])
            install.check_returncode()
        assert (folder / "libraries/net/neoforged/neoforge" / version / "unix_args.txt").is_file()
        (folder / "mods").mkdir(exist_ok=True)
        shutil.copyfile(artifact, folder / "mods" / artifact.name)
        (folder / "eula.txt").write_text("eula=true\n")
        (folder / "user_jvm_args.txt").write_text("-Xms384m\n-Xmx1500m\n-XX:ActiveProcessorCount=2\n")
        (folder / "server.properties").write_text(
            "server-ip=127.0.0.1\nserver-port=0\nonline-mode=false\nenforce-secure-profile=false\n"
            "generate-structures=false\nview-distance=3\nsimulation-distance=3\nlevel-seed=247\n")
        run_server(folder)
        # This module registers its config during mod construction. A skipped mod can't create it.
        configs = [*folder.glob("config/mobsear-server.toml"), *folder.glob("world/serverconfig/mobsear-server.toml")]
        settings = [tomllib.loads(path.read_text()) for path in configs]
        assert any(config["voiceRadius"] == 32 and config["hordeEnabled"] is False
                   and config["wildPokemonApproachChance"] == 0.20 for config in settings)
        print(f"PACKAGED_SERVER_PASS Minecraft 1.21.1  NeoForge {version}  mobsear SHA-256 {digest}")


if __name__ == "__main__":
    main()
