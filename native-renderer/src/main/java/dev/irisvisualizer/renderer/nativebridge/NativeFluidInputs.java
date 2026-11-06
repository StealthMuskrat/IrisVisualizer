package dev.irisvisualizer.renderer.nativebridge;

import art.arcane.volmlib.nativelib.minecraft26_2.modded.ModdedBlockState;
import dev.irisvisualizer.renderer.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HalfTransparentBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.shapes.Shapes;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Source-reviewed vanilla pure-fluid and verified JSON-base fluid layer producer. */
public final class NativeFluidInputs {
    private NativeFluidInputs() {}
    private static final String POLICY="Vanilla water/lava surfaces; native height/flow/occlusion; simple display lighting";
    public record Limits(WorldVisualEdits.Limits edits,NativeGeometry.Limits geometry,long queries,Optional<WorldBiomeBlend.Limits> waterBlend) {
        public static final Limits DEFAULT=new Limits(WorldVisualEdits.Limits.DEFAULT,NativeGeometry.Limits.DEFAULT,67108864);
        public Limits(WorldVisualEdits.Limits edits,NativeGeometry.Limits geometry,long queries) { this(edits,geometry,queries,Optional.empty()); }
        public Limits { Objects.requireNonNull(edits);Objects.requireNonNull(geometry);Objects.requireNonNull(waterBlend);if(queries<0||queries>268435456)throw new IllegalArgumentException("Fluid query bound exceeded"); }
        /** Explicit water-only averaging over the existing borrowed neighbor capture. */
        public Limits withWaterBlend(WorldBiomeBlend.Limits blend) { return new Limits(edits,geometry,queries,Optional.of(blend)); }
    }
    private record Key(int state,FluidGeometry.Cell input) {}
    private record ProblemKey(int state,String reason) {}
    private record LayerKey(WorldVisuals.ModelChoice base,BlockMesh fluid) {}
    private static final class Budget { long added; }
    private static final class Missing extends RuntimeException { Missing(String reason) { super(reason); } }
    /** Additional detached neighbors are borrowed only; no absent sample is replaced with air. */
    public static NativeVisualInputs.Result capture(NativeRenderContext context,WorldRenderWorker.Request original,Optional<WorldSnapshot> neighbors,Limits limits,BooleanSupplier cancelled) {
        return capture(context,original,neighbors,limits,false,cancelled);
    }
    /** Combined capture already subtracts borrowed neighbors from the ready request's phase ceiling. */
    static NativeVisualInputs.Result captureReservedNeighbors(NativeRenderContext context,WorldRenderWorker.Request original,Optional<WorldSnapshot> neighbors,Limits limits,BooleanSupplier cancelled) {
        return capture(context,original,neighbors,limits,true,cancelled);
    }
    private static NativeVisualInputs.Result capture(NativeRenderContext context,WorldRenderWorker.Request original,Optional<WorldSnapshot> neighbors,Limits limits,boolean reservedNeighbors,BooleanSupplier cancelled) {
        Objects.requireNonNull(context);Objects.requireNonNull(original);Objects.requireNonNull(neighbors);Objects.requireNonNull(limits);Objects.requireNonNull(cancelled);var input=original.visuals();var snapshot=input.snapshot();String colorsPolicy=colorPolicy(limits),policy=POLICY+"; "+colorsPolicy;
        try {
            check(cancelled);context.check();if(!snapshot.source().equals(context.source()))throw NativeIdentities.failure(WorldProblem.Code.ASSIGNMENT,"Fluid capture requires its exact native context");
            var halo=neighbors.orElse(snapshot);
            if(halo.requiresPlaceholder()||!halo.source().equals(snapshot.source())||halo.extent().minY()!=snapshot.extent().minY()||halo.extent().height()!=snapshot.extent().height())throw NativeIdentities.failure(WorldProblem.Code.ASSIGNMENT,"Fluid neighbor capture has another native epoch or height");
            long extra=halo==snapshot?0:halo.indexBytes(),colorBytes=limits.waterBlend().isPresent()?(long)halo.biomes().size()*Integer.BYTES:0,known=Math.min(original.workingBytes()-original.visibility().bytes(),input.limits().workingBytes());
            long working=Math.min(known-(reservedNeighbors?0:extra)-colorBytes,limits.edits().workingBytes()-extra-colorBytes);
            if(working<0||input.inputBytes()+(long)snapshot.cellCount()*8>working)throw NativeIdentities.failure(WorldProblem.Code.LIMIT,"Fluid neighbors and visual edit copy peak exceed the working allowance");
            var water=limits.waterBlend().isPresent()?new WaterColors(context,snapshot,halo,limits.waterBlend().orElseThrow(),cancelled):null;
            var view=new View(context,snapshot,halo,limits.queries(),cancelled);var cache=new HashMap<Key,WorldVisuals.Choice>();var layers=new HashMap<LayerKey,WorldVisuals.Choice>();var missing=new HashMap<ProblemKey,WorldVisuals.Choice>();var empty=new HashMap<Integer,WorldVisuals.Choice>();var missingAssets=new EnumMap<FluidGeometry.Kind,Optional<AssetId>>(FluidGeometry.Kind.class);var budget=new Budget();
            var edits=new WorldVisualEdits.Limits(limits.edits().cells(),limits.edits().changes(),limits.edits().packets(),limits.edits().addedPackedBytes(),working);
            var provider=new NativeVisualInputs.Provider(input.assets().receipt(),policy,(cell,state,biome,position,prior)->{
                check(cancelled);var fluid=state.getFluidState();if(fluid.isEmpty())return prior;
                if(prior instanceof WorldVisuals.NativeChoice||prior instanceof WorldVisuals.EmptyChoice)return prior;
                var kind=kind(fluid);boolean base=prior instanceof WorldVisuals.ModelChoice&&modelBase(state,snapshot.state(cell).block());
                if(kind==null||!base&&(state.hasBlockEntity()||state.getRenderShape()!=RenderShape.INVISIBLE))return prior;
                if(prior instanceof WorldVisuals.ModelChoice model&&(!base||model.nativeLayer().isPresent()))return prior;
                if(base&&state.hasOffsetFunction()&&input.offsets().mode()!=WorldOffsets.Mode.NATIVE)
                    return missing.computeIfAbsent(new ProblemKey(prior.stateIndex(),"Missing fluid base offset"),ignored->new WorldVisuals.ProblemChoice(prior.stateIndex(),List.of(new ModelPlan.Problem(ModelPlan.Code.UNSUPPORTED_GEOMETRY,ClientAssetSnapshot.Kind.BLOCKSTATE,snapshot.state(cell).block(),"/native/fluid/offset","Fluid base requires captured native model offsets"))));
                try {
                    var sample=sample(view,state,fluid,position,kind,biome,water);if(sample.faces()==0&&base)return prior;if(sample.faces()==0)return empty.computeIfAbsent(prior.stateIndex(),WorldVisuals.EmptyChoice::new);
                    var absentAsset=missingAssets.computeIfAbsent(kind,value->missingAsset(input.assets(),value));
                    if(absentAsset.isPresent())return missing.computeIfAbsent(new ProblemKey(prior.stateIndex(),"Missing fluid sprites"),ignored->new WorldVisuals.ProblemChoice(prior.stateIndex(),List.of(new ModelPlan.Problem(ModelPlan.Code.MISSING_ASSET,ClientAssetSnapshot.Kind.TEXTURE,absentAsset.orElseThrow(),"","Selected archive lacks a required vanilla fluid sprite"))));
                    var key=new Key(prior.stateIndex(),sample);var previous=cache.get(key);if(previous!=null)return layered(prior,previous,layers,input.limits().choices());
                    long bytes=FluidGeometry.packedBytes(sample);
                    if(cache.size()>=edits.packets()||bytes>edits.addedPackedBytes()-budget.added||input.inputBytes()+(long)snapshot.cellCount()*8+budget.added+bytes>working)throw NativeIdentities.failure(WorldProblem.Code.LIMIT,"Fluid prototype or packed/copy working allowance reached");
                    var mesh=FluidGeometry.bake(input.assets(),snapshot,cell,sample,kind==FluidGeometry.Kind.WATER?colorsPolicy:"native lava color; white",limits.geometry(),cancelled);
                    WorldVisuals.Choice choice=mesh.requiresPlaceholder()?new WorldVisuals.ProblemChoice(prior.stateIndex(),mesh.problems()):new WorldVisuals.NativeChoice(prior.stateIndex(),mesh);cache.put(key,choice);budget.added+=mesh.packet().map(MeshPacket::packedBytes).orElse(0L);return layered(prior,choice,layers,input.limits().choices());
                }catch(Missing absent) {
                    return missing.computeIfAbsent(new ProblemKey(prior.stateIndex(),absent.getMessage()),ignored->new WorldVisuals.ProblemChoice(prior.stateIndex(),List.of(new ModelPlan.Problem(ModelPlan.Code.UNSUPPORTED_GEOMETRY,ClientAssetSnapshot.Kind.BLOCKSTATE,snapshot.state(cell).block(),"/native/fluid/neighbors",absent.getMessage()))));
                }
            });
            return NativeVisualInputs.capture(context,original,provider,edits,cancelled);
        }catch(WorldCapture.ReadFailure failure) { check(cancelled);return failed(snapshot,policy,List.of(failure.problem())); }
        catch(CancellationException cancellation) { throw cancellation; }
        catch(RuntimeException failure) { check(cancelled);return failed(snapshot,policy,List.of(new WorldProblem(WorldProblem.Code.WORKER,Optional.empty(),"Native fluid capture failed: "+failure.getClass().getSimpleName()))); }
        catch(OutOfMemoryError exhausted) { check(cancelled);return failed(snapshot,policy,List.of(new WorldProblem(WorldProblem.Code.MEMORY,Optional.empty(),"Not enough memory for native fluid capture"))); }
    }
    private static String colorPolicy(Limits limits) { return limits.waterBlend().map(value->"native water color; horizontal RGB blend radius "+value.radius()).orElse("radius-zero native water color"); }
    /** Palette-sized primitive storage, admitted before native registry/color reads. No native handle escapes. */
    private static final class WaterColors {
        final WorldBiomeBlend blend;final int[] rgb;
        WaterColors(NativeRenderContext context,WorldSnapshot target,WorldSnapshot halo,WorldBiomeBlend.Limits limits,BooleanSupplier cancelled) {
            var extent=halo.extent();
            if(extent.minX()<Integer.MIN_VALUE||extent.minZ()<Integer.MIN_VALUE||extent.maxX()-1>Integer.MAX_VALUE||extent.maxZ()-1>Integer.MAX_VALUE)throw NativeIdentities.failure(WorldProblem.Code.COORDINATE,"Water color halo exceeds signed native coordinates");
            blend=new WorldBiomeBlend(target,halo,limits,cancelled);rgb=new int[halo.biomes().size()];
            for(int at=0;at<rgb.length;at++) {
                check(cancelled);context.checkSession();var expected=halo.biomes().get(at);var biome=context.biomes().byId(expected.nativeBiomeId());var key=biome==null?null:context.biomes().getKey(biome);
                if(key==null||context.biomes().getId(biome)!=expected.nativeBiomeId()||!key.toString().equals(expected.original())||!AssetId.parse(key.toString()).equals(expected.id()))throw NativeIdentities.failure(WorldProblem.Code.PALETTE,"Water color halo differs from the native biome registry");
                rgb[at]=biome.getWaterColor()&0xffffff;
            }
        }
        int rgb(BlockPos position) { return blend.rgb(new WorldSnapshot.Position(position.getX(),position.getY(),position.getZ()),(at,x,y,z)->rgb[at]); }
    }
    /** Keep the exact selected tickets; only identical fluid recipes share the native layer packet. */
    private static WorldVisuals.Choice layered(WorldVisuals.Choice prior,WorldVisuals.Choice fluid,Map<LayerKey,WorldVisuals.Choice> layers,int limit) {
        if(!(prior instanceof WorldVisuals.ModelChoice model)||!(fluid instanceof WorldVisuals.NativeChoice nativeChoice))return fluid;
        var key=new LayerKey(model,nativeChoice.mesh());var present=layers.get(key);if(present!=null)return present;
        if(layers.size()>=limit)throw NativeIdentities.failure(WorldProblem.Code.LIMIT,"Fluid/base composition palette allowance reached");
        var choice=new WorldVisuals.ModelChoice(model.stateIndex(),model.tickets(),Optional.of(nativeChoice.mesh()));layers.put(key,choice);return choice;
    }
    /** Complete vanilla JSON bases still require actual native offsets when their state uses them. */
    static boolean modelBase(BlockState state,AssetId block) {
        return kind(state.getFluidState())!=null&&state.getRenderShape()==RenderShape.MODEL&&(!state.hasBlockEntity()||VanillaModelPolicy.modelOnlyBlockEntity(BlockModels.PROFILE,block));
    }
    private static Optional<AssetId> missingAsset(ClientAssetSnapshot assets,FluidGeometry.Kind kind) {
        if(assets.find(ClientAssetSnapshot.Kind.TEXTURE,kind.still()).isEmpty())return Optional.of(kind.still());
        if(assets.find(ClientAssetSnapshot.Kind.TEXTURE,kind.flow()).isEmpty())return Optional.of(kind.flow());
        return kind.overlay().filter(id->assets.find(ClientAssetSnapshot.Kind.TEXTURE,id).isEmpty());
    }
    private static FluidGeometry.Kind kind(FluidState state) {
        var type=state.getType();return type==Fluids.WATER||type==Fluids.FLOWING_WATER?FluidGeometry.Kind.WATER:type==Fluids.LAVA||type==Fluids.FLOWING_LAVA?FluidGeometry.Kind.LAVA:null;
    }
    private static final class View implements BlockGetter {
        final NativeRenderContext context;final WorldSnapshot snapshot,halo;final BlockState[] states,outer;final long limit;final BooleanSupplier cancelled;long queries;
        View(NativeRenderContext context,WorldSnapshot snapshot,WorldSnapshot halo,long limit,BooleanSupplier cancelled) {
            this.context=context;this.snapshot=snapshot;this.halo=halo;this.limit=limit;this.cancelled=cancelled;states=states(context,snapshot,cancelled);outer=halo==snapshot?states:states(context,halo,cancelled);
        }
        private static BlockState[] states(NativeRenderContext context,WorldSnapshot snapshot,BooleanSupplier cancelled) {
            var result=new BlockState[snapshot.states().size()];var identities=new NativeIdentities(context.biomes(),result.length,1,16L*1024*1024,cancelled);
            for(int i=0;i<result.length;i++) { check(cancelled);context.checkSession();var expected=snapshot.states().get(i);var state=Block.stateById(expected.nativeStateId());if(Block.getId(state)!=expected.nativeStateId()||!identities.state(ModdedBlockState.of(state,null)).equals(expected))throw NativeIdentities.failure(WorldProblem.Code.STATE,"Fluid neighbor state differs from captured native metadata");result[i]=state; }return result;
        }
        private int index(WorldSnapshot source,BlockPos position) {
            var extent=source.extent();long x=(long)position.getX()-extent.minX(),y=(long)position.getY()-extent.minY(),z=(long)position.getZ()-extent.minZ();
            return x<0||x>=extent.width()||y<0||y>=extent.height()||z<0||z>=extent.depth()?-1:Math.toIntExact((y*extent.depth()+z)*extent.width()+x);
        }
        @Override public BlockState getBlockState(BlockPos position) {
            check(cancelled);context.checkSession();if(queries>=limit)throw NativeIdentities.failure(WorldProblem.Code.LIMIT,"Native fluid neighbor query allowance reached");queries++;
            int cell=index(snapshot,position);var source=snapshot;var palette=states;if(cell<0) { cell=index(halo,position);source=halo;palette=outer; }
            if(cell<0)throw new Missing("Fluid geometry needs uncaptured neighbors; no air or clamped sample was substituted");
            if(!source.state(cell).block().namespace().equals("minecraft"))throw new Missing("External neighbor visuals are unsupported by vanilla fluid capture");return palette[source.stateIndex(cell)];
        }
        @Override public FluidState getFluidState(BlockPos position) { return getBlockState(position).getFluidState(); }
        @Override public BlockEntity getBlockEntity(BlockPos position) { throw new Missing("Fluid sampling requested unavailable native block-entity data"); }
        @Override public int getHeight() { return snapshot.extent().height(); }
        @Override public int getMinY() { return snapshot.extent().minY(); }
    }
    private static boolean same(Fluid type,FluidState state) { return type.isSame(state.getType()); }
    private static boolean occluded(Direction face,float height,BlockState neighbor) {
        var shape=neighbor.getFaceOcclusionShape(face.getOpposite());if(shape==Shapes.empty())return false;if(shape==Shapes.block())return face!=Direction.UP||height==1;
        return Shapes.blockOccludes(Shapes.box(0,0,0,1,height,1),shape,face);
    }
    private static boolean render(FluidState self,BlockState host,Direction face,BlockState neighbor) { return !same(self.getType(),neighbor.getFluidState())&&!occluded(face.getOpposite(),1,host); }
    private static float height(View view,Fluid type,BlockPos position) {
        var state=view.getBlockState(position);var fluid=state.getFluidState();return same(type,fluid)?same(type,view.getFluidState(position.above()))?1:fluid.getOwnHeight():state.isSolid()?-1:0;
    }
    private static float weight(float height) { return height>=0.8f?10:height>=0?1:0; }
    private static float average(View view,Fluid type,float self,float a,float b,BlockPos diagonal) {
        if(a>=1||b>=1)return 1;float corner=a>0||b>0?height(view,type,diagonal):-1;if(corner>=1)return 1;
        float sum=0,count=0,w=weight(corner);if(w!=0) { sum+=corner*w;count+=w; }
        w=weight(self);if(w!=0) { sum+=self*w;count+=w; }w=weight(b);if(w!=0) { sum+=b*w;count+=w; }w=weight(a);if(w!=0) { sum+=a*w;count+=w; }return sum/count;
    }
    private static int bit(ModelPlan.Direction face) { return 1<<face.ordinal(); }
    private static FluidGeometry.Cell sample(View view,BlockState host,FluidState fluid,BlockPos position,FluidGeometry.Kind kind,Biome biome,WaterColors water) {
        var type=fluid.getType();var down=view.getBlockState(position.below());var up=view.getBlockState(position.above());
        var north=view.getBlockState(position.north());var south=view.getBlockState(position.south());var east=view.getBlockState(position.east());var west=view.getBlockState(position.west());
        boolean top=!same(type,up.getFluidState()),bottom=render(fluid,host,Direction.DOWN,down)&&!occluded(Direction.DOWN,0.8888889f,down);
        boolean n=render(fluid,host,Direction.NORTH,north),s=render(fluid,host,Direction.SOUTH,south),e=render(fluid,host,Direction.EAST,east),w=render(fluid,host,Direction.WEST,west);
        if(!top&&!bottom&&!n&&!s&&!e&&!w)return new FluidGeometry.Cell(kind,1,1,1,1,0,0,false,false,0,0,0xffffff,0);
        float self=height(view,type,position),nw,sw,se,ne;
        if(self>=1) { nw=sw=se=ne=1; }else {
            float hn=height(view,type,position.north()),hs=height(view,type,position.south()),he=height(view,type,position.east()),hw=height(view,type,position.west());
            ne=average(view,type,self,hn,he,position.north().east());nw=average(view,type,self,hn,hw,position.north().west());se=average(view,type,self,hs,he,position.south().east());sw=average(view,type,self,hs,hw,position.south().west());
        }
        top=top&&!occluded(Direction.UP,Math.min(Math.min(nw,sw),Math.min(se,ne)),up);float inset=top?0.001f:0;
        n=n&&!occluded(Direction.NORTH,Math.max(nw-inset,ne-inset),north);s=s&&!occluded(Direction.SOUTH,Math.max(se-inset,sw-inset),south);e=e&&!occluded(Direction.EAST,Math.max(ne-inset,se-inset),east);w=w&&!occluded(Direction.WEST,Math.max(sw-inset,nw-inset),west);
        int faces=(top?bit(ModelPlan.Direction.UP):0)|(bottom?bit(ModelPlan.Direction.DOWN):0)|(n?bit(ModelPlan.Direction.NORTH):0)|(s?bit(ModelPlan.Direction.SOUTH):0)|(e?bit(ModelPlan.Direction.EAST):0)|(w?bit(ModelPlan.Direction.WEST):0),overlays=0;
        if(kind==FluidGeometry.Kind.WATER) { if(n&&overlay(north))overlays|=bit(ModelPlan.Direction.NORTH);if(s&&overlay(south))overlays|=bit(ModelPlan.Direction.SOUTH);if(e&&overlay(east))overlays|=bit(ModelPlan.Direction.EAST);if(w&&overlay(west))overlays|=bit(ModelPlan.Direction.WEST); }
        boolean flowing=false,back=false;float sin=0,cos=0;
        if(top) { var flow=fluid.getFlow(view,position);if(!Double.isFinite(flow.x)||!Double.isFinite(flow.z))throw new Missing("Native fluid flow is not finite");flowing=flow.x!=0||flow.z!=0;
            if(flowing) { float angle=(float)Mth.atan2(flow.z,flow.x)-(float)(Math.PI/2);sin=Mth.sin(angle)*0.25f;cos=Mth.cos(angle)*0.25f; }back=fluid.shouldRenderBackwardUpFace(view,position.above()); }
        int rgb=kind==FluidGeometry.Kind.WATER&&faces!=0?(water==null?biome.getWaterColor()&0xffffff:water.rgb(position)):0xffffff;
        return new FluidGeometry.Cell(kind,nw,sw,se,ne,faces,overlays,back,flowing,sin,cos,rgb,host.getLightEmission());
    }
    private static boolean overlay(BlockState state) { return state.getBlock() instanceof HalfTransparentBlock||state.getBlock() instanceof LeavesBlock; }
    private static NativeVisualInputs.Result failed(WorldSnapshot snapshot,String policy,List<WorldProblem> problems) { return new NativeVisualInputs.Result(snapshot.source(),snapshot.id(),policy,Optional.empty(),Optional.empty(),problems); }
    private static void check(BooleanSupplier cancelled) { if(cancelled.getAsBoolean())throw new CancellationException("Native fluid capture cancelled"); }
}
