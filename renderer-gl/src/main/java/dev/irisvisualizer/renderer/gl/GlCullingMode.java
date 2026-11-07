package dev.irisvisualizer.renderer.gl;

/** Original-group CPU rejection strategy; fragment clipping and source geometry remain independent. */
public enum GlCullingMode {
    NONE, LINEAR, HIERARCHY;
    static GlCullingMode from(boolean enabled) { return enabled?HIERARCHY:NONE; }
    boolean enabled() { return this!=NONE; }
}
