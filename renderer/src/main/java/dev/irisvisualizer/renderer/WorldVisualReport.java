package dev.irisvisualizer.renderer;

import java.util.*;
import java.util.function.BooleanSupplier;

/** Detached full-capture visual limitations. Counts describe assignments, not visible GPU pixels. */
public final class WorldVisualReport {
    public record Limits(long cells,int issues,int references,int examples,long visits,long textBytes,long payloadBytes,long workingBytes) {
        public static final Limits DEFAULT=new Limits(16777216,4096,65536,4,67108864,4L*1024*1024,16L*1024*1024,512L*1024*1024);
        public Limits {
            if(cells<1||cells>16777216||issues<0||issues>65536||references<0||references>262144||examples<0||examples>16||visits<0||visits>268435456||textBytes<0||textBytes>16L*1024*1024||payloadBytes<0||payloadBytes>32L*1024*1024||workingBytes<0||workingBytes>512L*1024*1024)throw new IllegalArgumentException("Visual report limits exceed renderer bounds");
        }
    }
    public record Receipt(WorldSnapshot.Source source,UUID snapshotId,UUID modelsId,WorldSnapshot.Extent extent,String archiveSha256,String version,String expectedVersion) {
        public Receipt { Objects.requireNonNull(source);Objects.requireNonNull(snapshotId);Objects.requireNonNull(modelsId);Objects.requireNonNull(extent);Objects.requireNonNull(archiveSha256);Objects.requireNonNull(version);Objects.requireNonNull(expectedVersion); }
    }
    /** One diagnostic for one exact original state; a cell contributes once even for duplicate entry problems. */
    public record Issue(int nativeStateId,String originalState,AssetId block,ModelPlan.Problem problem,long affectedCells,WorldSnapshot.Position first,WorldSnapshot.Position last,List<WorldSnapshot.Position> examples) {
        public Issue {
            Objects.requireNonNull(originalState);Objects.requireNonNull(block);Objects.requireNonNull(problem);Objects.requireNonNull(first);Objects.requireNonNull(last);examples=List.copyOf(examples);
            if(nativeStateId<0||affectedCells<1||examples.size()>16||examples.size()>affectedCells)throw new IllegalArgumentException("Invalid detached visual issue");
        }
    }
    public record Usage(long capturedCells,long meshCells,long emptyCells,long placeholderCells,int issues,int references,long visits,long textBytes,long payloadBytes) {
        public Usage {
            if(capturedCells<1||meshCells<0||emptyCells<0||placeholderCells<0||meshCells+emptyCells+placeholderCells!=capturedCells||issues<0||references<0||visits<0||textBytes<0||payloadBytes<0)throw new IllegalArgumentException("Invalid visual report usage");
        }
    }
    private record Key(int nativeStateId,ModelPlan.Problem problem) {}
    private static final class Row {
        final RenderBlockState state;final ModelPlan.Problem problem;final List<WorldSnapshot.Position> examples=new ArrayList<>();
        long count;int first=Integer.MAX_VALUE,last=-1;
        Row(RenderBlockState state,ModelPlan.Problem problem) { this.state=state;this.problem=problem; }
    }
    private static final long BASE_BYTES=256,ROW_BYTES=64,POSITION_BYTES=20;
    private static final Comparator<Issue> ORDER=Comparator.comparingInt(Issue::nativeStateId).thenComparing(issue->issue.problem().code()).thenComparing(issue->issue.problem().kind()).thenComparing(issue->issue.problem().asset().namespace()).thenComparing(issue->issue.problem().asset().path()).thenComparing(issue->issue.problem().pointer()).thenComparing(issue->issue.problem().reason());
    private final Receipt receipt;private final List<Issue> issues;private final Optional<Usage> usage;private final List<WorldProblem> problems;
    private WorldVisualReport(Receipt receipt,List<Issue> issues,Optional<Usage> usage,List<WorldProblem> problems) {
        this.receipt=receipt;this.issues=List.copyOf(issues);this.usage=usage;this.problems=List.copyOf(problems);
        if(problems.isEmpty()!=usage.isPresent()||!problems.isEmpty()&&!issues.isEmpty())throw new IllegalArgumentException("Partial visual report escaped failure");
    }
    private static Receipt receipt(WorldScene scene) {
        var assets=scene.layout().assets();return new Receipt(scene.snapshot().source(),scene.snapshot().id(),scene.plan().models().id(),scene.snapshot().extent(),assets.archiveSha256(),assets.version(),assets.expectedVersion());
    }
    public Receipt receipt() { return receipt; }
    public List<Issue> issues() { return issues; }
    public Optional<Usage> usage() { return usage; }
    public List<WorldProblem> problems() { return problems; }
    public boolean requiresPlaceholder() { return !problems.isEmpty(); }
    public long bytes() { return usage.map(Usage::payloadBytes).orElse(0L); }
    public boolean matches(WorldScene scene) { return !requiresPlaceholder()&&receipt.equals(receipt(Objects.requireNonNull(scene))); }
    /** Source plus bounded retained output, entry counters/mappings and per-issue construction scratch. */
    public static long workingBytes(WorldScene scene,Limits limits) {
        Objects.requireNonNull(scene);Objects.requireNonNull(limits);
        return scene.usage().knownPayloadBytes()+limits.payloadBytes()+(long)scene.plan().models().entries().size()*16+(long)limits.references()*4+(long)limits.issues()*32;
    }
    public static WorldVisualReport capture(WorldScene scene,BooleanSupplier cancelled) { return capture(scene,Limits.DEFAULT,cancelled); }
    /** Borrow the complete CPU scene; its owner must stay live throughout this synchronous worker-side call. */
    public static WorldVisualReport capture(WorldScene scene,Limits limits,BooleanSupplier cancelled) {
        Objects.requireNonNull(scene);Objects.requireNonNull(limits);Objects.requireNonNull(cancelled);WorldProblem.check(cancelled);var receipt=receipt(scene);
        try { return execute(scene,receipt,limits,cancelled); }
        catch(WorldProblem.Failure failure) { WorldProblem.check(cancelled);return failed(receipt,failure.problem); }
        catch(OutOfMemoryError exhausted) { WorldProblem.check(cancelled);return failed(receipt,new WorldProblem(WorldProblem.Code.MEMORY,Optional.empty(),"Not enough memory for the visual report; no partial counts were published")); }
    }
    /** Source is borrowed only. Completed admission retains detached report bytes, not the scene graph. */
    public static RenderInput<WorldVisualReport> captureOwned(WorldScene scene,Limits limits,RenderMemory memory,BooleanSupplier cancelled) {
        Objects.requireNonNull(scene);Objects.requireNonNull(limits);Objects.requireNonNull(cancelled);WorldProblem.check(cancelled);
        long peak=workingBytes(scene,limits);
        if(peak>limits.workingBytes())throw new WorldCapture.ReadFailure(new WorldProblem(WorldProblem.Code.LIMIT,Optional.empty(),"Owned visual report exceeds its working allowance"));
        var hold=memory==null?RenderMemory.Hold.none():memory.hold("World visual report",peak);
        try(hold) {
            var report=capture(scene,limits,cancelled);if(report.requiresPlaceholder())throw new WorldCapture.ReadFailure(report.problems().getFirst());WorldProblem.check(cancelled);
            hold.shrinkTo(report.bytes());return RenderInput.own(report,hold);
        }
    }
    private static WorldVisualReport failed(Receipt receipt,WorldProblem problem) { return new WorldVisualReport(receipt,List.of(),Optional.empty(),List.of(problem)); }
    private static final class Budget {
        final Limits limits;long visits,text,payload=BASE_BYTES;int references;
        Budget(Limits limits) { this.limits=limits; }
        void visit(long count) { if(count>limits.visits()-visits)throw WorldProblem.fail(WorldProblem.Code.LIMIT,"Visual report visit allowance reached");visits+=count; }
        void text(String... values) {
            long bytes=0;for(var value:values)bytes+=(long)value.length()*Character.BYTES;
            if(bytes>limits.textBytes()-text||bytes>limits.payloadBytes()-payload)throw WorldProblem.fail(WorldProblem.Code.LIMIT,"Visual report text/payload allowance reached");text+=bytes;payload+=bytes;
        }
        void payload(long bytes) { if(bytes>limits.payloadBytes()-payload)throw WorldProblem.fail(WorldProblem.Code.LIMIT,"Visual report payload allowance reached");payload+=bytes; }
        void rowTextPreflight(RenderBlockState state,ModelPlan.Problem problem) {
            long characters=(long)state.original().length()+state.block().namespace().length()+state.block().path().length()+problem.asset().namespace().length()+problem.asset().path().length()+problem.pointer().length()+problem.reason().length();
            long bytes=characters*Character.BYTES;
            // Reject oversized strings before HashSet/HashMap invoke their potentially uncached hashes.
            if(bytes>limits.textBytes()||bytes+ROW_BYTES>limits.payloadBytes())throw WorldProblem.fail(WorldProblem.Code.LIMIT,"Visual report diagnostic text exceeds its allowance");
        }
    }
    private static WorldVisualReport execute(WorldScene scene,Receipt receipt,Limits limits,BooleanSupplier cancelled) {
        var models=scene.plan().models();var snapshot=scene.snapshot();int cells=snapshot.cellCount(),entries=models.entries().size();
        if(cells>limits.cells()||workingBytes(scene,limits)>limits.workingBytes()||limits.payloadBytes()<BASE_BYTES)throw WorldProblem.fail(WorldProblem.Code.LIMIT,"Visual report source/cell/working allowance reached");
        var budget=new Budget(limits);var source=receipt.source();
        budget.text(source.inputSha256(),source.backendBuild(),source.minecraftVersion(),source.dimension().namespace(),source.dimension().path(),receipt.archiveSha256(),receipt.version(),receipt.expectedVersion());
        int[] counts=new int[entries],first=new int[entries],last=new int[entries];Arrays.fill(first,-1);
        for(int cell=0;cell<cells;cell++) {
            WorldProblem.check(cancelled);budget.visit(1);int entry=models.entryIndex(cell);counts[entry]++;if(first[entry]<0)first[entry]=cell;last[entry]=cell;
        }
        var rows=new ArrayList<Row>();var lookup=new HashMap<Key,Integer>();int[][] mappings=new int[entries][];long mesh=0,empty=0,placeholder=0;
        for(int entry=0;entry<entries;entry++) {
            WorldProblem.check(cancelled);if(counts[entry]==0)continue;var value=models.entries().get(entry);
            if(value.kind()==WorldModels.Kind.MESH) { mesh+=counts[entry];continue; }
            if(value.kind()==WorldModels.Kind.EMPTY) { empty+=counts[entry];continue; }
            placeholder+=counts[entry];int references=value.problems().size();
            if(references>limits.references()-budget.references)throw WorldProblem.fail(WorldProblem.Code.LIMIT,"Visual report diagnostic-reference allowance reached");budget.references+=references;budget.visit(references);
            int[] mapping=new int[references+1];mappings[entry]=mapping;var seen=new HashSet<ModelPlan.Problem>();
            for(var problem:value.problems()) {
                WorldProblem.check(cancelled);budget.rowTextPreflight(value.state(),problem);if(!seen.add(problem))continue;var key=new Key(value.state().nativeStateId(),problem);var rowIndex=lookup.get(key);
                if(rowIndex==null) {
                    if(rows.size()>=limits.issues())throw WorldProblem.fail(WorldProblem.Code.LIMIT,"Visual report issue allowance reached");
                    budget.payload(ROW_BYTES);var state=value.state();budget.text(state.original(),state.block().namespace(),state.block().path(),problem.asset().namespace(),problem.asset().path(),problem.pointer(),problem.reason());
                    rowIndex=rows.size();lookup.put(key,rowIndex);rows.add(new Row(state,problem));
                }
                mapping[++mapping[0]]=rowIndex;var row=rows.get(rowIndex);row.count+=counts[entry];row.first=Math.min(row.first,first[entry]);row.last=Math.max(row.last,last[entry]);
            }
        }
        if(placeholder!=scene.usage().markers())throw WorldProblem.fail(WorldProblem.Code.ASSIGNMENT,"Visual report placeholders differ from the complete scene markers");
        long remaining=0;for(var row:rows)remaining+=Math.min(row.count,limits.examples());budget.payload(remaining*POSITION_BYTES);
        for(int cell=0;cell<cells&&remaining>0;cell++) {
            WorldProblem.check(cancelled);budget.visit(1);var mapping=mappings[models.entryIndex(cell)];if(mapping==null)continue;
            for(int at=1;at<=mapping[0];at++) {
                WorldProblem.check(cancelled);budget.visit(1);var row=rows.get(mapping[at]);if(row.examples.size()>=Math.min(row.count,limits.examples()))continue;
                row.examples.add(snapshot.extent().position(cell));remaining--;
            }
        }
        var output=new ArrayList<Issue>(rows.size());
        for(var row:rows) {
            WorldProblem.check(cancelled);output.add(new Issue(row.state.nativeStateId(),row.state.original(),row.state.block(),row.problem,row.count,snapshot.extent().position(row.first),snapshot.extent().position(row.last),row.examples));
        }
        output.sort((a,b)->{ WorldProblem.check(cancelled);budget.visit(1);return ORDER.compare(a,b); });
        WorldProblem.check(cancelled);var usage=new Usage(cells,mesh,empty,placeholder,output.size(),budget.references,budget.visits,budget.text,budget.payload);
        return new WorldVisualReport(receipt,output,Optional.of(usage),List.of());
    }
}
