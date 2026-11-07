package dev.irisvisualizer.renderer.nativebridge;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import dev.irisvisualizer.renderer.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.ARGB;
import net.minecraft.world.level.ColorMapColorUtil;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.RedstoneWireBlock;
import net.minecraft.world.level.block.StemBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** World-model tint sources using selected colormaps and exact captured native biome data. */
public final class NativeColormapTints {
    private NativeColormapTints() {}
    /** Borrowed full-height same-generation halo, held/frozen by the caller through capture. */
    public record Blend(WorldSnapshot neighbors,WorldBiomeBlend.Limits limits) {
        public Blend { Objects.requireNonNull(neighbors);Objects.requireNonNull(limits);if(neighbors.requiresPlaceholder())throw new IllegalArgumentException("Biome blending requires a successful captured halo"); }
    }
    private static final int GRASS_CACHE_ENTRIES=1024;
    private enum Rule { NONE, GRASS, DOUBLE_GRASS, SECOND_GRASS, FOLIAGE, DRY, WATER, SPRUCE, BIRCH, LILY, ATTACHED, REDSTONE, STEM }
    private static final class Tables {
        final int maximum;final Map<Long,WorldTintInput.Layers> values=new HashMap<>();
        Tables(int maximum) { this.maximum=maximum; }
        WorldTintInput.Layers get(int color,boolean second) {
            int rgb=color&0xffffff;long key=((long)rgb<<1)|(second?1:0);var prior=values.get(key);if(prior!=null)return prior;
            if(values.size()>=maximum-1)throw NativeIdentities.failure(WorldProblem.Code.LIMIT,"Vanilla tint table budget reached");
            var value=WorldTintInput.Layers.of(second?Map.of(0,0xffffff,1,rgb):Map.of(0,rgb));values.put(key,value);return value;
        }
    }
    /** Unblended source maps plus native utility copies and four palette-level RGB arrays. */
    public static long resourceBytes(ClientColormaps maps,int biomes) {
        Objects.requireNonNull(maps);if(biomes<0||biomes>8192)throw new IllegalArgumentException("Native tint biome palette exceeds its bound");
        return maps.bytes()+ClientColormaps.PAYLOAD_BYTES+(long)biomes*4*Integer.BYTES;
    }
    /** Blended grass adds one capture-local cache; other channels already use constant palette colors. */
    public static long resourceBytes(ClientColormaps maps,int biomes,int radius) {
        if(radius<0||radius>7)throw new IllegalArgumentException("Native biome blend radius exceeds its bound");
        return resourceBytes(maps,biomes)+(radius>0?WorldBiomeBlend.cacheBytes(GRASS_CACHE_ENTRIES):0);
    }
    public static NativeTintInputs.Result capture(NativeRenderContext context,ClientAssetSnapshot assets,WorldSnapshot snapshot,ClientColormaps maps,WorldTintInput.Limits limits,BooleanSupplier cancelled) {
        return capture(context,assets,snapshot,maps,Optional.empty(),limits,cancelled);
    }
    public static NativeTintInputs.Result capture(NativeRenderContext context,ClientAssetSnapshot assets,WorldSnapshot snapshot,ClientColormaps maps,Optional<Blend> blending,WorldTintInput.Limits limits,BooleanSupplier cancelled) {
        Objects.requireNonNull(context);Objects.requireNonNull(assets);Objects.requireNonNull(snapshot);Objects.requireNonNull(maps);Objects.requireNonNull(blending);Objects.requireNonNull(limits);Objects.requireNonNull(cancelled);
        try {
            check(cancelled);context.check();if(snapshot.requiresPlaceholder())return failed(snapshot,snapshot.problems());
            if(maps.requiresPlaceholder())throw NativeIdentities.failure(WorldProblem.Code.ATLAS,"Selected-client colormap preparation failed");
            if(!maps.matchesAssets(assets.receipt()))throw NativeIdentities.failure(WorldProblem.Code.VERSION,"Vanilla tint colormaps belong to another client archive");
            if(!snapshot.source().equals(context.source()))throw NativeIdentities.failure(WorldProblem.Code.ASSIGNMENT,"Vanilla tints require the exact native generation context");
            var extent=snapshot.extent();if(extent.minY()!=context.engine().getMinHeight()||extent.height()!=context.engine().getHeight()||(long)context.engine().getMaxHeight()-context.engine().getMinHeight()!=extent.height())throw NativeIdentities.failure(WorldProblem.Code.EXTENT,"Vanilla tints require complete configured height");
            var source=blending.map(Blend::neighbors).orElse(snapshot);var sourceExtent=source.extent();
            if(sourceExtent.minX()<Integer.MIN_VALUE||sourceExtent.minZ()<Integer.MIN_VALUE||sourceExtent.maxX()-1>Integer.MAX_VALUE||sourceExtent.maxZ()-1>Integer.MAX_VALUE)throw NativeIdentities.failure(WorldProblem.Code.COORDINATE,"Biome blend halo exceeds signed native coordinates");
            int radius=blending.map(b->b.limits().radius()).orElse(0);
            long resources=resourceBytes(maps,source.biomes().size(),radius)+(source==snapshot?0:source.indexBytes()),target=(long)snapshot.cellCount()*2+(long)limits.tables()*8+(long)limits.layers()*8+(long)snapshot.states().size()*4;
            if(target>limits.payloadBytes()||assets.receipt().capturedAssetBytes()+snapshot.indexBytes()+resources+target>limits.workingBytes())throw NativeIdentities.failure(WorldProblem.Code.LIMIT,"Vanilla tint maps, native copies and prospective tint payload exceed their allowance");
            var blend=new WorldBiomeBlend(snapshot,source,blending.map(Blend::limits).orElse(WorldBiomeBlend.Limits.UNBLENDED),cancelled);
            int[][] copies=new int[ClientColormaps.Kind.values().length][];
            for(var kind:ClientColormaps.Kind.values()) { check(cancelled);copies[kind.ordinal()]=maps.copyArgb(kind); }
            var biomes=new Biome[source.biomes().size()];int[][] colors=new int[4][biomes.length];
            for(int at=0;at<biomes.length;at++) {
                check(cancelled);context.checkSession();var expected=source.biomes().get(at);var biome=context.biomes().byId(expected.nativeBiomeId());var key=biome==null?null:context.biomes().getKey(biome);
                if(key==null||context.biomes().getId(biome)!=expected.nativeBiomeId()||!key.toString().equals(expected.original())||!AssetId.parse(key.toString()).equals(expected.id()))throw NativeIdentities.failure(WorldProblem.Code.PALETTE,"Native tint biome identity differs from the captured palette");
                biomes[at]=biome;
                readColors(biome,copies,colors,at);
            }
            WorldBiomeBlend.Sampler grass=(at,x,y,z)->biomes[at].getSpecialEffects().grassColorModifier().modifyColor((int)x,(int)z,colors[0][at]);
            if(radius>0)grass=blend.memoize(grass,GRASS_CACHE_ENTRIES,WorldBiomeBlend.cacheBytes(GRASS_CACHE_ENTRIES));
            var grassSampler=grass;
            var rules=new IdentityHashMap<BlockState,Rule>();var tables=new Tables(limits.tables());
            String policy="Vanilla 26.3 world-model tint sources; horizontal RGB blend radius "+blend.radius()+"; selected archive colormaps";
            var sampler=new NativeTintInputs.Provider(assets.receipt(),policy,(state,biome,position)->layers(context,blend,grassSampler,colors,state,position,rules.computeIfAbsent(state,NativeColormapTints::rule),tables));
            var bounded=new WorldTintInput.Limits(limits.tables(),limits.layers(),limits.payloadBytes(),limits.workingBytes()-resources);
            var result=NativeTintInputs.capture(context,assets,snapshot,sampler,bounded,cancelled);check(cancelled);context.check();return result;
        }catch(WorldCapture.ReadFailure failure) { check(cancelled);return failed(snapshot,List.of(failure.problem())); }
        catch(CancellationException cancellation) { throw cancellation; }
        catch(RuntimeException failure) { check(cancelled);return failed(snapshot,List.of(new WorldProblem(WorldProblem.Code.WORKER,Optional.empty(),"Vanilla colormap tint capture failed: "+failure.getClass().getSimpleName()))); }
        catch(OutOfMemoryError exhausted) { check(cancelled);return failed(snapshot,List.of(new WorldProblem(WorldProblem.Code.MEMORY,Optional.empty(),"Not enough memory for vanilla colormap tint capture"))); }
    }
    /** The native codec graph leaves this frame before the next biome is encoded. */
    private static void readColors(Biome biome,int[][] copies,int[][] colors,int at) {
        var encoded=Biome.NETWORK_CODEC.encodeStart(JsonOps.INSTANCE,biome).getOrThrow();
        double temperature=climate(encoded,"temperature"),rain=climate(encoded,"downfall");var effects=biome.getSpecialEffects();
        // Full 256x256 maps and clamped climate guarantee an in-range native utility index.
        colors[0][at]=effects.grassColorOverride().orElseGet(()->ColorMapColorUtil.get(temperature,rain,copies[ClientColormaps.Kind.GRASS.ordinal()],0));
        colors[1][at]=effects.foliageColorOverride().orElseGet(()->ColorMapColorUtil.get(temperature,rain,copies[ClientColormaps.Kind.FOLIAGE.ordinal()],0));
        colors[2][at]=effects.dryFoliageColorOverride().orElseGet(()->ColorMapColorUtil.get(temperature,rain,copies[ClientColormaps.Kind.DRY_FOLIAGE.ordinal()],0));
        colors[3][at]=biome.getWaterColor();
    }
    private static double climate(JsonElement encoded,String field) {
        if(!encoded.isJsonObject())throw new IllegalArgumentException("Native biome codec did not produce an object");var value=encoded.getAsJsonObject().get(field);
        if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isNumber())throw new IllegalArgumentException("Native biome codec lacks its climate field");
        // Vanilla clamps float climate values before converting to double for its colormap utility.
        float number=value.getAsFloat();if(!Float.isFinite(number))throw new IllegalArgumentException("Native climate is not finite");return Math.clamp(number,0,1);
    }
    private static int color(NativeRenderContext context,WorldBiomeBlend blend,WorldBiomeBlend.Sampler grass,int[][] colors,BlockPos position,int channel,boolean below) {
        long y=(long)position.getY()-(below?1:0);if(y<Integer.MIN_VALUE||y>Integer.MAX_VALUE)throw NativeIdentities.failure(WorldProblem.Code.COORDINATE,"Tint source position exceeds native Y");
        return blend.rgb(new WorldSnapshot.Position(position.getX(),(int)y,position.getZ()),(at,x,sy,z)->{
            context.checkSession();
            return channel==0?grass.rgb(at,x,sy,z):colors[channel][at];
        });
    }
    private static Rule rule(BlockState state) {
        var key=BuiltInRegistries.BLOCK.getKey(state.getBlock());if(key==null)return Rule.NONE;
        return switch(key.toString()) {
            case "minecraft:large_fern","minecraft:tall_grass"->Rule.DOUBLE_GRASS;
            case "minecraft:fern","minecraft:short_grass","minecraft:potted_fern","minecraft:bush","minecraft:grass_block","minecraft:sugar_cane"->Rule.GRASS;
            case "minecraft:pink_petals","minecraft:wildflowers"->Rule.SECOND_GRASS;
            case "minecraft:oak_leaves","minecraft:jungle_leaves","minecraft:acacia_leaves","minecraft:dark_oak_leaves","minecraft:vine","minecraft:mangrove_leaves"->Rule.FOLIAGE;
            case "minecraft:leaf_litter"->Rule.DRY;
            case "minecraft:water_cauldron"->Rule.WATER;
            case "minecraft:spruce_leaves"->Rule.SPRUCE;
            case "minecraft:birch_leaves"->Rule.BIRCH;
            case "minecraft:lily_pad"->Rule.LILY;
            case "minecraft:attached_melon_stem","minecraft:attached_pumpkin_stem"->Rule.ATTACHED;
            case "minecraft:redstone_wire"->Rule.REDSTONE;
            case "minecraft:melon_stem","minecraft:pumpkin_stem"->Rule.STEM;
            // Particle-only water/bubble-column sources do not supply a fluid-mesh tint layer.
            default->Rule.NONE;
        };
    }
    private static WorldTintInput.Layers layers(NativeRenderContext context,WorldBiomeBlend blend,WorldBiomeBlend.Sampler grass,int[][] colors,BlockState state,BlockPos position,Rule rule,Tables tables) {
        if(rule==Rule.NONE)return WorldTintInput.Layers.EMPTY;
        if(rule==Rule.SECOND_GRASS)return tables.get(color(context,blend,grass,colors,position,0,false),true);
        int rgb=switch(rule) {
            case DOUBLE_GRASS->color(context,blend,grass,colors,position,0,state.getValue(DoublePlantBlock.HALF)==DoubleBlockHalf.UPPER);
            case GRASS->color(context,blend,grass,colors,position,0,false);
            case FOLIAGE->color(context,blend,grass,colors,position,1,false);
            case DRY->color(context,blend,grass,colors,position,2,false);
            case WATER->color(context,blend,grass,colors,position,3,false);
            case SPRUCE->0x619961;case BIRCH->0x80a755;case LILY->0x208030;case ATTACHED->0xe0c71c;
            case REDSTONE->RedstoneWireBlock.getColorForPower(state.getValue(RedstoneWireBlock.POWER));
            case STEM->{ int age=state.getValue(StemBlock.AGE);yield ARGB.color(age*32,255-age*8,age*4); }
            default->throw new IllegalStateException("Invalid vanilla tint rule");
        };
        return tables.get(rgb,false);
    }
    private static NativeTintInputs.Result failed(WorldSnapshot snapshot,List<WorldProblem> problems) { return new NativeTintInputs.Result(snapshot.source(),snapshot.id(),Optional.empty(),problems); }
    private static void check(BooleanSupplier cancelled) { if(cancelled.getAsBoolean())throw new CancellationException("Vanilla colormap tint capture cancelled"); }
}
