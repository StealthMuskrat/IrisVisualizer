package dev.irisvisualizer.renderer;

import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Bounded model-selection metadata and streaming capture of native per-cell choices. No RNG is implemented here. */
public final class WorldModelChoices {
    private WorldModelChoices() {}
    public record Limits(WorldModelInputs.Limits models,int parts,int alternatives,long selectionVisits) {
        public static final Limits DEFAULT=new Limits(WorldModelInputs.Limits.DEFAULT,65536,65536,67108864);
        public Limits { Objects.requireNonNull(models);if(parts<1||parts>65536||alternatives<1||alternatives>65536||selectionVisits<0||selectionVisits>268435456)throw new IllegalArgumentException("Model-choice metadata/visit limits exceeded"); }
    }
    public record Alternative(int weight,int faces,Optional<ModelPlan.Problem> problem) {
        public Alternative { Objects.requireNonNull(problem);if(weight<1||faces<0)throw new IllegalArgumentException("Invalid model-selection alternative"); }
    }
    public static final class Part {
        private final List<Alternative> alternatives;private final int[] ends;
        private Part(List<Alternative> alternatives) {
            this.alternatives=List.copyOf(alternatives);ends=new int[alternatives.size()];long sum=0;
            for(int i=0;i<ends.length;i++) { sum+=alternatives.get(i).weight();ends[i]=Math.toIntExact(sum); }
        }
        public List<Alternative> alternatives() { return alternatives; }
        public int totalWeight() { return ends[ends.length-1]; }
        /** Canonical interval start identifies the selected model, not a recorded raw random draw. */
        public int firstTicket(int alternative) { if(alternative<0||alternative>=ends.length)throw new IndexOutOfBoundsException();return alternative==0?0:ends[alternative-1]; }
        private int selected(int ticket) {
            if(ticket<0||ticket>=totalWeight())throw WorldProblem.fail(WorldProblem.Code.ASSIGNMENT,"Native model ticket is outside the exact declared weight range");
            int low=0,high=ends.length-1;while(low<high) { int mid=(low+high)>>>1;if(ticket<ends[mid])high=mid;else low=mid+1; }return low;
        }
    }
    public record State(int stateIndex,boolean multipart,List<Part> parts,Optional<WorldVisuals.Choice> fixed) {
        public State { parts=List.copyOf(parts);Objects.requireNonNull(fixed);if(stateIndex<0||parts.size()>1024||fixed.isPresent()!=parts.isEmpty()||fixed.isPresent()&&fixed.orElseThrow().stateIndex()!=stateIndex)throw new IllegalArgumentException("Invalid model-choice state plan"); }
    }
    public interface Reader {
        void begin(int cell,State state);
        int ticket(int part);
        default void complete() {}
    }
    public static final class Plan {
        private final ClientAssetSnapshot assets;private final WorldSnapshot snapshot;private final WorldOffsets offsets;private final Limits limits;private final List<State> states;private final int maxParts;private final long bytes;private final List<WorldProblem> problems;private final List<ModelPlan.Problem> assetProblems;
        private Plan(ClientAssetSnapshot assets,WorldSnapshot snapshot,WorldOffsets offsets,Limits limits,List<State> states,int maxParts,long bytes,List<WorldProblem> problems,List<ModelPlan.Problem> assetProblems) {
            this.assets=assets;this.snapshot=snapshot;this.offsets=offsets;this.limits=limits;this.states=List.copyOf(states);this.maxParts=maxParts;this.bytes=bytes;this.problems=List.copyOf(problems);this.assetProblems=List.copyOf(assetProblems);
            if((!problems.isEmpty()||!assetProblems.isEmpty())&&(!states.isEmpty()||bytes!=0||maxParts!=0))throw new IllegalArgumentException("Partial model-choice plan escaped failure");
        }
        public WorldSnapshot snapshot() { return snapshot; }
        public List<State> states() { return states; }
        public long bytes() { return bytes; }
        public List<WorldProblem> problems() { return problems; }
        public List<ModelPlan.Problem> assetProblems() { return assetProblems; }
        public boolean requiresPlaceholder() { return !problems.isEmpty()||!assetProblems.isEmpty(); }
    }
    public record Usage(String policy,int choices,int modelVariants,int ticketReferences,long selectionVisits) { public Usage { Objects.requireNonNull(policy); } }
    public record Capture(WorldSnapshot.Source source,UUID snapshotId,Optional<WorldVisuals.Request> request,Optional<Usage> usage,List<WorldProblem> problems,List<ModelPlan.Problem> assetProblems) {
        public Capture {
            Objects.requireNonNull(source);Objects.requireNonNull(snapshotId);Objects.requireNonNull(request);Objects.requireNonNull(usage);problems=List.copyOf(problems);assetProblems=List.copyOf(assetProblems);boolean success=problems.isEmpty()&&assetProblems.isEmpty();
            if(success!=(request.isPresent()&&usage.isPresent())||!success&&(request.isPresent()||usage.isPresent())||request.isPresent()&&(!request.orElseThrow().source().equals(source)||!request.orElseThrow().snapshotId().equals(snapshotId)))throw new IllegalArgumentException("Partial model-choice capture escaped failure");
        }
        public boolean requiresPlaceholder() { return !problems.isEmpty()||!assetProblems.isEmpty(); }
    }
    private static void fatal(List<ModelPlan.Problem> problems) { for(var problem:problems)if(TextureBatch.fatal(problem.code()))throw new ModelFields.Failure(problem); }
    private static ModelPlan.Problem problem(RenderBlockState state,String pointer,String reason) { return new ModelPlan.Problem(ModelPlan.Code.UNSUPPORTED_GEOMETRY,ClientAssetSnapshot.Kind.BLOCKSTATE,state.block(),pointer,reason); }
    private static State fixed(int index,WorldVisuals.Choice choice) { return new State(index,false,List.of(),Optional.of(choice)); }
    public static Plan inspect(ClientAssetSnapshot assets,WorldSnapshot snapshot,List<WorldModelInputs.Decision> decisions,WorldOffsets offsets,Limits limits,BooleanSupplier cancelled) {
        Objects.requireNonNull(assets);Objects.requireNonNull(snapshot);Objects.requireNonNull(decisions);Objects.requireNonNull(offsets);Objects.requireNonNull(limits);Objects.requireNonNull(cancelled);WorldProblem.check(cancelled);
        try { return inspectStates(assets,snapshot,decisions,offsets,limits,cancelled); }
        catch(WorldProblem.Failure failure) { WorldProblem.check(cancelled);return failedPlan(assets,snapshot,offsets,limits,List.of(failure.problem),List.of()); }
        catch(ModelFields.Failure failure) { WorldProblem.check(cancelled);return failedPlan(assets,snapshot,offsets,limits,List.of(),List.of(failure.problem)); }
        catch(OutOfMemoryError exhausted) { WorldProblem.check(cancelled);return failedPlan(assets,snapshot,offsets,limits,List.of(new WorldProblem(WorldProblem.Code.MEMORY,Optional.empty(),"Not enough memory to inspect native model choices")),List.of()); }
    }
    private static Plan failedPlan(ClientAssetSnapshot assets,WorldSnapshot snapshot,WorldOffsets offsets,Limits limits,List<WorldProblem> problems,List<ModelPlan.Problem> assetProblems) { return new Plan(assets,snapshot,offsets,limits,List.of(),0,0,problems,assetProblems); }
    private static Plan inspectStates(ClientAssetSnapshot assets,WorldSnapshot snapshot,List<WorldModelInputs.Decision> decisions,WorldOffsets offsets,Limits limits,BooleanSupplier cancelled) {
        if(snapshot.requiresPlaceholder())return failedPlan(assets,snapshot,offsets,limits,snapshot.problems(),List.of());
        if(offsets.snapshot()!=snapshot||offsets.requiresPlaceholder())throw WorldProblem.fail(WorldProblem.Code.ASSIGNMENT,"Native model choices require offsets from the exact successful snapshot");
        if(!snapshot.source().minecraftVersion().equals(BlockModels.PROFILE)||!assets.receipt().version().equals(BlockModels.PROFILE)||!assets.receipt().expectedVersion().equals(BlockModels.PROFILE))throw WorldProblem.fail(WorldProblem.Code.VERSION,"Native model choices require the pinned native/client profile");
        var v=limits.models().visuals();if(decisions.size()!=snapshot.states().size()||decisions.size()>v.choices())throw WorldProblem.fail(WorldProblem.Code.PALETTE,"Supply exactly one bounded native decision per state");
        long base=assets.receipt().capturedAssetBytes()+snapshot.indexBytes()+offsets.bytes();if(base>v.workingBytes())throw WorldProblem.fail(WorldProblem.Code.LIMIT,"Native model-choice source working budget reached");
        var input=List.copyOf(decisions);var states=new ArrayList<State>(input.size());int inspected=0,parts=0,alternatives=0,faces=0,maxParts=0,problems=0;long bytes=0;
        for(int index=0;index<input.size();index++) {
            WorldProblem.check(cancelled);var state=snapshot.states().get(index);var decision=input.get(index);fatal(decision.problems());State result;
            if(!state.block().namespace().equals("minecraft"))result=fixed(index,new WorldVisuals.ProblemChoice(index,List.of(new ModelPlan.Problem(ModelPlan.Code.EXTERNAL_ASSET,ClientAssetSnapshot.Kind.BLOCKSTATE,state.block(),"/native/render_shape","External generated blocks are not supported by vanilla model choices"))));
            else if(decision.mode()==WorldModelInputs.Mode.EMPTY)result=fixed(index,new WorldVisuals.EmptyChoice(index));
            else if(decision.mode()==WorldModelInputs.Mode.PLACEHOLDER)result=fixed(index,new WorldVisuals.ProblemChoice(index,decision.problems()));
            else {
                if(++inspected>limits.models().inspectedStates())throw WorldProblem.fail(WorldProblem.Code.LIMIT,"Native model-choice inspected-state limit reached");
                var plan=BlockModels.resolve(assets,state,limits.models().models(),cancelled);fatal(plan.problems());
                if(plan.requiresPlaceholder())result=fixed(index,new WorldVisuals.ProblemChoice(index,plan.problems()));
                else if(plan.parts().isEmpty())result=fixed(index,new WorldVisuals.ProblemChoice(index,List.of(problem(state,"/native/render_shape","Native MODEL without verified faces is not inferred invisible"))));
                else {
                    if(plan.parts().size()>1024||plan.parts().size()>limits.parts()-parts)throw WorldProblem.fail(WorldProblem.Code.LIMIT,"Native model-choice part metadata limit reached");parts+=plan.parts().size();maxParts=Math.max(maxParts,plan.parts().size());var selected=new ArrayList<Part>(plan.parts().size());
                    for(var part:plan.parts()) {
                        WorldProblem.check(cancelled);if(part.choices().size()>limits.alternatives()-alternatives)throw WorldProblem.fail(WorldProblem.Code.LIMIT,"Native model-choice alternative metadata limit reached");alternatives+=part.choices().size();bytes+=(long)part.choices().size()*4;
                        if(base+bytes>v.workingBytes())throw WorldProblem.fail(WorldProblem.Code.LIMIT,"Native model-choice descriptor payload exceeds the source working allowance");var choices=new ArrayList<Alternative>(part.choices().size());
                        for(var choice:part.choices()) {
                            int selectedFaces=0;ModelPlan.Problem tint=null;
                            for(var element:choice.model().elements())for(var entry:element.faces().entrySet()) {
                                WorldProblem.check(cancelled);selectedFaces++;if(++faces>limits.models().inspectedFaces())throw WorldProblem.fail(WorldProblem.Code.LIMIT,"Native model-choice aggregate face inspection limit reached");
                                if(entry.getValue().tintIndex()>=0&&tint==null)tint=new ModelPlan.Problem(ModelPlan.Code.UNSUPPORTED_GEOMETRY,element.source().kind(),element.source().asset(),element.source().pointer()+"/faces/"+entry.getKey().name().toLowerCase(Locale.ROOT)+"/tintindex","Native biome/block tint producer is not supplied for this selected visual");
                            }
                            choices.add(new Alternative(choice.weight(),selectedFaces,Optional.ofNullable(tint)));if(tint!=null&&++problems>v.problemReferences())throw WorldProblem.fail(WorldProblem.Code.LIMIT,"Native model-choice descriptor diagnostic limit reached");
                        }
                        selected.add(new Part(choices));
                    }
                    result=new State(index,plan.parts().getFirst().dispatch().pointer().startsWith("/multipart/"),selected,Optional.empty());
                }
            }
            if(result.fixed().orElse(null) instanceof WorldVisuals.ProblemChoice issue) { fatal(issue.problems());problems+=issue.problems().size();if(problems>v.problemReferences())throw WorldProblem.fail(WorldProblem.Code.LIMIT,"Native model-choice diagnostic limit reached"); }states.add(result);
        }
        WorldProblem.check(cancelled);return new Plan(assets,snapshot,offsets,limits,states,maxParts,bytes,List.of(),List.of());
    }
    /** Keys retain private canonical tickets only for new mesh variants; cell probes borrow one reusable scratch array. */
    private static final class Key {
        final int state,count;final int[] tickets;final int hash;
        Key(int state,int count,int[] tickets) { this.state=state;this.count=count;this.tickets=tickets;int hash=state;for(int i=0;i<count;i++)hash=31*hash+tickets[i];this.hash=hash; }
        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object other) { if(!(other instanceof Key key)||state!=key.state||count!=key.count)return false;for(int i=0;i<count;i++)if(tickets[i]!=key.tickets[i])return false;return true; }
    }
    public static Capture capture(Plan plan,String policy,Reader reader,long tick,double partialTick,long workingBytes,BooleanSupplier cancelled) {
        Objects.requireNonNull(plan);Objects.requireNonNull(policy);Objects.requireNonNull(reader);Objects.requireNonNull(cancelled);WorldProblem.check(cancelled);
        if(policy.isBlank()||policy.length()>256||tick<0||!Double.isFinite(partialTick)||partialTick<0||partialTick>=1||workingBytes<0||workingBytes>plan.limits.models().visuals().workingBytes())throw new IllegalArgumentException("Invalid native model-choice policy, clock or working allowance");
        try { return captureCells(plan,policy,reader,tick,partialTick,workingBytes,cancelled); }
        catch(WorldCapture.ReadFailure failure) { WorldProblem.check(cancelled);return failedCapture(plan,List.of(failure.problem()),List.of()); }
        catch(WorldProblem.Failure failure) { WorldProblem.check(cancelled);return failedCapture(plan,List.of(failure.problem),List.of()); }
        catch(CancellationException cancelledInput) { throw cancelledInput; }
        catch(RuntimeException failure) { WorldProblem.check(cancelled);return failedCapture(plan,List.of(new WorldProblem(WorldProblem.Code.WORKER,Optional.empty(),"Native model-choice reader failed: "+failure.getClass().getSimpleName())),List.of()); }
        catch(OutOfMemoryError exhausted) { WorldProblem.check(cancelled);return failedCapture(plan,List.of(new WorldProblem(WorldProblem.Code.MEMORY,Optional.empty(),"Not enough memory to capture native per-cell choices")),List.of()); }
    }
    private static Capture failedCapture(Plan plan,List<WorldProblem> problems,List<ModelPlan.Problem> assets) { return new Capture(plan.snapshot.source(),plan.snapshot.id(),Optional.empty(),Optional.empty(),problems,assets); }
    private static Capture captureCells(Plan plan,String policy,Reader reader,long tick,double partialTick,long workingBytes,BooleanSupplier cancelled) {
        if(plan.requiresPlaceholder())return failedCapture(plan,plan.problems,plan.assetProblems);var snapshot=plan.snapshot;var v=plan.limits.models().visuals();
        long scratch=(long)(plan.maxParts+plan.states.size()+v.ticketReferences())*4,base=plan.assets.receipt().capturedAssetBytes()+snapshot.indexBytes()+plan.offsets.bytes();
        if(base+plan.bytes+scratch+(long)snapshot.cellCount()*8>workingBytes)throw WorldProblem.fail(WorldProblem.Code.LIMIT,"Native model-choice source, descriptor, scratch and assignment-copy peak exceeds its working allowance");
        int[] assignments=new int[snapshot.cellCount()],fixedIndices=new int[plan.states.size()],tickets=new int[plan.maxParts];Arrays.fill(fixedIndices,-1);var keys=new HashMap<Key,Integer>();var choices=new ArrayList<WorldVisuals.Choice>();var modelStates=new HashSet<Integer>();int variants=0,ticketReferences=0,problemReferences=0;long visits=0;
        for(int cell=0;cell<assignments.length;cell++) {
            WorldProblem.check(cancelled);var state=plan.states.get(snapshot.stateIndex(cell));WorldVisuals.Choice choice;Integer index=null;
            if(state.fixed().isPresent()) { int fixed=fixedIndices[state.stateIndex()];if(fixed>=0) { assignments[cell]=fixed;continue; }choice=state.fixed().orElseThrow(); }
            else {
                if(state.parts().size()>plan.limits.selectionVisits()-visits)throw WorldProblem.fail(WorldProblem.Code.LIMIT,"Native model-choice part traversal budget reached");visits+=state.parts().size();reader.begin(cell,state);ModelPlan.Problem issue=null;int selectedFaces=0;
                for(int part=0;part<state.parts().size();part++) { WorldProblem.check(cancelled);var weights=state.parts().get(part);int selected=weights.selected(reader.ticket(part));tickets[part]=weights.firstTicket(selected);var alternative=weights.alternatives().get(selected);selectedFaces+=alternative.faces();if(issue==null)issue=alternative.problem().orElse(null); }
                var probe=new Key(state.stateIndex(),state.parts().size(),tickets);index=keys.get(probe);if(index!=null) { assignments[cell]=index;continue; }
                if(issue==null&&selectedFaces==0)issue=problem(snapshot.states().get(state.stateIndex()),"/native/render_shape","A selected native MODEL without verified faces is not inferred invisible");
                if(++variants>v.meshVariants()||state.parts().size()>v.ticketReferences()-ticketReferences)throw WorldProblem.fail(WorldProblem.Code.LIMIT,"Distinct native model-choice palette/ticket budget reached");ticketReferences+=state.parts().size();
                int[] owned=Arrays.copyOf(tickets,state.parts().size());var key=new Key(state.stateIndex(),owned.length,owned);index=choices.size();keys.put(key,index);
                if(issue!=null)choice=new WorldVisuals.ProblemChoice(state.stateIndex(),List.of(issue));
                else { var values=new ArrayList<Integer>(owned.length);for(int value:owned)values.add(value);choice=new WorldVisuals.ModelChoice(state.stateIndex(),values); }
            }
            if(choices.size()>=v.choices())throw WorldProblem.fail(WorldProblem.Code.LIMIT,"Native model-choice palette budget reached");
            if(choice instanceof WorldVisuals.ModelChoice&&modelStates.add(state.stateIndex())&&modelStates.size()>v.modelStates())throw WorldProblem.fail(WorldProblem.Code.LIMIT,"Native model-choice model-state budget reached");
            if(choice instanceof WorldVisuals.ProblemChoice issue) { problemReferences+=issue.problems().size();if(problemReferences>v.problemReferences())throw WorldProblem.fail(WorldProblem.Code.LIMIT,"Native model-choice published diagnostic budget reached"); }
            if(index==null) { index=choices.size();fixedIndices[state.stateIndex()]=index; }choices.add(choice);assignments[cell]=index;
        }
        reader.complete();WorldProblem.check(cancelled);
        var bounded=new WorldVisuals.Limits(v.choices(),v.modelStates(),v.meshVariants(),v.ticketReferences(),v.problemReferences(),v.sourcePackedBytes(),workingBytes);
        var request=WorldVisuals.Request.capture(plan.assets,snapshot,choices,assignments,tick,partialTick,bounded,plan.offsets,cancelled);reader.complete();WorldProblem.check(cancelled);
        return new Capture(snapshot.source(),snapshot.id(),Optional.of(request),Optional.of(new Usage(policy,choices.size(),variants,ticketReferences,visits)),List.of(),List.of());
    }
}
