# YSM AR

YSM AR (`ysm_ar`) is a client-side NeoForge 1.21.1 mod for players who run Yes Steve Model 2.6.5 together with Accelerated Rendering. It draws the body of the models Yes Steve Model shows (players, and Touhou Little Maid maids that use such a model) through the compute path of Accelerated Rendering: one static mesh per bone on the GPU, one bone matrix per frame. Yes Steve Model keeps animating the model and draws everything else: layers, held items, the name tag. With the companion mod AR Velocity (`ar_velocity`) installed as well, the models get per-bone motion vectors.

This is an unofficial community mod. It is not affiliated with or endorsed by the Yes Steve Model project or the authors of Accelerated Rendering, Iris or Super Resolution. Please do not report problems with it to those projects.

## Why

Temporal upscalers and temporal anti-aliasing (DLSS, FSR, TAA) build each frame from the frames before it and need a motion vector for every pixel. A shader pack can only give them one for a moving model when it is told, per vertex, how far the vertex went since the previous frame. Yes Steve Model draws its models on a path of its own, on which no such motion reaches the shader pack, so its models ghost when they move.

Accelerated Rendering moves entity vertices on the GPU, and AR Velocity provides the motion vectors for everything drawn that way. YSM AR is the piece in between: it hands the bodies of Yes Steve Model's models to Accelerated Rendering, bone by bone.

## Supported Environment

- Windows x64. The native library in the jar is built for that platform only; elsewhere the mod stays inert.
- Minecraft 1.21.1, NeoForge 21.1.x, Java 21. Client only.
- Yes Steve Model `2.6.5-neoforge+mc1.21.1`, exactly this build. Next to any other build the mod applies no mixin and takes nothing over.
- Accelerated Rendering 1.0.14 (`1.0.14-1.21.1-alpha`) with its entity acceleration on. Models with partly transparent parts also need its option `force_translucent_acceleration` enabled.
- For motion vectors: AR Velocity 0.1.3 and what it needs: Iris 1.8.x, Super Resolution 0.9.2 with `enable_iris_extension = true` in the root of its config, and a shader pack that reads `at_velocity` behind the macros `SR_IRIS_EXT_ENABLED` / `SR_IRIS_EXT_VELOCITY`.
- Optional: Touhou Little Maid. Maids that use a Yes Steve Model model are taken over like players.

Without Accelerated Rendering the mod changes nothing.

## Installation

1. Install the mods listed above.
2. Place `ysm_ar-0.2.3.jar` in the client's `mods` directory. SHA-256 of the released jar: `de696c8a0f38a759954529229a33b1ff08a48bd65f89e5a63d864078714a44a9`.
3. Start the game. `config/ysm_ar.properties` is written with the defaults on the first start.

With the first model the native library is extracted to `<game>/ysm_ar/native/ysm-41b9bbca51ab.dll`, or to `%LOCALAPPDATA%\ysm_ar\native` when the path of the game directory has non-ASCII characters. The log then holds, among others, these lines:

```text
ysm_ar: mixins: LivingEntityRenderer.isBodyVisible applied, PoseStack.scale applied (Yes Steve Model: 2.6.5-neoforge+mc1.21.1, needed: 2.6.5-neoforge+mc1.21.1)
ysm_ar: take-over front-end ready for yes_steve_model 2.6.5-neoforge+mc1.21.1: 12 required public members resolved by name and descriptor; ...
ysm_ar: take-over late read: on, the hook on PoseStack.scale is applied (2 of 2 test calls answered): ...
ysm_ar: take-over binding ready: <model id> [texture <name>], N bones matched by name, ...
```

## Settings And Commands

`/ysm_ar status` shows the settings, the state of the native library and of Accelerated Rendering, and one line per model: `READY`, or `STAYS WITH YSM` with the reason. `/ysm_ar reload` reads the settings file again and drops the loaded models and meshes.

The settings are in `config/ysm_ar.properties`, a UTF-8 text file:

