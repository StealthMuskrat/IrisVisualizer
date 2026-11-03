package dev.irisvisualizer.renderer.nativebridge;

import art.arcane.iris.generation.runtime.Engine;
import art.arcane.volmlib.nativelib.minecraft26_2.modded.ModdedBiome;
import art.arcane.volmlib.nativelib.minecraft26_2.modded.ModdedBlockState;
import art.arcane.volmlib.nativelib.terrain.NativeBiome;
import art.arcane.volmlib.nativelib.terrain.NativeBlockState;
import dev.irisvisualizer.renderer.*;
import net.minecraft.SharedConstants;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Per-operation native registry detachment; never bootstraps registries or loads client classes. */
final class NativeIdentities {
    static final String BUILD="iris-c3359e0a16438b1bb8468901c6171dcefdf63f7f/volmlib-210705c5ab1321dc74b86b48457fd6d5e0452944";
    final Registry<Biome> registry;final int stateLimit,biomeLimit;final long characterLimit;final BooleanSupplier cancelled;
    final IdentityHashMap<BlockState,RenderBlockState> states=new IdentityHashMap<>();final IdentityHashMap<Biome,WorldSnapshot.Biome> biomes=new IdentityHashMap<>();long characters;
    NativeIdentities(Registry<Biome> registry,int stateLimit,int biomeLimit,long characterLimit,BooleanSupplier cancelled) { this.registry=Objects.requireNonNull(registry);this.stateLimit=stateLimit;this.biomeLimit=biomeLimit;this.characterLimit=characterLimit;this.cancelled=cancelled; }
    static WorldCapture.ReadFailure failure(WorldProblem.Code code,String message) { return new WorldCapture.ReadFailure(new WorldProblem(code,Optional.empty(),message)); }
    void check() { if(cancelled.getAsBoolean())throw new CancellationException("Native renderer capture cancelled"); }
    static void context(Engine engine,WorldSnapshot.Source source) {
        if(Runtime.version().feature()!=25||!System.getProperty("os.name","").startsWith("Windows")||!List.of("amd64","x86_64").contains(System.getProperty("os.arch","")))throw failure(WorldProblem.Code.VERSION,"Native renderer capture requires Windows x64 and Java 25");
        if(!source.minecraftVersion().equals(BlockModels.PROFILE)||!source.minecraftVersion().equals(SharedConstants.getCurrentVersion().name())||!source.backendBuild().equals(BUILD))throw failure(WorldProblem.Code.VERSION,"Native runtime/source receipt does not match the pinned renderer bridge build");
        if(source.seed()!=engine.getSeedManager().getSeed()||!source.dimension().path().equals(engine.getDimension().getLoadKey()))throw failure(WorldProblem.Code.ASSIGNMENT,"Native engine seed/dimension differs from the capture receipt");
    }
    RenderBlockState state(NativeBlockState input) {
        check();if(!(input instanceof ModdedBlockState nativeState))throw failure(WorldProblem.Code.STATE,"Generated block is absent or is not an actual native block state");
        if(nativeState.isCustom())throw failure(WorldProblem.Code.STATE,"Unresolved deferred/custom placement cannot be captured as its base block: "+nativeState.key());
        var handle=nativeState.handle();var cached=states.get(handle);if(cached!=null) { if(!cached.original().equals(nativeState.key()))throw failure(WorldProblem.Code.STATE,"One native block handle has conflicting original serialization");return cached; }
        if(states.size()>=stateLimit)throw failure(WorldProblem.Code.PALETTE,"Native registry state detachment limit reached");
        var key=BuiltInRegistries.BLOCK.getKey(handle.getBlock());if(key==null)throw failure(WorldProblem.Code.STATE,"Generated native block has no registry key");var id=AssetId.parse(key.toString());
        if(!id.namespace().equals("minecraft"))throw failure(WorldProblem.Code.STATE,"Unsupported external generated block: "+nativeState.key());
        int nativeId=Block.getId(handle);if(nativeId<0||Block.stateById(nativeId)!=handle||!ModdedBlockState.serialize(handle).equals(nativeState.key()))throw failure(WorldProblem.Code.STATE,"Generated native state failed exact registry/serialization round-trip");
        var values=new TreeMap<String,String>();var domains=new TreeMap<String,Set<String>>();long text=nativeState.key().length()+id.toString().length();int domainValues=0;
        try(var stream=handle.getValues()) {
            var properties=stream.limit(129).toList();if(properties.size()>128)throw failure(WorldProblem.Code.LIMIT,"Native property count exceeds the render-state bound");
            for(var value:properties) {
                check();var property=value.property();String name=property.getName();var allowed=domain(property);domainValues+=allowed.size();
                if(domainValues>4096)throw failure(WorldProblem.Code.LIMIT,"Native property domain values exceed the render-state bound");
                if(values.put(name,value.valueName())!=null)throw failure(WorldProblem.Code.STATE,"Duplicate native property name");domains.put(name,allowed);text+=(long)name.length()*2+value.valueName().length();for(String possible:allowed)text+=possible.length();
                if(text>262144)throw failure(WorldProblem.Code.LIMIT,"Native state identity/domain text exceeds its detachment bound");
            }
        }
        if(text>characterLimit-characters)throw failure(WorldProblem.Code.LIMIT,"Native registry palette character budget reached");
        var result=new RenderBlockState(nativeId,nativeState.key(),id,values,domains);characters+=text;states.put(handle,result);return result;
    }
    private <T extends Comparable<T>> Set<String> domain(Property<T> property) {
        if(property.getPossibleValues().size()>1024)throw failure(WorldProblem.Code.LIMIT,"Native property domain count exceeded");var values=new TreeSet<String>();
        for(T value:property.getPossibleValues()) { check();values.add(property.getName(value)); }return values;
    }
    WorldSnapshot.Biome biome(NativeBiome input) {
        check();if(!(input instanceof ModdedBiome nativeBiome)||!(nativeBiome.nativeHandle() instanceof Biome handle))throw failure(WorldProblem.Code.PALETTE,"Generated biome is absent or has no actual native registry handle");
        var cached=biomes.get(handle);if(cached!=null) { if(!cached.original().equals(nativeBiome.key()))throw failure(WorldProblem.Code.PALETTE,"One native biome handle has conflicting original keys");return cached; }
        if(biomes.size()>=biomeLimit)throw failure(WorldProblem.Code.PALETTE,"Native biome registry detachment limit reached");
        var key=registry.getKey(handle);int id=registry.getId(handle);
        if(key==null||id<0||!key.toString().equals(nativeBiome.key()))throw failure(WorldProblem.Code.PALETTE,"Native biome is absent from the supplied registry or its exact key differs");
        long text=(long)nativeBiome.key().length()*2;if(text>characterLimit-characters)throw failure(WorldProblem.Code.LIMIT,"Native biome identity character budget reached");
        var result=new WorldSnapshot.Biome(id,nativeBiome.key(),AssetId.parse(key.toString()));characters+=text;biomes.put(handle,result);return result;
    }
}
