package ysmar;

import ysmar.core.ModelData;
import ysmar.core.ModelFolder;

import java.nio.file.Path;
import java.util.List;

/**
 * One requested model, of a model file or of a model folder. LOADING until the loader thread is done with it, then
 * READY or FAILED with a reason. Everything but the state is read on the render thread only.
 */
public final class ModelEntry {
    public enum State { LOADING, READY, FAILED }

    /** Why an entry that was taken out of the cache is of no use any more. */
    public static final String DROPPED = "our copy of the model was dropped: its file or folder is read again, or the models were cleared";
    /** Why an entry that was dropped as idle is of no use any more (see YsmArModels.dropIdle). */
    public static final String IDLE = "our copy of the model was dropped: no entity has shown it for as long as models.idle_seconds allows;"
            + " it is read again when one does";

    private final Path file;
    private final String requestedTexture;
    private final boolean keepsPixels;
    private final String realPath;

    private volatile boolean notRead;
    private volatile State state = State.LOADING;
    private volatile String failure;
    private volatile ModelData data;
    private volatile List<String> textureKeys = List.of();
    private volatile List<ModelFolder.Opened> opened = List.of();
    private boolean discarded;
    // Render thread: who shows the model says so, and the idle sweep of the cache asks and forgets.
    private boolean shown = true;
    private long idleSince;
    private boolean droppedIdle;

    /** Render thread data of the Accelerated Rendering glue and of the stand-alone front-end. */
    private Object renderData;
    private Object frontEndData;

    ModelEntry(Path file, String requestedTexture, boolean keepsPixels) {
        this(file, requestedTexture, keepsPixels, null);
    }

    /** realPath: where the file or folder was found to lie when it was looked up, or null. */
    ModelEntry(Path file, String requestedTexture, boolean keepsPixels, String realPath) {
        this.file = file;
        this.requestedTexture = requestedTexture;
        this.keepsPixels = keepsPixels;
        this.realPath = realPath;
    }

    String realPath() {
        return realPath;
    }

    /**
     * True for an entry that failed without its model having been read: a file was held by another program or
     * access to it was denied, or memory ran short. Such a failure says nothing about what the file or folder holds.
     */
    public boolean notRead() {
        return notRead;
    }

    public State state() {
        return state;
    }

    /** Said by whoever is about to show the model on an entity: the cache keeps it. */
    public void shown() {
        shown = true;
    }

    /** True when the cache dropped the model because no entity had shown it for a while; a new request reads it again. */
    public boolean droppedIdle() {
        return droppedIdle;
    }

    /** For the idle sweep, at the time now: since when the model was not shown, which is now when it was since the sweep before. */
    long idleSince(long now) {
        if (shown) {
            shown = false;
            idleSince = now;
        }
        return idleSince;
    }

    /** Why the entry is FAILED, else null. */
    public String failure() {
        return failure;
    }

    public Path file() {
        return file;
    }

    public String fileName() {
        Path name = file.getFileName();
        return name == null ? file.toString() : name.toString();
    }

    public String requestedTexture() {
        return requestedTexture;
    }

    public boolean keepsPixels() {
        return keepsPixels;
    }

    /** The loaded model while READY, else null. */
    public ModelData data() {
        return state == State.READY ? data : null;
    }

    public int boneCount() {
        ModelData current = data();
        return current == null ? 0 : current.boneCount;
    }

    /** Bone names in the sorted order of the bake, which is the index space of the attribute array. */
    public List<String> boneNames() {
        ModelData current = data();
        return current == null ? List.of() : current.boneNames;
    }

    public float heightScale() {
        ModelData current = data();
        return current == null ? 0.0f : current.heightScale;
    }

    public float widthScale() {
        ModelData current = data();
        return current == null ? 0.0f : current.widthScale;
    }

    /** Texture keys of the PLAYER target; also known for an entry that failed after the manifest was read. */
    public List<String> textureKeys() {
        return textureKeys;
    }

    public Object renderData() {
        return renderData;
    }

    public void renderData(Object value) {
        renderData = value;
    }

    public Object frontEndData() {
        return frontEndData;
    }

    public void frontEndData(Object value) {
        frontEndData = value;
    }

    /**
     * A model folder: the files this model was read from, as they were then; also known for an entry that failed
     * after the folder was listed. Empty for a model file, which is one file and part of what identifies the entry.
     */
    public List<ModelFolder.Opened> opened() {
        return opened;
    }

    void textureKeys(List<String> keys) {
        textureKeys = keys;
    }

    void opened(List<ModelFolder.Opened> files) {
        opened = files;
    }

    synchronized boolean isDiscarded() {
        return discarded;
    }

    /** False when the entry was dropped while its model loaded: the model is closed and never shown. */
    synchronized boolean complete(ModelData loaded) {
        if (discarded) {
            loaded.close();
            return false;
        }
        data = loaded;
        state = State.READY;
        return true;
    }

    synchronized void fail(String reason) {
        if (state == State.LOADING) {
            failure = reason;
            state = State.FAILED;
        }
    }

    /** Fails without the model having been read (see notRead). */
    synchronized void failNotRead(String reason) {
        if (state == State.LOADING) {
            notRead = true;
            failure = reason;
            state = State.FAILED;
        }
    }

    /** Takes a READY entry out of use for good; its natives stay open until it is discarded. */
    public synchronized void disable(String reason) {
        if (state == State.READY) {
            failure = reason;
            state = State.FAILED;
        }
    }

    synchronized void discard() {
        discard(DROPPED);
    }

    /** As discard(), for a model that no entity has shown for a while. */
    synchronized void discardIdle() {
        droppedIdle = !discarded;
        discard(IDLE);
    }

    private void discard(String reason) {
        if (discarded) {
            return;
        }
        discarded = true;
        ModelData loaded = data;
        data = null;
        renderData = null;
        frontEndData = null;
        if (state != State.FAILED) {
            failure = reason;
            state = State.FAILED;
        }
        if (loaded != null) {
            loaded.close();
        }
    }
}
