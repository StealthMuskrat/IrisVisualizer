package dev.irisvisualizer.renderer.gl;

/** Explicit backend failure; no partial GPU scene is returned and CPU generation is never rewritten. */
public final class GlFailure extends RuntimeException {
    public enum Code { CONTEXT, CAPABILITY, LIMIT, GL_ERROR, SHADER, MATERIAL, STATE, MEMORY }
    private final Code code;
    public GlFailure(Code code,String reason) { super(reason);this.code=java.util.Objects.requireNonNull(code); }
    public Code code() { return code; }
}
