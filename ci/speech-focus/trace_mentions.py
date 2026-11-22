"""Inspect fixed generated clips only. The mod itself does not print decoder text."""
import os
from pathlib import Path
import struct
import subprocess
from zipfile import ZipFile

def show(root):
    assert os.environ.get("RUNNER_ENVIRONMENT") == "github-hosted" and os.name == "nt"
    script = Path("src/main/resources/dev/mobsear/client/recognize.ps1").read_text(encoding="utf8")
    for hook,event in [("$plain = if ($null",'("first="+$result.Grammar.Name+" "+$result.Text)'),
                       ("if ($text.Length -le 500)",'("words="+$text.Trim())'),
                       ("if (-not $child.WaitForExit(3200))",'("sentence-decoder-started")')]:
        assert script.count(hook) == 1, hook
        script = script.replace(hook, "[Console]::Error.WriteLine(" + event + ")\n            " + hook)
    script=script.replace("$pcm = $reader.ReadBytes($length)",'[Console]::Error.WriteLine("NEXT")\n        $pcm = $reader.ReadBytes($length)')
    model=root/"synthetic-decoder"
    model.mkdir(exist_ok=True)
    with ZipFile("build/generated/offline-speech/dev/mobsear/client/words-win-x64.zip") as z: z.extractall(model)
    files = sorted(root.glob("miss-*.pcm"))
    data = bytearray((root / "registry.bin").read_bytes())
    hints=b"pikachu\nlopunny\nenderman\nlanturn\nspider\ncave spider\npoponee\nbuneary\nguardian\nowl guardian\nendermite\nhoglin\nzoglin"
    data.extend(struct.pack("<ii",0,len(hints))+hints)
    for file in files:
        clip = file.read_bytes()
        data.extend(struct.pack("<i",len(clip)) + clip)
    shell = Path(os.environ["SystemRoot"])/"System32/WindowsPowerShell/v1.0/powershell.exe"
    proc = subprocess.run([str(shell),"-NoProfile","-NonInteractive","-Command",script],
                          input=data,capture_output=True,timeout=180,check=True,
                          env=dict(os.environ,MOBSEAR_LOCAL_WORD_MODEL=str(model)))
    for file,output in zip(files,proc.stderr.decode("utf8","replace").split("NEXT")[1:]):
        print("GENERATED_MENTION_TRACE",file.stem," / ".join(output.strip().splitlines()),flush=True)
