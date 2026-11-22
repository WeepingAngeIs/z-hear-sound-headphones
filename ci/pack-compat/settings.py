"""Recreate only the public AI controls under test and check what the disposable server loaded."""
import json
from pathlib import Path
import sys
import tomllib

CONFIG = Path("build/pack-compat/game/config")
# Table and setting names come from the public mods' config specs. Other options are upstream defaults.
TABLES = {
    "mobsear-server.toml": {(): {"voiceRadius": 32, "hordeEnabled": False}},
    "enhancedai/common.toml": {(): {"Enable Mobs": True}},
    "enhancedai/server.toml": {
        (): {"Enable Endermen": True, "Enable Spiders": True},
        ("Endermen",): {"Enable Look anger cone": True},
        ("Endermen", "Look anger cone"): {"Look anger cone": 0.07},
        ("Spiders",): {"Enable Stuck fix": True},
        ("Spiders", "Stuck fix"): {"Stuck fix": True},
        ("Mobs",): {"Enable Custom targeting": True, "Enable Climbing": True, "Enable Targeting": True,
                    "Enable Teleport anti-cheese": True, "Enable Teleport to target": True, "Enable Web thrower": True},
        ("Mobs", "Targeting", "Follow range override"): {"Minimum": 32.0, "Maximum": 48.0},
        ("Mobs", "Targeting", "Better nearby targeting"): {"Enable": True, "Player only": False, "Target chance": 7},
        ("Mobs", "Targeting", "Xray range override"): {"Minimum": 4.0, "Maximum": 12.0},
        ("Mobs", "Targeting", "Alert"): {"Range": 32, "Chance for mob to be able to": 0.5},
        ("Mobs", "Teleport anti-cheese"): {"Chance": 1.0},
        ("Mobs", "Web thrower"): {"Chance": 0.1},
    },
    "aiimprovements-common.toml": {
        ("entity", "general"): {"allow_remove_calls": True, "enable_call_bubbling": True},
        **{("entity", "mob", key): {key: enabled, "is_allowlist": False, "filter_list": []}
           for key, enabled in [("remove_look_goal", False), ("remove_look_random", False), ("replace_look_controller", True)]},
    },
    "dangerous-common.toml": {("Spider Speed Settings",): {"spiderSpeedMultiplier": 1.25}},
}

def values(profile):
    for filename, sections in TABLES.items():
        if profile == "ai" or filename == "mobsear-server.toml":
            yield CONFIG / filename, sections

def seed(profile):
    for path, sections in values(profile):
        path.parent.mkdir(parents=True, exist_ok=True)
        lines = []
        for keys, options in sections.items():
            if keys:
                lines.append("[" + ".".join(json.dumps(k) for k in keys) + "]")
            lines.extend(f"{json.dumps(k)} = {json.dumps(v)}" for k, v in options.items())
            lines.append("")
        path.write_text("\n".join(lines), encoding="utf-8")
    if profile == "ai":
        (CONFIG / "lithium.properties").write_text("# Use the normal upstream defaults.\n", encoding="utf-8")

def verify(profile):
    count = 0
    for path, sections in values(profile):
        with path.open("rb") as stream:
            config = tomllib.load(stream)
        for keys, options in sections.items():
            node = config
            for key in keys:
                node = node[key]
            for key, expected in options.items():
                if node[key] != expected:
                    raise ValueError(f"Unmatched active setting {path.name} {keys} {key}: {node[key]!r}")
                count += 1
    if profile == "ai":
        lithium = (CONFIG / "lithium.properties").read_text(encoding="utf-8")
        if any(line.strip() and not line.lstrip().startswith(("#", "!")) for line in lithium.splitlines()):
            raise ValueError("Unexpected Lithium override")
    print(f"PACK_CONFIG_MATCH profile={profile} matchedKeys={count} lithiumOverrides=0")

if __name__ == "__main__":
    if sys.argv[1] == "prepare":
        seed(sys.argv[2])
    else:
        verify(sys.argv[2])
