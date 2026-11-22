"""Inspect only the mod's fixed-format lines in a disposable generated-speech Minecraft log."""
import re

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
