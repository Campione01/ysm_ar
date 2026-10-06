package ysmar.takeover;

import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Looks members of another mod up by name and descriptor. Only what is public is found: public classes and their
 * public methods, as Class.getMethods lists them. Nothing is made accessible.
 */
public final class Members {
    /** A class or member is not there as expected. */
    public static final class Missing extends Exception {
        private static final long serialVersionUID = 1L;

        Missing(String what) {
            super(what, null, false, false);
        }
    }

    private Members() {
    }

    /** The public class of that name, loaded without being initialised. */
    public static Class<?> type(String name, ClassLoader loader) throws Missing {
        Class<?> found;
        try {
            found = Class.forName(name, false, loader);
        } catch (ClassNotFoundException | LinkageError problem) {
            throw new Missing("class " + name + " cannot be loaded (" + problem.getClass().getSimpleName() + ")");
        }
        if (!Modifier.isPublic(found.getModifiers())) {
            throw new Missing("class " + name + " is not public");
        }
        return found;
    }

    /**
     * The public method with exactly this name, JVM descriptor and static-ness, declared in a public class.
     * descriptor: for example "()[F" or "(Ljava/lang/String;)Ljava/util/Optional;".
     */
    public static Method method(Class<?> owner, String name, String descriptor, boolean isStatic) throws Missing {
        Method found = null;
        int sameName = 0;
        for (Method candidate : owner.getMethods()) {
            if (!candidate.getName().equals(name)) {
                continue;
            }
            sameName++;
            if (descriptor(candidate).equals(descriptor)) {
                found = candidate;
                break;
            }
        }
        String label = owner.getName() + "." + name + descriptor;
        if (found == null) {
            throw new Missing("no public method " + label + " (public methods of that name: " + sameName + ")");
        }
        if (!Modifier.isPublic(found.getModifiers()) || !Modifier.isPublic(found.getDeclaringClass().getModifiers())) {
            throw new Missing(label + " or the class that declares it is not public");
        }
        if (Modifier.isStatic(found.getModifiers()) != isStatic) {
            throw new Missing(label + " is " + (isStatic ? "not static" : "static"));
        }
        return found;
    }

    public static String descriptor(Method method) {
        return MethodType.methodType(method.getReturnType(), method.getParameterTypes()).toMethodDescriptorString();
    }
}
