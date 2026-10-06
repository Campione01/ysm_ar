# Third-Party Material

YSM AR itself, the package `ysmar`, is licensed under the Apache License 2.0; see [LICENSE](LICENSE).

The repository and the released jar also hold material of other projects. Its licence texts and notices are kept where the jar ships them, under [`src/main/resources/META-INF/licenses`](src/main/resources/META-INF/licenses). [`README.txt`](src/main/resources/META-INF/licenses/README.txt) in that folder describes the contents of the jar in full.

| What | Where | Terms |
|---|---|---|
| Java bindings of the Yes Steve Model native library, package `com.elfmcys.ysm`, 38 sources | `src/main/java/com/elfmcys/ysm` | Apache License 2.0: `LICENSE-yes-steve-model.txt`, `NOTICE-yes-steve-model.md`. 26 files are unchanged; 11 are modified copies that say so in their first two lines, with `wrapper-edits.diff` as the complete difference; one file, `LegacyImport.java`, is not from that project and says so. |
| Yes Steve Model native library for Windows x64 | `src/main/resources/ysm_ar/native/ysm.dll` | Apache License 2.0: `LICENSE-yes-steve-model-native.txt`. Built from commit `e9ac1624` of that project with the changes in `native-local-fixes.patch`. |
| Third-party components inside the native library | part of `ysm.dll` | `THIRD_PARTY_LICENSES.md`, the inventory kept by the Yes Steve Model project, and the licence texts in `third-party/`. |

[BUILDING.md](BUILDING.md) says how the native library was built.
