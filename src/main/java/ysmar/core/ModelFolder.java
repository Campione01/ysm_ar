package ysmar.core;

import com.elfmcys.ysm.buffer.NativeBuffer;
import com.elfmcys.ysm.natives.NativeArchive;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * A model of Yes Steve Model that lies in a folder as plain files, in one of two layouts: with a manifest (ysm.json,
 * which names the model and texture files) or the old one (main.json with its textures next to it). Read here: the
 * player model as the geo model the bake takes, the bytes of one texture and the few settings of the manifest that
 * reach the bake.
 *
 * The rules for the manifest and the cubes are those of the open-source Yes Steve Model (its format.parser package,
 * Apache-2.0). What is read by them was compared with what Yes Steve Model 2.6.5 itself makes of the same folders
 * (its own export of them, imported the usual way): cubes with a texture rectangle on any or none of their faces or
 * with box UV, with or without rotation and inflate, with mirror when they have box UV, with a negative size on one
 * axis when they have a rectangle on all six faces, with faces turned by a right angle (uv_rotation, which 2.6.5
 * leaves out of what it makes of a model), one or several textures, with or without normal and specular maps.
 * Whatever no compared folder holds is refused with its reason rather than read by a rule nobody has checked against
 * 2.6.5: mirror on a cube with a rectangle per face or on a bone, inflate on a bone, a negative size on more than
 * one axis, on a cube with fewer than six faces, together with box UV, with a size of zero or with an inflate that
 * does not turn it positive, box UV with a size of zero or with a side turned inside out, a side turned inside out
 * on a cube that has other faces than the one across it, a side that inflate makes exactly flat, two sizes of zero,
 * a cube without faces on a bone that has children, keys this reader does not know, at any level of the model file.
 * A texture is a PNG picture of any kind the format defines; PngGate holds it to the rules of the format.
 *
 * The same files can lie in a raw model file: the archive of a model folder that Yes Steve Model wrote before it
 * had its packed format (a .ysm file that starts with YSGP and the version 1 or 2). The open-source Yes Steve Model
 * opens such a file with the archive reader of its native library and reads it with the parser it reads a folder
 * with; so does this class, in memory, with every rule a folder is held to. No export of such a file was compared.
 *
 * A folder is input a user edits: every file has a size limit, the model has limits of its own, a path named in the
 * manifest has to be a plain path that stays inside the folder after links are resolved, a picture goes through
 * PngGate, and whatever is wrong is a refusal. Messages name files by their role and carry numbers. Of the text of a
 * file they repeat one thing: a key this reader does not read, when it is a short word in lower case, which is what
 * the keys of the format are; a name or value somebody made up is never repeated. Runs on the loader thread.
 */
public final class ModelFolder {
    /** The file that makes a folder a model: the manifest, or the model file of the old layout. */
    public static final String MANIFEST = "ysm.json";
    public static final String OLD_MODEL = "main.json";
    /** The format version Yes Steve Model 2.6.5 reads a folder with: its export of a folder model says so. */
    public static final int ORIGIN_VERSION = 32;
    public static final int MAX_MANIFEST_BYTES = 1 << 20;
    public static final int MAX_MODEL_BYTES = 16 << 20;
    /** All that is read from a folder for one model; the importer takes no larger model file either. */
    public static final long MAX_TOTAL_BYTES = 66L << 20;
    public static final int MAX_TEXTURES = 64;
    /**
     * Bones, cubes and faces of one model. The largest model seen has 1,020 bones and 34,779 faces (a packed file);
     * the largest folder 526 bones, 4,533 cubes and 26,540 faces. The bake takes fewer than 65,536 bones.
     */
    public static final int MAX_BONES = 8192;
    public static final int MAX_CUBES = 65536;
    public static final int MAX_FACES = 262144;
    /** Width and height of the texture as the description of a model file gives them, in the units of its UVs. */
    public static final int MAX_TEXTURE_UNITS = 16384;
    /** Entries of a folder of the old layout that are looked at for textures. */
    public static final int MAX_ENTRIES = 4096;

    private static final int MANIFEST_SPEC = 2;
    private static final float DEFAULT_SCALE = 0.7f;
    private static final String OLD_ARROW_MODEL = "arrow.json";
    private static final String OLD_ARROW_TEXTURE = "arrow.png";
    private static final String TEXTURE_ENDING = ".png";
    private static final String PACKED_ENDING = ".ysm";
    /** The keys of a model file above its bones, and the format versions, that the compared folders carry. */
    private static final Set<String> FILE_KEYS = Set.of("format_version", "minecraft:geometry");
    private static final List<String> FORMAT_VERSIONS = List.of("1.12.0", "1.21.0");
    private static final Set<String> GEOMETRY_KEYS = Set.of("description", "bones");
    private static final Set<String> DESCRIPTION_KEYS = Set.of("identifier", "texture_width", "texture_height", "visible_bounds_width",
            "visible_bounds_height", "visible_bounds_offset", "ysm_extra_info", "ysm_height_scale", "ysm_width_scale");
    private static final Set<String> TEXTURE_KEYS = Set.of("uv", "normal", "specular");
    private static final Set<String> BONE_KEYS = Set.of("name", "parent", "pivot", "rotation", "cubes", "mirror", "inflate");
    private static final Set<String> CUBE_KEYS = Set.of("origin", "size", "pivot", "rotation", "inflate", "mirror", "uv");
    private static final Set<String> FACE_KEYS = Set.of("uv", "uv_size", "uv_rotation");
    /** The faces in the order they are written, with the four corners of each (numbers of corner()) and its normal. */
    private static final String[] FACES = {"west", "east", "north", "south", "up", "down"};
    private static final int[][] FACE_CORNERS = {{3, 2, 0, 1}, {6, 7, 5, 4}, {2, 6, 4, 0}, {7, 3, 1, 5}, {3, 7, 6, 2}, {0, 4, 5, 1}};
    private static final float[][] FACE_NORMALS = {{-1, 0, 0}, {1, 0, 0}, {0, 0, -1}, {0, 0, 1}, {0, 1, 0}, {0, -1, 0}};
    /** A cube with mirror and box UV: whose corners each face takes. West and east change places. */
    private static final int[] MIRRORED = {1, 0, 2, 3, 4, 5};
    /** The axis each face lies across: x for west and east, z for north and south, y for up and down. */
    private static final int[] FACE_AXIS = {0, 0, 2, 2, 1, 1};

