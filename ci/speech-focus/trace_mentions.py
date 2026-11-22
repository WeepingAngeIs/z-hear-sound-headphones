"""Instrument an in-memory copy of the worker only for known, hosted, generated test clips."""
import os
from pathlib import Path
import struct
import subprocess

def show(root):
    assert os.environ.get("RUNNER_ENVIRONMENT") == "github-hosted" and os.name == "nt"
    script = Path("src/main/resources/dev/mobsear/client/recognize.ps1").read_text(encoding="utf8")
    for hook, event in [
        ("$plain = if ($null", '("first=" + $result.Grammar.Name + " " + $result.Text)'),
        ("if ($null -eq $mention -or", '("context=" + $mention.Grammar.Name + " " + $mention.Text)'),
        ("$units = $label.Split", '("slot=" + $label + " words=" + (($mention.Words | ForEach-Object { $_.LexicalForm + "/" + $_.Confidence }) -join " "))'),
        ("if (-not ($startEdge", '("edges=" + $startEdge + "," + $endEdge)'),
        ("if ($null -ne $isolated -and", '("isolated=" + $isolated.Text + "/" + $isolated.Confidence)')]:
        assert script.count(hook) == 1, hook
        script = script.replace(hook, "[Console]::Error.WriteLine(" + event + ")\n            " + hook)
    start = "$pcm = $reader.ReadBytes($length)"
    script = script.replace(start, '[Console]::Error.WriteLine("NEXT")\n        ' + start)
    files = sorted(root.glob("miss-*.pcm"))
    data = bytearray((root / "registry.bin").read_bytes())
    for file in files:
        clip = file.read_bytes()
        data.extend(struct.pack("<i",len(clip)) + clip)
    shell = Path(os.environ["SystemRoot"])/"System32/WindowsPowerShell/v1.0/powershell.exe"
    proc = subprocess.run([str(shell),"-NoProfile","-NonInteractive","-Command",script],
                          input=data,capture_output=True,timeout=120,check=True)
    for file, output in zip(files,proc.stderr.decode("utf8","replace").split("NEXT")[1:]):
        print("GENERATED_MENTION_TRACE",file.stem," / ".join(output.strip().splitlines()),flush=True)
