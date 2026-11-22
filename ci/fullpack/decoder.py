"""Time the installed desktop decoder with public registry names and generated speech only."""
from pathlib import Path
import os
import re
import struct
import subprocess
from zipfile import ZipFile


def diagnose(root, mod):
    if os.name != "nt" or os.getenv("RUNNER_ENVIRONMENT") != "github-hosted":
        raise RuntimeError("Hosted Windows only")
    vocab = (root / "public-registry.bin").read_bytes()
    assert len(vocab) == 4 + struct.unpack_from("<I", vocab)[0]
    entries = [line.split(":") for line in vocab[4:].decode("ascii").splitlines()]
    assert all(len(row) == 3 and re.fullmatch(r"[a-z ]+",row[2]) for row in entries)
    expected = {name: int(code) for code, _, name in entries}
    print(f"ACOUSTIC_PUBLIC_REGISTRY labels={len(entries)} bytes={len(vocab)}", flush=True)
    with ZipFile(mod) as jar:
        script = jar.read("dev/mobsear/client/recognize.ps1").decode("utf8").replace("\r\n", "\n")

    # All inserted probes write fixed phases to stderr. The original decision and binary output stay intact.
    script = "$packClock=[Diagnostics.Stopwatch]::StartNew()\n" + script
    phases = {
        "    $engine = [System.Speech.Recognition.SpeechRecognitionEngine]::new($english)": "desktop",
        "    $phrases = [System.Speech.Recognition.Choices]::new()": "registry",
        "    $phrases.Add($words.ToArray())": "choices",
        "    $calls = [System.Speech.Recognition.Grammar]::new($builder)": "construct",
        "    $engine.LoadGrammar($calls)": "load",
        "    $background = [System.Speech.Recognition.DictationGrammar]::new()": "dictation",
        "    $warmup = [System.IO.MemoryStream]::new([byte[]]::new(16000), $false)": "warmup",
        "    $output.WriteByte(82)": "ready",
    }
    for needle, name in phases.items():
        assert script.count(needle) == 1
        script = script.replace(needle, f"[Console]::Error.WriteLine('phase {name} ' + $packClock.ElapsedMilliseconds)\n" + needle)
    catch = "} catch {\n    exit 1"
    assert script.count(catch) == 1
    script = script.replace(catch, "} catch {\n    [Console]::Error.WriteLine('error ' + "
        "$_.Exception.GetBaseException().GetType().Name + ' ' + $_.Exception.GetBaseException().HResult + ' ' + "
        "$_.InvocationInfo.ScriptLineNumber)\n    exit 1")
    names = ("enderman", "pikachu", "lopunny")
    data = bytearray(vocab)
    for name in names:
        clip = (root / "synth" / (name + ".pcm")).read_bytes()
        data.extend(struct.pack("<I",len(clip)))
        data.extend(clip)
    shell = Path(os.environ["SystemRoot"]) / "System32/WindowsPowerShell/v1.0/powershell.exe"
    with subprocess.Popen([str(shell), "-NoLogo", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-Command", script],
                          stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE) as child:
        try:
            decisions, trace = child.communicate(data,timeout=210)
        except subprocess.TimeoutExpired:
            child.kill()
            decisions, trace = child.communicate()
            print("ACOUSTIC_DECODER timeout after 210 seconds",flush=True)
    for entry in trace.decode("ascii",errors="replace").splitlines():
        if re.fullmatch(r"phase [a-z]+ [0-9]+|error [A-Za-z0-9]+ -?[0-9]+ [0-9]+",entry):
            print("ACOUSTIC_DECODER",entry,flush=True)
    print(f"ACOUSTIC_DECODER exit={child.returncode} ready={decisions[:1] == b'R'}",flush=True)
    if len(decisions)==7 and decisions[:1]==b"R":
        for name, code in zip(names,struct.unpack(">HHH",decisions[1:])):
            print(f"ACOUSTIC_DECODER generated_{name}_matched={code==expected.get(name)}",flush=True)
