"""Build data-only Whisper Tiny English graphs for the Windows ML runtime supplied by Windows."""
import hashlib
import io
from pathlib import Path
import struct
import subprocess
import os
import sys
import time
import urllib.request

# Build tools live outside the resource tree. A game client never executes this script.
if __name__ == "__main__" and "--compiler" not in sys.argv:
    target = Path(sys.argv[1]).resolve()
    venv = target.parent.parent / "speech-graph-tools"
    python = venv / ("Scripts/python.exe" if os.name == "nt" else "bin/python")
    if not python.exists():
        subprocess.run([sys.executable, "-m", "venv", str(venv)], check=True)
    subprocess.run([str(python), "-m", "pip", "--disable-pip-version-check", "install", "--only-binary=:all:",
                    "numpy==1.26.4", "onnx==1.17.0", "protobuf==5.29.5"], check=True)
    subprocess.run([str(python), __file__, str(target), "--compiler"], check=True)
    sys.exit()

import numpy as np
import onnx
from onnx import TensorProto as T, helper as op, numpy_helper

MODEL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/5359861c739e955e79d9a303bcbc70fb988958b1/ggml-tiny.en-q5_1.bin"
AUDIO = 512  # Same short-utterance context as the original decoder.
DIM = 384
HEADS = 6


def fetch(url, digest):
    for attempt in range(3):
        try:
            with urllib.request.urlopen(url, timeout=90) as response:
                data = response.read(40_000_001)
            break
        except OSError:
            if attempt == 2:
                raise
            time.sleep(2 ** attempt)
    if hashlib.sha256(data).hexdigest() != digest:
        raise ValueError("Unexpected speech data digest")
    return data


