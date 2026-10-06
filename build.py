#!/usr/bin/env python3
"""Builds ysm_ar without Gradle: plain `javac --release 21` and `jar`.

    python build.py            compile and package out/ysm_ar-<version>.jar, print its listing
    python build.py --quiet    same, without the listing

NeoForge 1.21.1 runs Mojang names, so the classes are used as compiled: no remapping.
Where the JDK and the jars to compile against are is read from build.local.json: build.example.json lists the
entries, BUILDING.md says where each file comes from. Nothing outside this folder is written. Inputs are only
read; the two jars that are given by path are copied into work/lib first, because the JDK tools cannot open a
path with characters outside the platform code page.
"""
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path

MOD_ID = "ysm_ar"
VERSION = "0.2.3"

ROOT = Path(__file__).resolve().parent
SRC = ROOT / "src" / "main" / "java"
RESOURCES = ROOT / "src" / "main" / "resources"
OUT = ROOT / "out"
CLASSES = OUT / "classes"
JAR = OUT / f"{MOD_ID}-{VERSION}.jar"
LIB_CACHE = ROOT / "work" / "lib"

SETTINGS_FILE = ROOT / "build.local.json"
SETTINGS_KEYS = ("jdk_home", "minecraft_libraries", "version_json", "neoforge_merged_jar", "accelerated_rendering_jar")

NATIVE_RESOURCE = "ysm_ar/native/ysm.dll"
NATIVE_SHA256 = "41b9bbca51ab41befba610188270cf812d7bd3b6510129ab03897d8dca175a33"
NATIVE_SIZE = 12110336

_settings = None


def fail(message):
    sys.exit(f"build.py: {message}")


def settings():
    """The entries of build.local.json. A path in it is absolute or relative to this folder."""
    global _settings
    if _settings is None:
        if not SETTINGS_FILE.is_file():
            fail(f"{SETTINGS_FILE.name} is missing: copy build.example.json to that name and enter your paths (see BUILDING.md)")
        try:
            values = json.loads(SETTINGS_FILE.read_text(encoding="utf-8"))
        except ValueError as error:
            fail(f"{SETTINGS_FILE.name} is not valid JSON: {error}")
        if not isinstance(values, dict):
            fail(f"{SETTINGS_FILE.name} has to hold one JSON object")
        missing = [key for key in SETTINGS_KEYS if not values.get(key)]
        if missing:
            fail(f"{SETTINGS_FILE.name} has no value for: {', '.join(missing)}")
        _settings = values
    return _settings


def setting(key):
    return ROOT / settings()[key]


def version_manifests():
    """One file, or several when the launcher keeps the libraries of the game in more than one manifest."""
    value = settings()["version_json"]
    return [ROOT / name for name in ([value] if isinstance(value, str) else value)]


def tool(name):
    path = setting("jdk_home") / "bin" / (name + (".exe" if os.name == "nt" else ""))
    if not path.is_file():
        fail(f"{path} not found: jdk_home has to be the home folder of a JDK 21 or newer")
    return str(path)


def run(command, **kwargs):
    result = subprocess.run(command, text=True, capture_output=True, encoding="utf-8", errors="replace", **kwargs)
    return result.returncode, (result.stdout + result.stderr).strip()


def cached_copy(source, name=None):
    """Copies a file into work/lib (once, refreshed when it changes) and returns the copy."""
    target = LIB_CACHE / (name or source.name)
    if not source.is_file():
        if target.is_file():
            return target
        fail(f"missing input: {source}")
    stat = source.stat()
    if not target.is_file() or target.stat().st_size != stat.st_size or target.stat().st_mtime < stat.st_mtime:
        LIB_CACHE.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, target)
    return target


def merged_jar():
    return cached_copy(setting("neoforge_merged_jar"))


def ar_jar():
    return cached_copy(setting("accelerated_rendering_jar"))


def game_libraries():
    """The library jars of the game, as its version manifest lists them (Windows x64 natives only)."""
    libraries = setting("minecraft_libraries")
    jars = []
    for manifest_file in version_manifests():
        if not manifest_file.is_file():
            fail(f"missing input: {manifest_file}")
        manifest = json.loads(manifest_file.read_text(encoding="utf-8"))
        for library in manifest["libraries"]:
            parts = library["name"].split(":")
            group, artifact, version = parts[:3]
            classifier = parts[3] if len(parts) > 3 else None
            if classifier is not None and classifier not in ("api", "natives-windows"):
                continue
            if group == "ca.weblite":
                continue
            name = f"{artifact}-{version}" + (f"-{classifier}" if classifier else "") + ".jar"
            jar = libraries / group.replace(".", "/") / artifact / version / name
            if not jar.is_file():
                fail(f"missing library: {jar}")
            if jar not in jars:
                jars.append(jar)
    return jars


