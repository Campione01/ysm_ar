package ysmar.takeover;

import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;

import java.lang.reflect.Method;
import java.util.function.BiConsumer;

/**
 * Listens to an event of another mod that is only known by name: after every other listener, also when one of them
 * cancelled it, and hands two of its values on. Yes Steve Model posts such an event before it draws a player; a
 * listener may set a texture in it, and the player is then drawn with that one.
 */
public final class TextureEventTap {
    private TextureEventTap() {
    }

    /** True when a listener left a texture in the event and it is not the texture the model itself is drawn with. */
    public static boolean overrides(Object eventTexture, Object own) {
        return eventTexture != null && !eventTexture.equals(own);
    }

    /**
     * sink gets what the two public getters of the event answer once all listeners have run. An event whose getters
     * throw is passed over. False: the listener could not be registered.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static boolean register(IEventBus bus, Class<?> eventType, Method subject, Method texture, BiConsumer<Object, Object> sink) {
        if (eventType == null || subject == null || texture == null || !Event.class.isAssignableFrom(eventType)) {
            return false;
        }
        try {
            bus.addListener(EventPriority.LOWEST, true, (Class) eventType, event -> {
                Object who;
                Object what;
                try {
                    who = subject.invoke(event);
                    what = texture.invoke(event);
                } catch (ReflectiveOperationException | RuntimeException unreadable) {
                    return;
                }
                sink.accept(who, what);
            });
            return true;
        } catch (RuntimeException refused) {
            return false;
        }
    }
}
