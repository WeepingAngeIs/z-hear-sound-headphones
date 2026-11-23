"""Diagnose only the known generated Nidorine trial after a failed hosted run."""
import hashlib
import io
import os
from pathlib import Path
import struct
import subprocess
import tempfile
from zipfile import ZipFile

assert os.name == "nt" and os.environ.get("RUNNER_ENVIRONMENT") == "github-hosted"
root = Path(os.environ["MOBSEAR_SMOKE_DIR"])
hints = root / "segments/nidorine-public-hints.txt"
parts = sorted((root / "segments").glob("*_nearby-nidorine_*.pcm"))
if not hints.exists() or not parts:
    print("No generated relative trial was reached.")
    raise SystemExit()
jar, = (root / "game/mods").glob("mobsear-*.jar")
assert hashlib.sha256(jar.read_bytes()).hexdigest() == os.environ["MOBSEAR_CANDIDATE_SHA256"]
rows = hints.read_text().splitlines()
wire_hints = "\n".join(rows).encode("ascii")
wire = bytearray((root / "public-registry.bin").read_bytes())
wire.extend(struct.pack("<ii",0,len(wire_hints)) + wire_hints)
for part in parts:
    pcm = part.read_bytes()
    assert 0 < len(pcm) <= 256000 and len(pcm) % 2 == 0
    wire.extend(struct.pack("<i",len(pcm)) + pcm)
with tempfile.TemporaryDirectory(dir=root) as temp, ZipFile(jar) as mod:
    directory = Path(temp)
    base = "dev/mobsear/client/"
    script = mod.read(base+"recognize.ps1").decode()
    matcher = mod.read(base+"NearbyPokemonWords.cs").decode()
    with ZipFile(io.BytesIO(mod.read(base+"words-win-x64.zip"))) as model:
        model.extractall(directory)
    hook = "if ($text.Length -le 500)"
    assert script.count(hook)==1
    script = script.replace(hook, '[Console]::Error.WriteLine("GENERATED_RELATIVE_REPLAY words="+$text.Trim()+" independent="+($original -join " ")+" prompt="+$prompt); '+hook)
    diagnostic = directory / "generated-replay.ps1"
    diagnostic.write_text(script, encoding="utf-8-sig")
    shell = Path(os.environ["SystemRoot"])/"System32/WindowsPowerShell/v1.0/powershell.exe"
    result = subprocess.run([str(shell),"-NoProfile","-NonInteractive","-Command",
                             "& ([ScriptBlock]::Create([IO.File]::ReadAllText($env:MOBSEAR_GENERATED_SCRIPT)))"],
                            input=wire, capture_output=True, timeout=45, check=True,
                            env=dict(os.environ,MOBSEAR_GENERATED_SCRIPT=str(diagnostic),MOBSEAR_NEARBY_MATCHER=matcher,
                                     MOBSEAR_LOCAL_WORD_MODEL=str(directory),MOBSEAR_STARTUP_STEPS="0"))
    assert result.stdout[:1]==b"R" and len(result.stdout)==1+2*len(parts)
    for line in result.stderr.decode(errors="replace").splitlines():
        if line.startswith("GENERATED_RELATIVE_REPLAY "): print(line[:650])
    print("GENERATED_RELATIVE_CODES",[n for (n,) in struct.iter_unpack(">H",result.stdout[1:])])
