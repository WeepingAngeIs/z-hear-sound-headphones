"""Evaluate direct sentence examples strictly and publish recall on longer, continuous speech."""
from collections import defaultdict

def failures(rows):
    wrong=[]
    long_speech=defaultdict(list)
    for row in rows:
        label=row["phrase"]
        if label not in ("sentence-long","sentence-long-creeper"):
            if (label.startswith("sentence-") or label=="low-money") and row["actual"]!=row["expected"]:
                wrong.append((row["fixture"],row["mode"],row["actual"]))
            continue
        if row["actual"] not in ("neutral",row["expected"]):
            wrong.append((row["fixture"],row["mode"],row["actual"]))
        long_speech[row["voice"],row["mode"]].append(row)
    assert long_speech
    for (voice,mode),items in long_speech.items():
        assert {r["phrase"] for r in items}=={"sentence-long","sentence-long-creeper"}
        correct=[r["phrase"] for r in items if r["actual"]==r["expected"]]
        print("LONG_SPEECH_RECALL", voice, mode, str(len(correct))+"/"+str(len(items)), "recognized="+",".join(correct), flush=True)
        if not correct: wrong.append((voice,mode,"no name recovered from either long passage"))
    return wrong
