# Building YSM AR

There is no Gradle project. `build.py` calls `javac --release 21` and `jar` directly. NeoForge 1.21.1 runs Mojang names, so the classes are used as compiled, without remapping. The native library is not built here: it is in the repository as a file (see below).

## What Is Needed

- Python 3.8 or newer.
- A JDK 21 or newer. The released jar was built with javac 21.0.11.
- A Minecraft 1.21.1 installation with NeoForge 21.1.x: its `libraries` folder and the version manifest (JSON) of its NeoForge profile. The manifest has to list the libraries of the game; when a launcher keeps the vanilla libraries in a manifest of their own, give both files as a list.
- The Minecraft and NeoForge classes in one jar with Mojang names. The ModDevGradle plugin of NeoForge writes such a jar to `build/moddev/artifacts/neoforge-<version>-merged.jar` of any mod project that is set up for that NeoForge version.
- The jar of Accelerated Rendering 1.0.14 (`acceleratedrendering-1.0.14-1.21.1-alpha.jar`).

Yes Steve Model is not needed to build: the mod reaches it by reflection only. Only the Windows x64 natives are taken from the manifest, and the script has only been run on Windows.

## Steps

1. Copy `build.example.json` to `build.local.json` and enter your paths. A path is absolute or relative to this folder; forward slashes work on Windows. The entry `_about` only explains the others and may stay or go. `build.local.json` is ignored by git.
2. Run `python build.py` (or `python build.py --quiet`).
3. The mod is `out/ysm_ar-0.2.3.jar`.

The script first checks that `src/main/resources/ysm_ar/native/ysm.dll` is the expected file (size and SHA-256). It then compiles the bindings under `src/main/java/com/elfmcys` against JOML, fastutil, LWJGL, the Log4j API and Netty alone, and after that the package `ysmar` against those classes and the game.

The script reads its inputs and writes only into `out/` and `work/` of this folder. Keep the repository in a folder whose path has ASCII characters only: javac is handed the list of sources in a file, which it reads in the platform code page.

## Reproducing The Released Jar

`ysm_ar-0.2.3.jar` (5,060,048 bytes, SHA-256 `de696c8a0f38a759954529229a33b1ff08a48bd65f89e5a63d864078714a44a9`) was built from these sources with javac 21.0.11 against NeoForge 21.1.227 and Accelerated Rendering 1.0.14-1.21.1-alpha. With the same inputs `build.py` produces the same 216 files byte for byte, the manifest included; the jar file itself differs, because every entry carries the time it was written.

`.gitattributes` stores everything under `src/main` byte for byte. The bindings and the licence texts keep the CRLF line endings they came with, and the released jar holds the licence texts in exactly that form.

## The Bindings

The 38 sources under `src/main/java/com/elfmcys/ysm` are the Java bindings of the native library, taken from the Yes Steve Model source tree (https://github.com/YesSteveModel), commit `74c53b58b2f9`, Apache License 2.0. 26 files are unchanged. 11 files are modified copies; each says so in its first two lines, and `src/main/resources/META-INF/licenses/wrapper-edits.diff` is the complete difference to the originals. One file, `natives/legacy/LegacyImport.java`, is not from that tree and says so. The package names are kept because the native library binds its methods to these class names.

## The Native Library

`src/main/resources/ysm_ar/native/ysm.dll` (12,110,336 bytes, SHA-256 `41b9bbca51ab41befba610188270cf812d7bd3b6510129ab03897d8dca175a33`) is the official open-source Yes Steve Model native library for Windows x64 (https://github.com/YesSteveModel/YesSteveModel-Native, Apache License 2.0), built from commit `e9ac1624` with the changes in `src/main/resources/META-INF/licenses/native-local-fixes.patch`. That patch is the complete difference to the originals, 7 files:

- five build fixes for the MSVC compiler, which change no behaviour;
- one change of behaviour in the legacy importer, switched by the build option `YSM_AR_TOLERATE_INVALID_SOUND` (on in this build): a sound stream that fails its Ogg validation is left out instead of the whole model being refused, the way the unchanged importer already leaves out sounds of an unknown family.

It was built with Microsoft Visual C++ 19.44 (toolset 14.44, x64, Release, static runtime), CMake 4.3.2, Ninja 1.12.1, NASM 2.16.03 and Conan 2.29.1 by the two documented commands of that source tree, `bootstrap.cmd setup` and `bootstrap.cmd build native`, with all 29 dependency packages built from their sources in the same run. The source tree and the package cache lay on a drive root of their own, so the source file names the compiler puts into the library start with that neutral root; `src/main/resources/META-INF/licenses/README.txt` names it.

To rebuild it: check out that commit, apply the patch, run the two commands. A rebuild does not give the same bytes, so `NATIVE_SIZE` and `NATIVE_SHA256` in `build.py` and the numbers in the licence `README.txt` have to follow the new file. The third-party components of the library and their licences are listed in `src/main/resources/META-INF/licenses/THIRD_PARTY_LICENSES.md` and `third-party/`.

## Tests

The offline test suite is not part of this repository.
