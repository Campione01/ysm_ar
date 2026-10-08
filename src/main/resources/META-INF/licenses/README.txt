ysm_ar 0.2.4: what is in this jar and under which terms

ysmar/**
    The mod itself. Copyright 2026 Campione01. Apache License 2.0, see /LICENSE.
    ysmar/core/ModelFolder reads model folders (ysm.json, or main.json with its textures) by the rules of the
    package com.elfmcys.ysm.format.parser of the Yes Steve Model source tree, commit 74c53b58b2f9 (Apache
    License 2.0: LICENSE-yes-steve-model.txt, NOTICE-yes-steve-model.md). No file of that package is copied.
    Raw model files (the archive of such a folder) are opened with the archive reader of the native library,
    through the binding com.elfmcys.ysm.natives.NativeArchive, and read by the same rules.

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
    third-party/ holds the 33 licence texts the Yes Steve Model project ships for them and for the Java-side
    components named in its notice file, and three texts for parts of the library that project ships none for:
    jni (the headers of the Java Native Interface; the text is the LICENSE file of OpenJDK 21), miniogg
    (the text is the one at the end of its source file) and stx-cstringview (STX CStringView,
    modules/core/src/c_string_view.h of the native source tree, MIT License; the text is the LICENSE file
    of https://github.com/lamarrr/STX, git blob 451d170f).
    With these, each of the 29 parts of the library that inventory lists has its text in third-party/.
    The profiler client the build also compiles (Tracy) is not part of the library.
    This software is based in part on the work of the Independent JPEG Group.