    /** Why a folder that is named like a packed model file is never read as a model, neither as a file nor as a folder. */
    public static final String NAMED_LIKE_A_FILE = "a folder whose name ends in .ysm: an id with that ending is a model file to this mod, and what Yes Steve"
            + " Model makes of such a folder was not compared; it is not read";

    public static final class NamedLikeFile extends IOException {
        private static final long serialVersionUID = 1L;

        public NamedLikeFile() {
            super(NAMED_LIKE_A_FILE);
        }
    }

    /**
     * A file or folder a reader of the model folder depends on, as the file system showed it when the folder was
     * read: its path below the folder ("" is the folder itself), size (0 for a folder), modification and creation
     * time; all -1 for one that was not there. A folder is among them for what is moved into or out of it.
     */
    public record Opened(String path, long size, long modified, long created) {
    }

    /** Told the path of a file of a folder just before the file is opened; a check turns a link here. */
    static Consumer<String> beforeOpening = path -> {
    };

    /** What read() hands to the loader. */
    static final class Content {
        byte[] geoModel;
        /** The picture as PngGate hands it on, and the size its header gives. */
        byte[] texture;
        int textureWidth;
        int textureHeight;
        float heightScale = DEFAULT_SCALE;
        float widthScale = DEFAULT_SCALE;
        boolean forceCulling;
        boolean hasPbr;
        List<String> textureKeys = List.of();
        String textureKey;
    }

    private static final class Texture {
        String key;
        String path;
        /** The normal and specular maps the manifest names for it. */
        final List<String> maps = new ArrayList<>(2);
    }

    private ModelFolder() {
    }

    /** The file that makes the folder a model, or null for a folder that holds none. */
    public static Path marker(Path folder) {
        for (String name : new String[]{MANIFEST, OLD_MODEL}) {
            Path file = folder.resolve(name);
            if (Files.isRegularFile(file)) {
                return file;
            }
        }
        return null;
    }

    /** True while the file is as it was when the folder was read; one that was missing then is still missing. */
    public static boolean unchanged(Path folder, Opened file) {
        return file.equals(stamp(folder, file.path()));
    }

    /** True for the name of a packed model file, in any case. */
    public static boolean namedLikeFile(String name) {
        return name.regionMatches(true, name.length() - PACKED_ENDING.length(), PACKED_ENDING, 0, PACKED_ENDING.length());
    }

    private static Opened stamp(Path folder, String path) {
        try {
            Path file = path.isEmpty() ? folder : PathRules.resolve(folder, path);
            if (file != null) {
                BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
                return new Opened(path, attributes.isDirectory() ? 0 : attributes.size(), attributes.lastModifiedTime().toMillis(),
                        attributes.creationTime().toMillis());
            }
        } catch (IOException | RuntimeException missing) {
            // told by the stamp
        }
        return new Opened(path, -1, -1, -1);
    }

    /** The folders between the model folder and a file of it: what is moved into one of them changes its time, not its own. */
    private static void folders(List<Opened> files, Path real, String path) {
        for (int slash = path.indexOf('/'); slash >= 0; slash = path.indexOf('/', slash + 1)) {
            String parent = path.substring(0, slash);
            boolean listed = false;
            for (Opened file : files) {
                listed |= file.path().equals(parent);
            }
            if (!listed) {
                files.add(stamp(real, parent));
            }
        }
    }

    /** Where the files of a model lie: a folder, or the archive of a raw model file, which holds the same files. */
    private interface Store {
        /** True when a file of this path is there. */
        boolean has(String path);

        /** The bytes of one file. budget[0]: what may still be read for the model, less this file afterwards. */
        byte[] open(String path, String role, int limit, long[] budget) throws ModelLoader.Refusal;

        /** The names of the .png files that lie directly in it, for the old layout. */
        List<String> pictures() throws ModelLoader.Refusal;

        /** Adds what the file system shows of a file and of the folders on the way to it; nothing for an archive, which is one file. */
        void note(List<Opened> files, String path);
    }

    private static final class Folder implements Store {
        private final Path real;

        Folder(Path real) {
            this.real = real;
        }

        @Override
        public boolean has(String path) {
            return Files.isRegularFile(real.resolve(path));
        }

        @Override
        public byte[] open(String path, String role, int limit, long[] budget) throws ModelLoader.Refusal {
            return ModelFolder.open(real, path, role, limit, budget);
        }

        @Override
        public List<String> pictures() throws ModelLoader.Refusal {
            return ModelFolder.pictures(real);
        }

        @Override
        public void note(List<Opened> files, String path) {
            folders(files, real, path);
            files.add(stamp(real, path));
        }
    }

    /** The files of a raw model file, each unpacked by the native library when it is asked for and copied from there. */
    private static final class Archive implements Store {
        private final NativeArchive archive;

        Archive(NativeArchive archive) {
            this.archive = archive;
        }

        @Override
        public boolean has(String path) {
            return archive.hasFile(path);
        }

        @Override
        public byte[] open(String path, String role, int limit, long[] budget) throws ModelLoader.Refusal {
            if (!archive.hasFile(path)) {
                throw new ModelLoader.Refusal(role + " is not in the model folder");
            }
            // Borrowed: the bytes are those of the archive and are gone with the next file that is asked for.
            NativeBuffer file = archive.getFile(path);
            if (file == null) {
                throw new ModelLoader.Refusal(role + " cannot be unpacked");
            }
            if (file.size() > limit) {
                throw new ModelLoader.Refusal(limit < budget[0] ? role + " is larger than " + (limit >> 20) + " MiB"
                        : "the files of the model folder are larger than " + (MAX_TOTAL_BYTES >> 20) + " MiB together");
            }
            byte[] bytes = new byte[file.size()];
            file.nio().get(0, bytes);
            budget[0] -= bytes.length;
            return bytes;
        }

