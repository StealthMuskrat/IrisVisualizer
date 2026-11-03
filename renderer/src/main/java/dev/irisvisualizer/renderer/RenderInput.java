package dev.irisvisualizer.renderer;

import java.util.Objects;

/** Owned detached input plus a shared admission hold. Borrowed values do not extend this lifetime. */
public final class RenderInput<T> implements AutoCloseable {
    private T value;private final RenderMemory.Hold admission;
    private RenderInput(T value,RenderMemory.Hold admission) { this.value=value;this.admission=admission; }
    /** Consumes the supplied hold on every outcome after nonnull checks. The input owns a fresh handle. */
    public static <T> RenderInput<T> own(T value,RenderMemory.Hold admission) {
        Objects.requireNonNull(value);Objects.requireNonNull(admission);RenderMemory.Hold owned=RenderMemory.Hold.none();
        try { owned=admission.retain();return new RenderInput<>(value,owned); }
        catch(RuntimeException|OutOfMemoryError failure) { owned.close();throw failure; }
        finally { admission.close(); }
    }
    private void open() { if(value==null)throw new IllegalStateException("Renderer input is closed"); }
    public synchronized T value() { open();return value; }
    /** Borrowed admission metadata/handle; retain it before independently owning escaped data. */
    public synchronized RenderMemory.Hold admission() { open();return admission; }
    public synchronized boolean closed() { return value==null; }
    public synchronized RenderInput<T> retain() {
        open();var held=admission.retain();
        try { return new RenderInput<>(value,held); }
        catch(RuntimeException|OutOfMemoryError failure) { held.close();throw failure; }
    }
    @Override public synchronized void close() { if(value==null)return;value=null;admission.close(); }
}
