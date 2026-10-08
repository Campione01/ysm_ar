package ysmar.takeover;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import ysmar.Text;

import java.io.Reader;
import java.util.ArrayList;
import java.util.List;

/**
 * Works out which mixins of other mods sit on a class, from records that ForeignMixins reads in the game. In the
 * Mixin version of the game the records of a class do not list the mixins applied to it: a mixin that was applied
 * is noted in the records of the mixin class itself. So the answer is put together the other way round: every
 * mixin class the installed mods declare is asked which classes it targets and whether it was applied.
 *
 * What cannot be asked this way has no record anywhere: the mixin classes a plugin of a configuration adds, and
 * configurations a mod registers in code.
 */
public final class MixinListing {
    private static final int MAX_NAMES = 8;
    private static final String[] CLASS_LISTS = {"mixins", "client", "server"};
    /** The attribute of a jar's manifest that Mixin takes configurations from, next to those the mod loader lists. */
    public static final String MANIFEST_ATTRIBUTE = "MixinConfigs";

    /** One mixin configuration of an installed mod. Class names are in the form with dots. */
    public static final class Config {
        public final String name;
        /** The package of its mixin classes; null when the configuration cannot be read. */
        public final String mixinPackage;
        /** Its mixin classes; null when the configuration cannot be read. */
        public final List<String> classes;
        /** Whether it names a plugin: a plugin can add mixin classes that no file lists. */
        public final boolean plugin;

        public Config(String name, String mixinPackage, List<String> classes) {
            this(name, mixinPackage, classes, false);
        }

        public Config(String name, String mixinPackage, List<String> classes, boolean plugin) {
            this.name = name;
            this.mixinPackage = mixinPackage;
            this.classes = classes;
            this.plugin = plugin;
        }
    }

    /**
     * One mixin configuration from its text, read the way Mixin reads it: leniently, so that a comma in front of a
     * closing bracket stands for an entry that is not there, and such an entry is passed over as Mixin passes it
     * over. So is every other entry that is no text. Unreadable is only what is no JSON object with a package.
     */
    public static Config read(String name, Reader text) {
        try {
            JsonObject json = JsonParser.parseReader(text).getAsJsonObject();
            if (!isText(json.get("package"))) {
                return new Config(name, null, null);
            }
            String mixinPackage = json.get("package").getAsString();
            List<String> classes = new ArrayList<>();
            for (String list : CLASS_LISTS) {
                JsonElement entries = json.get(list);
                if (entries != null && entries.isJsonArray()) {
                    for (JsonElement entry : entries.getAsJsonArray()) {
                        if (isText(entry)) {
                            classes.add(mixinPackage + "." + entry.getAsString());
                        }
                    }
                }
            }
            return new Config(name, mixinPackage, classes, isText(json.get("plugin")));
        } catch (Exception | LinkageError | StackOverflowError unreadable) {
            return new Config(name, null, null);
        }
    }

    private static boolean isText(JsonElement element) {
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString() && !element.getAsString().isBlank();
    }

    /**
     * The configurations to ask for one mod file, each once: those the mod loader lists for it, then those its
     * manifest names in the attribute MixinConfigs (separated by commas), which Mixin registers by itself.
     */
    public static List<String> declared(List<String> listed, String manifestAttribute) {
        List<String> names = new ArrayList<>();
        for (String name : listed) {
            if (name != null && !names.contains(name)) {
                names.add(name);
            }
        }
        if (manifestAttribute != null) {
            for (String part : manifestAttribute.split(",")) {
                String name = part.trim();
                if (!name.isEmpty() && !names.contains(name)) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    public interface Records {
        /** The mixin configurations the installed mods declare; null when they cannot be listed. */
        List<Config> configs();

        /**
         * The classes this mixin class targets, when it was applied to at least one class; an empty list when it was
         * applied to none yet; null when the records do not know the class as a mixin.
         */
        List<String> appliedTargets(String mixinClass);

        /** The mixin classes the records of the target class itself list as applied; null when they cannot be asked. */
        List<String> appliedTo(String target);
    }

    private MixinListing() {
    }

    /**
     * The mixin classes on the target that are not in ownPackage, separated by commas. "none" comes with the number
     * of configurations that were asked and only when every one of them could be read; "unknown" when nothing was
     * found and something could not be asked.
     */
    public static String describe(Records records, String target, String ownPackage) {
        List<String> names = new ArrayList<>();
        List<String> direct = records.appliedTo(target);
        if (direct != null) {
            for (String name : direct) {
                add(names, name, ownPackage);
            }
        }
        List<Config> configs = records.configs();
        int asked = 0;
        int unreadable = 0;
        int plugins = 0;
        if (configs != null) {
            for (Config config : configs) {
                if (config.classes == null) {
                    unreadable++;
                    continue;
                }
                asked++;
                plugins += config.plugin ? 1 : 0;
                for (String mixin : config.classes) {
                    List<String> applied = mixin.startsWith(ownPackage) ? null : records.appliedTargets(mixin);
                    if (applied != null && applied.contains(target)) {
                        add(names, mixin, ownPackage);
                    }
                }
            }
        }
        String gaps = configs == null ? "the mixin configurations of the installed mods could not be listed"
                : unreadable == 0 ? null : unreadable + " of " + (asked + unreadable) + " mixin configurations could not be read";
        if (names.isEmpty()) {
            return gaps != null ? "unknown (" + gaps + ")" : "none (asked: " + asked + " mixin configurations of the installed mods"
                    + (plugins == 0 ? "" : "; " + plugins + " of them name" + (plugins == 1 ? "s" : "") + " a plugin, and the mixins a plugin adds are listed nowhere") + ")";
        }
        StringBuilder text = new StringBuilder();
        for (int index = 0; index < Math.min(names.size(), MAX_NAMES); index++) {
            text.append(index == 0 ? "" : ", ").append(Text.clean(names.get(index)));
        }
        if (names.size() > MAX_NAMES) {
            text.append(" and ").append(names.size() - MAX_NAMES).append(" more");
        }
        if (gaps != null) {
            text.append(" (possibly more: ").append(gaps).append(')');
        }
        return text.toString();
    }

    private static void add(List<String> names, String name, String ownPackage) {
        if (name != null && !name.startsWith(ownPackage) && !names.contains(name)) {
            names.add(name);
        }
    }
}