        @Override
        public List<String> pictures() throws ModelLoader.Refusal {
            String[] listed = archive.listFiles(null);
            if (listed.length > MAX_ENTRIES) {
                throw new ModelLoader.Refusal("the folder holds more than " + MAX_ENTRIES + " entries");
            }
            List<String> names = new ArrayList<>();
            for (String name : listed) {
                if (name.regionMatches(true, name.length() - TEXTURE_ENDING.length(), TEXTURE_ENDING, 0, TEXTURE_ENDING.length())) {
                    names.add(name);
                }
            }
            return names;
        }

        @Override
        public void note(List<Opened> files, String path) {
        }
    }

    /**
     * textureKeys and opened are told what the folder holds as soon as that is known, also when the model is then
     * refused: the texture keys of the player model, and the files a model of this folder is read from.
     */
    static Content read(Path folder, String textureKeyOrNull, Consumer<List<String>> textureKeys, Consumer<List<Opened>> opened)
            throws ModelLoader.Refusal {
        return read(folder, textureKeyOrNull, textureKeys, opened, null);
    }

    /** realFolder: where the folder lay when the caller looked it up, or null. */
    static Content read(Path folder, String textureKeyOrNull, Consumer<List<String>> textureKeys, Consumer<List<Opened>> opened,
                        String realFolder) throws ModelLoader.Refusal {
        Path real;
        try {
            real = folder.toRealPath();
        } catch (IOException | RuntimeException missing) {
            ModelLoader.Refusal refusal = new ModelLoader.Refusal("the model folder cannot be read");
            throw passing(missing) ? refusal.heldFile() : refusal;
        }
        // The caller has checked where the folder lies; a link that was turned since then leads somewhere else.
        if (realFolder != null && !real.toString().equals(realFolder)) {
            throw new ModelLoader.Refusal("the model folder no longer lies where it lay when it was looked up");
        }
        return read(new Folder(real), textureKeyOrNull, textureKeys, opened);
    }

    /**
     * The model of a raw model file (see the class comment), read in memory: nothing of it is written anywhere. The
     * reasons of a refusal are those of a folder, said of the file. Called for a file the importer has read and
     * named a raw model file.
     */
    static Content readArchive(Path file, String textureKeyOrNull, Consumer<List<String>> textureKeys) throws ModelLoader.Refusal {
        // The native reader takes the whole file into memory; the importer, which was asked first, has its own limit for a file.
        NativeArchive archive;
        try {
            archive = new NativeArchive(file.toAbsolutePath().toString());
        } catch (RuntimeException notTaken) {
            // The native reader says no more than that it did not take the file.
            throw new ModelLoader.Refusal("the reader of raw model files does not take the file (it gives no reason: a file that is damaged or cut short,"
                    + " or one it could not read)");
        }
        try {
            return read(new Archive(archive), textureKeyOrNull, textureKeys, ignored -> {
            });
        } catch (ModelLoader.Refusal refusal) {
            throw refusal.worded(refusal.getMessage().replace("the model folder", "the raw model file").replace("the folder", "the raw model file"));
        } catch (RuntimeException fromTheReader) {
            throw new ModelLoader.Refusal("the raw model file cannot be listed or unpacked by the reader of raw model files");
        } finally {
            archive.close();
        }
    }

    /** True for a failure of the file system that may pass: the file is there and could not be read at this moment. */
    private static boolean passing(Exception problem) {
        return problem instanceof IOException && !(problem instanceof NoSuchFileException) && !(problem instanceof NotDirectoryException);
    }

    private static Content read(Store store, String textureKeyOrNull, Consumer<List<String>> textureKeys, Consumer<List<Opened>> opened)
            throws ModelLoader.Refusal {
        Content content = new Content();
        List<Opened> files = new ArrayList<>();
        List<Texture> textures = new ArrayList<>();
        String model;
        String defaultTexture = null;
        Float manifestHeight = null;
        Float manifestWidth = null;
        long[] budget = {MAX_TOTAL_BYTES};
        try {
            store.note(files, "");
            if (store.has(MANIFEST)) {
                store.note(files, MANIFEST);
                Map<?, ?> manifest = object(parse(store.open(MANIFEST, MANIFEST, MAX_MANIFEST_BYTES, budget), MANIFEST), MANIFEST);
                if (!(manifest.get("spec") instanceof Double spec) || spec != MANIFEST_SPEC) {
                    throw new ModelLoader.Refusal(MANIFEST + ": spec is not " + MANIFEST_SPEC + ", the one layout this version reads");
                }
                Map<?, ?> player = object(object(manifest.get("files"), MANIFEST + ": files").get("player"), MANIFEST + ": files.player");
                if (!(object(player.get("model"), MANIFEST + ": files.player.model").get("main") instanceof String main)) {
                    throw new ModelLoader.Refusal(MANIFEST + ": files.player.model.main is not a path");
                }
                model = plain(main, MANIFEST + ": files.player.model.main");
                for (Object entry : list(player.get("texture"), MANIFEST + ": files.player.texture")) {
                    textures.add(texture(entry));
                }
                if (manifest.get("properties") != null) {
                    Map<?, ?> properties = object(manifest.get("properties"), MANIFEST + ": properties");
                    manifestHeight = optionalNumber(properties.get("height_scale"), MANIFEST + ": properties.height_scale");
                    manifestWidth = optionalNumber(properties.get("width_scale"), MANIFEST + ": properties.width_scale");
                    content.forceCulling = flag(properties.get("all_cutout"), MANIFEST + ": properties.all_cutout");
                    Object name = properties.get("default_texture");
                    if (name != null && !(name instanceof String)) {
                        throw new ModelLoader.Refusal(MANIFEST + ": properties.default_texture is not text");
                    }
                    defaultTexture = (String) name;
                }
            } else if (store.has(OLD_MODEL)) {
                model = OLD_MODEL;
                oldTextures(store, textures);
            } else {
                throw new ModelLoader.Refusal("the folder has neither " + MANIFEST + " nor " + OLD_MODEL);
            }
            if (textures.isEmpty()) {
                throw new ModelLoader.Refusal("the folder names no texture for the player model");
            }
            if (textures.size() > MAX_TEXTURES) {
                throw new ModelLoader.Refusal("the folder names " + textures.size() + " textures for the player model, more than " + MAX_TEXTURES);
            }
            store.note(files, model);
            List<String> keys = new ArrayList<>();
            for (Texture texture : textures) {
                if (texture.key.isEmpty() || keys.contains(texture.key)) {
                    throw new ModelLoader.Refusal("two textures of the folder have the same name, or one has none");
                }
                keys.add(texture.key);
                store.note(files, texture.path);
                for (String map : texture.maps) {
                    store.note(files, map);
                }
            }
            content.textureKeys = Collections.unmodifiableList(keys);
        } finally {
            opened.accept(List.copyOf(files));
        }
        textureKeys.accept(content.textureKeys);

        Texture chosen = null;
        for (Texture texture : textures) {
            if (textureKeyOrNull != null ? texture.key.equals(textureKeyOrNull) : texture.key.equals(defaultTexture)) {
                chosen = texture;
            }
        }
        if (chosen == null && textureKeyOrNull != null) {
            throw new ModelLoader.Refusal("the requested texture key is not among the " + textures.size() + " keys of the folder").ofTheTexture();
        }
        if (chosen == null) {
            chosen = textures.get(0);
        }
        content.textureKey = chosen.key;
        content.hasPbr = !chosen.maps.isEmpty();
        try {
            maps(store, chosen, budget);
        } catch (ModelLoader.Refusal refusal) {
            throw refusal.ofTheTexture();
        }

        float[] scales = {Float.NaN, Float.NaN};
        content.geoModel = geoModel(store.open(model, "the model file", MAX_MODEL_BYTES, budget), scales);
        content.heightScale = scale(manifestHeight, scales[0]);
        content.widthScale = scale(manifestWidth, scales[1]);
        PngGate.Picture picture;
        try {
            picture = PngGate.rebuild(store.open(chosen.path, "the texture file", (int) Math.min(budget[0], Integer.MAX_VALUE - 16), budget));
        } catch (ModelLoader.Refusal refusal) {
            throw refusal.ofTheTexture();
        }
        content.texture = picture.stream;
        content.textureWidth = picture.width;
        content.textureHeight = picture.height;
        return content;
    }

