"""Recreate the installed Configtopia binary exclusively from published sources and artifacts."""
from copy import copy
from hashlib import sha256
from io import BytesIO
import os
from pathlib import Path
import subprocess
from zipfile import ZIP_DEFLATED, ZIP_STORED, ZipFile, ZipInfo

BASE_HASH = "e58f31bbc6a31cca48cd7a1b7e2635336313c41d5712fc23ac81a0383c31a10c"
FIRST_HASH = "59cd3adfcd4c07403f81cadd575a2ad01bddf3bb236c449491236bbfd3cf612d"
FINAL_HASH = "e4f34f6a4640fa6c59fc8efe3d2253d0b701bd83371fd960c5d38cfac73ccf2c"
CONTENTS = "a9824624d537e3332e6c0cdf1d9bc61694a346b0363a8192667aee6426a2acf0"
PACKAGE = "dev/unyielding/quests/"
SOURCE = "https://raw.githubusercontent.com/WeepingAngeIs/Server-Jars/3d7ea8da66830b811fb848ebcf63b7ce4b349cdd/unyielding-quests/src/main/java/"
FIRST = ["client/" + name for name in ("CommandCatalog", "ModCommandControls", "ModCommandsBrowser", "ModSettingsScreen",
          "ModSettingsView", "NumericCommandInput")] + ["settings/" + name for name in ("ConfigAudit", "ConfigAuditPolicy",
          "ConfigEditor", "ModSettingsMenu", "NeoForgeSettings", "UwUExcavationEndpoint")]
SECOND = {PACKAGE + name for name in ("settings/ModSettingsMenu", "settings/NeoForgeSettings",
          "settings/WeaponSettingsPresentation", "settings/StoragePlusSettings", "client/ModSettingsScreen",
          "client/ModSettingsView", "client/WeaponTimeSlider", "client/ModCommandControls")}


def combined(source, output, changes, archive=None):
    # Both inputs are read-only. Only the new scratch archive is opened for writing.
    with ZipFile(source) as old, ZipFile(output, "w", ZIP_DEFLATED if archive is None else ZIP_STORED) as new:
        new.comment = old.comment
        existing = set(old.namelist())
        assert len(existing) == len(old.infolist())
        for item in old.infolist():
            data = changes[item.filename] if item.filename in changes else old.read(item)
            new.writestr(copy(item), data)
        for name in sorted(changes.keys() - existing):
            if archive is None:
                new.writestr(ZipInfo(name, old.getinfo("META-INF/MANIFEST.MF").date_time), changes[name], compress_type=ZIP_DEFLATED)
            else:
                new.writestr(copy(archive.getinfo(name)), changes[name])


def reconstruct(published, cache, fetch, member):
    assert sha256(published.read_bytes()).hexdigest()==BASE_HASH
    if os.environ.get("RUNNER_ENVIRONMENT")!="github-hosted": raise RuntimeError("Hosted check only")
    sources=[]
    for family in FIRST:
        file=cache/"src"/(PACKAGE+family+".java")
        file.parent.mkdir(parents=True,exist_ok=True)
        file.write_bytes(fetch(SOURCE+PACKAGE+family+".java"))
        sources.append(file)
    classes=cache/"classes"
    classes.mkdir(exist_ok=True)
    cp=Path("build/public-configtopia-classpath.txt").read_text(encoding="utf-8")
    args=["--release","21","-implicit:none","-proc:none","-encoding","UTF-8","-classpath",str(published)+os.pathsep+cp,
          "-d",str(classes),*map(str,sources)]
    arguments=cache/"compile-args.txt"
    arguments.write_text("\n".join('"'+arg.replace("\\","/")+'"' for arg in args),encoding="utf-8")
    if os.name == 'nt':
        archive=fetch("https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.9%2B10/OpenJDK21U-jdk_x64_windows_hotspot_21.0.9_10.zip")
        assert sha256(archive).hexdigest()=="1c67df516e9795c0b09f5714bfe151da2e3cc988082042f5bbb60d75e4e63fb5"
        with ZipFile(BytesIO(archive)) as zip_jdk:
            zip_jdk.extractall(cache/"compiler")
        del archive
        javac,=(cache/"compiler").glob("*/bin/javac.exe")
    else:
        javac = Path(os.environ["JAVA_HOME"]) / "bin/javac"
        version = subprocess.check_output([str(javac), "-version"], text=True)
        if version.strip() != "javac 21.0.9":
            raise RuntimeError("Expected the same public Java 21.0.9 compiler")
    status=subprocess.run([str(javac),"@"+str(arguments)]).returncode
    fallback="365 exact JARs; public Configtopia release 9708806 substituted"
    if status:
        print("CONFIGTOPIA_PUBLIC_COMPILE unavailable, using the published edition",flush=True)
        return published, fallback
    changes={f.relative_to(classes).as_posix():f.read_bytes() for f in classes.rglob("*.class")}
    assert {k.removesuffix(".class").split("$",1)[0] for k in changes}=={PACKAGE+s for s in FIRST}
    first=cache/"public-first.jar"
    combined(published,first,changes)
    print("CONFIGTOPIA_FIRST_ARCHIVE",sha256(first.read_bytes()).hexdigest(),"expected",FIRST_HASH,flush=True)
    module=member("Server-Jars",44980339689,"configtopia-optional-compiled","UnyieldingQuests-1.6.9.jar",
                  "a6cee4c09da16ae319381187e40faee326bb819a60690324ee8551289135586e")
    final=cache/"public-final.jar"
    with ZipFile(BytesIO(module)) as jar:
        second={name:jar.read(name) for name in jar.namelist() if name.endswith(".class") and name[:-6].split("$",1)[0] in SECOND}
        assert len(second)==31
        combined(first,final,second,jar)
    digest=sha256(final.read_bytes()).hexdigest()
    with ZipFile(final) as jar:
        manifest=sha256()
        for item in sorted(jar.infolist(),key=lambda i:i.filename):
            manifest.update(item.filename.encode()+b"\0"+sha256(jar.read(item)).digest())
    print("CONFIGTOPIA_FINAL_ARCHIVE",digest,"entry digest",manifest.hexdigest(),flush=True)
    if digest==FINAL_HASH:
        assert manifest.hexdigest()==CONTENTS
        return final,"366 of 366 exact JARs, including byte-identical Configtopia"
    if manifest.hexdigest()==CONTENTS:
        return final,"365 exact archives; all 1,765 Configtopia entries match TEST, ZIP envelope differs"
    return published,fallback
