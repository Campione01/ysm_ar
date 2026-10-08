# Changelog

## 0.2.4

- Folder models are taken over: a model that lies below `config/yes_steve_model/custom` as a folder of plain files, with a manifest (`ysm.json`) or in the old layout (`main.json` with its textures next to it), is read directly and drawn like a packed `.ysm` file. New setting `takeover.folder_models` (default `true`).
- A folder model is taken over when everything in it is of a kind that was compared with what Yes Steve Model 2.6.5 itself makes of the same folder (13 folders, 42 model files): cubes with a texture rectangle per face or with box UV, with rotation and inflate, `mirror` together with box UV, a negative size on one axis of a cube with six faces (as it is, or turned positive by inflate), one side turned inside out by inflate on a cube whose one face lies across it, a cube without faces on a bone without children, one or several textures, normal and specular maps. Anything else stays with Yes Steve Model, and the status line says what it was.
- A texture of a folder may be a PNG of any kind the format defines (palette, grey, 16 bits per channel, interlaced). Such a file is taken apart before it is decoded: only the header, the palette with its transparency and the picture data are handed on, and a file whose picture data is not exactly as long as its header says is refused.
- Raw model files are taken over: a `.ysm` file that is the archive of a model folder (it starts with `YSGP` and the version 1 or 2) is opened with the archive reader of the native library and read in memory by the rules of a folder. Nothing is written to disk. `takeover.folder_models` does not cover these files; `takeover.deny` does.
- Built-in models stay with Yes Steve Model. So does a custom folder whose id is also the id of a built-in model (`default`) or the name of a built-in pack.
- The status line of a model that is not taken over gives the actual reason instead of one text for all; a folder model is listed with `folder=`. A model id has to be written exactly as its folder is named; a folder whose name ends in `.ysm` is not read.
- Limits for folders: `ysm.json` up to 1 MiB, the model file up to 16 MiB, 66 MiB per model in all, PNG textures up to 4096x4096, at most 64 textures, 8,192 bones, 65,536 cubes, 262,144 faces; a file that a link leads to outside the model folder is not read.
- Shading as in Yes Steve Model 2.6.5: a bone that an animation scales by different factors along its axes comes out brighter or darker, as 2.6.5 draws it. Without a shader pack such a bone is drawn in one piece per direction of its faces, with the shade in the vertex colour; the meshes for these pieces are built within `prewarm.ms_per_frame`, and until a bone has all of them it is drawn in one piece. With a shader pack nothing changes. A model that would need more than 4096 such pieces in one draw is drawn as before. New setting `takeover.legacy_shading` (default `true`).
- Where two bones have a face in exactly the same place, the face of the later bone shows, as in Yes Steve Model; before, which of the two showed could change from one start of the game to the next. While both bones are drawn they are handed to Accelerated Rendering as client meshes, which keep the order of the bones. New setting `takeover.face_order` (default `true`).
- A model that no entity has shown for 30 minutes is dropped from memory and read again when an entity shows it; until it is there Yes Steve Model draws that entity, as at the first use of a model. New setting `models.idle_seconds` (default `1800`; `0`: never). `/ysm_ar status` counts these as `dropped_idle`. The meshes Accelerated Rendering holds are not given back by this: a model that is read again finds them there, and nothing is dropped while Accelerated Rendering is set not to merge equal meshes.
- `takeover.non_finite` now defaults to `prune`: a bone whose value is no finite number is left out with the bones below it, as Yes Steve Model 2.6.5 itself was seen to draw it. `refuse` is still there, and a settings file that already holds `takeover.non_finite=refuse` keeps it.
- A model is bound to its file or folder as they are when it is first drawn; for a folder, every file the model is read from counts. Later bindings of the same model are held against that (the status says "unchanged ok ... since this model was first bound"). A model that is being drawn keeps being drawn when its files are edited, and `/ysm model reload` binds the new content. A model whose files were edited and that is then dropped as idle stays with Yes Steve Model until that reload.
- A file that cannot be read when its model is first drawn (held by another program, access denied) is tried again after 1, 2 and 4 seconds; only then is the model refused, and that is remembered for 10 seconds, not as a refusal of the file.
- A model that was refused for what it holds is remembered for the file or folder as it was: further texture names read nothing, and the "is not used" line is logged once per session. A refusal of one texture is remembered for that texture only.
- The thread that reads models is started again by the next request when it has ended. A load that runs out of memory fails with "not enough memory to read the model"; this does not protect the rest of the game from the same shortage. Loading takes about 12 MB less Java heap for a 4096x4096 texture.
- A binding that was taken back shows what its identity guards said as "when it was bound: ...". With more than 256 model objects of Yes Steve Model in use at once, those beyond the 256 stay with Yes Steve Model, with that reason.
- The extracted native library stays open, for readers only, from the check of its content until it is loaded; a first extraction that takes longer than the retry time no longer counts as a failure. The library itself is the one of 0.2.3.
- The line about mixins of other mods on the renderer classes of Yes Steve Model: a mixin configuration with a comma before a closing bracket is read as Mixin reads it, and configurations a mod file names in its manifest are asked too.
- The mod names its author. The licence folder of the jar has the copyright line of the mod, the acknowledgement libjpeg-turbo asks for, and the three licence texts that were missing: those of the JNI headers, of MiniOgg and of STX `CStringView`. Each of the 29 components that the inventory of the native library lists as part of the library now has its text in the jar.
- A settings file of an earlier version has no lines for the new settings: their defaults hold, and the lines can be added by hand.