    private static void maps(Store store, Texture chosen, long[] budget) throws ModelLoader.Refusal {
        for (String map : chosen.maps) {
            // Only that there are such maps reaches the bake; they still have to be pictures as a texture has to be one.
            PngGate.rebuild(store.open(map, "a normal or specular map", (int) Math.min(budget[0], Integer.MAX_VALUE - 16), budget));
        }
    }

    /** The scale of the manifest; one that is left at its default gives way to the one written in the model file. */
    private static float scale(Float manifest, float model) {
        float value = manifest == null ? DEFAULT_SCALE : manifest;
        if (value == DEFAULT_SCALE && !Float.isNaN(model)) {
            value = model;
        }
        return Float.isFinite(value) && value > 0.0f ? value : DEFAULT_SCALE;
    }

    /** One entry of files.player.texture: a path, or an object with the path as uv and optional normal and specular maps. */
    private static Texture texture(Object entry) throws ModelLoader.Refusal {
        Texture texture = new Texture();
        Object uv = entry;
        if (entry instanceof Map<?, ?> set) {
            for (Object key : set.keySet()) {
                if (!TEXTURE_KEYS.contains(key)) {
                    throw new ModelLoader.Refusal(MANIFEST + ": an entry of files.player.texture has a key this version does not read");
                }
            }
            uv = set.get("uv");
            map(texture, set.get("normal"));
            map(texture, set.get("specular"));
        }
        if (!(uv instanceof String path) || path.isBlank()) {
            throw new ModelLoader.Refusal(MANIFEST + ": an entry of files.player.texture names no texture file");
        }
        texture.path = plain(path, MANIFEST + ": an entry of files.player.texture");
        String name = texture.path.substring(texture.path.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        texture.key = dot >= 0 ? name.substring(0, dot) : name;
        return texture;
    }

    private static void map(Texture texture, Object value) throws ModelLoader.Refusal {
        if (value == null || value == Json.NULL) {
            return;
        }
        if (!(value instanceof String path)) {
            throw new ModelLoader.Refusal(MANIFEST + ": a normal or specular map of files.player.texture is not a path");
        }
        if (!path.isBlank()) {
            texture.maps.add(plain(path, MANIFEST + ": a normal or specular map of files.player.texture"));
        }
    }

    /**
     * The old layout: every .png directly in the folder is a texture of the player model, named after the file.
     * arrow.png belongs to arrow.json, the arrow that comes with the model.
     */
    private static void oldTextures(Store store, List<Texture> textures) throws ModelLoader.Refusal {
        List<String> names = store.pictures();
        Collections.sort(names);
        for (String name : names) {
            if (!name.endsWith(TEXTURE_ENDING) || name.equalsIgnoreCase(OLD_ARROW_TEXTURE) && !name.equals(OLD_ARROW_TEXTURE)) {
                throw new ModelLoader.Refusal("a texture of the folder has an ending or a name in upper case: which of them Yes Steve Model takes is not known");
            }
            if (name.equals(OLD_ARROW_TEXTURE)) {
                if (!store.has(OLD_ARROW_MODEL)) {
                    throw new ModelLoader.Refusal(OLD_ARROW_TEXTURE + " without " + OLD_ARROW_MODEL + ": whether it is a texture of the player model is not known");
                }
                continue;
            }
            Texture texture = new Texture();
            texture.path = plain(name, "a texture of the folder");
            texture.key = name.substring(0, name.length() - TEXTURE_ENDING.length());
            textures.add(texture);
        }
    }

    /** The names of the .png files that lie directly in the folder. */
    private static List<String> pictures(Path real) throws ModelLoader.Refusal {
        List<String> names = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(real)) {
            int seen = 0;
            for (Path entry : entries) {
                if (++seen > MAX_ENTRIES) {
                    throw new ModelLoader.Refusal("the folder holds more than " + MAX_ENTRIES + " entries");
                }
                String name = entry.getFileName().toString();
                if (name.regionMatches(true, name.length() - TEXTURE_ENDING.length(), TEXTURE_ENDING, 0, TEXTURE_ENDING.length())
                        && Files.isRegularFile(entry)) {
                    names.add(name);
                }
            }
        } catch (IOException | RuntimeException unreadable) {
            ModelLoader.Refusal refusal = new ModelLoader.Refusal("the model folder cannot be listed");
            throw passing(unreadable) ? refusal.heldFile() : refusal;
        }
        return names;
    }

