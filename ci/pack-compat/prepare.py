"""Provision the disposable server from upstream release files; no mod binaries are committed."""
from concurrent.futures import ThreadPoolExecutor
from hashlib import sha256
from pathlib import Path
import os
import sys
from urllib.parse import quote
from urllib.request import urlopen

# Public CurseForge file IDs and SHA-256 hashes of the matching installed files.
FILES = [
    (9509081, "enhancedai-4.2.7.2.jar", "47aaca8ff8166bfe498d191baf461371f458628a3f7ed66e10348ff3b54d882c"),
    (9548012, "insanelib-2.5.3.1.jar", "fb286a57fbd878e781fedfd64de04f4185db8d1b67189e0b226626d0ac9547ea"),
    (5426792, "AI-Improvements-1.21-0.5.3.jar", "795618769ff1ac782750be9e8faaf3e3d212f1687c3baab98c1282a98a63a41e"),
    (8330365, "lithium-neoforge-0.15.4+mc1.21.1.jar", "488f33216030c1cb0baa33e59de3d657d08d5795a6c2080c8c8bbbc133fc8757"),
    (8330971, "Dangerous NeoForge - 1.21.1 v1.5.2.jar", "689212c0bd6ee8e02ab5084ffe70b463b3dc0846786c59020a648da14647fad4"),
    (9292580, "buffmobs-4.0.0+mc1.21.1-neoforge.jar", "4cf34244246030738b57f46cae9ad21070bfa12a2c71156cd738dfbf4c349a01"),
    (6130429, "infernalmobs-1.21.1.3NF.jar", "331ce9d68ca4b1358368a825532666a8cf3ca2a0de2f09c953ebe8ba86b58738"),
]

def fetch(entry):
    file_id, name, expected = entry
    url = f"https://edge.forgecdn.net/files/{file_id // 1000}/{file_id % 1000}/{quote(name)}"
    with urlopen(url, timeout=45) as response:
        data = response.read()
    if sha256(data).hexdigest() != expected:
        raise ValueError(f"Upstream checksum mismatch: {name}")
    (mods / name).write_bytes(data)
    print(f"PACK_PUBLIC_FILE {file_id} {name} {expected}", flush=True)

if __name__ == "__main__":
    if os.getenv("GITHUB_ACTIONS") != "true" or sys.argv[1:] not in (["clean"], ["ai"]):
        raise SystemExit("Expected a hosted clean or ai profile")
    mods = Path("build/pack-compat/game/mods")
    mods.mkdir(parents=True, exist_ok=True)
    if sys.argv[1] == "ai":
        with ThreadPoolExecutor(max_workers=4) as pool:
            list(pool.map(fetch, FILES))
