"""Compare only generated sound, reporting confidence and timing without transcription."""
from difflib import SequenceMatcher
import os
from pathlib import Path
import re
import struct
import subprocess
from zipfile import ZipFile


def compare(root):
    if os.name!="nt" or os.getenv("RUNNER_ENVIRONMENT")!="github-hosted":
        raise RuntimeError("Hosted Windows only")
    earlier=root/"mobsear-0.2.1.jar"
    installed=root/"mobsear-0.3.1.jar"
    corrected,=(root/"candidate").glob("mobsear-*.jar")
    labels=subprocess.check_output([str(Path(os.environ["JAVA_HOME"])/"bin/java.exe"),"--class-path",str(earlier),
        str(Path(__file__).with_name("LegacyLabels.java"))],text=True,timeout=30)
    old={row.split(":")[1]:int(row.split(":")[0]) for row in labels.split("|")}
    public=(root/"public-registry.bin").read_bytes()
    assert len(public)==struct.unpack_from("<I",public)[0]+4
    rows=public[4:].decode("ascii").splitlines()
    small=[row for row in rows if row.split(":")[2] in old]
    samples={name:(root/"synth"/(name+".pcm")).read_bytes() for name in
             ("enderman","enderman-alternate","pikachu","lopunny","lopunny-alternate","hold-on","silence","fan")}
    controls={"silence","fan","hold-on"}
    targets={name:name.removesuffix("-alternate") for name in samples}
    for file in sorted((root/"segments").glob("*.pcm")):
        _,source,index=file.stem.split("_")
        if source not in samples: continue
        clip=file.read_bytes()
        name="captured-"+file.stem
        targets[name]=targets[source]
        samples[name]=clip
        def voiced(pcm):
            return [pcm[i:i+640] for i in range(0,len(pcm),640) if any(pcm[i:i+640])]
        original,submitted=voiced(samples[source]),voiced(clip)
        alignment=SequenceMatcher(a=original,b=submitted,autojunk=False)
        count=sum(size for _,_,size in alignment.get_matching_blocks())
        print(f"ACOUSTIC_CONTINUITY sample={name} originalVoiced={len(original)} submittedVoiced={len(submitted)} identicalInOrder={count} missing={len(original)-count}",flush=True)
    for label,mod,registry in (("earlier",earlier,None),("installed-small",installed,small),
                               ("installed-full",installed,rows),("corrected-full",corrected,rows)):
        legacy=registry is None
        env=dict(os.environ,MOBSEAR_STARTUP_STEPS="0")
        if legacy:
            env["MOBSEAR_GRAMMAR"]=labels
            expected=old
            data=bytearray()
        else:
            expected={row.split(":")[2]:int(row.split(":")[0]) for row in registry}
            encoded=("\n".join(registry)+"\n").encode("ascii")
            data=bytearray(struct.pack("<I",len(encoded))+encoded)
        names=[name for name in samples if targets[name] in expected or targets[name] in controls]
        with ZipFile(mod) as jar:
            script=jar.read("dev/mobsear/client/recognize.ps1").decode("utf8").replace("\r\n","\n")
        # Only the generated-test copy gets these stderr probes. No result text is printed.
        function="""
function Trace-Score($stage,$result) {
    $kind = if ($null -eq $result) {'none'} elseif ($result.Grammar.Name -eq 'mob call') {'command'} else {'dictation'}
    $score = if ($null -eq $result) {'0'} else {$result.Confidence.ToString('0.000',[Globalization.CultureInfo]::InvariantCulture)}
    [Console]::Error.WriteLine(('score {0} {1} {2} {3}' -f $clipNumber,$stage,$kind,$score))
}
$clipNumber=0
"""
        script=function+script
        def after(needle, addition):
            nonlocal script
            assert script.count(needle)==1,needle
            script=script.replace(needle,needle+"\n"+addition)
        after("    if ($null -eq $english) { exit 2 }","    [Console]::Error.WriteLine('culture '+$english.Culture.Name)")
        after("\n        $decision = 0","        $clipNumber++; $clock=[Diagnostics.Stopwatch]::StartNew()")
        after("\n            $result = $engine.Recognize()","            Trace-Score first $result")
        after("\n                    $result = $engine.Recognize()","                    Trace-Score retry $result")
        last="        $output.WriteByte([byte]$decision)" if legacy else "        $output.WriteByte([byte]($decision -band 255))"
        after(last,"        [Console]::Error.WriteLine(('decision {0} {1} {2}' -f $clipNumber,$decision,$clock.ElapsedMilliseconds))")
        for name in names:
            clip=samples[name]
            data.extend(struct.pack("<I",len(clip))+clip)
        shell=Path(os.environ["SystemRoot"])/"System32/WindowsPowerShell/v1.0/powershell.exe"
        p=subprocess.run([str(shell),"-NoLogo","-NoProfile","-NonInteractive","-WindowStyle","Hidden","-Command",script],
                         input=data,capture_output=True,timeout=210,env=env)
        assert p.returncode==0 and p.stdout.startswith(b"R"),"Calibration decoder was unavailable"
        codes=struct.unpack(">"+("B" if legacy else "H")*len(names),p.stdout[1:])
        print(f"ACOUSTIC_CALIBRATION revision={label} labels={len(expected)}",flush=True)
        for line in p.stderr.decode("ascii",errors="replace").splitlines():
            if re.fullmatch(r"culture [A-Za-z-]+",line):
                print("ACOUSTIC_CALIBRATION",label,line,flush=True)
            elif re.fullmatch(r"score [1-9][0-9]* (first|retry) (command|dictation|none) [0-9.]+|decision [1-9][0-9]* [0-9]+ [0-9]+",line):
                bits=line.split()
                print("ACOUSTIC_CALIBRATION",label,names[int(bits[1])-1],bits[0],*bits[2:],flush=True)
        for name,code in zip(names,codes):
            key=targets[name]
            matched=code==expected.get(key,0)
            print(f"ACOUSTIC_CALIBRATION {label} sample={name} expectedDecision={matched}",flush=True)
            if key in controls and label=="corrected-full" and not matched:
                raise AssertionError("Corrected decoder triggered on a negative control")
