package dev.irisvisualizer.renderer.gl;

import dev.irisvisualizer.renderer.NoiseSurface;

/** Independently retained immutable scalar face instances and original CPU samples. */
public final class GlNoiseScene implements AutoCloseable {
    private static final class Shared {
        final GlDevice device;final GlDevice.Allocation allocation;int references=1;boolean closing;
        Shared(GlDevice device,GlDevice.Allocation allocation) { this.device=device;this.allocation=allocation; }
    }
    final GlDevice device;final GlDevice.Allocation allocation;private Shared shared;
    GlNoiseScene(GlDevice device,GlDevice.Allocation allocation) { this(new Shared(device,allocation)); }
    private GlNoiseScene(Shared value) { shared=value;device=value.device;allocation=value.allocation; }
    private Shared live() { var value=shared;if(value==null||value.closing)throw new GlFailure(GlFailure.Code.STATE,"Scalar scene owner is closed or closing");device.live(allocation);return value; }
    public NoiseSurface surface() { live();return (NoiseSurface)allocation.retained; }
    public boolean closed() { return shared==null; }
    public GlNoiseScene retain() { var value=live();if(value.references>=65536)throw new GlFailure(GlFailure.Code.LIMIT,"Scalar scene owner count reached");var result=new GlNoiseScene(value);value.references++;return result; }
    @Override public void close() {
        var value=shared;if(value==null)return;if(!allocation.released)device.current();
        if(value.references>1) { value.references--;shared=null;return; }value.closing=true;
        try { device.retire(allocation); }finally { if(allocation.retired||allocation.released) { value.references=0;shared=null; } }
    }
}