| Key | Default | Meaning |
|---|---|---|
| `enabled` | `true` | `false` switches the whole mod off. |
| `takeover.enabled` | `true` | The take-over of the models Yes Steve Model draws. `false` leaves every model to it. |
| `takeover.deny` | empty | Model ids, as `/ysm model set` takes them, that always stay with Yes Steve Model; separated by `;`. |
| `takeover.pivot_abs` | `exclude` | Models whose animations use the Molang function `bone_pivot_abs`: `exclude` leaves them to Yes Steve Model, `provide` takes them over and writes the values of that function. |
| `takeover.late_read` | `true` | Bone values and pose are read as late as Yes Steve Model reads them itself, so that what another mod changes just before the draw is in the picture. A change from `false` to `true` takes a restart. |
| `takeover.identity` | `strict` | `strict`: a local model file is only used when its content agrees with what Yes Steve Model has loaded (texture size, locator bone chains, bone pivots and rest rotations, file unchanged). `names`: bone names and texture name alone decide. |
| `takeover.legacy_glow` | `true` | Bones whose name starts with `ysmGlow` are drawn at full light, as Yes Steve Model 2.6.5 shows them. |
| `takeover.non_finite` | `refuse` | A model whose animation gives a bone a value that is no finite number: `refuse` leaves it to Yes Steve Model while it has such a value, `prune` takes it over and leaves that bone and the bones below it out. |
| `prewarm.ms_per_frame` | `2` | Milliseconds per frame for building the bone meshes of a model before its first draw; until all are built, Yes Steve Model draws the model. `0` builds everything in one frame. |
| `mesh_type` | `server` | How Accelerated Rendering keeps the bone meshes: `server` (uploaded once) or `client` (copied on every draw). |
| `mirrored_bones` | `as_baked` | Bones whose final transform mirrors: `as_baked` draws them as the official renderer does, `reversed` keeps their outer faces visible. |
| `ar_version_check` | `true` | Work only with the Accelerated Rendering build the mod was written against. |
| `bake.uv_rules` | `file` | How texture coordinates are rounded to texels: `file` applies the rules of the version the model file was exported with, which is what Yes Steve Model 2.6.5 shows; `official` applies the newest rules to every file. |
| `stats.interval_seconds` | `0` | Above 0: one log line with counters every so many seconds. |
| `takeover.debug_side_by_side` | `0` | For picture comparisons: not 0 moves the model drawn by this mod that many pixels to the right while Yes Steve Model keeps drawing its own. |
| `standalone.model` | empty | Stand-alone test front-end, off while empty: the absolute path of one `.ysm` file that is drawn in place of every entity of one type. It works without Yes Steve Model. |
| `standalone.texture`, `standalone.entity`, `standalone.animation`, `standalone.scale` | empty, `minecraft:husk`, `wave`, empty | Texture key, replaced entity type, test animation (`wave` or `rest`) and scale of that front-end. |

## How It Works

- Yes Steve Model posts the normal render event of an entity and then asks the vanilla method `LivingEntityRenderer.isBodyVisible` whether to draw the body. A mixin of this mod on that vanilla method decides there. Everything that can speak against the take-over is checked before anything is hidden; only then is the answer "not visible", for which Yes Steve Model draws no body.
- In its next step Yes Steve Model scales the pose stack by the scale of the model. A second mixin, on the vanilla `PoseStack.scale`, draws the body when that call returns: with the bone values and the pose Yes Steve Model's own draw would have used.
- The geometry comes from your own model file in `config/yes_steve_model/custom`. It is read with the open-source native library of the Yes Steve Model project, which is in the jar, and turned into one static mesh per bone that Accelerated Rendering keeps on the GPU.
- Per frame the mod reads the bone values Yes Steve Model has computed (through public members, by reflection), lets the native library turn them into one matrix per bone and gives Accelerated Rendering one draw per visible bone. AR Velocity sees each bone as a renderer of its own, which is where the per-bone motion vectors come from.
- A local file stands in for a model of Yes Steve Model only when the bone names match exactly and, by default, the identity guards agree.

## What It Deliberately Does Not Do

- It does not patch, transform or unpack Yes Steve Model. Its two mixins are on vanilla classes. Of Yes Steve Model only public members are used, by reflection: values are read, and one method is called that marks the entity as drawn. Only with `takeover.pivot_abs=provide` are values written, into the array Yes Steve Model keeps for `bone_pivot_abs`.
- It does not bypass any check of Yes Steve Model: a body is only drawn for a model Yes Steve Model itself has loaded and is showing for that entity.
- It writes no model data to disk. Decoded content stays in memory, and log lines carry counts and reasons, not content.
- It leaves every model it cannot match exactly to Yes Steve Model, and falls back to Yes Steve Model's own draw whenever something is not as expected.

