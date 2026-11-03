package dev.irisvisualizer.renderer;

import java.util.*;
import java.util.function.BooleanSupplier;

/** Optional native per-cell directional suppression. The renderer does not derive opacity/shapes. */
public final class WorldVisibility {
    public enum Mode { KEEP_ALL, NATIVE_DIRECTION_MASK }
    /** Captures native masks before atlas creation; binding later shares only privately frozen bytes. */
    public static final class Input {
        private final WorldSnapshot snapshot;private final Mode mode;private final String policy;private final byte[] masks;private final List<WorldProblem> problems;
        private Input(WorldSnapshot snapshot,Mode mode,String policy,byte[] owned,List<WorldProblem> problems) { this.snapshot=snapshot;this.mode=mode;this.policy=policy;masks=owned;this.problems=List.copyOf(problems); }
        public static Input keepAll(WorldSnapshot snapshot) { Objects.requireNonNull(snapshot);return new Input(snapshot,Mode.KEEP_ALL,"No directional suppression",new byte[0],snapshot.problems()); }
        public static Input capture(WorldSnapshot snapshot,String policy,byte[] directions,BooleanSupplier cancelled) { return capture(snapshot,policy,directions,16L*1024*1024,cancelled); }
        public static Input capture(WorldSnapshot snapshot,String policy,byte[] directions,long maximumBytes,BooleanSupplier cancelled) {
            Objects.requireNonNull(snapshot);Objects.requireNonNull(policy);Objects.requireNonNull(directions);Objects.requireNonNull(cancelled);WorldProblem.check(cancelled);
            if(policy.isBlank()||policy.length()>256||maximumBytes<0||maximumBytes>16L*1024*1024)throw new IllegalArgumentException("Invalid native visibility policy or byte limit");
            try {
                if(snapshot.requiresPlaceholder())return new Input(snapshot,Mode.NATIVE_DIRECTION_MASK,policy,new byte[0],snapshot.problems());
                if(directions.length!=snapshot.cellCount())throw WorldProblem.fail(WorldProblem.Code.VISIBILITY,"Native visibility must cover every captured cell");
                if(directions.length>maximumBytes)throw WorldProblem.fail(WorldProblem.Code.LIMIT,"Native directional mask byte budget reached");
                byte[] captured=new byte[directions.length];
                for(int i=0;i<captured.length;i++) { if((i&4095)==0)WorldProblem.check(cancelled);int value=directions[i]&255;if((value&~63)!=0)throw WorldProblem.at(WorldProblem.Code.VISIBILITY,snapshot.extent().position(i),"Native visibility contains undefined direction bits");captured[i]=(byte)value; }
                WorldProblem.check(cancelled);return new Input(snapshot,Mode.NATIVE_DIRECTION_MASK,policy,captured,List.of());
            }catch(WorldProblem.Failure failure) { WorldProblem.check(cancelled);return new Input(snapshot,Mode.NATIVE_DIRECTION_MASK,policy,new byte[0],List.of(failure.problem)); }
            catch(OutOfMemoryError exhausted) { WorldProblem.check(cancelled);return new Input(snapshot,Mode.NATIVE_DIRECTION_MASK,policy,new byte[0],List.of(new WorldProblem(WorldProblem.Code.MEMORY,Optional.empty(),"Not enough memory to freeze native visibility; no partial mask was published"))); }
        }
        public WorldSnapshot snapshot() { return snapshot; }
        public long bytes() { return masks.length; }
        public List<WorldProblem> problems() { return problems; }
        public WorldVisibility bind(AtlasLayout layout,BooleanSupplier cancelled) {
            Objects.requireNonNull(layout);Objects.requireNonNull(cancelled);WorldProblem.check(cancelled);var errors=problems.isEmpty()?inputs(snapshot,layout):problems;
            return new WorldVisibility(snapshot,layout,mode,policy,errors.isEmpty()?masks:new byte[0],errors);
        }
    }
    private final WorldSnapshot snapshot;private final AtlasLayout layout;private final Mode mode;private final String policy;
    private final byte[] masks;private final List<WorldProblem> problems;
    private WorldVisibility(WorldSnapshot snapshot,AtlasLayout layout,Mode mode,String policy,byte[] ownedMasks,List<WorldProblem> problems) {
        this.snapshot=snapshot;this.layout=layout;this.mode=mode;this.policy=policy;masks=ownedMasks;this.problems=List.copyOf(problems);
        if(!problems.isEmpty()&&masks.length!=0)throw new IllegalArgumentException("Partial native visibility escaped failure");
    }
    public static WorldVisibility keepAll(WorldSnapshot snapshot,AtlasLayout layout) {
        Objects.requireNonNull(snapshot);Objects.requireNonNull(layout);var problems=inputs(snapshot,layout);
        return new WorldVisibility(snapshot,layout,Mode.KEEP_ALL,"No directional suppression",new byte[0],problems);
    }
    /** Bits 0..5 are DOWN, UP, NORTH, SOUTH, WEST, EAST. Input producer must freeze native results. */
    public static WorldVisibility capture(WorldSnapshot snapshot,AtlasLayout layout,String nativePolicy,byte[] hiddenDirections,BooleanSupplier cancelled) { return capture(snapshot,layout,nativePolicy,hiddenDirections,16L*1024*1024,cancelled); }
    public static WorldVisibility capture(WorldSnapshot snapshot,AtlasLayout layout,String nativePolicy,byte[] hiddenDirections,long maximumBytes,BooleanSupplier cancelled) {
        Objects.requireNonNull(snapshot);Objects.requireNonNull(layout);Objects.requireNonNull(nativePolicy);Objects.requireNonNull(hiddenDirections);Objects.requireNonNull(cancelled);WorldProblem.check(cancelled);
        if(nativePolicy.isBlank()||nativePolicy.length()>256||maximumBytes<0||maximumBytes>16L*1024*1024)throw new IllegalArgumentException("Invalid native visibility policy or byte limit");
        try { return execute(snapshot,layout,nativePolicy,hiddenDirections,maximumBytes,cancelled); }
        catch(WorldProblem.Failure failure) { WorldProblem.check(cancelled);return new WorldVisibility(snapshot,layout,Mode.NATIVE_DIRECTION_MASK,nativePolicy,new byte[0],List.of(failure.problem)); }
        catch(OutOfMemoryError exhausted) { WorldProblem.check(cancelled);return new WorldVisibility(snapshot,layout,Mode.NATIVE_DIRECTION_MASK,nativePolicy,new byte[0],List.of(new WorldProblem(WorldProblem.Code.MEMORY,Optional.empty(),"Not enough memory to capture native visibility; no partial mask was published"))); }
    }
    private static WorldVisibility execute(WorldSnapshot snapshot,AtlasLayout layout,String nativePolicy,byte[] hiddenDirections,long maximumBytes,BooleanSupplier cancelled) {
        var problems=inputs(snapshot,layout);if(!problems.isEmpty())return new WorldVisibility(snapshot,layout,Mode.NATIVE_DIRECTION_MASK,nativePolicy,new byte[0],problems);
        if(hiddenDirections.length!=snapshot.cellCount())throw WorldProblem.fail(WorldProblem.Code.VISIBILITY,"Native visibility must cover every captured cell");
        if(hiddenDirections.length>maximumBytes)throw WorldProblem.fail(WorldProblem.Code.LIMIT,"Native directional mask byte budget reached");
        byte[] copy=new byte[hiddenDirections.length];
        for(int i=0;i<copy.length;i++) { if((i&4095)==0)WorldProblem.check(cancelled);int value=hiddenDirections[i]&255;if((value&~63)!=0)throw WorldProblem.at(WorldProblem.Code.VISIBILITY,snapshot.extent().position(i),"Native visibility contains undefined direction bits");copy[i]=(byte)value; }
        WorldProblem.check(cancelled);return new WorldVisibility(snapshot,layout,Mode.NATIVE_DIRECTION_MASK,nativePolicy,copy,List.of());
    }
    private static List<WorldProblem> inputs(WorldSnapshot snapshot,AtlasLayout layout) {
        if(snapshot.requiresPlaceholder())return snapshot.problems();
        if(layout.requiresPlaceholder())return List.of(new WorldProblem(WorldProblem.Code.ATLAS,Optional.empty(),"Native visibility requires a successful atlas epoch"));
        if(!snapshot.source().minecraftVersion().equals(layout.assets().version()))return List.of(new WorldProblem(WorldProblem.Code.VERSION,Optional.empty(),"Visibility snapshot and assets target different Minecraft versions"));return List.of();
    }
    public WorldSnapshot snapshot() { return snapshot; }
    public AtlasLayout layout() { return layout; }
    public Mode mode() { return mode; }
    public String policy() { return policy; }
    public long bytes() { return masks.length; }
    public byte[] copyMasks() { return masks.clone(); }
    public boolean hidden(int cell,ModelPlan.Direction direction) {
        Objects.requireNonNull(direction);if(requiresPlaceholder()||cell<0||cell>=snapshot.cellCount())throw new IllegalArgumentException("Native visibility query requires a successful captured cell");
        return mode==Mode.NATIVE_DIRECTION_MASK&&(masks[cell]&(1<<direction.ordinal()))!=0;
    }
    public List<WorldProblem> problems() { return problems; }
    public boolean requiresPlaceholder() { return !problems.isEmpty(); }
}
