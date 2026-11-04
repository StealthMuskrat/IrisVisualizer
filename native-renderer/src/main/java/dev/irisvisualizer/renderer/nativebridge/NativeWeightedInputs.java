package dev.irisvisualizer.renderer.nativebridge;

import dev.irisvisualizer.renderer.*;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.util.random.WeightedList;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Native model selection with a source-reviewed dedicated factory or an explicitly supplied native RNG. */
public final class NativeWeightedInputs {
    private NativeWeightedInputs() {}
    public record Limits(NativeSceneInputs.Limits scene,int parts,int alternatives,long selectionVisits,long weightUnits,long nativeQueries) {
        public static final Limits DEFAULT=new Limits(NativeSceneInputs.Limits.DEFAULT,65536,65536,67108864,1048576,16777216);
        public Limits { Objects.requireNonNull(scene);if(parts<1||parts>65536||alternatives<1||alternatives>65536||selectionVisits<0||selectionVisits>268435456||weightUnits<0||weightUnits>1048576||nativeQueries<0||nativeQueries>16777216)throw new IllegalArgumentException("Native weighted input limits exceeded"); }
    }
    public record Usage(WorldModelChoices.Usage choices,NativeVisibility.Usage visibility,String randomClass,long weightedQueries,long multipartSeeds,long weightUnits) {
        public Usage { Objects.requireNonNull(choices);Objects.requireNonNull(visibility);Objects.requireNonNull(randomClass); }
    }
    public record Result(WorldSnapshot.Source source,UUID snapshotId,Optional<WorldRenderWorker.Request> request,Optional<Usage> usage,List<WorldProblem> problems,List<ModelPlan.Problem> assetProblems) {
        public Result {
            Objects.requireNonNull(source);Objects.requireNonNull(snapshotId);Objects.requireNonNull(request);Objects.requireNonNull(usage);problems=List.copyOf(problems);assetProblems=List.copyOf(assetProblems);boolean success=problems.isEmpty()&&assetProblems.isEmpty();
            if(success!=(request.isPresent()&&usage.isPresent())||!success&&(request.isPresent()||usage.isPresent())||request.isPresent()&&(!request.orElseThrow().visuals().source().equals(source)||!request.orElseThrow().visuals().snapshotId().equals(snapshotId)))throw new IllegalArgumentException("Partial native weighted input escaped failure");
        }
        public boolean requiresPlaceholder() { return !problems.isEmpty()||!assetProblems.isEmpty(); }
    }
    public static final String VANILLA_RANDOM_PROFILE="26.3";
    public static final String VANILLA_RANDOM_FACTORY="RandomSource.createThreadLocalInstance(0L)";
    /** A fresh common-native source per capture; no generation RNG or global unique-seed factory is touched. */
    private static RandomSource vanillaRandom(String minecraft) {
        if(!VANILLA_RANDOM_PROFILE.equals(minecraft)||!VANILLA_RANDOM_PROFILE.equals(BlockModels.PROFILE))throw NativeIdentities.failure(WorldProblem.Code.VERSION,"Vanilla model random factory requires its separately reviewed 26.3 profile");
        var random=RandomSource.createThreadLocalInstance(0L);
        if(!random.getClass().getName().equals("net.minecraft.world.level.levelgen.SingleThreadedRandomSource"))throw NativeIdentities.failure(WorldProblem.Code.ASSIGNMENT,"Pinned vanilla model random factory returned an unexpected native class");
        return random;
    }
    public static Result capture(NativeRenderContext context,ClientAssetSnapshot assets,WorldSnapshot snapshot,long tick,double partialTick,BooleanSupplier cancelled) { return capture(context,assets,snapshot,tick,partialTick,Limits.DEFAULT,WorldTintInput.none(snapshot),cancelled); }
    public static Result capture(NativeRenderContext context,ClientAssetSnapshot assets,WorldSnapshot snapshot,long tick,double partialTick,Limits limits,BooleanSupplier cancelled) { return capture(context,assets,snapshot,tick,partialTick,limits,WorldTintInput.none(snapshot),cancelled); }
    /** Factory allocation remains inside the checked capture; failures retain only detached diagnostics. */
    public static Result capture(NativeRenderContext context,ClientAssetSnapshot assets,WorldSnapshot snapshot,long tick,double partialTick,Limits limits,WorldTintInput tints,BooleanSupplier cancelled) { return capture(context,assets,snapshot,Optional.empty(),tick,partialTick,limits,tints,false,cancelled); }
    /** Caller owns/freezes this dedicated RNG and verifies its algorithm matches the pinned renderer; no factory is guessed. */
    public static Result capture(NativeRenderContext context,ClientAssetSnapshot assets,WorldSnapshot snapshot,RandomSource rendererRandom,long tick,double partialTick,BooleanSupplier cancelled) { return capture(context,assets,snapshot,rendererRandom,tick,partialTick,Limits.DEFAULT,cancelled); }
    public static Result capture(NativeRenderContext context,ClientAssetSnapshot assets,WorldSnapshot snapshot,RandomSource rendererRandom,long tick,double partialTick,Limits limits,BooleanSupplier cancelled) { return capture(context,assets,snapshot,rendererRandom,tick,partialTick,limits,WorldTintInput.none(snapshot),cancelled); }
    public static Result capture(NativeRenderContext context,ClientAssetSnapshot assets,WorldSnapshot snapshot,RandomSource rendererRandom,long tick,double partialTick,Limits limits,WorldTintInput tints,BooleanSupplier cancelled) {
        return capture(context,assets,snapshot,rendererRandom,tick,partialTick,limits,tints,false,cancelled);
    }
    /** Package-private so incomplete waterlogged bases cannot escape the admitted combined route. */
    static Result capture(NativeRenderContext context,ClientAssetSnapshot assets,WorldSnapshot snapshot,RandomSource rendererRandom,long tick,double partialTick,Limits limits,WorldTintInput tints,boolean fluidBases,BooleanSupplier cancelled) {
        return capture(context,assets,snapshot,Optional.of(rendererRandom),tick,partialTick,limits,tints,fluidBases,cancelled);
    }
    /** An absent explicit source selects only the reviewed vanilla factory, never a replacement algorithm. */
    static Result capture(NativeRenderContext context,ClientAssetSnapshot assets,WorldSnapshot snapshot,Optional<RandomSource> suppliedRandom,long tick,double partialTick,Limits limits,WorldTintInput tints,boolean fluidBases,BooleanSupplier cancelled) {
        Objects.requireNonNull(context);Objects.requireNonNull(assets);Objects.requireNonNull(snapshot);Objects.requireNonNull(suppliedRandom);Objects.requireNonNull(limits);Objects.requireNonNull(cancelled);
        if(tick<0||!Double.isFinite(partialTick)||partialTick<0||partialTick>=1)throw new IllegalArgumentException("Invalid native weighted input clock");
        try {
            check(cancelled);context.check();if(snapshot.requiresPlaceholder())return failed(snapshot,snapshot.problems(),List.of());var rendererRandom=suppliedRandom.orElseGet(()->vanillaRandom(snapshot.source().minecraftVersion()));String randomClass=rendererRandom.getClass().getName();
            if(!randomClass.startsWith("net.minecraft.")||randomClass.length()>128)throw NativeIdentities.failure(WorldProblem.Code.ASSIGNMENT,"Weighted inputs require an actual native renderer-matching random source, exclusively owned by the caller");
            if(tints.snapshot()!=snapshot||tints.requiresPlaceholder()||!tints.matchesAssets(assets.receipt()))throw NativeIdentities.failure(WorldProblem.Code.ASSIGNMENT,"Native weighted tints require the exact snapshot/assets");
            var s=limits.scene();long catalog=assets.receipt().capturedAssetBytes(),base=catalog+snapshot.indexBytes()+tints.bytes();
            if(base+(long)snapshot.cellCount()*9>s.workingBytes())throw NativeIdentities.failure(WorldProblem.Code.LIMIT,"Native weighted catalog/world/mask/assignment-copy peak exceeds its working budget");
            var o=s.offsets();long offsetWorking=Math.min(o.workingBytes(),s.workingBytes()-catalog-tints.bytes());
            var capturedOffsets=NativeOffsets.capture(context,snapshot,new WorldOffsets.Limits(o.translations(),o.payloadBytes(),offsetWorking,o.componentBlocks()),cancelled);if(capturedOffsets.requiresPlaceholder())return failed(snapshot,capturedOffsets.problems(),List.of());var offsets=capturedOffsets.offsets().orElseThrow();
            var original=s.models();var v=original.visuals();long modelWorking=Math.min(v.workingBytes(),s.workingBytes());
            var visualLimits=new WorldVisuals.Limits(v.choices(),v.modelStates(),v.meshVariants(),v.ticketReferences(),v.problemReferences(),v.sourcePackedBytes(),modelWorking);
            var modelLimits=new WorldModelInputs.Limits(visualLimits,original.models(),original.inspectedStates(),original.inspectedFaces());
            var capturedModels=NativeModelInputs.capture(context,assets,snapshot,tick,partialTick,modelLimits,offsets,tints,fluidBases,cancelled);if(capturedModels.requiresPlaceholder())return failed(snapshot,capturedModels.problems(),List.of());
            var choiceLimits=new WorldModelChoices.Limits(modelLimits,limits.parts(),limits.alternatives(),limits.selectionVisits());
            var plan=WorldModelChoices.inspect(assets,snapshot,capturedModels.inputs().orElseThrow().decisions(),offsets,tints,choiceLimits,cancelled);if(plan.requiresPlaceholder())return failed(snapshot,plan.problems(),plan.assetProblems());
            long other=catalog+offsets.bytes()+tints.bytes()+plan.bytes();if(other>s.workingBytes())throw NativeIdentities.failure(WorldProblem.Code.LIMIT,"Native weighted descriptors exceed combined source admission");
            var visibility=s.visibility();var mask=visibility.visibility();long maskWorking=Math.min(mask.workingBytes(),s.workingBytes()-other);
            var boundedVisibility=new NativeVisibility.Limits(new WorldVisibility.Limits(mask.maskBytes(),maskWorking),visibility.cachedPairs(),visibility.nativeQueries(),visibility.pairProbes());
            var capturedVisibility=NativeVisibility.capture(context,snapshot,boundedVisibility,cancelled);if(capturedVisibility.requiresPlaceholder())return failed(snapshot,capturedVisibility.problems(),List.of());var input=capturedVisibility.input().orElseThrow();
            long available=Math.min(modelWorking,s.workingBytes()-input.bytes());
            var reader=new NativeReader(context,snapshot,plan,rendererRandom,limits,cancelled);
            String policy="Native BlockState seed; "+(suppliedRandom.isEmpty()?"26.3 factory "+VANILLA_RANDOM_FACTORY:"supplied "+randomClass)+"; native WeightedList; multipart seed reset";
            var choices=WorldModelChoices.capture(plan,policy,reader,tick,partialTick,available,cancelled);if(choices.requiresPlaceholder())return failed(snapshot,choices.problems(),choices.assetProblems());
            var request=new WorldRenderWorker.Request(choices.request().orElseThrow(),input,s.scene(),s.section(),s.workingBytes());
            context.check();check(cancelled);var usage=new Usage(choices.usage().orElseThrow(),capturedVisibility.usage().orElseThrow(),randomClass,reader.queries,reader.multipartSeeds,reader.weights);
            return new Result(snapshot.source(),snapshot.id(),Optional.of(request),Optional.of(usage),List.of(),List.of());
        }catch(WorldCapture.ReadFailure failure) { check(cancelled);return failed(snapshot,List.of(failure.problem()),List.of()); }
        catch(CancellationException cancellation) { throw cancellation; }
        catch(RuntimeException failure) { check(cancelled);return failed(snapshot,List.of(new WorldProblem(WorldProblem.Code.WORKER,Optional.empty(),"Native weighted input capture failed: "+failure.getClass().getSimpleName())),List.of()); }
        catch(OutOfMemoryError exhausted) { check(cancelled);return failed(snapshot,List.of(new WorldProblem(WorldProblem.Code.MEMORY,Optional.empty(),"Not enough memory to capture native weighted scene input")),List.of()); }
    }
    private static final class NativeReader implements WorldModelChoices.Reader {
        final NativeRenderContext context;final WorldSnapshot snapshot;final RandomSource random;final Limits limits;final BooleanSupplier cancelled;final List<List<WeightedList<Integer>>> lists=new ArrayList<>();final BlockState[] states;
        final BlockPos.MutableBlockPos position=new BlockPos.MutableBlockPos();WorldModelChoices.State current;long partSeed,queries,multipartSeeds,weights;
        NativeReader(NativeRenderContext context,WorldSnapshot snapshot,WorldModelChoices.Plan plan,RandomSource random,Limits limits,BooleanSupplier cancelled) {
            this.context=context;this.snapshot=snapshot;this.random=random;this.limits=limits;this.cancelled=cancelled;states=new BlockState[plan.states().size()];
            for(var state:plan.states()) {
                check(cancelled);context.checkSession();var nativeState=Block.stateById(snapshot.states().get(state.stateIndex()).nativeStateId());
                if(Block.getId(nativeState)!=snapshot.states().get(state.stateIndex()).nativeStateId())throw NativeIdentities.failure(WorldProblem.Code.STATE,"Native weighted state ID changed after registry verification");states[state.stateIndex()]=nativeState;var parts=new ArrayList<WeightedList<Integer>>(state.parts().size());
                for(var part:state.parts()) {
                    check(cancelled);context.checkSession();if(part.alternatives().size()==1) { parts.add(null);continue; }
                    if(part.totalWeight()>limits.weightUnits()-weights)throw NativeIdentities.failure(WorldProblem.Code.LIMIT,"Native weighted-list aggregate weight budget reached");weights+=part.totalWeight();
                    var builder=WeightedList.<Integer>builder();for(int i=0;i<part.alternatives().size();i++) { check(cancelled);builder.add(part.firstTicket(i),part.alternatives().get(i).weight()); }parts.add(builder.build());
                }
                lists.add(Collections.unmodifiableList(parts));
            }
        }
        @Override public void begin(int cell,WorldModelChoices.State state) {
            check(cancelled);context.checkSession();current=state;var extent=snapshot.extent();long layer=(long)extent.width()*extent.depth();
            position.set((int)(extent.minX()+cell%extent.width()),(int)((long)extent.minY()+cell/layer),(int)(extent.minZ()+(cell/extent.width())%extent.depth()));
            random.setSeed(states[state.stateIndex()].getSeed(position));
            if(state.multipart()) { query();partSeed=random.nextLong();multipartSeeds++; }
        }
        private void query() { if(queries+multipartSeeds>=limits.nativeQueries())throw NativeIdentities.failure(WorldProblem.Code.LIMIT,"Native weighted/multipart query budget reached"); }
        @Override public int ticket(int part) {
            check(cancelled);context.checkSession();if(current.multipart())random.setSeed(partSeed);var list=lists.get(current.stateIndex()).get(part);if(list==null)return 0;
            query();queries++;return list.getRandomOrThrow(random);
        }
        @Override public void complete() { context.check(); }
    }
    private static Result failed(WorldSnapshot snapshot,List<WorldProblem> problems,List<ModelPlan.Problem> assets) { return new Result(snapshot.source(),snapshot.id(),Optional.empty(),Optional.empty(),problems,assets); }
    private static void check(BooleanSupplier cancelled) { if(cancelled.getAsBoolean())throw new CancellationException("Native weighted input capture cancelled"); }
}
