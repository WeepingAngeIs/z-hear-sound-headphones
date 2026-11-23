"""Generated clips through the shipped Windows recognizer; synthetic text tracing exists only in this fixture."""
import os
from pathlib import Path
import struct
import subprocess
import sys
import io
import wave
from zipfile import ZipFile

assert os.environ.get("RUNNER_ENVIRONMENT") == "github-hosted" and os.name == "nt"
root = Path(sys.argv[1])
model = root / "nearby-model"
model.mkdir(exist_ok=True)
with ZipFile("build/generated/offline-speech/dev/mobsear/client/words-win-x64.zip") as package:
    package.extractall(model)
data = bytearray((root/"registry.bin").read_bytes())
codes = {int(code):label for code,mode,label in (line.split(":") for line in data[4:].decode().splitlines())
         if mode!="2" and " " not in label}
cases = []
inputs = []
for file in sorted(root.glob("near-*.pcm")):
    voice,label=file.stem[5:].split("_")
    roster={"exact":"raichu pikachu", "charmandor":"charmeleon charmander", "bulbazore":"ivysaur bulbasaur",
            "nidorine":"nidorina nidorino"}.get(label,"lopunny lanturn nidorina nidorino charmander pikachu")
    trials=[(roster,label)]
    if label=="nidorine": trials.append(("nidorino nidorina","reverse"))
    if label in ("bulbazore","charmandor"): trials.append(("nidorina lanturn","out-"+label))
    for nearby,test in trials:
        names="\n".join("+"+s for s in nearby.split()).encode()
        pcm=file.read_bytes()
        data.extend(struct.pack("<ii",0,len(names))+names+struct.pack("<i",len(pcm))+pcm)
        cases.append((voice,test,nearby.split()))
        inputs.append(file.name+"\t"+nearby)
(root/"nearby-input.tsv").write_text("\n".join(inputs),encoding="ascii")
subprocess.run(["javac","--release","21","-cp","ci-classes","-d","ci-classes","ci/speech-focus/NearbySpeechFixture.java"],check=True)
subprocess.run(["java","-cp","ci-classes;src/main/resources;build/generated/offline-speech",
                "dev.mobsear.client.NearbySpeechFixture",str(root)],check=True,timeout=180)
actual=(root/"nearby-actual.txt").read_text().splitlines()
script=Path("src/main/resources/dev/mobsear/client/recognize.ps1").read_text(encoding="utf-8")
script=script.replace("$pcm = $reader.ReadBytes($length)",'[Console]::Error.WriteLine("NEXT"); $pcm = $reader.ReadBytes($length)')
script=script.replace("if ($text.Length -le 500)",'[Console]::Error.WriteLine("decoded="+$text.Trim()+" independent="+($original -join " ")); if ($text.Length -le 500)')
shell=Path(os.environ["SystemRoot"])/"System32/WindowsPowerShell/v1.0/powershell.exe"
worker=root/"trace-known-generated-input.ps1"
worker.write_text(script,encoding="utf-8-sig")
proc=subprocess.run([str(shell),"-NoProfile","-NonInteractive","-ExecutionPolicy","RemoteSigned","-File",str(worker)],input=data,capture_output=True,
                    timeout=180,check=True,env=dict(os.environ,MOBSEAR_LOCAL_WORD_MODEL=str(model)))
assert proc.stdout[0]==82 and len(proc.stdout)==1+2*len(cases)
traces=proc.stderr.decode(errors="replace").split("NEXT")[1:]
hits=set()
guesses=[]
errors=[]
for index,((voice,test,roster),wireText,trace) in enumerate(zip(cases,actual,traces,strict=True)):
    assert "," not in wireText,("multiple decisions",test,wireText)
    wire=int(wireText)
    result=codes.get(wire & 32767,"neutral") if wire else "neutral"
    guessed=bool(wire & 32768)
    print("NEARBY_GENERATED",voice,test,",".join(roster),"result="+result,"approximate="+str(guessed),trace.strip(),flush=True)
    if test in ("charmandor","bulbazore","nidorine","exact"):
        for piece in sorted(root.glob(f"near-segment-{index}-*.pcm")):
            sound=io.BytesIO()
            with wave.open(sound,"wb") as w:
                w.setparams((1,2,16000,0,"NONE","not compressed")); w.writeframes(piece.read_bytes())
            command=[str(model/"whisper-cli.exe"),"-m",str(model/"tiny.en.bin"),"-f","-","-of","pipe-only","-t","3","-nt","-np","-l","en","-bs","5","-ac","512","--prompt","Minecraft, Cobblemon, "+", ".join(s.title() for s in roster)+", Enderman, Lopunny, Hoglin, Zoglin, Pikachu."]
            raw=subprocess.run(command,input=sound.getvalue(),capture_output=True,timeout=20,check=True)
            print("NEARBY_GENERATED_WORDS",voice,test,raw.stdout.decode("utf8","replace").strip(),flush=True)
    if wire>=65534: errors.append((test,"decoder failed"))
    if test=="exact" and result!="pikachu": errors.append((test,result))
    if (test.startswith("out-") or test in ("ordinary","money","lantern")) and guessed: errors.append((test,"unjustified guess",result))
    if test in ("ordinary","money","lantern") and result!="neutral": errors.append((test,result))
    expected={"charmandor":"charmander","bulbazore":"bulbasaur"}.get(test)
    if expected and result not in ("neutral",expected): errors.append((test,result))
    if expected and result==expected: hits.add(voice)
    if expected and result==expected and guessed: guesses.append((voice,test))
    if test in ("nidorine","reverse") and result not in ("neutral",*roster): errors.append((test,result))
assert not errors and guesses and len(hits)>=2,(errors,guesses,hits)
print("NEARBY_SPEECH_PASS voices="+str(len(hits)),"imperfect="+str(guesses),flush=True)
