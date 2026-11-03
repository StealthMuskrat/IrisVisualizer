package dev.irisvisualizer.renderer.nativebridge;

import art.arcane.iris.generation.noise.IrisExpression;
import art.arcane.iris.generation.noise.IrisGenerator;
import art.arcane.volmlib.util.math.RNG;
import art.arcane.volmlib.util.stream.ProceduralStream;
import dev.irisvisualizer.renderer.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Native 3-coordinate resource APIs, preserving their actual dimensional behavior and full configured Y. */
public final class NativeNoiseSampler {
    private NativeNoiseSampler() {}
    public static final String GENERATOR="IrisGenerator.getHeight(X,Y,Z,seed); exact X/Z; all configured world Y; native resource dimensional policy";
    public static final String EXPRESSION="IrisExpression.stream(native RNG(seed)).get(X,Y,Z); exact X/Z; all configured world Y";
    private abstract static class Checked implements NoiseCapture.Sampler {
        final NativeRenderContext nativeContext;final MapSamples.Context source;final NoiseVolume.Grid grid;final String policy,type,key;final Object loader;
        Checked(NativeRenderContext context,MapSamples.Context source,NoiseVolume.Grid grid,String policy,String type,String key,Object loader) {
            nativeContext=Objects.requireNonNull(context);this.source=Objects.requireNonNull(source);this.grid=Objects.requireNonNull(grid);this.policy=policy;this.type=type;this.key=key;this.loader=loader;
        }
        @Override public void begin() {
            nativeContext.check();var engine=nativeContext.engine();
            if(!nativeContext.source().equals(source.world())||!policy.equals(source.samplingPolicy())||!type.equals(source.resourceType())||!Objects.equals(key,source.resourceKey())||loader!=engine.getData()||engine.getData().getEngine()!=engine)throw NativeIdentities.failure(WorldProblem.Code.ASSIGNMENT,"Scalar resource/policy is not loaded in this exact native engine context");
            var y=grid.vertical();long height=(long)engine.getMaxHeight()-engine.getMinHeight();
            if(y.minY()!=engine.getMinHeight()||y.height()!=height||height!=engine.getHeight())throw NativeIdentities.failure(WorldProblem.Code.EXTENT,"Native scalar volume requires the entire configured world-height range");
        }
        @Override public boolean present(double x,int y,double z) { nativeContext.checkSession();return true; }
        @Override public void complete() { nativeContext.check(); }
    }
    /** The pinned generator currently ignores Y internally. This adapter deliberately preserves that native result. */
    public static NoiseVolume generator(NativeRenderContext context,IrisGenerator generator,MapSamples.Context source,NoiseVolume.Grid grid,NoiseCapture.Limits limits,BooleanSupplier cancelled) {
        Objects.requireNonNull(generator);
        return NoiseCapture.capture(source,grid,new Checked(context,source,grid,GENERATOR,"generators",generator.getLoadKey(),generator.getLoader()) {
            @Override public double value(double x,int y,double z) { return generator.getHeight(x,y,z,source.world().seed()); }
        },limits,cancelled);
    }
    public static NoiseVolume expression(NativeRenderContext context,IrisExpression expression,MapSamples.Context source,NoiseVolume.Grid grid,NoiseCapture.Limits limits,BooleanSupplier cancelled) {
        Objects.requireNonNull(expression);
        return NoiseCapture.capture(source,grid,new Checked(context,source,grid,EXPRESSION,"expressions",expression.getLoadKey(),expression.getLoader()) {
            private ProceduralStream<Double> stream;
            @Override public double value(double x,int y,double z) {
                if(stream==null)stream=expression.stream(new RNG(source.world().seed()));return stream.get(x,y,z);
            }
        },limits,cancelled);
    }
}