def library(pattern):
    """The one library jar whose file name matches the regular expression."""
    found = [jar for jar in game_libraries() if re.fullmatch(pattern, jar.name)]
    if len(found) != 1:
        fail(f"{len(found)} libraries match {pattern} in the version manifest, expected one")
    return found[0]


def plain_libraries():
    """What the bindings of the native library need: they are compiled against these alone, without the game."""
    return [library(pattern) for pattern in (r"joml-[\d.]+\.jar", r"fastutil-[\d.]+\.jar", r"lwjgl-[\d.]+\.jar",
                                             r"lwjgl-[\d.]+-natives-windows\.jar", r"log4j-api-[\d.]+\.jar",
                                             r"netty-buffer-[\w.]+\.jar", r"netty-common-[\w.]+\.jar")]


def classpath():
    return [merged_jar(), ar_jar()] + game_libraries()


def javac(sources, out_dir, jars, lint="-Xlint:all"):
    """javac --release 21 into a fresh out_dir. Stops on errors, returns the number of warnings."""
    if out_dir.exists():
        shutil.rmtree(out_dir)
    out_dir.mkdir(parents=True)
    listing = out_dir.with_name(out_dir.name + "-sources.txt")
    listing.write_text("".join(f'"{source.as_posix()}"\n' for source in sources), encoding="utf-8", newline="\n")
    classpath_file = out_dir.with_name(out_dir.name + "-classpath.txt")
    joined = os.pathsep.join(str(jar) for jar in jars).replace("\\", "/")
    classpath_file.write_text(f'-cp "{joined}"\n', encoding="utf-8", newline="\n")
    command = [tool("javac"), "--release", "21", "-encoding", "UTF-8", "-proc:none", "-g", lint,
               "-d", str(out_dir), f"@{classpath_file}", f"@{listing}"]
    code, output = run(command)
    if output:
        print(output)
    if code != 0:
        fail(f"javac failed with exit code {code}")
    return len(re.findall(r"^(?:.*: )?warning: ", output, re.M))


def compile_sources(source_root=SRC, out_dir=CLASSES):
    """The copy of the official bindings is compiled as it is; lint output is asked for our own package only."""
    wrapper = sorted((source_root / "com").rglob("*.java"))
    own = sorted((source_root / "ysmar").rglob("*.java"))
    if not wrapper or not own:
        fail(f"no sources under {source_root}")
    wrapper_out = out_dir.with_name(out_dir.name + "-wrapper")
    javac(wrapper, wrapper_out, plain_libraries(), "-Xlint:none")
    warnings = javac(own, out_dir, [wrapper_out] + classpath(), "-Xlint:all,-classfile,-processing")
    shutil.copytree(wrapper_out, out_dir, dirs_exist_ok=True)
    classes = sorted(out_dir.rglob("*.class"))
    print(f"javac --release 21: {len(wrapper)} wrapper + {len(own)} own sources -> {len(classes)} classes, 0 errors, "
          f"{warnings} warnings in our own package")
    return classes


def check_native():
    native = RESOURCES / NATIVE_RESOURCE
    if not native.is_file():
        fail(f"missing native library: {native}")
    data = native.read_bytes()
    digest = hashlib.sha256(data).hexdigest()
    if len(data) != NATIVE_SIZE or digest != NATIVE_SHA256:
        fail(f"{native} is not the expected build ({len(data)} bytes, sha256 {digest})")
    print(f"native library: {NATIVE_RESOURCE}, {len(data)} bytes, sha256 {digest[:12]}...")


def package():
    manifest = OUT / "MANIFEST.MF"
    manifest.write_text(
        "Manifest-Version: 1.0\n"
        f"Implementation-Title: {MOD_ID}\n"
        f"Implementation-Version: {VERSION}\n",
        encoding="utf-8", newline="\n")
    if JAR.exists():
        JAR.unlink()
    command = [tool("jar"), "--create", "--file", str(JAR), "--manifest", str(manifest),
               "-C", str(CLASSES), ".", "-C", str(RESOURCES), "."]
    code, output = run(command)
    if output:
        print(output)
    if code != 0:
        fail(f"jar failed with exit code {code}")


def list_jar():
    with zipfile.ZipFile(JAR) as jar:
        names = [info for info in jar.infolist() if not info.is_dir()]
        print(f"{JAR} ({JAR.stat().st_size} bytes, {len(names)} files)")
        for info in names:
            print(f"  {info.file_size:8d}  {info.filename}")


def main():
    OUT.mkdir(exist_ok=True)
    code, version = run([tool("javac"), "-version"])
    print(version)
    check_native()
    compile_sources()
    package()
    if "--quiet" in sys.argv[1:]:
        print(JAR)
    else:
        list_jar()


if __name__ == "__main__":
    main()
