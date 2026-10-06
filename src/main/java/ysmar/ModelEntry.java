package ysmar;

import ysmar.core.ModelData;

import java.nio.file.Path;
import java.util.List;

/**
 * One requested model. LOADING until the loader thread is done with it, then READY or FAILED with a reason.
 * Everything but the state is read on the render thread only.
 */
public final class ModelEntry {
    public enum State { LOADING, READY, FAILED }

    private final Path file;
    private final String requestedTexture;
    private final boolean keepsPixels;

    private volatile State state = State.LOADING;
    private volatile String failure;
    private volatile ModelData data;
    private volatile List<String> textureKeys = List.of();
    private boolean discarded;

    /** Render thread data of the Accelerated Rendering glue and of the stand-alone front-end. */
    private Object renderData;
    private Object frontEndData;

    ModelEntry(Path file, String requestedTexture, boolean keepsPixels) {
        this.file = file;
        this.requestedTexture = requestedTexture;
        this.keepsPixels = keepsPixels;
    }

    public State state() {
        return state;
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

    void textureKeys(List<String> keys) {
        textureKeys = keys;
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

    /** Takes a READY entry out of use for good; its natives stay open until it is discarded. */
    public synchronized void disable(String reason) {
        if (state == State.READY) {
            failure = reason;
            state = State.FAILED;
        }
    }

    synchronized void discard() {
        if (discarded) {
            return;
        }
        discarded = true;
        ModelData loaded = data;
        data = null;
        renderData = null;
        frontEndData = null;
        if (state != State.FAILED) {
            failure = "discarded";
            state = State.FAILED;
        }
        if (loaded != null) {
            loaded.close();
        }
    }
}