def unpack(root):
    f = io.BytesIO(fetch(MODEL, "c77c5766f1cef09b6b7d47f21b546cbddd4157886b3b5d6d4f709e91e66c7c2b"))
    def ints(n):
        return struct.unpack("<"+"i"*n, f.read(4*n))
    if ints(12) != (0x67676d6c, 51864, 1500, 384, 6, 4, 448, 384, 6, 4, 80, 1009):
        raise ValueError("Unexpected Tiny English data")
    assert ints(2) == (80, 201)
    (root/"mel-filters.f32").write_bytes(f.read(80*201*4))
    n, = ints(1)
    assert n == 50257
    with (root/"english.vocab").open("wb") as vocab:
        for _ in range(n):
            size, = ints(1)
            vocab.write(struct.pack(">H", size))
            vocab.write(f.read(size))
    weights = {}
    while f.tell() < len(f.getbuffer()):
        ndim, nlen, kind = ints(3)
        shape = ints(ndim)[::-1]
        name = f.read(nlen).decode("ascii")
        count = int(np.prod(shape))
        if kind in (0, 1):
            dtype = "<f4" if kind == 0 else "<f2"
            weights[name] = np.frombuffer(f.read(count*np.dtype(dtype).itemsize), dtype=dtype).reshape(shape)
        elif kind == 7:
            blocks = np.frombuffer(f.read(count//32*24), dtype=np.uint8).reshape(-1, 24).copy()
            scale = blocks[:, :2].copy().view("<f2").astype(np.float32)
            offset = blocks[:, 2:4].copy().view("<f2").astype(np.float32)
            high = np.unpackbits(blocks[:, 4:8], axis=1, bitorder="little")
            low = np.concatenate((blocks[:, 8:] & 15, blocks[:, 8:] >> 4), axis=1)
            weights[name] = ((low | (high << 4))*scale + offset).astype("<f2").reshape(shape)
        else:
            raise ValueError("Unsupported GGML tensor")
    return weights


class Graph:
    def __init__(self, weights):
        self.weights, self.nodes, self.constants = weights, [], {}
        self.inputs, self.outputs, self.cache = [], [], {}

    def input(self, name, shape, kind=T.FLOAT):
        self.inputs.append(op.make_tensor_value_info(name,kind,shape))
        return name

    def output(self, name, source, shape):
        self.nodes.append(op.make_node("Identity",[source],[name]))
        self.outputs.append(op.make_tensor_value_info(name,T.FLOAT,shape))

    def constant(self, name, values):
        if name not in self.constants:
            self.constants[name] = numpy_helper.from_array(np.ascontiguousarray(values),name=name)
        return name

    def op(self, kind, *args, **attrs):
        out = "node"+str(len(self.nodes))
        self.nodes.append(op.make_node(kind,list(args),[out],**attrs))
        return out

    def weight(self, name):
        if name not in self.cache:
            values=self.weights[name]
            if name.endswith(".bias"):
                values=values.reshape(-1)
            if name=="encoder.positional_embedding":
                values=values[:AUDIO]
            value=self.constant(name,values)
            self.cache[name]=self.op("Cast",value,to=T.FLOAT) if values.dtype==np.float16 else value
        return self.cache[name]

    def num(self, value):
        return self.constant("number"+str(value),np.array([value],dtype="<f4"))

    def reshape(self,x,shape):
        return self.op("Reshape",x,self.constant("shape"+".".join(map(str,shape)),np.array(shape,dtype="<i8")))

    def linear(self,x,p):
        y=self.op("MatMul",x,self.op("Transpose",self.weight(p+".weight"),perm=[1,0]))
        return self.op("Add",y,self.weight(p+".bias")) if p+".bias" in self.weights else y

    def norm(self,x,p):
        centered=self.op("Sub",x,self.op("ReduceMean",x,axes=[-1],keepdims=1))
        var=self.op("ReduceMean",self.op("Mul",centered,centered),axes=[-1],keepdims=1)
        y=self.op("Div",centered,self.op("Sqrt",self.op("Add",var,self.num(.00001))))
        return self.op("Add",self.op("Mul",y,self.weight(p+".weight")),self.weight(p+".bias"))

    def gelu(self,x):
        erf=self.op("Erf",self.op("Mul",x,self.num(2**-.5)))
        return self.op("Mul",self.op("Mul",x,self.num(.5)),self.op("Add",erf,self.num(1.)))

    def heads(self,x,keys=False):
        return self.op("Transpose",self.reshape(x,[0,-1,HEADS,64]),perm=[0,2,3,1] if keys else [0,2,1,3])

    def attention(self,x,p,mask=None,kv=None):
        q=self.heads(self.linear(x,p+".query"))
        k,v=kv if kv else (self.heads(self.linear(x,p+".key"),True),self.heads(self.linear(x,p+".value")))
        scores=self.op("Mul",self.op("MatMul",q,k),self.num(.125))
        if mask: scores=self.op("Add",scores,mask)
        values=self.op("MatMul",self.op("Softmax",scores,axis=3),v)
        return self.linear(self.reshape(self.op("Transpose",values,perm=[0,2,1,3]),[0,-1,DIM]),p+".out")

    def block(self,x,p,mask=None,kv=None):
        x=self.op("Add",x,self.attention(self.norm(x,p+".attn_ln"),p+".attn",mask))
        if kv:
            x=self.op("Add",x,self.attention(self.norm(x,p+".cross_attn_ln"),p+".cross_attn",kv=kv))
        return self.op("Add",x,self.linear(self.gelu(self.linear(self.norm(x,p+".mlp_ln"),p+".mlp.0")),p+".mlp.2"))

    def save(self,path):
        # Windows 10's inbox WinML uses opset 9. IR 3 declares initializer shapes as graph inputs.
        defaults = [op.make_tensor_value_info(t.name, t.data_type, list(t.dims)) for t in self.constants.values()]
        graph=op.make_graph(self.nodes,path.stem,self.inputs+defaults,self.outputs,initializer=list(self.constants.values()))
        model=op.make_model(graph,opset_imports=[op.make_opsetid("",9)],ir_version=3,producer_name="Mobs Hear U")
        onnx.checker.check_model(model)
        onnx.save(model,path)


def build(root):
    root.mkdir(parents=True,exist_ok=True)
    weights=unpack(root)
    enc=Graph(weights); x=enc.input("mel",[1,80,2*AUDIO])
    for n,stride in ((1,1),(2,2)):
        p="encoder.conv"+str(n)
        x=enc.gelu(enc.op("Conv",x,enc.weight(p+".weight"),enc.weight(p+".bias"),pads=[1,1],strides=[stride]))
    x=enc.op("Add",enc.op("Transpose",x,perm=[0,2,1]),enc.weight("encoder.positional_embedding"))
    for i in range(4): x=enc.block(x,"encoder.blocks."+str(i))
    x=enc.norm(x,"encoder.ln_post")
    for i in range(4):
        p="decoder.blocks."+str(i)+".cross_attn"
        enc.output("k"+str(i),enc.heads(enc.linear(x,p+".key"),True),[1,6,64,AUDIO])
        enc.output("v"+str(i),enc.heads(enc.linear(x,p+".value")),[1,6,AUDIO,64])
    enc.save(root/"encoder.onnx")

    dec=Graph(weights)
    tokens=dec.input("tokens",["beams","words"],T.INT64)
    pos=dec.input("positions",["words"],T.INT64)
    mask=dec.input("mask",[1,1,"words","words"])
    emb=dec.weight("decoder.token_embedding.weight")
    x=dec.op("Add",dec.op("Gather",emb,tokens,axis=0),dec.op("Gather",dec.weight("decoder.positional_embedding"),pos,axis=0))
    for i in range(4):
        k=dec.input("k"+str(i),[1,6,64,AUDIO]);v=dec.input("v"+str(i),[1,6,AUDIO,64])
        x=dec.block(x,"decoder.blocks."+str(i),mask,(k,v))
    last=dec.op("Gather",dec.norm(x,"decoder.ln"),dec.input("last",[1],T.INT64),axis=1)
    logits=dec.op("MatMul",last,dec.op("Transpose",emb,perm=[1,0]))
    dec.output("logits",logits,["beams",1,51864])
    dec.save(root/"decoder.onnx")
    for filename,url,digest in (
        ("LICENSE-Whisper.txt","openai/whisper/v20250625/LICENSE","b5d65a59060e68c4ff940e1eddfa6f94b2d68fdf58ed7f4dd57721c997e35e9d"),
        ("LICENSE-whisper.cpp.txt","ggml-org/whisper.cpp/v1.8.3/LICENSE","e562a2ddfaf8280537795ac5ecd34e3012b6582a147ef69ba6a6a5c08c84757d"),
    ):
        (root/filename).write_bytes(fetch("https://raw.githubusercontent.com/"+url,digest))


if __name__=="__main__":
    build(Path(sys.argv[1])/"dev/mobsear/client/speech/model")
