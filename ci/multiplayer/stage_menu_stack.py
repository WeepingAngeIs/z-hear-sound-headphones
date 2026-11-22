"""Fetch pinned public client mods for the hosted menu compatibility fixture."""
from hashlib import sha512
from pathlib import Path
import sys
from urllib.parse import unquote
from urllib.request import Request, urlopen


CDN = "https://cdn.modrinth.com/data/"
RELEASES = {
    "Wq5SjeWM/versions/Jjr0fpDH/fancymenu_neoforge_3.9.12_MC_1.21.1.jar":
        "b33695b9dd996860084f84739b94695932aa663a056b8fcf0b3aa70cf6d251e802b8ef5aa067a92eecdff7935c2e388ebf9c5ab4bf9438ad0965b556cde9e271",
    "J81TRJWm/versions/stJDU839/konkrete_neoforge_1.9.9_MC_1.21.jar":
        "378bd12e3c32329abbae5cd514e1d1cc2a3cc3a45979fedb1d4e4c0a6732bdcf4b7d1bf7f7a112074bf288957edf86152c9c090a27d1d5de84a9a7825d3d6511",
    "CVT4pFB2/versions/efcdRVZP/melody_neoforge_1.0.10_MC_1.21.jar":
        "3bd96b851a65fa95d92d051851e6a7a415927f718fcb05f0dfb5983eb3ca54edfb842ea85f1497e1b3510b5884181b8f9c6080d2b15642ceadac695011c00ec1",
    "v3CYg2V9/versions/MVgsDsSB/drippyloadingscreen_neoforge_3.1.5_MC_1.21.1.jar":
        "3ef65f88cfa42cca781a91a2490efc890fb1865502370f71d0871d5b33a70f53fc80af9ab99650a3b4a82e1c6a13aa62c1de3dd3b2f674ac18f0e9b9dd49f4d5",
    "AANobbMI/versions/uMOpc5uV/sodium-neoforge-0.8.13%2Bmc1.21.1.jar":
        "4f537696af95411e9daf15795fb5fcbc49913d488a96fa912fa8f1d7db408710995e8a1461190f3cd0f8217bf9e35e9539176592428ed587ca2d7aa84450a390",
    "YL57xq9U/versions/KduFYu4t/iris-neoforge-1.8.14-beta.1%2Bmc1.21.1.jar":
        "33fddb430e16222902eac75b8b3d57c381b0f3cc729f3b56c4628df12ca108f23a0b11e2feb167923d6d14d1fc7e8ee1a977ec421b19d1951bdca74c4092b9c5",
}


def stage(destination):
    destination.mkdir(parents=True, exist_ok=True)
    for path, digest in RELEASES.items():
        request = Request(CDN + path, headers={"User-Agent": "MobsEar/hosted-menu-compatibility"})
        with urlopen(request, timeout=60) as response:
            payload = response.read()
        name = unquote(path.split("/")[-1])
        if sha512(payload).hexdigest() != digest:
            raise ValueError(f"SHA-512 mismatch for {name}")
        (destination / name).write_bytes(payload)
        print(f"Verified official release {name}")


if __name__ == "__main__":
    stage(Path(sys.argv[1]))
