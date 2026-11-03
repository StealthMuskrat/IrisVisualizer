package dev.irisvisualizer.renderer.nativebridge;

import art.arcane.iris.generation.noise.IrisNoiseGenerator;
import art.arcane.iris.generation.noise.IrisGenerator;
import art.arcane.iris.generation.noise.IrisExpression;
import art.arcane.iris.generation.runtime.Engine;
import art.arcane.volmlib.util.math.RNG;
import art.arcane.volmlib.util.stream.ProceduralStream;
import dev.irisvisualizer.renderer.*;

import java.util.*;
import java.util.function.BooleanSupplier;

/** Native Iris APIs supply every height/biome/noise value; caller owns and freezes the engine session. */
public final class NativeMapSampler {
    private NativeMapSampler() {}
    public static final String TERRAIN_DRY="Iris Engine.getHeight(ignoreFluid=true); floor X/Z; native minY + relative height";
    public static final String TERRAIN_FLUID="Iris Engine.getHeight(ignoreFluid=false); floor X/Z; native minY + relative height";
    public static final String BIOMES="Iris trueBiomeDerivativeStream; exact fractional X/Z; native registry identity";
    public static final String NOISE_LAYER="IrisNoiseGenerator.getNoise; exact fractional X/Z; native engine seed/data";
    public static final String GENERATOR="IrisGenerator.getHeight(X,Z,seed); exact fractional X/Z; native 2D resource policy";
    public static final String EXPRESSION="IrisExpression.stream(native RNG(seed)); exact fractional X/Z; native resource policy";
    private abstract static class Checked implements MapCapture.Sampler {
        final Engine engine;final NativeRenderContext nativeContext;final MapSamples.Context context;final String policy;boolean checked;
        Checked(NativeRenderContext nativeContext,MapSamples.Context context,String policy) { this.nativeContext=Objects.requireNonNull(nativeContext);this.engine=nativeContext.engine();this.context=Objects.requireNonNull(context);this.policy=policy; }
        @Override public boolean present(double x,double z) {
            nativeContext.checkSession();if(!checked) { nativeContext.check();if(!nativeContext.source().equals(context.world())||!policy.equals(context.samplingPolicy()))throw NativeIdentities.failure(WorldProblem.Code.ASSIGNMENT,"Map source policy does not match its native sampler");checked=true; }return true;
        }
        @Override public void complete() { nativeContext.check(); }
    }
    public static MapSamples terrain(NativeRenderContext nativeContext,MapSamples.Context context,MapSamples.Grid grid,boolean includeFluids,MapCapture.Limits limits,BooleanSupplier cancelled) {
        Objects.requireNonNull(nativeContext);Objects.requireNonNull(cancelled);var engine=nativeContext.engine();
        var vertical=new MapSamples.Vertical(engine.getMinHeight(),Math.subtractExact(engine.getMaxHeight(),engine.getMinHeight()));
        return MapCapture.capture(context,grid,MapSamples.Kind.TERRAIN,Optional.of(vertical),new Checked(nativeContext,context,includeFluids?TERRAIN_FLUID:TERRAIN_DRY) {
            @Override public int height(double x,double z) { int relative=engine.getHeight((int)Math.floor(x),(int)Math.floor(z),!includeFluids);return Math.addExact(engine.getMinHeight(),relative); }
        },limits,cancelled);
    }
    /** Native surface derivative stream, not the pack biome key and not a generated 3D biome volume. */
    public static MapSamples biomes(NativeRenderContext nativeContext,MapSamples.Context context,MapSamples.Grid grid,MapCapture.Limits limits,BooleanSupplier cancelled) {
        Objects.requireNonNull(limits);var identities=new NativeIdentities(nativeContext.biomes(),1,limits.samples().biomes(),limits.paletteCharacters(),cancelled);
        return MapCapture.capture(context,grid,MapSamples.Kind.BIOME,Optional.empty(),new Checked(nativeContext,context,BIOMES) {
            @Override public WorldSnapshot.Biome biome(double x,double z) { return identities.biome(engine.getComplex().getTrueBiomeDerivativeStream().get(x,z)); }
        },limits,cancelled);
    }
    /** A resolved inline noise layer; its resource identity/pointer must be supplied explicitly. */
    public static MapSamples noiseLayer(NativeRenderContext nativeContext,IrisNoiseGenerator generator,MapSamples.Context context,MapSamples.Grid grid,MapCapture.Limits limits,BooleanSupplier cancelled) {
        Objects.requireNonNull(generator);
        return MapCapture.capture(context,grid,MapSamples.Kind.NOISE,Optional.empty(),new Checked(nativeContext,context,NOISE_LAYER) {
            @Override public double noise(double x,double z) { return generator.getNoise(context.world().seed(),x,z,engine.getData()); }
        },limits,cancelled);
    }
    public static MapSamples generator(NativeRenderContext nativeContext,IrisGenerator generator,MapSamples.Context context,MapSamples.Grid grid,MapCapture.Limits limits,BooleanSupplier cancelled) {
        Objects.requireNonNull(generator);
        return MapCapture.capture(context,grid,MapSamples.Kind.NOISE,Optional.empty(),new Checked(nativeContext,context,GENERATOR) {
            @Override public double noise(double x,double z) {
                if(!context.resourceType().equals("generators")||!context.resourceKey().equals(generator.getLoadKey())||generator.getLoader()!=engine.getData()||engine.getData().getEngine()!=engine)throw NativeIdentities.failure(WorldProblem.Code.ASSIGNMENT,"Generator resource is not loaded in this exact native engine context");
                return generator.getHeight(x,z,context.world().seed());
            }
        },limits,cancelled);
    }
    public static MapSamples expression(NativeRenderContext nativeContext,IrisExpression expression,MapSamples.Context context,MapSamples.Grid grid,MapCapture.Limits limits,BooleanSupplier cancelled) {
        Objects.requireNonNull(expression);
        return MapCapture.capture(context,grid,MapSamples.Kind.NOISE,Optional.empty(),new Checked(nativeContext,context,EXPRESSION) {
            private ProceduralStream<Double> stream;
            @Override public double noise(double x,double z) {
                if(!context.resourceType().equals("expressions")||!context.resourceKey().equals(expression.getLoadKey())||expression.getLoader()!=engine.getData()||engine.getData().getEngine()!=engine)throw NativeIdentities.failure(WorldProblem.Code.ASSIGNMENT,"Expression resource is not loaded in this exact native engine context");
                if(stream==null)stream=expression.stream(new RNG(context.world().seed()));return stream.get(x,z);
            }
        },limits,cancelled);
    }
}
