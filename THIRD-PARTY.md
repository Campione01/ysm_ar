# Third-Party Material

YSM AR itself, the package `ysmar`, is licensed under the Apache License 2.0; see [LICENSE](LICENSE).

The repository and the released jar also hold material of other projects. Its licence texts and notices are kept where the jar ships them, under [`src/main/resources/META-INF/licenses`](src/main/resources/META-INF/licenses). [`README.txt`](src/main/resources/META-INF/licenses/README.txt) in that folder describes the contents of the jar in full.

| What | Where | Terms |
|---|---|---|
| Java bindings of the Yes Steve Model native library, package `com.elfmcys.ysm`, 38 sources | `src/main/java/com/elfmcys/ysm` | Apache License 2.0: `LICENSE-yes-steve-model.txt`, `NOTICE-yes-steve-model.md`. 26 files are unchanged; 11 are modified copies that say so in their first two lines, with `wrapper-edits.diff` as the complete difference; one file, `LegacyImport.java`, is not from that project and says so. |
| Yes Steve Model native library for Windows x64 | `src/main/resources/ysm_ar/native/ysm.dll` | Apache License 2.0: `LICENSE-yes-steve-model-native.txt`. Built from commit `e9ac1624` of that project with the changes in `native-local-fixes.patch`. |
| Third-party components inside the native library | part of `ysm.dll` | `THIRD_PARTY_LICENSES.md` is the inventory kept by the Yes Steve Model project. It lists 29 components as part of the library, and each of them has its licence text in `third-party/`. |
| The rules by which model folders and raw model files are read | `src/main/java/ysmar/core/ModelFolder.java` | Written for this mod by the rules of the package `com.elfmcys.ysm.format.parser` of the Yes Steve Model source tree, commit `74c53b58b2f9` (Apache License 2.0: `LICENSE-yes-steve-model.txt`, `NOTICE-yes-steve-model.md`). No file of that package is copied. |

## The Texts In `third-party/`

The folder holds 36 texts. 33 are the ones the Yes Steve Model project ships, for components of the native library and for the Java-side components named in its notice file. Three were added here for parts of the library that project ships no text for:

| File | Component | Text |
|---|---|---|
| `jni` | The headers of the Java Native Interface the library is compiled against (GPL-2.0-only with the Classpath exception) | The notice the headers carry, and the `LICENSE` file of OpenJDK 21 that the notice refers to. |
| `miniogg` | MiniOgg (0BSD) | The licence as it stands at the end of its source file in the native source tree. |
| `stx-cstringview` | STX `CStringView` (MIT), `modules/core/src/c_string_view.h` of the native source tree | The `LICENSE` file of https://github.com/lamarrr/STX (git blob `451d170fde549be22c3bf3f717257a08e23ee967`), byte for byte. |

The native library contains libjpeg-turbo, whose terms ask for this acknowledgement, which the `README.txt` of the jar carries as well: "This software is based in part on the work of the Independent JPEG Group."

[BUILDING.md](BUILDING.md) says how the native library was built.