    /** A path as a manifest writes it, with '/' for a backslash; refused unless it is a plain path below the folder. */
    private static String plain(String path, String what) throws ModelLoader.Refusal {
        String normal = path.replace('\\', '/');
        if (normal.isEmpty() || PathRules.problem(normal) != null) {
            throw new ModelLoader.Refusal(what + " is not a plain path below the model folder");
        }
        return normal;
    }

    /** The bytes of one file of the folder. budget[0]: what may still be read from the folder, less this file afterwards. */
    private static byte[] open(Path real, String path, String role, int limit, long[] budget) throws ModelLoader.Refusal {
        Path file = PathRules.resolve(real, path);
        if (file == null) {
            throw new ModelLoader.Refusal(role + " is not a plain path below the model folder");
        }
        byte[] bytes;
        try {
            // Links and junctions: what is opened has to lie inside the folder itself.
            Path target = file.toRealPath();
            if (!target.startsWith(real)) {
                throw new ModelLoader.Refusal(role + " lies outside the model folder");
            }
            BasicFileAttributes attributes = Files.readAttributes(target, BasicFileAttributes.class);
            if (!attributes.isRegularFile()) {
                throw new ModelLoader.Refusal(role + " is not a file");
            }
            if (attributes.size() <= limit) {
                beforeOpening.accept(path);
                try (InputStream stream = Files.newInputStream(target)) {
                    // What is open now is what was looked at only when no folder on the way to it was turned into a link since.
                    if (!target.toRealPath().equals(target)) {
                        throw new ModelLoader.Refusal(role + " was moved while it was opened");
                    }
                    bytes = stream.readNBytes(limit + 1);
                }
            } else {
                bytes = null;
            }
        } catch (IOException | RuntimeException unreadable) {
            if (!passing(unreadable)) {
                throw new ModelLoader.Refusal(unreadable instanceof IOException ? role + " is not in the model folder" : role + " cannot be read");
            }
            // Held by another program, or access denied: that says nothing of the file and may be over a moment later.
            throw new ModelLoader.Refusal(role + " cannot be read (" + unreadable.getClass().getSimpleName() + ")").heldFile();
        }
        if (bytes == null || bytes.length > limit) {
            throw new ModelLoader.Refusal(limit < budget[0] ? role + " is larger than " + (limit >> 20) + " MiB"
                    : "the files of the model folder are larger than " + (MAX_TOTAL_BYTES >> 20) + " MiB together");
        }
        budget[0] -= bytes.length;
        return bytes;
    }

    private static Object parse(byte[] bytes, String role) throws ModelLoader.Refusal {
        try {
            return Json.parse(bytes);
        } catch (Json.Malformed problem) {
            throw new ModelLoader.Refusal(role + " is not valid JSON: " + problem.getMessage());
        }
    }

    // ---- the model file: minecraft:geometry to asset/model/data/geo_model.proto ----

    /** scales: set to ysm_height_scale and ysm_width_scale of the description, not a number where the file has none. */
    static byte[] geoModel(byte[] file, float[] scales) throws ModelLoader.Refusal {
        Map<?, ?> root = object(parse(file, "the model file"), "the model file");
        for (Object key : root.keySet()) {
            if (String.valueOf(key).startsWith("geometry.")) {
                throw new ModelLoader.Refusal("the model file holds a geometry of the old style (geometry.*), which was not compared with Yes Steve Model");
            }
        }
        known(root, FILE_KEYS, "the file");
        if (!(root.get("format_version") instanceof String version) || !FORMAT_VERSIONS.contains(version)) {
            throw new ModelLoader.Refusal("the model file: format_version is none of " + String.join(" and ", FORMAT_VERSIONS)
                    + ", the versions that were compared with Yes Steve Model");
        }
        List<?> geometries = list(root.get("minecraft:geometry"), "the model file: minecraft:geometry");
        if (geometries.size() != 1) {
            throw new ModelLoader.Refusal("the model file holds " + geometries.size() + " geometries, one is expected");
        }
        Map<?, ?> geometry = object(geometries.get(0), "the model file: the geometry");
        known(geometry, GEOMETRY_KEYS, "the geometry");
        Map<?, ?> description = object(geometry.get("description"), "the model file: description");
        known(description, DESCRIPTION_KEYS, "the description");
        float[] textureSize = {number(description.get("texture_width"), "the model file: texture_width"),
                number(description.get("texture_height"), "the model file: texture_height")};
        if (!(textureSize[0] > 0.0f) || !(textureSize[1] > 0.0f)) {
            throw new ModelLoader.Refusal("the model file: the texture size of the description is not positive");
        }
        for (float units : textureSize) {
            if (units != (float) Math.rint(units) || units > MAX_TEXTURE_UNITS) {
                throw new ModelLoader.Refusal("the model file: the texture size of the description is not a whole number up to " + MAX_TEXTURE_UNITS);
            }
        }
        Float height = optionalNumber(description.get("ysm_height_scale"), "the model file: ysm_height_scale");
        Float width = optionalNumber(description.get("ysm_width_scale"), "the model file: ysm_width_scale");
        scales[0] = height == null ? Float.NaN : height;
        scales[1] = width == null ? Float.NaN : width;

        List<?> bones = list(geometry.get("bones"), "the model file: bones");
        if (bones.size() > MAX_BONES) {
            throw new ModelLoader.Refusal("the model file holds " + bones.size() + " bones, more than " + MAX_BONES);
        }
        Set<Object> parents = new HashSet<>();
        for (Object entry : bones) {
            if (entry instanceof Map<?, ?> bone && bone.get("parent") != null) {
                parents.add(bone.get("parent"));
            }
        }
        ByteArrayOutputStream model = new ByteArrayOutputStream(bones.size() * 48);
        ByteArrayOutputStream cubeList = new ByteArrayOutputStream(1 << 16);
        ByteArrayOutputStream scratch = new ByteArrayOutputStream(512);
        int cubeCount = 0;
        int faceCount = 0;
        for (Object entry : bones) {
            Map<?, ?> bone = object(entry, "the model file: a bone");
            known(bone, BONE_KEYS, "a bone");
            if (!(bone.get("name") instanceof String name)) {
                throw new ModelLoader.Refusal("the model file: a bone has no name");
            }
            Object parent = bone.get("parent");
            if (parent != null && !(parent instanceof String)) {
                throw new ModelLoader.Refusal("the model file: the parent of a bone is not a name");
            }
            if (flag(bone.get("mirror"), "the model file: mirror of a bone") || optional(bone.get("inflate"), "the model file: inflate of a bone") != 0.0f) {
                throw new ModelLoader.Refusal("the model file: a bone has mirror or inflate set, which was not compared with Yes Steve Model");
            }
            float[] pivot = numbers(bone.get("pivot"), 3, "the model file: the pivot of a bone");
            float[] rotation = bone.get("rotation") == null ? new float[3] : numbers(bone.get("rotation"), 3, "the model file: the rotation of a bone");
            scratch.reset();
            byte[] nameBytes = name.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            if (nameBytes.length > 0) {
                Wire.writeBytes(scratch, 1, nameBytes, nameBytes.length);
            }
            if (parent instanceof String parentName && !parentName.isEmpty()) {
                byte[] parentBytes = parentName.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                Wire.writeBytes(scratch, 2, parentBytes, parentBytes.length);
            }
            Wire.writeFloats(scratch, 3, new float[]{-pivot[0], pivot[1], pivot[2]}, 3);
            Wire.writeFloats(scratch, 4, new float[]{-radians(rotation[0]), -radians(rotation[1]), radians(rotation[2])}, 3);
            if (bone.get("cubes") != null) {
                List<?> cubes = list(bone.get("cubes"), "the model file: the cubes of a bone");
                cubeCount += cubes.size();
                if (cubeCount > MAX_CUBES) {
                    throw new ModelLoader.Refusal("the model file holds more than " + MAX_CUBES + " cubes");
                }
                for (Object cube : cubes) {
                    byte[] bytes = cube(object(cube, "the model file: a cube"), textureSize);
                    if (bytes.length == 0 && parents.contains(name)) {
                        throw new ModelLoader.Refusal("the model file: a bone that has children holds a cube without faces, which was not compared with Yes Steve Model");
                    }
                    // A cube starts with the number of its faces, which is one byte after the tag of the field.
                    faceCount += bytes.length > 1 ? bytes[1] : 0;
                    if (faceCount > MAX_FACES) {
                        throw new ModelLoader.Refusal("the model file holds more than " + MAX_FACES + " faces");
                    }
                    Wire.writeBytes(cubeList, 1, bytes, bytes.length);
                }
                Wire.writeVarint(scratch, 6, cubes.size());
            }
            Wire.writeBytes(model, 1, scratch.toByteArray(), scratch.size());
        }
        Wire.writeBytes(model, 3, cubeList.toByteArray(), cubeList.size());
        return model.toByteArray();
    }