## 0.2.3

- The native library was rebuilt from a neutral build path. It behaves as before; the extracted file is now `ysm-41b9bbca51ab.dll`.
- Up to 64 texture names per model are kept at a time (before: 8). When all of them were used within the last second, a further name stays with Yes Steve Model instead of pushing another one out.
- The outcome for a model id and texture name is logged once per session, and at most 256 such lines are written; `/ysm_ar status` still shows every binding. The file of a model id is looked at once per second at most.
- New setting `takeover.non_finite=refuse|prune` (default `refuse`). When the attribute check refuses the bone values of a model, the status line of the model names the bone, the slot and the value.
- The line about mixins of other mods on the renderer classes of Yes Steve Model now finds them; "none" is only said when every mixin configuration of the installed mods could be read.
- Chat output of `/ysm_ar status` shows `&` where a model id or texture name has a section sign.
- The texture size guard no longer asks the graphics card for the size of a texture name that was reserved but never bound.
- Left-over temporary files of an extraction of the native library are removed after a week, like the extracted libraries of other versions.

## 0.2.2

Not released on its own; its changes are part of 0.2.3.

- Late read: the body of a taken-over model is drawn when Yes Steve Model itself reads the bone values and the pose (the scale call of its pre-render step), not at the visibility test before it. What another mod changes just before the draw, a lifted or carried maid for example, is in the picture. Setting `takeover.late_read`.
- Identity guards: a local model file is only used when its texture size, locator bone chains, bone pivots and rest rotations agree with what Yes Steve Model has loaded, and the file is unchanged. Setting `takeover.identity=strict|names`.
- A player whose texture a listener of Yes Steve Model's player texture event has replaced stays with Yes Steve Model.
- Model ids and texture names, which may come from a server, are cleaned before they are logged, and their number per model is limited.
- Bones whose name starts with `ysmGlow` are drawn at full light, as Yes Steve Model 2.6.5 shows them. Setting `takeover.legacy_glow`.
- Renders outside the level (screens, previews) are counted apart from the fallbacks.
- Extracted native libraries of other versions that nobody has used for a week are removed.

## 0.2.1

- The native library leaves out a sound stream that fails its validation instead of refusing the whole model, so more model files load.
- A model with one bone whose name is empty loads; the bone gets a name of this mod.
- Several game instances can extract the native library at the same time.
- A bone with 65,536 or more cubes of one kind is refused instead of being drawn incompletely.
- The settings file may begin with a byte order mark. A file that cannot be read as UTF-8 switches the mod off for that load instead of being misread.
- The model cache and the submitter refuse calls from threads other than the render thread.
- New setting `takeover.pivot_abs=exclude|provide` for models that use the Molang function `bone_pivot_abs`.

## 0.2.0

- First version with the take-over: next to Yes Steve Model 2.6.5, the bodies of its player models, and of Touhou Little Maid maids that use them, are drawn through Accelerated Rendering, while Yes Steve Model keeps animating them and draws everything else.
- Models are baked with the texture coordinate rules of the version the file was exported with (`bake.uv_rules=file`).
