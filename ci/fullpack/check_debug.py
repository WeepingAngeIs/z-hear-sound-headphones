"""Inspect only the mod's fixed-format lines in a disposable generated-speech Minecraft log."""
import re
from difflib import SequenceMatcher

def verify_capture(root):
    def audible(pcm):
        return [pcm[i:i+640] for i in range(0,len(pcm),640) if any(pcm[i:i+640])]
    source=audible((root/"synth/pikachu.pcm").read_bytes())
    assert source
    for trial in (1,3,11,20,21):
        parts=sorted((root/"segments").glob(f"{trial}_pikachu_*.pcm"))
        captured=[frame for file in parts for frame in audible(file.read_bytes())]
        spans=SequenceMatcher(None, source, captured, autojunk=False).get_matching_blocks()
        print("ACOUSTIC_PIKACHU_INPUT", "trial="+str(trial),"original="+str(len(source)),
              "submitted="+str(len(captured)),"spans="+str([(m.a,m.b,m.size) for m in spans if m.size]),flush=True)
        assert captured==source, f"Pikachu trial {trial} lost or changed part of the generated word"
    print("ACOUSTIC_PIKACHU_INPUT_PASS all nonzero frames identical on open mic and push-to-talk",flush=True)

def verify(path):
    enabled = False
    disabled_success = False
    windows = []
    events = []
    for line in path.read_text(encoding="utf8",errors="replace").splitlines():
        if "ACOUSTIC_DEBUG_WINDOW enabled=" in line:
            enabled = "ACOUSTIC_DEBUG_WINDOW enabled=true" in line
            windows.append(enabled)
        if not enabled and "ACOUSTIC_DEBUG_CONFIRM_WHILE_DISABLED " in line and windows:
            disabled_success = True
        if "MOBSEAR_CALL_DEBUG " not in line:
            continue
        assert enabled, "A debug entry was written while disabled"
        match = re.fullmatch(r"event=([a-z_]+) target=(-|[a-z0-9_.-]+:[a-z0-9_./-]+) request=([0-9]+)",
                             line.split("MOBSEAR_CALL_DEBUG ",1)[1])
        assert match, "Unexpected information in a local debug event"
        events.append(match.groups())
    print("ACOUSTIC_DEBUG_OBSERVED", "windows="+str(windows), "offConfirmation="+str(disabled_success),
          "outcomes="+str(sorted(set((e,t) for e,t,_ in events))), flush=True)
    assert windows == [True, False, True] and disabled_success, "Missing live opt-out test"
    assert any(event=="ready" and target=="-" for event,target,_ in events)
    assert any(event=="no_match" and target=="-" for event,target,_ in events)
    for name in ("cobblemon:lopunny", "cobblemon:pikachu"):
        for want in ("detected","heard","no_eligible_mob"):
            matches = [(e,t,i) for e,t,i in events if e==want and t==name]
            assert matches and (want=="detected" or int(matches[0][2])>0), (want,name)
    assert any(e=="game_mode" for e,_,_ in events)
    print("ACOUSTIC_LOCAL_LOG_PASS default-off ready detected no-match confirmed no-eligible game-mode disabled-during-confirmed-call no-transcript",flush=True)