    /**
     * One cube as a CubeLegacy message. Model space mirrors x and is in blocks: a cube from origin with size spans
     * -(origin.x + size.x) / 16 to -origin.x / 16 on x. Faces are written in the order of FACES, each with the corner
     * order and the UV corners Yes Steve Model gives it; a rotation turns corners and normals about the pivot, x
     * first, then y, then z, with the angles of x and y negated. A cube without rotation is left where it is: moved
     * to its pivot and back, a corner can end up one float apart. Box UV gives all six faces their rectangles (see
     * unfold); mirror, which is read together with box UV only, gives west and east each other's corners, turns the
     * texture corners of every face the other way round and the normals on x. A cube that names no face is an empty
     * message. A negative size is taken as it is written: the same sums put the "low" corner above the "high" one.
     * Which sizes are read at all is decided by sizes().
     */
    private static byte[] cube(Map<?, ?> cube, float[] textureSize) throws ModelLoader.Refusal {
        known(cube, CUBE_KEYS, "a cube");
        float[] origin = numbers(cube.get("origin"), 3, "the model file: the origin of a cube");
        float[] size = numbers(cube.get("size"), 3, "the model file: the size of a cube");
        boolean mirror = flag(cube.get("mirror"), "the model file: mirror of a cube");
        float[] box = cube.get("uv") instanceof List<?> ? numbers(cube.get("uv"), 2, "the model file: the uv of a cube") : null;
        Map<?, ?> faces = box == null ? object(cube.get("uv"), "the model file: the uv of a cube") : Map.of();
        float inflate = optional(cube.get("inflate"), "the model file: inflate of a cube") / 16f;
        if (mirror && box == null) {
            throw new ModelLoader.Refusal("the model file: a cube has mirror set and a texture rectangle per face, which was not compared with Yes Steve Model");
        }
        if ((size[0] < 0.0f || size[1] < 0.0f || size[2] < 0.0f) && (box != null || inflate < 0.0f)) {
            throw new ModelLoader.Refusal("the model file: a cube has a negative size together with " + (box != null ? "box UV" : "inflate below zero")
                    + ", which was not compared with Yes Steve Model");
        }
        float[] rotation = cube.get("rotation") == null ? null : numbers(cube.get("rotation"), 3, "the model file: the rotation of a cube");
        float[] pivot = cube.get("pivot") == null ? null : numbers(cube.get("pivot"), 3, "the model file: the pivot of a cube");
        boolean turned = rotation != null && (rotation[0] != 0.0f || rotation[1] != 0.0f || rotation[2] != 0.0f);
        if (turned && pivot == null) {
            throw new ModelLoader.Refusal("the model file: a cube has a rotation without a pivot, which was not compared with Yes Steve Model");
        }

        float lowX = -(origin[0] + size[0]) / 16f - inflate;
        float lowY = origin[1] / 16f - inflate;
        float lowZ = origin[2] / 16f - inflate;
        float highX = -(origin[0] + size[0]) / 16f + inflate + size[0] / 16f;
        float highY = origin[1] / 16f + inflate + size[1] / 16f;
        float highZ = origin[2] / 16f + inflate + size[2] / 16f;
        float[] positions = new float[24];
        for (int corner = 0; corner < 8; corner++) {
            positions[corner * 3] = (corner & 4) == 0 ? lowX : highX;
            positions[corner * 3 + 1] = (corner & 2) == 0 ? lowY : highY;
            positions[corner * 3 + 2] = (corner & 1) == 0 ? lowZ : highZ;
        }
        float[] uvs = new float[48];
        float[] normals = new float[18];
        byte[] corners = new byte[24];
        byte[] uvIndices = new byte[24];
        int count = 0;
        int last = 0;
        for (Object key : faces.keySet()) {
            if (!(key instanceof String name) || !List.of(FACES).contains(name)) {
                throw new ModelLoader.Refusal("the model file: the uv of a cube names a face that is none of the six");
            }
        }
        for (int face = 0; face < FACES.length; face++) {
            float[] start;
            float[] extent;
            if (box != null) {
                start = new float[2];
                extent = new float[2];
                unfold(box, size, face, start, extent);
            } else {
                if (faces.get(FACES[face]) == null) {
                    continue;
                }
                Map<?, ?> rectangle = object(faces.get(FACES[face]), "the model file: a face of a cube");
                known(rectangle, FACE_KEYS, "a face");
                // Yes Steve Model 2.6.5 leaves uv_rotation out of what it makes of a model, as the open-source parser
                // does: its export of a folder with such faces has them unturned.
                if (rectangle.get("uv_rotation") != null && !(rectangle.get("uv_rotation") instanceof Double angle
                        && (angle == 0 || angle == 90 || angle == 180 || angle == 270))) {
                    throw new ModelLoader.Refusal("the model file: uv_rotation of a face is none of 0, 90, 180 and 270");
                }
                start = numbers(rectangle.get("uv"), 2, "the model file: uv of a face");
                extent = numbers(rectangle.get("uv_size"), 2, "the model file: uv_size of a face");
            }
            float u1 = start[0] / textureSize[0];
            float u2 = (start[0] + extent[0]) / textureSize[0];
            float v1 = start[1] / textureSize[1];
            float v2 = (start[1] + extent[1]) / textureSize[1];
            float[] corner = {u2, v1, u1, v1, u1, v2, u2, v2};
            if (mirror) {
                corner = new float[]{u1, v1, u2, v1, u2, v2, u1, v2};
            }
            System.arraycopy(corner, 0, uvs, count * 8, 8);
            System.arraycopy(FACE_NORMALS[face], 0, normals, count * 3, 3);
            if (mirror) {
                normals[count * 3] = -normals[count * 3];
            }
            int from = mirror ? MIRRORED[face] : face;
            for (int vertex = 0; vertex < 4; vertex++) {
                corners[count * 4 + vertex] = (byte) FACE_CORNERS[from][vertex];
                uvIndices[count * 4 + vertex] = (byte) (count * 4 + vertex);
            }
            last = face;
            count++;
        }
        if (count == 0) {
            return new byte[0];
        }
        sizes(size, inflate, box != null, count, FACE_AXIS[last]);

        if (turned) {
            float rx = -radians(rotation[0]);
            float ry = -radians(rotation[1]);
            float rz = radians(rotation[2]);
            float pivotX = -pivot[0] / 16f;
            float pivotY = pivot[1] / 16f;
            float pivotZ = pivot[2] / 16f;
            for (int corner = 0; corner < 8; corner++) {
                positions[corner * 3] -= pivotX;
                positions[corner * 3 + 1] -= pivotY;
                positions[corner * 3 + 2] -= pivotZ;
                rotate(positions, corner * 3, rx, ry, rz);
                positions[corner * 3] += pivotX;
                positions[corner * 3 + 1] += pivotY;
                positions[corner * 3 + 2] += pivotZ;
            }
            for (int face = 0; face < count; face++) {
                rotate(normals, face * 3, rx, ry, rz);
            }
        }
        for (float value : positions) {
            if (!Float.isFinite(value)) {
                throw new ModelLoader.Refusal("the model file: a cube has a corner that is no finite number");
            }
        }
        for (int index = 0; index < count * 8; index++) {
            if (!Float.isFinite(uvs[index])) {
                throw new ModelLoader.Refusal("the model file: a face has a texture coordinate that is no finite number");
            }
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream(256);
        Wire.writeVarint(out, 1, count);
        Wire.writeFloats(out, 2, positions, 24);
        Wire.writeBytes(out, 3, corners, count * 4);
        Wire.writeFloats(out, 4, uvs, count * 8);
        Wire.writeBytes(out, 5, uvIndices, count * 4);
        Wire.writeFloats(out, 6, normals, count * 3);
        return out.toByteArray();
    }

    /**
     * The sizes of a cube that has faces, held against what the compared folders hold. inflate is in blocks: 32 of
     * it are what both sides take from a size in the units of the file. Compared were: a flat side, also one that
     * inflate makes thick, on a cube with a rectangle per face; a negative size on one axis of a cube with a
     * rectangle on all six faces, as it is or turned positive by inflate; one side that inflate turns inside out on
     * a cube whose one face lies across that side. across: the axis the last face of the cube lies across.
     */
    private static void sizes(float[] size, float inflate, boolean box, int faces, int across) throws ModelLoader.Refusal {
        int flat = 0;
        int negative = 0;
        int turned = 0;
        for (int axis = 0; axis < 3; axis++) {
            flat += size[axis] == 0.0f ? 1 : 0;
            negative += size[axis] < 0.0f ? 1 : 0;
            turned += size[axis] > 0.0f && size[axis] + 32f * inflate < 0.0f ? 1 : 0;
            if (size[axis] > 0.0f && size[axis] + 32f * inflate == 0.0f || size[axis] == 0.0f && inflate < 0.0f) {
                throw new ModelLoader.Refusal("the model file: a cube has a side its inflate makes flat, or a flat side with inflate below zero, which was not"
                        + " compared with Yes Steve Model");
            }
            if (size[axis] < 0.0f && inflate > 0.0f && size[axis] + 32f * inflate <= 0.0f) {
                throw new ModelLoader.Refusal("the model file: a cube has a negative size that its inflate "
                        + (size[axis] + 32f * inflate == 0.0f ? "makes exactly flat" : "leaves negative") + ", which was not compared with Yes Steve Model");
            }
        }
        if (flat > 1) {
            throw new ModelLoader.Refusal("the model file: a cube has " + flat + " sizes of zero, which was not compared with Yes Steve Model");
        }
        if (negative > 1) {
            throw new ModelLoader.Refusal("the model file: a cube has a negative size on " + negative + " axes, which was not compared with Yes Steve Model");
        }
        if (negative > 0 && flat > 0) {
            throw new ModelLoader.Refusal("the model file: a cube has a negative size together with a size of zero, which was not compared with Yes Steve Model");
        }
        if (negative > 0 && faces < 6) {
            throw new ModelLoader.Refusal("the model file: a cube has a negative size and fewer than six faces, which was not compared with Yes Steve Model");
        }
        if (box && flat > 0) {
            throw new ModelLoader.Refusal("the model file: a cube has box UV and " + (inflate > 0.0f ? "a flat side its inflate makes thick" : "a size of zero")
                    + ", which was not compared with Yes Steve Model");
        }
        if (box && turned > 0) {
            throw new ModelLoader.Refusal("the model file: a cube has box UV and a side its inflate turns inside out, which was not compared with Yes Steve Model");
        }
        if (turned > 0 && !(turned == 1 && faces == 1 && size[across] > 0.0f && size[across] + 32f * inflate < 0.0f)) {
            throw new ModelLoader.Refusal("the model file: a cube with " + faces + " face(s) has " + turned + " side(s) turned inside out by its inflate; compared"
                    + " with Yes Steve Model was one such side on a cube whose one face lies across it");
        }
    }

    /**
     * Box UV: the texture rectangle of one face of a cube whose six faces lie unfolded next to the texel the cube
     * names, each as many whole texels wide and high as the side it covers. start and extent are filled.
     */
    private static void unfold(float[] box, float[] size, int face, float[] start, float[] extent) {
        float x = (float) Math.floor(size[0]);
        float y = (float) Math.floor(size[1]);
        float z = (float) Math.floor(size[2]);
        float u = box[0];
        float v = box[1];
        switch (face) {
            case 0 -> set(start, extent, u + z + x, v + z, z, y);
            case 1 -> set(start, extent, u, v + z, z, y);
            case 2 -> set(start, extent, u + z, v + z, x, y);
            case 3 -> set(start, extent, u + z + x + z, v + z, x, y);
            case 4 -> set(start, extent, u + z, v, x, z);
            default -> set(start, extent, u + z + x, v + z, x, -z);
        }
    }

    private static void set(float[] start, float[] extent, float u, float v, float width, float height) {
        start[0] = u;
        start[1] = v;
        extent[0] = width;
        extent[1] = height;
    }

    /** Degrees as Yes Steve Model turns them into radians; the rest rotations of its bones are these floats exactly. */
    private static float radians(float degrees) {
        return degrees / 180f * (float) Math.PI;
    }

    /** Turns the vector at offset about x, then y, then z. */
    private static void rotate(float[] vector, int offset, float rx, float ry, float rz) {
        float x = vector[offset];
        float y = vector[offset + 1];
        float z = vector[offset + 2];
        float y1 = y * cos(rx) - z * sin(rx);
        float z1 = y * sin(rx) + z * cos(rx);
        float x2 = x * cos(ry) + z1 * sin(ry);
        float z2 = -x * sin(ry) + z1 * cos(ry);
        vector[offset] = x2 * cos(rz) - y1 * sin(rz);
        vector[offset + 1] = x2 * sin(rz) + y1 * cos(rz);
        vector[offset + 2] = z2;
    }

    // Sine and cosine of an angle the same on every machine and with every setting: StrictMath, not a library that
    // can be told to trade precision for speed.
    private static float sin(float angle) {
        return (float) StrictMath.sin(angle);
    }

    private static float cos(float angle) {
        return (float) StrictMath.cos(angle);
    }

    // ---- values of a JSON text, each refused when it is not what the reader takes ----

    private static Map<?, ?> object(Object value, String what) throws ModelLoader.Refusal {
        if (!(value instanceof Map<?, ?> map)) {
            throw new ModelLoader.Refusal(what + " is not an object");
        }
        return map;
    }

    private static List<?> list(Object value, String what) throws ModelLoader.Refusal {
        if (!(value instanceof List<?> list)) {
            throw new ModelLoader.Refusal(what + " is not a list");
        }
        return list;
    }

    private static float number(Object value, String what) throws ModelLoader.Refusal {
        if (!(value instanceof Double number) || !Float.isFinite(number.floatValue())) {
            throw new ModelLoader.Refusal(what + " is not a number");
        }
        return number.floatValue();
    }

    private static Float optionalNumber(Object value, String what) throws ModelLoader.Refusal {
        return value == null ? null : (Float) number(value, what);
    }

    private static float optional(Object value, String what) throws ModelLoader.Refusal {
        return value == null ? 0.0f : number(value, what);
    }

    private static boolean flag(Object value, String what) throws ModelLoader.Refusal {
        if (value != null && !(value instanceof Boolean)) {
            throw new ModelLoader.Refusal(what + " is neither true nor false");
        }
        return Boolean.TRUE.equals(value);
    }

    private static float[] numbers(Object value, int count, String what) throws ModelLoader.Refusal {
        if (!(value instanceof List<?> list) || list.size() != count) {
            throw new ModelLoader.Refusal(what + " is not " + count + " numbers");
        }
        float[] result = new float[count];
        for (int index = 0; index < count; index++) {
            if (!(list.get(index) instanceof Double number) || !Float.isFinite(number.floatValue())) {
                throw new ModelLoader.Refusal(what + " is not " + count + " numbers");
            }
            result[index] = number.floatValue();
        }
        return result;
    }

    /** A key the reader does not know may carry geometry: the model is refused, never read without it. */
    private static void known(Map<?, ?> object, Set<String> keys, String what) throws ModelLoader.Refusal {
        for (Object key : object.keySet()) {
            if (!keys.contains(key)) {
                String name = String.valueOf(key);
                boolean plain = name.length() <= 32;
                for (int index = 0; plain && index < name.length(); index++) {
                    char letter = name.charAt(index);
                    plain = letter >= 'a' && letter <= 'z' || letter == '_';
                }
                throw new ModelLoader.Refusal("the model file: " + what + " has a key this version does not read"
                        + (plain ? " (" + name + ")" : "") + ", it was not compared with Yes Steve Model");
            }
        }
    }
}
