# YSM AR

[简体中文说明](#简体中文)

YSM AR (`ysm_ar`) is a client-side NeoForge 1.21.1 mod for players who run Yes Steve Model 2.6.5 together with Accelerated Rendering. It draws the body of the models Yes Steve Model shows (players, and Touhou Little Maid maids that use such a model) through the compute path of Accelerated Rendering: as a rule one static mesh per bone on the GPU and one bone matrix per frame. Yes Steve Model keeps animating the model and draws everything else: layers, held items, the name tag. With the companion mod AR Velocity (`ar_velocity`) installed as well, the models get per-bone motion vectors.

This is an unofficial community mod. It is not affiliated with or endorsed by the Yes Steve Model project or the authors of Accelerated Rendering, Iris or Super Resolution. Please do not report problems with it to those projects.

It does not patch, transform or unpack Yes Steve Model and does not circumvent its protection or any of its checks. Of that mod it uses public members only, by reflection. Your model files are read with the open-source native library that the Yes Steve Model project itself publishes; model folders are read by this mod, by the rules of that project's open-source code.

## Why

Temporal upscalers and temporal anti-aliasing (DLSS, FSR, TAA) build each frame from the frames before it and need a motion vector for every pixel. A shader pack can only give them one for a moving model when it is told, per vertex, how far the vertex went since the previous frame. Yes Steve Model draws its models on a path of its own, on which no such motion reaches the shader pack, so its models ghost when they move.

Accelerated Rendering moves entity vertices on the GPU, and AR Velocity provides the motion vectors for everything drawn that way. YSM AR is the piece in between: it hands the bodies of Yes Steve Model's models to Accelerated Rendering, bone by bone.

## Supported Environment

- Windows x64. The native library in the jar is built for that platform only; elsewhere the mod stays inert.
- Minecraft 1.21.1, NeoForge 21.1.x, Java 21. Client only.
- Yes Steve Model `2.6.5-neoforge+mc1.21.1`, exactly this build. Next to any other build the mod applies no mixin and takes nothing over.
- Accelerated Rendering 1.0.14 (`1.0.14-1.21.1-alpha`) with its entity acceleration on. Models with partly transparent parts also need its option `force_translucent_acceleration` enabled.
- For motion vectors: AR Velocity 0.1.4, the version this release was checked with, and what it needs: Iris 1.8.x, Super Resolution 0.9.2 with `enable_iris_extension = true` in the root of its config, and a shader pack that reads `at_velocity` behind the macros `SR_IRIS_EXT_ENABLED` / `SR_IRIS_EXT_VELOCITY`.
- Optional: Touhou Little Maid. Maids that use a Yes Steve Model model are taken over like players.

Without Accelerated Rendering the mod changes nothing.

## What Is Taken Over

Your own models below `config/yes_steve_model/custom`, of these three kinds:

- packed `.ysm` files;
- raw model files: a `.ysm` file that is the archive of a model folder (it starts with `YSGP` and the version 1 or 2);
- model folders, with a manifest (`ysm.json`) or in the old layout (`main.json` with its textures next to it).

The built-in models of Yes Steve Model stay with Yes Steve Model, and so does every model the mod cannot match exactly; see [Known Limitations](#known-limitations).

## Installation

1. Install the mods listed above.
2. Place `ysm_ar-0.2.4.jar` in the client's `mods` directory. SHA-256 of the released jar: `eaa7127fb7ef10c6d4b754f81f490aa0361ca44a2f07231bc9cc647c13cd8a7d`.
3. Start the game. `config/ysm_ar.properties` is written with the defaults on the first start. A settings file of an earlier version is kept as it is: settings it has no line for take their defaults, and a line `takeover.non_finite=refuse` in it stays in force.

With the first model the native library is extracted to `<game>/ysm_ar/native/ysm-41b9bbca51ab.dll`, or to `%LOCALAPPDATA%\ysm_ar\native` when the path of the game directory has non-ASCII characters. The log then holds, among others, these lines:

```text
ysm_ar: mixins: LivingEntityRenderer.isBodyVisible applied, PoseStack.scale applied (Yes Steve Model: 2.6.5-neoforge+mc1.21.1, needed: 2.6.5-neoforge+mc1.21.1)
ysm_ar: take-over front-end ready for yes_steve_model 2.6.5-neoforge+mc1.21.1: 12 required public members resolved by name and descriptor; ...
ysm_ar: take-over late read: on, the hook on PoseStack.scale is applied (2 of 2 test calls answered): ...
ysm_ar: take-over binding ready: <model id> [texture <name>], N bones matched by name, ...
```

## Settings And Commands

`/ysm_ar status` shows the settings, the state of the native library and of Accelerated Rendering, how many models are loaded or were dropped as idle, and one line per model: `READY`, or `STAYS WITH YSM` with the reason. `/ysm_ar reload` reads the settings file again and drops the loaded models and meshes.

The settings are in `config/ysm_ar.properties`, a UTF-8 text file:

| Key | Default | Meaning |
|---|---|---|
| `enabled` | `true` | `false` switches the whole mod off. |
| `takeover.enabled` | `true` | The take-over of the models Yes Steve Model draws. `false` leaves every model to it. |
| `takeover.deny` | empty | Model ids, as `/ysm model set` takes them, that always stay with Yes Steve Model; separated by `;`. |
| `takeover.folder_models` | `true` | Models that lie below `config/yes_steve_model/custom` as a folder of plain files are taken over like packed `.ysm` files. `false` leaves them to Yes Steve Model. Raw model files are not covered by this setting; `takeover.deny` covers them. |
| `takeover.pivot_abs` | `exclude` | Models whose animations use the Molang function `bone_pivot_abs`: `exclude` leaves them to Yes Steve Model, `provide` takes them over and writes the values of that function. |
| `takeover.late_read` | `true` | Bone values and pose are read as late as Yes Steve Model reads them itself, so that what another mod changes just before the draw is in the picture. A change from `false` to `true` takes a restart. |
| `takeover.identity` | `strict` | `strict`: a local model is only used when its content agrees with what Yes Steve Model has loaded (texture size, locator bone chains, bone pivots and rest rotations, file or folder unchanged since the model was first bound). `names`: bone names and texture name alone decide. |
| `takeover.legacy_glow` | `true` | Bones whose name starts with `ysmGlow` are drawn at full light, as Yes Steve Model 2.6.5 shows them. |
| `takeover.legacy_shading` | `true` | A bone that an animation scales by different factors along its axes is shaded as Yes Steve Model 2.6.5 shades it without a shader pack: stretched parts get brighter or darker. Such a bone is then drawn in more pieces. `false` shades it like every other bone. With a shader pack this setting changes nothing. |
| `takeover.face_order` | `true` | Where two bones have a face in exactly the same place, the face of the later bone shows, as in Yes Steve Model. While both bones are drawn they are handed over as client meshes, whatever `mesh_type` says. `false`: `mesh_type` alone decides, and which of the two faces shows is left to Accelerated Rendering. |
| `takeover.non_finite` | `prune` | A model whose animation gives a bone a value that is no finite number: `prune` takes it over and leaves that bone and the bones below it out, which is what Yes Steve Model 2.6.5 itself was seen to draw for such a bone; `refuse` leaves the model to Yes Steve Model while it has such a value. |
| `models.idle_seconds` | `1800` | Seconds after which a model that no entity has shown is dropped from memory. It is read again when an entity shows it; until it is there, Yes Steve Model draws that entity. `0` keeps every model until `/ysm_ar reload`. A whole number from 0 to 86400. |
| `prewarm.ms_per_frame` | `2` | Milliseconds per frame for building the bone meshes of a model before its first draw; until all are built, Yes Steve Model draws the model. `0` builds everything in one frame. The meshes for the pieces of a stretched bone (`takeover.legacy_shading`) are built within the same time; until a bone has all of them it is drawn in one piece. |
| `mesh_type` | `server` | How Accelerated Rendering keeps the bone meshes: `server` (uploaded once) or `client` (copied on every draw). With `takeover.face_order` on, bones that share a face go the `client` way while both are drawn. |
| `mirrored_bones` | `as_baked` | Bones whose final transform mirrors: `as_baked` draws them as the official renderer does, `reversed` keeps their outer faces visible. |
| `ar_version_check` | `true` | Work only with the Accelerated Rendering build the mod was written against. |
| `bake.uv_rules` | `file` | How texture coordinates are rounded to texels: `file` applies the rules of the version the model file was exported with, which is what Yes Steve Model 2.6.5 shows; `official` applies the newest rules to every file. |
| `stats.interval_seconds` | `0` | Above 0: one log line with counters every so many seconds. |
| `takeover.debug_side_by_side` | `0` | For picture comparisons: not 0 moves the model drawn by this mod that many pixels to the right while Yes Steve Model keeps drawing its own. |
| `standalone.model` | empty | Stand-alone test front-end, off while empty: the absolute path of one `.ysm` file or model folder that is drawn in place of every entity of one type. It works without Yes Steve Model. |
| `standalone.texture`, `standalone.entity`, `standalone.animation`, `standalone.scale` | empty, `minecraft:husk`, `wave`, empty | Texture key, replaced entity type, test animation (`wave` or `rest`) and scale of that front-end. |

New in 0.2.4: `takeover.folder_models`, `takeover.legacy_shading`, `takeover.face_order` and `models.idle_seconds`; the default of `takeover.non_finite` was `refuse` before.

## How It Works

- Yes Steve Model posts the normal render event of an entity and then asks the vanilla method `LivingEntityRenderer.isBodyVisible` whether to draw the body. A mixin of this mod on that vanilla method decides there. Everything that can speak against the take-over is checked before anything is hidden; only then is the answer "not visible", for which Yes Steve Model draws no body.
- In its next step Yes Steve Model scales the pose stack by the scale of the model. A second mixin, on the vanilla `PoseStack.scale`, draws the body when that call returns: with the bone values and the pose Yes Steve Model's own draw would have used.
- The geometry comes from your own model in `config/yes_steve_model/custom`. A packed `.ysm` file is read by the importer of the open-source native library of the Yes Steve Model project, which is in the jar. A model folder is read by this mod, by the rules of the open-source Yes Steve Model; a raw model file is opened with the archive reader of the same library and then read like a folder, in memory. Either way the native library bakes the model, and each bone becomes a static mesh that Accelerated Rendering keeps on the GPU.
- Per frame the mod reads the bone values Yes Steve Model has computed (through public members, by reflection), lets the native library turn them into one matrix per bone and gives Accelerated Rendering one draw per visible bone. AR Velocity sees each bone as a renderer of its own, which is where the per-bone motion vectors come from.
- Two things go beyond one mesh and one draw per bone. Without a shader pack, a bone that an animation scales by different factors along its axes is drawn in one piece per direction of its faces, each with the shade Yes Steve Model 2.6.5 gives it (`takeover.legacy_shading`). And two bones that have a face in exactly the same place are handed over as meshes that are copied on every draw while both are drawn, because only those keep the order of the bones (`takeover.face_order`).
- A local file or folder stands in for a model of Yes Steve Model only when the bone names match exactly and, by default, the identity guards agree.

## What It Deliberately Does Not Do

- It does not patch, transform or unpack Yes Steve Model. Its two mixins are on vanilla classes. Of Yes Steve Model only public members are used, by reflection: values are read, and one method is called that marks the entity as drawn. Only with `takeover.pivot_abs=provide` are values written, into the array Yes Steve Model keeps for `bone_pivot_abs`. Of its mod file the mod loader is asked one thing: whether a model id is the name of a built-in model; nothing in that file is read.
- It does not bypass any check of Yes Steve Model: a body is only drawn for a model Yes Steve Model itself has loaded and is showing for that entity.
- It writes no model data to disk. Decoded content stays in memory, and log lines carry counts and reasons, not content.
- It leaves every model it cannot match exactly to Yes Steve Model, and falls back to Yes Steve Model's own draw whenever something is not as expected.

## Measured Results

Measured on one machine in one scene, not a benchmark:

- Under a shader pack that reads `at_velocity`, with DLSS at ratio 3.0, a walking model of 414 bones kept 0.875 of the detail of the same model standing with the take-over on, against 0.627 with it off.
- In that scene the draw of this mod cost less CPU time per frame than Yes Steve Model's own draw of the body: 0.26 ms against 0.31 ms for one such model, drawn in two passes.

The build these numbers were measured with differs from the released jar in two licence files only; its classes and its native library are the same bytes.

## Known Limitations

- Only the one build of Yes Steve Model named above is supported; the member names the mod reads belong to that build.
- Built-in models stay with Yes Steve Model. So does a custom folder whose id is also the id of a built-in model (`default`) or the name of a built-in pack, and a `.ysm` file the open-source importer refuses.
- A model folder or raw model file is taken over only when everything in it is of a kind that was compared with what Yes Steve Model 2.6.5 itself makes of a folder. These stay with Yes Steve Model, and the status line says which it was: `mirror` on a cube with a texture rectangle per face or on a bone, `inflate` on a bone, a negative size on more than one axis or together with box UV, a `format_version` other than `1.12.0` and `1.21.0`, a key the reader does not know, a manifest whose `spec` is not 2, a texture that is no PNG. For raw model files there was no export of Yes Steve Model to compare with; the identity guards decide, as for every model.
- These also stay with Yes Steve Model: models whose animations use `bone_pivot_abs` (unless the setting says otherwise), an entity that appears glowing, a player whose texture another mod replaced, and every render outside the level (inventory, screens, previews).
- The first frames after a model or texture switch are drawn by Yes Steve Model while the model is read and the bone meshes are built. The same holds when a model that was dropped as idle is shown again.
- A model whose files are edited while the game runs is drawn from what was read before, like Yes Steve Model's own copy, until `/ysm model reload`. If it is dropped as idle in between, it stays with Yes Steve Model until that reload.
- Without a shader pack, `takeover.legacy_shading` costs draws: for one model of 414 bones in made-up poses in which 93 to 98 bones were stretched, the draw of this mod took about twice as long in an offline measurement. A model that would need more than 4096 pieces in one draw is drawn without that shading.
- With `takeover.face_order` on, the vertices of bones that share a face are copied on every draw while both are drawn. Where such bones have partly transparent faces, their place among the translucent draws changes when the other bone appears or disappears; whether that shows was not looked at in the game.
- Every texture of a model is a loaded copy of its own. While a model with a large texture (4096 x 4096) loads, memory use rises for a short time: about 640 MB of native memory and about 120 MB of Java heap were measured for one such load. The allocator of the native library keeps that memory and uses it again, so dropping a model gives little of it back.
- Dropping an idle model frees this mod's copy only: Accelerated Rendering 1.0.14 keeps the meshes it was given.
- At most 64 texture names of one model and 256 model objects of Yes Steve Model are kept at a time; what goes beyond while all of them are in use stays with Yes Steve Model.
- The identity guards cannot tell two files apart that have the same bones, pivots, rotations and texture size and differ only in their geometry or pixels.
- Whether the normal and specular maps of a PBR model reach the shader pack on this path has not been verified.
- Seen while testing, and not caused by this mod as far as is known: with Touhou Little Maid 1.5.3, Yes Steve Model 2.6.5 can crash the game with a `ConcurrentModificationException` (from inside its `setYsmModel`) when the model of a maid is switched in the maid's model screen. This happened with this mod installed and without it; whether the mod makes it more likely is not known.

## Build

See [BUILDING.md](BUILDING.md), which also says how the native library was built. The changes of each version are in [CHANGELOG.md](CHANGELOG.md).

## License And Credits

YSM AR is licensed under the [Apache License 2.0](LICENSE).

- The native library and its Java bindings are the open-source work of the Yes Steve Model project (Apache License 2.0). The library is built with a small patch, which is in the jar and in this repository.
- Model folders and raw model files are read by the rules of the open-source Yes Steve Model (Apache License 2.0); no file of its parser is copied.
- The native library contains third-party components. Their licences are listed in [THIRD-PARTY.md](THIRD-PARTY.md) and kept under [`src/main/resources/META-INF/licenses`](src/main/resources/META-INF/licenses); each component that is part of the library has its licence text there.
- Accelerated Rendering is by Argon4W. The motion vectors come from AR Velocity, which builds on the Iris velocity extension of Super Resolution by 187J3X1-114514.

## 简体中文

YSM AR（`ysm_ar`）是一个仅客户端的 NeoForge 1.21.1 模组，面向同时使用 Yes Steve Model 2.6.5 与 Accelerated Rendering 的玩家。它把 Yes Steve Model 所显示模型（玩家，以及使用这类模型的 Touhou Little Maid 女仆）的身体改由 Accelerated Rendering 在 GPU 上绘制：通常每根骨骼一个静态网格、每帧每根骨骼一个矩阵。动画、图层、手持物品、名牌等仍由 Yes Steve Model 负责。配合 AR Velocity，模型可以获得逐骨骼的运动矢量，减轻 DLSS、FSR、TAA 下的拖影。

- 声明：这是非官方的社区模组，与 Yes Steve Model、Accelerated Rendering、Iris、Super Resolution 等项目无关，也未获得它们的认可；出现问题请不要向这些项目反馈。本模组不修改、不转换、不解包 Yes Steve Model，不绕过它的保护和任何检查，对它只通过反射使用公开（public）成员；模型文件由 Yes Steve Model 项目自己公开的开源原生库读取；文件夹模型由本模组按该项目开源代码的规则读取。
- 需要：Windows x64、Minecraft 1.21.1、NeoForge 21.1.x、Java 21、Yes Steve Model `2.6.5-neoforge+mc1.21.1`（必须是这一版本）、Accelerated Rendering `1.0.14-1.21.1-alpha`；运动矢量还需要 AR Velocity 0.1.4（本版本是与它一起检查的）以及它所需的 Iris、Super Resolution 和读取 `at_velocity` 的光影包。
- 接管范围：`config/yes_steve_model/custom` 下你自己的模型，共三种——打包的 `.ysm` 文件；原始模型文件（以 `YSGP` 开头、版本为 1 或 2 的 `.ysm` 文件，也就是模型文件夹的归档）；文件夹模型（带清单 `ysm.json` 的布局，或 `main.json` 与贴图放在一起的旧布局）。Yes Steve Model 的内置模型仍由它自己绘制，与内置模型或内置模型包同名的自定义文件夹也是如此。
- 安装：把 `ysm_ar-0.2.4.jar` 放进客户端的 `mods` 文件夹。发布文件的 SHA-256：`eaa7127fb7ef10c6d4b754f81f490aa0361ca44a2f07231bc9cc647c13cd8a7d`。设置文件是 `config/ysm_ar.properties`，命令是 `/ysm_ar status` 和 `/ysm_ar reload`。旧版本留下的设置文件会原样保留：其中没有的设置取默认值，已有的 `takeover.non_finite=refuse` 继续有效。
- 0.2.4 新增的设置：`takeover.folder_models`（默认 `true`，接管文件夹模型；不包括原始模型文件）、`takeover.legacy_shading`（默认 `true`，不使用光影包时，被动画沿各轴按不同比例缩放的骨骼按 Yes Steve Model 2.6.5 的方式着色，这样的骨骼会分成几块绘制）、`takeover.face_order`（默认 `true`，两根骨骼有完全重合的面时显示后一根骨骼的面，与 Yes Steve Model 一致）、`models.idle_seconds`（默认 `1800`，模型在这么多秒内没有被任何实体显示就从内存中释放，再次显示时重新读取；`0` 表示不释放）。`takeover.non_finite` 的默认值由 `refuse` 改为 `prune`：骨骼的数值不是有限数时，略去这根骨骼及其下级骨骼。全部设置见上方的英文表格。
- 实测结果（一台机器、一个场景，不是基准测试）：在读取 `at_velocity` 的光影包下，DLSS 比例为 3.0 时，一个 414 根骨骼的模型在行走时保留了同一模型站立时细节的 0.875（开启接管），关闭接管时为 0.627；本模组绘制身体所用的 CPU 时间少于 Yes Steve Model 自己的绘制（一个这样的模型、两个渲染通道，每帧 0.26 毫秒对 0.31 毫秒）。测量所用的文件与发布文件只差两个许可证文件，类文件和原生库完全相同。
- 已知限制（完整列表见上方 Known Limitations）：只支持上面写明的这一版 Yes Steve Model；文件夹模型或原始模型文件里有未经比对的内容时仍由 Yes Steve Model 绘制，`/ysm_ar status` 会写明原因；外观发光的实体、贴图被其他模组替换的玩家、世界之外的渲染（物品栏、界面、预览）不接管；切换模型或贴图后的最初几帧由 Yes Steve Model 绘制；读取带 4096 x 4096 贴图的模型时内存占用会短时间升高；PBR 模型的法线贴图和高光贴图能否到达光影包尚未验证。
- 测试中见到、就目前所知并非本模组引起的问题：与 Touhou Little Maid 1.5.3 一起使用时，在女仆的模型界面切换模型，Yes Steve Model 2.6.5 有时会抛出 `ConcurrentModificationException`（出自它的 `setYsmModel`）使游戏崩溃。安装和未安装本模组时都出现过；本模组是否会让它更容易出现尚不清楚。
- 本模组不向磁盘写入模型数据；凡是无法精确匹配的模型，一律交还给 Yes Steve Model 绘制。
- 许可证：Apache-2.0。原生库及其 Java 绑定来自 Yes Steve Model 项目的开源代码（Apache-2.0）；第三方许可证见 `THIRD-PARTY.md` 和 `src/main/resources/META-INF/licenses`。
