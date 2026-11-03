package dev.irisvisualizer.renderer.nativebridge;

import art.arcane.iris.generation.runtime.Engine;
import dev.irisvisualizer.renderer.*;
import net.minecraft.core.Registry;
import net.minecraft.world.level.biome.Biome;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Borrowed frozen native session; host supplies a live epoch/error gate, including reported errors. */
public record NativeRenderContext(Engine engine,WorldSnapshot.Source source,Registry<Biome> biomes,BooleanSupplier currentAndHealthy) {
    public NativeRenderContext { Objects.requireNonNull(engine);Objects.requireNonNull(source);Objects.requireNonNull(biomes);Objects.requireNonNull(currentAndHealthy); }
    public void checkSession() { if(engine.hasFailed()||!currentAndHealthy.getAsBoolean())throw NativeIdentities.failure(WorldProblem.Code.ASSIGNMENT,"Native session became obsolete, closed or reported generation/sampling errors"); }
    public void check() { checkSession();NativeIdentities.context(engine,source); }
}
