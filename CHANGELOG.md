# Changelog

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
