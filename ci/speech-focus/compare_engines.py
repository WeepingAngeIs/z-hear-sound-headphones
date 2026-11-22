"""Explore offline decoders on stock synthesized fixtures on a disposable Windows runner."""
import hashlib
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tarfile
import urllib.request
import wave
import zipfile

def fetch(url, dest, digest=None):
    req = urllib.request.Request(url, headers={"User-Agent":"mobs-hear-u-public-synthetic-check"})
    data = urllib.request.urlopen(req,timeout=90).read()
    if digest and hashlib.sha256(data).hexdigest() != digest: raise ValueError(dest)
    dest.write_bytes(data)

def explore(root):
    import numpy as np
    import sentencepiece as spm
    import sherpa_onnx
    folder = "sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01"
    f = root / "kws.tar.bz2"
    fetch("https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/"+folder+".tar.bz2",f)
    with tarfile.open(f,"r:bz2") as src:
        for member in src:
            if member.isfile() and Path(member.name).suffix in (".model",".txt",".onnx"):
                (root/Path(member.name).name).write_bytes(src.extractfile(member).read())
    print("MODEL", sorted(x.name for x in root.glob("*.onnx")))
    sp = spm.SentencePieceProcessor(model_file=str(root/"bpe.model"))
    print("TOKENS",sp.encode(" PIKACHU",out_type=str),sp.encode(" pikachu",out_type=str))
    raw=(root/"tokens.txt").read_text(encoding="utf8")
    upper="▁A " in raw or "▁THE " in raw
    word = lambda w:w.upper() if upper else w.lower()
    W = root/"whisper.zip"
    fetch("https://github.com/ggml-org/whisper.cpp/releases/download/v1.8.3/whisper-bin-x64.zip",W,"d824b1e37599f882b396e73f1ee0bfd5d0529f700314c48311dcbd00b803321d")
    with zipfile.ZipFile(W) as src:
        for basename in src.namelist():
            if basename.endswith((".exe",".dll")): (root/Path(basename).name).write_bytes(src.read(basename))
    model=root/"tiny.bin"
    fetch("https://huggingface.co/ggerganov/whisper.cpp/resolve/5359861c739e955e79d9a303bcbc70fb988958b1/ggml-tiny.en-q5_1.bin",model,"c77c5766f1cef09b6b7d47f21b546cbddd4157886b3b5d6d4f709e91e66c7c2b")
    names = ["Pikachu","Lopunny","Enderman","Spider","Cave Spider","Mob","Hoglin","Zoglin","Endermite","Poponee","Lanturn","Guardian","Owl Guardian","Lop Bunny","Lo Punny","Pika Chu","Ender Man"]
    rows = [line.split("\t") for line in (root/"sources.tsv").read_text(encoding="utf-8-sig").splitlines()]
    rows = [r for r in rows if r[2].startswith("sentence-") or r[2] in ("ordinary","hanging","mobility","enderman","lopunny") and r[3]=="0"]
    def spotter(labels):
        keywords = root/"keywords.txt"
        lines=[]
        for name in labels:
            ids=sp.encode(word(name))
            if not ids or sp.unk_id() in ids: continue
            lines.append(" ".join(sp.id_to_piece(i) for i in ids)+" @"+name.replace(" ","_"))
        keywords.write_text("\n".join(lines)+"\n",encoding="utf8")
        def network(kind):
            return str(next(x for x in sorted(root.glob(kind+"*.onnx")) if "int8" not in x.name))
        return sherpa_onnx.KeywordSpotter(tokens=str(root/"tokens.txt"),encoder=network("encoder"), decoder=network("decoder"),joiner=network("joiner"),keywords_file=str(keywords),keywords_score=1.5,keywords_threshold=0.18, num_threads=2)
    engine=spotter(names)
    for file,_,phrase,_,expected in rows:
        pcm=(root/(file+".raw")).read_bytes()
        samples=np.frombuffer(pcm,dtype=np.int16).astype(np.float32)/32768.0
        stream=engine.create_stream()
        stream.accept_waveform(16000,np.concatenate([np.zeros(3200,dtype=np.float32),samples,np.zeros(10000,dtype=np.float32)]))
        results=[]
        while engine.is_ready(stream):
            engine.decode_stream(stream)
            res=engine.get_result(stream)
            if res: results.append(str(res));engine.reset_stream(stream)
        wav=io.BytesIO()
        with wave.open(wav,"wb") as writer:
            writer.setparams((1,2,16000,0,"NONE","not compressed"));writer.writeframes(pcm)
        inp=root/"synth.wav";inp.write_bytes(wav.getvalue())
        cli=[str(root/"whisper-cli.exe"),"-m",str(model),"-t","3","-l","en","-nt","-f",str(inp)]
        texts=[]
        for prompt in ([],["--prompt","Minecraft, Cobblemon, Pikachu, Lopunny, Enderman, Lanturn, Spider, Cave Spider, Zoglin, Hoglin, Poponee, Guardian."]):
            out=subprocess.run(cli+prompt,capture_output=True,text=True,timeout=30)
            if out.returncode: raise RuntimeError(out.stderr[:1200])
            texts.append(out.stdout.strip().replace("\n"," "))
        print("GENERATED_ENGINE_COMPARISON",file,expected,"kws="+str(results),"plain="+texts[0],"prompt="+texts[1],flush=True)

if os.environ.get("GITHUB_ACTIONS")!="true": raise SystemExit("Hosted generated inputs only")
explore(Path(sys.argv[1]))
