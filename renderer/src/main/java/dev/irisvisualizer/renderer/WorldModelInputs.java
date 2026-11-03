package dev.irisvisualizer.renderer;

import java.util.*;
import java.util.function.BooleanSupplier;

/** Builds frozen per-cell inputs from explicit native state decisions, with no random-choice substitute. */
public final class WorldModelInputs {
    private WorldModelInputs() {}
    public enum Mode { MODEL, EMPTY, PLACEHOLDER }
    public record Decision(Mode mode,List<ModelPlan.Problem> problems) {
        public Decision { Objects.requireNonNull(mode);problems=List.copyOf(problems);if(problems.size()>256||((mode==Mode.PLACEHOLDER)!=!problems.isEmpty()))throw new IllegalArgumentException("Native visual decision needs consistent bounded diagnostics"); }
        public static Decision model() { return new Decision(Mode.MODEL,List.of()); }
        public static Decision empty() { return new Decision(Mode.EMPTY,List.of()); }
        public static Decision placeholder(List<ModelPlan.Problem> problems) { return new Decision(Mode.PLACEHOLDER,problems); }
    }
    public record Limits(WorldVisuals.Limits visuals,BlockModels.Limits models,int inspectedStates,int inspectedFaces) {
        public static final Limits DEFAULT=new Limits(WorldVisuals.Limits.DEFAULT,BlockModels.Limits.DEFAULT,1024,262144);
        public Limits { Objects.requireNonNull(visuals);Objects.requireNonNull(models);if(inspectedStates<0||inspectedStates>1024||inspectedFaces<0||inspectedFaces>262144)throw new IllegalArgumentException("Native visual inspection limits exceed renderer bounds"); }
    }
    public record Usage(int inspectedStates,int inspectedFaces,int modelStates,int emptyStates,int placeholderStates,int ticketReferences,long assignmentBytes) {}
    /** Frozen native decisions; queuing this object retains no engine, registry or native callback. */
    public static final class Request {
        private final ClientAssetSnapshot assets;private final WorldSnapshot snapshot;private final List<Decision> decisions;private final long tick;private final double partialTick;private final Limits limits;
        private Request(ClientAssetSnapshot assets,WorldSnapshot snapshot,List<Decision> decisions,long tick,double partialTick,Limits limits) { this.assets=assets;this.snapshot=snapshot;this.decisions=decisions;this.tick=tick;this.partialTick=partialTick;this.limits=limits; }
        public static Request capture(ClientAssetSnapshot assets,WorldSnapshot snapshot,List<Decision> decisions,long tick,double partialTick,BooleanSupplier cancelled) { return capture(assets,snapshot,decisions,tick,partialTick,Limits.DEFAULT,cancelled); }
        public static Request capture(ClientAssetSnapshot assets,WorldSnapshot snapshot,List<Decision> decisions,long tick,double partialTick,Limits limits,BooleanSupplier cancelled) {
            Objects.requireNonNull(assets);Objects.requireNonNull(snapshot);Objects.requireNonNull(decisions);Objects.requireNonNull(limits);Objects.requireNonNull(cancelled);WorldProblem.check(cancelled);
            if(tick<0||!Double.isFinite(partialTick)||partialTick<0||partialTick>=1)throw new IllegalArgumentException("Invalid native decision clock");
            if(snapshot.requiresPlaceholder()||decisions.size()!=snapshot.states().size()||decisions.size()>limits.visuals().choices())throw new IllegalArgumentException("Native decisions require a complete bounded exact state palette");
            if(!snapshot.source().minecraftVersion().equals(BlockModels.PROFILE)||!assets.receipt().version().equals(BlockModels.PROFILE)||!assets.receipt().expectedVersion().equals(BlockModels.PROFILE))throw new IllegalArgumentException("Native decisions require the pinned native/client profile");
            if(assets.receipt().capturedAssetBytes()+snapshot.indexBytes()>limits.visuals().workingBytes())throw new IllegalArgumentException("Native decision input payload exceeds its working budget");
            var frozen=List.copyOf(decisions);long problems=0;
            for(var decision:frozen) { WorldProblem.check(cancelled);problems+=decision.problems().size();if(problems>limits.visuals().problemReferences())throw new IllegalArgumentException("Native decision diagnostic-reference budget reached"); }
            return new Request(assets,snapshot,frozen,tick,partialTick,limits);
        }
        public WorldSnapshot snapshot() { return snapshot; }
        public Limits limits() { return limits; }
        public long inputBytes() { return assets.receipt().capturedAssetBytes()+snapshot.indexBytes(); }
        /** Source catalog/raw input outside the future scene's shared native/choice indices. */
        public long extraSceneBytes() { return assets.receipt().capturedAssetBytes(); }
        public Request withWorkingBytes(long bytes) {
            if(bytes<inputBytes()||bytes>limits.visuals().workingBytes())throw new IllegalArgumentException("Reduced native decision allowance cannot hold its frozen inputs");
            var v=limits.visuals();var bounded=new WorldVisuals.Limits(v.choices(),v.modelStates(),v.meshVariants(),v.ticketReferences(),v.problemReferences(),v.sourcePackedBytes(),bytes);
            return new Request(assets,snapshot,decisions,tick,partialTick,new Limits(bounded,limits.models(),limits.inspectedStates(),limits.inspectedFaces()));
        }
    }
    public record Result(WorldSnapshot.Source source,UUID snapshotId,Optional<WorldVisuals.Request> request,Optional<Usage> usage,List<WorldProblem> problems,List<ModelPlan.Problem> assetProblems) {
        public Result {
            Objects.requireNonNull(source);Objects.requireNonNull(snapshotId);Objects.requireNonNull(request);Objects.requireNonNull(usage);problems=List.copyOf(problems);assetProblems=List.copyOf(assetProblems);boolean success=problems.isEmpty()&&assetProblems.isEmpty();
            if(success!=(request.isPresent()&&usage.isPresent())||!success&&(request.isPresent()||usage.isPresent())||request.isPresent()&&(!request.orElseThrow().source().equals(source)||!request.orElseThrow().snapshotId().equals(snapshotId)))throw new IllegalArgumentException("Partial native visual input escaped failure");
        }
        public boolean requiresPlaceholder() { return !problems.isEmpty()||!assetProblems.isEmpty(); }
    }
    public static Result prepare(ClientAssetSnapshot assets,WorldSnapshot snapshot,List<Decision> decisions,long tick,double partialTick,BooleanSupplier cancelled) { return prepare(assets,snapshot,decisions,tick,partialTick,Limits.DEFAULT,cancelled); }
    public static Result prepare(Request request,BooleanSupplier cancelled) { Objects.requireNonNull(request);return prepare(request.assets,request.snapshot,request.decisions,request.tick,request.partialTick,request.limits,cancelled); }
    public static Result prepare(ClientAssetSnapshot assets,WorldSnapshot snapshot,List<Decision> decisions,long tick,double partialTick,Limits limits,BooleanSupplier cancelled) {
        Objects.requireNonNull(assets);Objects.requireNonNull(snapshot);Objects.requireNonNull(decisions);Objects.requireNonNull(limits);Objects.requireNonNull(cancelled);WorldProblem.check(cancelled);
        if(tick<0||!Double.isFinite(partialTick)||partialTick<0||partialTick>=1)throw new IllegalArgumentException("Invalid visual animation clock");
        try { return execute(assets,snapshot,decisions,tick,partialTick,limits,cancelled); }
        catch(WorldProblem.Failure failure) { WorldProblem.check(cancelled);return failed(snapshot,List.of(failure.problem),List.of()); }
        catch(ModelFields.Failure failure) { WorldProblem.check(cancelled);return failed(snapshot,List.of(),List.of(failure.problem)); }
        catch(OutOfMemoryError exhausted) { WorldProblem.check(cancelled);return failed(snapshot,List.of(new WorldProblem(WorldProblem.Code.MEMORY,Optional.empty(),"Not enough memory to prepare native model inputs; no partial request was published")),List.of()); }
    }
    private static Result failed(WorldSnapshot snapshot,List<WorldProblem> problems,List<ModelPlan.Problem> assets) { return new Result(snapshot.source(),snapshot.id(),Optional.empty(),Optional.empty(),problems,assets); }
    private static void fatal(List<ModelPlan.Problem> problems) { for(var problem:problems)if(TextureBatch.fatal(problem.code()))throw new ModelFields.Failure(problem); }
    private static ModelPlan.Problem unsupported(RenderBlockState state,ModelPlan.Code code,String pointer,String reason) { return new ModelPlan.Problem(code,ClientAssetSnapshot.Kind.BLOCKSTATE,state.block(),pointer,reason); }
    private static Result execute(ClientAssetSnapshot assets,WorldSnapshot snapshot,List<Decision> input,long tick,double partialTick,Limits limits,BooleanSupplier cancelled) {
        if(snapshot.requiresPlaceholder())return failed(snapshot,snapshot.problems(),List.of());
        if(!snapshot.source().minecraftVersion().equals(BlockModels.PROFILE)||!assets.receipt().version().equals(BlockModels.PROFILE)||!assets.receipt().expectedVersion().equals(BlockModels.PROFILE))throw WorldProblem.fail(WorldProblem.Code.VERSION,"Native model inputs require the pinned snapshot/client profile");
        if(input.size()!=snapshot.states().size()||input.size()>limits.visuals().choices())throw WorldProblem.fail(WorldProblem.Code.PALETTE,"Supply one bounded native visual decision for every exact state palette entry");
        long assignmentBytes=(long)snapshot.cellCount()*4,known=assets.receipt().capturedAssetBytes()+snapshot.indexBytes()+assignmentBytes*2;
        if(known>limits.visuals().workingBytes())throw WorldProblem.fail(WorldProblem.Code.LIMIT,"Native input inspection plus full-volume assignment/copy payload exceeds its working budget");
        var decisions=List.copyOf(input);var choices=new ArrayList<WorldVisuals.Choice>(input.size());int inspected=0,faces=0,models=0,empty=0,placeholders=0,tickets=0,problems=0;
        for(int index=0;index<decisions.size();index++) {
            WorldProblem.check(cancelled);var decision=decisions.get(index);var state=snapshot.states().get(index);WorldVisuals.Choice choice;fatal(decision.problems());
            if(!state.block().namespace().equals("minecraft")) { choice=new WorldVisuals.ProblemChoice(index,List.of(unsupported(state,ModelPlan.Code.EXTERNAL_ASSET,"/native/render_shape","External generated blocks are not supported by vanilla model preparation")));placeholders++;problems++; }
            else if(decision.mode()==Mode.EMPTY) { choice=new WorldVisuals.EmptyChoice(index);empty++; }
            else if(decision.mode()==Mode.PLACEHOLDER) { fatal(decision.problems());choice=new WorldVisuals.ProblemChoice(index,decision.problems());placeholders++;problems+=decision.problems().size(); }
            else {
                if(++inspected>limits.inspectedStates())throw WorldProblem.fail(WorldProblem.Code.LIMIT,"Native model inspection state budget reached");
                // Plans die at the end of this iteration. Later baking resolves the same immutable asset capture.
                var plan=BlockModels.resolve(assets,state,limits.models(),cancelled);fatal(plan.problems());var errors=plan.problems();
                if(errors.isEmpty()) {
                    ModelPlan.Problem unsupported=null;int selectedFaces=0;
                    for(var part:plan.parts()) {
                        WorldProblem.check(cancelled);
                        if(part.totalWeight()!=1) { unsupported=unsupported(state,ModelPlan.Code.SELECTION,part.dispatch().pointer(),"Verified native weighted model tickets are not supplied; no random policy was substituted");break; }
                        for(var element:part.choose(0).model().elements())for(var entry:element.faces().entrySet()) {
                            var face=entry.getValue();
                            WorldProblem.check(cancelled);selectedFaces++;if(++faces>limits.inspectedFaces())throw WorldProblem.fail(WorldProblem.Code.LIMIT,"Native model inspection aggregate face budget reached");
                            if(face.tintIndex()>=0&&unsupported==null)unsupported=new ModelPlan.Problem(ModelPlan.Code.UNSUPPORTED_GEOMETRY,element.source().kind(),element.source().asset(),element.source().pointer()+"/faces/"+entry.getKey().name().toLowerCase(Locale.ROOT)+"/tintindex","Native biome/block tint producer is not supplied for this visual");
                        }
                    }
                    if(unsupported==null&&selectedFaces==0)unsupported=unsupported(state,ModelPlan.Code.UNSUPPORTED_GEOMETRY,"/native/render_shape","A native MODEL decision with no verified geometric faces is not inferred to be invisible");
                    if(unsupported!=null)errors=List.of(unsupported);
                }
                if(!errors.isEmpty()) { choice=new WorldVisuals.ProblemChoice(index,errors);placeholders++;problems+=errors.size(); }
                else {
                    if(++models>limits.visuals().modelStates()||models>limits.visuals().meshVariants()||plan.parts().size()>limits.visuals().ticketReferences()-tickets)throw WorldProblem.fail(WorldProblem.Code.LIMIT,"Native deterministic model/ticket metadata budget reached");
                    tickets+=plan.parts().size();choice=new WorldVisuals.ModelChoice(index,Collections.nCopies(plan.parts().size(),0));
                }
            }
            if(problems>limits.visuals().problemReferences())throw WorldProblem.fail(WorldProblem.Code.LIMIT,"Native visual problem-reference budget reached");choices.add(choice);
        }
        int[] assignments=new int[snapshot.cellCount()];for(int cell=0;cell<assignments.length;cell++) { if((cell&4095)==0)WorldProblem.check(cancelled);assignments[cell]=snapshot.stateIndex(cell); }
        var request=WorldVisuals.Request.capture(assets,snapshot,choices,assignments,tick,partialTick,limits.visuals(),cancelled);WorldProblem.check(cancelled);
        return new Result(snapshot.source(),snapshot.id(),Optional.of(request),Optional.of(new Usage(inspected,faces,models,empty,placeholders,tickets,assignmentBytes)),List.of(),List.of());
    }
}
