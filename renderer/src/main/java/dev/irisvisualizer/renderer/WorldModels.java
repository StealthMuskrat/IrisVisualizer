package dev.irisvisualizer.renderer;

import java.util.*;
import java.util.function.BooleanSupplier;

/** Per-cell visual assignments supplied by the native path, including its position-dependent choices. */
public final class WorldModels {
    public enum Kind { MESH, EMPTY, PLACEHOLDER }
    public record Entry(RenderBlockState state,Kind kind,Optional<AtlasMesh> mesh,List<ModelPlan.Problem> problems) {
        public Entry {
            Objects.requireNonNull(state);Objects.requireNonNull(kind);Objects.requireNonNull(mesh);problems=List.copyOf(problems);
            if(problems.size()>256||kind==Kind.MESH&&(mesh.isEmpty()||mesh.get().requiresPlaceholder()||!mesh.get().state().equals(state)||!problems.isEmpty())
                ||kind==Kind.EMPTY&&(mesh.isPresent()||!problems.isEmpty())||kind==Kind.PLACEHOLDER&&(mesh.isPresent()||problems.isEmpty()))throw new IllegalArgumentException("Invalid native visual assignment");
        }
        public static Entry from(AtlasMesh mesh) { Objects.requireNonNull(mesh);return mesh.requiresPlaceholder()?placeholder(mesh.state(),mesh.problems()):new Entry(mesh.state(),Kind.MESH,Optional.of(mesh),List.of()); }
        public static Entry empty(RenderBlockState state) { return new Entry(state,Kind.EMPTY,Optional.empty(),List.of()); }
        public static Entry placeholder(RenderBlockState state,List<ModelPlan.Problem> problems) { return new Entry(state,Kind.PLACEHOLDER,Optional.empty(),problems); }
    }
    public record Limits(int entries,long indexBytes,long meshBytes,long sourceBytes) {
        public static final Limits DEFAULT=new Limits(65536,64L*1024*1024,64L*1024*1024,256L*1024*1024);
        public Limits { if(entries<1||entries>65536||indexBytes<0||indexBytes>64L*1024*1024||meshBytes<0||meshBytes>64L*1024*1024||sourceBytes<0||sourceBytes>256L*1024*1024)throw new IllegalArgumentException("World assignment limits exceed renderer bounds"); }
    }
    private final WorldSnapshot snapshot;private final AtlasLayout layout;private final List<Entry> entries;private final int[] indices;private final long meshBytes;private final List<WorldProblem> problems;
    private WorldModels(WorldSnapshot snapshot,AtlasLayout layout,List<Entry> entries,int[] ownedIndices,long meshBytes,List<WorldProblem> problems) {
        this.snapshot=snapshot;this.layout=layout;this.entries=List.copyOf(entries);indices=ownedIndices;this.meshBytes=meshBytes;this.problems=List.copyOf(problems);
        if(!problems.isEmpty()&&(!entries.isEmpty()||indices.length!=0||meshBytes!=0))throw new IllegalArgumentException("Partial visual assignments escaped failure");
    }
    public static WorldModels capture(WorldSnapshot snapshot,AtlasLayout layout,List<Entry> entries,int[] cellAssignments,BooleanSupplier cancelled) { return capture(snapshot,layout,entries,cellAssignments,Limits.DEFAULT,cancelled); }
    public static WorldModels capture(WorldSnapshot snapshot,AtlasLayout layout,List<Entry> entries,int[] cellAssignments,Limits limits,BooleanSupplier cancelled) {
        Objects.requireNonNull(snapshot);Objects.requireNonNull(layout);Objects.requireNonNull(entries);Objects.requireNonNull(cellAssignments);Objects.requireNonNull(limits);Objects.requireNonNull(cancelled);WorldProblem.check(cancelled);
        try { return execute(snapshot,layout,entries,cellAssignments,limits,cancelled); }
        catch(WorldProblem.Failure failure) { WorldProblem.check(cancelled);return placeholder(snapshot,layout,List.of(failure.problem)); }
        catch(OutOfMemoryError exhausted) { WorldProblem.check(cancelled);return placeholder(snapshot,layout,List.of(new WorldProblem(WorldProblem.Code.MEMORY,Optional.empty(),"Not enough memory to capture per-cell model choices; no partial assignments were published"))); }
    }
    private static WorldModels execute(WorldSnapshot snapshot,AtlasLayout layout,List<Entry> input,int[] cells,Limits limits,BooleanSupplier cancelled) {
        if(snapshot.requiresPlaceholder())return placeholder(snapshot,layout,snapshot.problems());
        if(layout.requiresPlaceholder())throw WorldProblem.fail(WorldProblem.Code.ATLAS,"World model assignments require a successful CPU atlas; inspect its asset problems");
        if(!snapshot.source().minecraftVersion().equals(layout.assets().version()))throw WorldProblem.fail(WorldProblem.Code.VERSION,"World snapshot and captured visual assets target different Minecraft versions");
        long bytes=(long)cells.length*4;
        if(cells.length!=snapshot.cellCount())throw WorldProblem.fail(WorldProblem.Code.ASSIGNMENT,"Supply one native visual choice for every captured cell");
        if(bytes>limits.indexBytes()||bytes+snapshot.indexBytes()>limits.sourceBytes())throw WorldProblem.fail(WorldProblem.Code.LIMIT,"World state, biome and visual-choice index budget reached");
        if(input.isEmpty()||input.size()>limits.entries())throw WorldProblem.fail(WorldProblem.Code.LIMIT,"Visual assignment palette count is outside its budget");
        var nativeStates=new HashMap<Integer,Integer>();for(int i=0;i<snapshot.states().size();i++)nativeStates.put(snapshot.states().get(i).nativeStateId(),i);
        var captured=new ArrayList<Entry>();var stateIndices=new ArrayList<Integer>();var meshes=new IdentityHashMap<AtlasMesh,Boolean>();long meshBytes=0;
        for(var entry:input) {
            WorldProblem.check(cancelled);Objects.requireNonNull(entry);if(captured.size()>=limits.entries())throw WorldProblem.fail(WorldProblem.Code.LIMIT,"Visual assignment palette budget reached");
            var index=nativeStates.get(entry.state().nativeStateId());
            if(index==null||!snapshot.states().get(index).equals(entry.state()))throw WorldProblem.fail(WorldProblem.Code.STATE,"Visual entry does not preserve an exact native state in the captured palette");
            if(entry.kind()==Kind.MESH) {
                var mesh=entry.mesh().orElseThrow();if(mesh.layout()!=layout)throw WorldProblem.fail(WorldProblem.Code.ATLAS,"World prototypes must use the exact atlas layout epoch");
                if(meshes.put(mesh,Boolean.TRUE)==null)meshBytes+=mesh.packedBytes();
                if(meshBytes>limits.meshBytes())throw WorldProblem.fail(WorldProblem.Code.LIMIT,"Unique atlas-bound prototype payload budget reached");
            }
            captured.add(entry);stateIndices.add(index);
        }
        int[] indices=new int[cells.length];
        for(int i=0;i<cells.length;i++) {
            if((i&4095)==0)WorldProblem.check(cancelled);int choice=cells[i];
            if(choice<0||choice>=captured.size()||stateIndices.get(choice)!=snapshot.stateIndex(i))throw WorldProblem.at(WorldProblem.Code.ASSIGNMENT,snapshot.extent().position(i),"Per-cell visual choice is absent or belongs to a different native block state");indices[i]=choice;
        }
        WorldProblem.check(cancelled);return new WorldModels(snapshot,layout,captured,indices,meshBytes,List.of());
    }
    private static WorldModels placeholder(WorldSnapshot snapshot,AtlasLayout layout,List<WorldProblem> problems) { return new WorldModels(snapshot,layout,List.of(),new int[0],0,problems); }
    public WorldSnapshot snapshot() { return snapshot; }
    public AtlasLayout layout() { return layout; }
    public List<Entry> entries() { return entries; }
    public int entryIndex(int cell) { return indices[cell]; }
    public Entry entry(int cell) { return entries.get(indices[cell]); }
    public int[] copyAssignments() { return indices.clone(); }
    public long indexBytes() { return (long)indices.length*4; }
    public long meshBytes() { return meshBytes; }
    public List<WorldProblem> problems() { return problems; }
    public boolean requiresPlaceholder() { return !problems.isEmpty(); }
}
