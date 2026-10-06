ysm_ar 0.2.3: what is in this jar and under which terms

ysmar/**
    The mod itself. Apache License 2.0, see /LICENSE.

com/elfmcys/ysm/**
    Java bindings of the Yes Steve Model native library, copied from the Yes Steve Model source tree
    (https://github.com/YesSteveModel), commit 74c53b58b2f9. Apache License 2.0:
    LICENSE-yes-steve-model.txt, NOTICE-yes-steve-model.md.
    26 files are unchanged. 11 files are modified copies; each says so in its first two lines, and
    wrapper-edits.diff is the complete difference to the originals. One file is not from that tree:
    com/elfmcys/ysm/natives/legacy/LegacyImport.java, which says so in its head.
    The package names are kept because the native library binds its methods to these class names.

ysm_ar/native/ysm.dll
    The Yes Steve Model native library (Windows x64), built from the Yes Steve Model Native source tree,
    commit e9ac1624, with the changes in native-local-fixes.patch. That file is the complete difference to
    the originals (7 files; each changed file says so in its first two lines):
      - five build fixes for the MSVC compiler, which change no behaviour;
      - one change of behaviour, in the legacy importer (modules/legacy/src/v3/container/decoder.cc, switched
        by the build option YSM_AR_TOLERATE_INVALID_SOUND in modules/legacy/CMakeLists.txt, ON in this build):
        a sound stream that fails the Ogg validation is left out, the way the unchanged importer already
        leaves out sounds of an unknown family, instead of the whole model being refused.
    12,110,336 bytes, sha256 41b9bbca51ab41befba610188270cf812d7bd3b6510129ab03897d8dca175a33.
    Built with Microsoft Visual C++ 19.44 (toolset 14.44, x64, Release, static runtime), CMake 4.3.2,
    Ninja 1.12.1, NASM 2.16.03 and Conan 2.29.1 by the two documented commands of that source tree,
    bootstrap.cmd setup and bootstrap.cmd build native, with all 29 dependency packages built from
    their sources in the same run. The source tree and the package cache lay on a drive root of their
    own (N:\nb\src, N:\nb\conan), so the file names the compiler puts into the library start there.
    Apache License 2.0: LICENSE-yes-steve-model-native.txt.
    It contains third-party components. THIRD_PARTY_LICENSES.md is the inventory kept by that project;
    third-party/ holds the licence texts the Yes Steve Model project ships for them and for the Java-side
    components named in its notice file.
