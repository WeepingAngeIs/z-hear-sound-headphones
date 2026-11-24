"""Inspect the bytes that are actually distributed, including embedded archives and loader metadata."""
import argparse
import bz2
import gzip
import hashlib
import io
import json
import lzma
from pathlib import Path, PurePosixPath
import stat
import tarfile
import tomllib
import zipfile

RUNNABLE = set(".exe .dll .sys .com .scr .msi .bat .cmd .ps1 .psm1 .psd1 .sh .bash .vbs .vbe .js .jse .wsf .py .cs .so .dylib .appimage .lnk .wasm".split())
ARCHIVE = set(".zip .jar .war .aar .tar .gz .tgz .bz2 .xz .7z .rar .cab".split())
LIMIT = 512 * 1024 * 1024


def audit(raw, expected_version=None):
    report = dict(sha256=hashlib.sha256(raw).hexdigest(), bytes=len(raw), entries=0, archives=[],
                  findings=[], dependencies={}, embedded_dependencies=[])
    remaining = LIMIT

    def finding(path, reason):
        report["findings"].append(dict(entry=path, reason=reason))

    def visit(data, path, depth):
        nonlocal remaining
        if depth > 12 or len(data) > remaining or report["entries"] > 100_000:
            raise ValueError("Archive inspection limit exceeded")
        remaining -= len(data)
        name = PurePosixPath(path.split("!/")[-1])
        ext = name.suffix.lower()
        reasons = set()
        if ext in RUNNABLE:
            reasons.add("executable or script extension " + ext)
        if len(data) >= 64 and data[:2] == b"MZ":
            offset = int.from_bytes(data[60:64], "little")
            if data[offset:offset+4] == b"PE\0\0" and offset + 24 <= len(data):
                flags = int.from_bytes(data[offset+22:offset+24], "little")
                reasons.add("Windows PE DLL" if flags & 0x2000 else "Windows PE executable")
            else:
                reasons.add("DOS MZ executable header")
        if data.startswith((b"\x7fELF", b"\0asm", b"\xfe\xed\xfa\xce", b"\xce\xfa\xed\xfe", b"\xfe\xed\xfa\xcf", b"\xcf\xfa\xed\xfe", b"\xca\xfe\xba\xbf", b"\xbe\xba\xfe\xca", b"\xbf\xba\xfe\xca")):
            reasons.add("native or WebAssembly executable magic")
        if data.startswith(b"\xca\xfe\xba\xbe"):
            # Java's class magic overlaps a fat Mach-O. A Java 21 class has a class version
            # and nonempty constant pool where a fat binary stores its architecture count.
            if ext != ".class" or len(data) < 12 or not 45 <= int.from_bytes(data[6:8], "big") <= 65 or int.from_bytes(data[8:10], "big") < 1:
                reasons.add("unexpected class or Mach-O magic")
        first = data[:256].removeprefix(b"\xef\xbb\xbf").lstrip().lower()
        if first.startswith((b"#!", b"@echo off", b"@echo on", b"@setlocal")):
            reasons.add("script header")
        if data.startswith((b"7z\xbc\xaf\x27\x1c", b"Rar!", b"MSCF")):
            reasons.add("unsupported nested archive")
        for reason in sorted(reasons):
            finding(path, reason)
        if zipfile.is_zipfile(io.BytesIO(data)):
            report["archives"].append(path)
            with zipfile.ZipFile(io.BytesIO(data)) as z:
                seen = set()
                for item in z.infolist():
                    if item.is_dir():
                        continue
                    label = path + "!/" + item.filename
                    parts = PurePosixPath(item.filename.replace("\\", "/"))
                    if parts.is_absolute() or ".." in parts.parts or item.filename.casefold() in seen or (item.flag_bits & 1) or stat.S_ISLNK(item.external_attr >> 16):
                        raise ValueError("Ambiguous or inaccessible archive entry " + label)
                    seen.add(item.filename.casefold())
                    if item.file_size > remaining:
                        raise ValueError("Archive too large " + label)
                    value = z.read(item)
                    report["entries"] += 1
                    if depth == 0 and item.filename == "META-INF/neoforge.mods.toml":
                        meta = tomllib.loads(value.decode())
                        mods = meta.get("mods", [])
                        if len(mods) != 1 or mods[0]["modId"] != "mobsear":
                            raise ValueError("Unexpected declared mod identity")
                        report["mod_version"] = mods[0]["version"]
                        report["dependencies"] = {dep["modId"]: dict(type=dep["type"], version=dep["versionRange"])
                                                  for dep in meta["dependencies"]["mobsear"]}
                    if item.filename.casefold() == "meta-inf/jarjar/metadata.json":
                        for lib in json.loads(value)["jars"]:
                            report["embedded_dependencies"].append(lib["path"])
                            if lib["path"] not in z.namelist():
                                finding(label, "declared embedded dependency is absent")
                    visit(value, label, depth+1)
            return
        for magic, opener in [(b"\x1f\x8b", gzip.GzipFile), (b"BZh", bz2.BZ2File), (b"\xfd7zXZ\x00", lzma.LZMAFile)]:
            if data.startswith(magic):
                report["archives"].append(path)
                with (opener(fileobj=io.BytesIO(data)) if opener is gzip.GzipFile else opener(io.BytesIO(data))) as src:
                    unpacked = src.read(remaining+1)
                visit(unpacked, path+"!/expanded", depth+1)
                return
        if data[257:262] == b"ustar" or ext == ".tar":
            report["archives"].append(path)
            with tarfile.open(fileobj=io.BytesIO(data), mode="r:") as tar:
                for member in tar:
                    if member.isdir():
                        continue
                    if not member.isfile() or member.size > remaining:
                        raise ValueError("Unsupported TAR member: "+member.name)
                    report["entries"] += 1
                    visit(tar.extractfile(member).read(), path+"!/"+member.name, depth+1)
            return
        if ext in ARCHIVE:
            finding(path, "unreadable or unsupported archive")

    visit(raw, "mod.jar", 0)
    if expected_version:
        if report.get("mod_version") != expected_version:
            finding("META-INF/neoforge.mods.toml", "wrong mod version")
        if report["dependencies"] != {"minecraft": {"type": "required", "version": "[1.21.1]"},
                                      "neoforge": {"type": "required", "version": "[21.1.249,21.2)"}}:
            finding("META-INF/neoforge.mods.toml", "unexpected runtime dependency")
        if report["embedded_dependencies"]:
            finding("META-INF/jarjar/metadata.json", "unexpected embedded dependency")
    return report


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("jar", type=Path)
    parser.add_argument("--version")
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    result = audit(args.jar.read_bytes(), args.version)
    encoded = json.dumps(result, indent=2)
    print(encoded)
    if args.report:
        args.report.write_text(encoded+"\n", encoding="utf8")
    raise SystemExit(1 if result["findings"] else 0)