## Measured Results

Measured on one machine, not a benchmark:

- Under DLSS through Super Resolution 0.9.2 at ratio 3.0, a moving entity kept about 98 % of the sharpness it has when it stands still with the motion vectors, against about 85 % without them.
- In a pack of about 640 mods the frame rate with the take-over on was not lower than with it off.
- A bound model costs about 19 MB of memory.

## Known Limitations

- Only the one build of Yes Steve Model named above is supported; the member names the mod reads belong to that build.
- Only models from `.ysm` files in `config/yes_steve_model/custom` that the open-source importer can read are taken over. Built-in models, folder models and files it refuses stay with Yes Steve Model.
- These also stay with Yes Steve Model: models whose animations use `bone_pivot_abs` and models with bone values that are no finite numbers (both unless the setting says otherwise), an entity that appears glowing, a player whose texture another mod replaced, and every render outside the level (inventory, screens, previews).
- The first frames after a model or texture switch are drawn by Yes Steve Model while the bone meshes are built.
- Every texture of a model is a loaded copy of its own. While models with large textures (4096 x 4096) load, memory use can rise by more than a gigabyte for a short time, and a reload gives little of it back.
- At most 64 texture names of one model are kept at a time.
- The identity guards cannot tell two files apart that have the same bones, pivots, rotations and texture size and differ only in their geometry or pixels.
- Whether the normal and specular maps of a PBR model reach the shader pack on this path has not been verified.

## Build

See [BUILDING.md](BUILDING.md), which also says how the native library was built. The changes of each version are in [CHANGELOG.md](CHANGELOG.md).

## License And Credits

YSM AR is licensed under the [Apache License 2.0](LICENSE).

- The native library and its Java bindings are the open-source work of the Yes Steve Model project (Apache License 2.0). The library is built with a small patch, which is in the jar and in this repository.
- The native library contains third-party components. Their licences are listed in [THIRD-PARTY.md](THIRD-PARTY.md) and kept under [`src/main/resources/META-INF/licenses`](src/main/resources/META-INF/licenses).
- Accelerated Rendering is by Argon4W. The motion vectors come from AR Velocity, which builds on the Iris velocity extension of Super Resolution by 187J3X1-114514.

## 简体中文

YSM AR（`ysm_ar`）是一个仅客户端的 NeoForge 1.21.1 模组，面向同时使用 Yes Steve Model 2.6.5 与 Accelerated Rendering 的玩家。它把 Yes Steve Model 模型的身体改由 Accelerated Rendering 在 GPU 上绘制（每根骨骼一个静态网格，每帧一个骨骼矩阵），动画、图层、手持物品等仍由 Yes Steve Model 负责。配合 AR Velocity，模型可以获得逐骨骼的运动矢量，减轻 DLSS、FSR、TAA 下的拖影。

- 需要：Windows x64、Minecraft 1.21.1、NeoForge 21.1.x、Yes Steve Model `2.6.5-neoforge+mc1.21.1`（必须是这一版本）、Accelerated Rendering `1.0.14-1.21.1-alpha`；运动矢量还需要 AR Velocity 以及它所需的 Iris、Super Resolution 和光影包。
- 安装：把 `ysm_ar-0.2.3.jar` 放进客户端的 `mods` 文件夹。设置文件是 `config/ysm_ar.properties`，命令是 `/ysm_ar status` 和 `/ysm_ar reload`。
- 本模组不修改、不转换、不解包 Yes Steve Model，不绕过它的任何检查，也不向磁盘写入模型数据；凡是无法精确匹配的模型，一律交还给 Yes Steve Model 绘制。
- 许可证：Apache-2.0。原生库及其 Java 绑定来自 Yes Steve Model 项目的开源代码（Apache-2.0），第三方许可证见 `src/main/resources/META-INF/licenses`。
- 这是非官方的社区模组，与 Yes Steve Model、Accelerated Rendering、Super Resolution 等项目无关，也未获得它们的认可；出现问题请不要向这些项目反馈。
