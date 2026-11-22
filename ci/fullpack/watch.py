"""Publish a bounded diagnostic excerpt while the disposable pack is still starting."""
import os
from pathlib import Path
import subprocess
import sys
import time

if os.name!="nt" or os.getenv("RUNNER_ENVIRONMENT")!="github-hosted":
    raise SystemExit("Hosted Windows only")
root=Path(os.environ["MOBSEAR_SMOKE_DIR"])
log=root/"generated-run.log"
done=root/"result.txt"
started=root/"started.txt"
if sys.argv[1]=="start":
    started.write_text(str(time.time()),encoding="ascii")
    with log.open("wb") as out:
        subprocess.Popen([sys.executable,"-u",str(Path(__file__).with_name("run.py"))],stdout=out,stderr=out,
                         stdin=subprocess.DEVNULL,close_fds=True,creationflags=subprocess.CREATE_NO_WINDOW)
    raise SystemExit()

delay=int(sys.argv[1]) if sys.argv[1]!="finish" else 44*60
deadline=float(started.read_text(encoding="ascii"))+delay
while not done.exists() and time.time()<deadline:
    time.sleep(3)
with log.open(encoding="utf8",errors="replace") as stream:
    markers=[l.strip()[:1800] for l in stream if l.startswith(("ACOUSTIC_", "Fresh flat", "Provisioning official",
             "Hosted client reached", "Hosted game created", "RuntimeError:")) or "Starting integrated minecraft server" in l]
report="\n".join(markers)+"\n"
(root/"boot-progress.txt").write_text(report,encoding="utf8")
print(report,flush=True)
if sys.argv[1]=="finish" and (not done.is_file() or done.read_text(encoding="ascii")!="success"):
    raise SystemExit("The production client did not complete all six generated calls")
