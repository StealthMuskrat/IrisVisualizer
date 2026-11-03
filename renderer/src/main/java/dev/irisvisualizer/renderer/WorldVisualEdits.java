package dev.irisvisualizer.renderer;

import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Streaming explicit visual decisions over an immutable request; never infers native renderers or tickets. */
public final class WorldVisualEdits {
    private WorldVisualEdits() {}
    public interface Reader {
        WorldVisuals.Choice choice(int cell,WorldVisuals.Choice original);
        default void complete() {}
    }
    public record Limits(long cells,long changes,int packets,long addedPackedBytes,long workingBytes) {
        public static final Limits DEFAULT=new Limits(16777216,16777216,1024,64L*1024*1024,512L*1024*1024);
        public Limits {
            if(cells<0||cells>16777216||changes<0||changes>16777216||packets<0||packets>1024||addedPackedBytes<0||addedPackedBytes>64L*1024*1024||workingBytes<0||workingBytes>512L*1024*1024)throw new IllegalArgumentException("Visual edit limits exceed renderer bounds");
        }
    }
    public record Usage(long visitedCells,long changedCells,int choices,int addedPackets,long addedPackedBytes,long knownPeakBytes) {}
    public record Result(WorldSnapshot.Source source,UUID snapshotId,Optional<WorldVisuals.Request> request,Optional<Usage> usage,List<WorldProblem> problems) {
        public Result {
            Objects.requireNonNull(source);Objects.requireNonNull(snapshotId);Objects.requireNonNull(request);Objects.requireNonNull(usage);problems=List.copyOf(problems);
            boolean success=problems.isEmpty();
            if(success!=(request.isPresent()&&usage.isPresent())||!success&&(request.isPresent()||usage.isPresent()))throw new IllegalArgumentException("Partial visual edits escaped failure");
            if(success&&(!request.orElseThrow().snapshotId().equals(snapshotId)||!request.orElseThrow().source().equals(source)))throw new IllegalArgumentException("Visual edit capture identities differ");
        }
        public boolean requiresPlaceholder() { return !problems.isEmpty(); }
    }
    public static Result capture(WorldVisuals.Request original,Reader reader,BooleanSupplier cancelled) { return capture(original,reader,Limits.DEFAULT,cancelled); }
    public static Result capture(WorldVisuals.Request original,Reader reader,Limits limits,BooleanSupplier cancelled) {
        Objects.requireNonNull(original);Objects.requireNonNull(reader);Objects.requireNonNull(limits);Objects.requireNonNull(cancelled);WorldProblem.check(cancelled);
        try { return execute(original,reader,limits,cancelled); }
        catch(WorldCapture.ReadFailure failure) { WorldProblem.check(cancelled);return failed(original,failure.problem()); }
        catch(WorldProblem.Failure failure) { WorldProblem.check(cancelled);return failed(original,failure.problem); }
        catch(CancellationException cancellation) { throw cancellation; }
        catch(RuntimeException failure) { WorldProblem.check(cancelled);return failed(original,new WorldProblem(WorldProblem.Code.WORKER,Optional.empty(),"Visual edit reader failed: "+failure.getClass().getSimpleName())); }
        catch(OutOfMemoryError exhausted) { WorldProblem.check(cancelled);return failed(original,new WorldProblem(WorldProblem.Code.MEMORY,Optional.empty(),"Not enough memory to capture visual edits; no partial request published")); }
    }
    private static Result failed(WorldVisuals.Request original,WorldProblem problem) { return new Result(original.source(),original.snapshotId(),Optional.empty(),Optional.empty(),List.of(problem)); }
    static BlockMesh mesh(WorldVisuals.Choice choice) { return choice instanceof WorldVisuals.NativeChoice n?n.mesh():choice instanceof WorldVisuals.ModelChoice m?m.nativeLayer().orElse(null):null; }
    private static Result execute(WorldVisuals.Request original,Reader reader,Limits limits,BooleanSupplier cancelled) {
        var snapshot=original.snapshot();var visualLimits=original.limits();long working=Math.min(visualLimits.workingBytes(),limits.workingBytes());
        // The original index and packets remain live while the scratch index and final defensive copy coexist.
        long peak=original.inputBytes()+(long)snapshot.cellCount()*8;
        if(snapshot.cellCount()>limits.cells()||peak>working)throw WorldProblem.fail(WorldProblem.Code.LIMIT,"Visual edit traversal and original/scratch/copy payload exceed their budget");
        var knownPackets=new IdentityHashMap<MeshPacket,Boolean>();
        for(var choice:original.choices()) { var mesh=mesh(choice);if(mesh!=null&&mesh.packet().isPresent())knownPackets.put(mesh.packet().orElseThrow(),Boolean.TRUE); }
        var outputPackets=new IdentityHashMap<MeshPacket,Boolean>();var palette=new ArrayList<WorldVisuals.Choice>();var lookup=new HashMap<WorldVisuals.Choice,Integer>();var modelStates=new HashSet<Integer>();var variants=new HashSet<WorldVisuals.Choice>();
        int[] assignments=new int[snapshot.cellCount()];long changes=0,addedBytes=0,packedBytes=0,tickets=0,problems=0;int addedPackets=0;
        for(int cell=0;cell<assignments.length;cell++) {
            WorldProblem.check(cancelled);var prior=original.choices().get(original.choiceAt(cell));var choice=Objects.requireNonNull(reader.choice(cell,prior));
            if(choice.stateIndex()!=snapshot.stateIndex(cell))throw WorldProblem.at(WorldProblem.Code.STATE,snapshot.extent().position(cell),"Visual edit changed the original native state identity");
            if(!choice.equals(prior)) {
                if(!snapshot.states().get(choice.stateIndex()).block().namespace().equals("minecraft")&&!(choice instanceof WorldVisuals.ProblemChoice))throw WorldProblem.at(WorldProblem.Code.STATE,snapshot.extent().position(cell),"External native visuals remain unsupported; retain their original-state problem");
                if(++changes>limits.changes())throw WorldProblem.fail(WorldProblem.Code.LIMIT,"Visual edit changed-cell allowance reached");
            }
            Integer index=lookup.get(choice);
            if(index==null) {
                if(palette.size()>=visualLimits.choices())throw WorldProblem.fail(WorldProblem.Code.LIMIT,"Visual edit choice palette allowance reached");
                if(choice instanceof WorldVisuals.ModelChoice model) { tickets+=model.tickets().size();modelStates.add(model.stateIndex());variants.add(choice); }
                else if(choice instanceof WorldVisuals.NativeChoice)variants.add(choice);
                else if(choice instanceof WorldVisuals.ProblemChoice problem)problems+=problem.problems().size();
                var mesh=mesh(choice);
                if(mesh!=null) {
                    WorldVisuals.Request.validateNative(original.assets(),snapshot,choice.stateIndex(),mesh);problems+=mesh.problems().size();
                    if(mesh.packet().isPresent()&&outputPackets.put(mesh.packet().orElseThrow(),Boolean.TRUE)==null) {
                        var packet=mesh.packet().orElseThrow();packedBytes+=packet.packedBytes();
                        if(knownPackets.put(packet,Boolean.TRUE)==null) { addedBytes+=packet.packedBytes();addedPackets++; }
                    }
                }
                if(modelStates.size()>visualLimits.modelStates()||variants.size()>visualLimits.meshVariants()||tickets>visualLimits.ticketReferences()||problems>visualLimits.problemReferences()||packedBytes>visualLimits.sourcePackedBytes()||addedPackets>limits.packets()||addedBytes>limits.addedPackedBytes()||peak+addedBytes>working)throw WorldProblem.fail(WorldProblem.Code.LIMIT,"Visual edit metadata, source mesh or combined copy allowance reached");
                index=palette.size();palette.add(choice);lookup.put(choice,index);
            }
            var mesh=mesh(choice);
            if(mesh!=null&&mesh.nativeSource().isPresent()) { var position=mesh.nativeSource().orElseThrow().position();if(position.isPresent()&&!position.orElseThrow().equals(snapshot.extent().position(cell)))throw WorldProblem.at(WorldProblem.Code.ASSIGNMENT,snapshot.extent().position(cell),"Position-dependent native visual edited onto another cell"); }
            assignments[cell]=index;
        }
        reader.complete();WorldProblem.check(cancelled);
        var bounded=new WorldVisuals.Limits(visualLimits.choices(),visualLimits.modelStates(),visualLimits.meshVariants(),visualLimits.ticketReferences(),visualLimits.problemReferences(),visualLimits.sourcePackedBytes(),working);
        var request=WorldVisuals.Request.capture(original.assets(),snapshot,palette,assignments,original.tick(),original.partialTick(),bounded,original.offsets(),original.tints(),cancelled);
        reader.complete();WorldProblem.check(cancelled);
        return new Result(original.source(),original.snapshotId(),Optional.of(request),Optional.of(new Usage(assignments.length,changes,palette.size(),addedPackets,addedBytes,peak+addedBytes)),List.of());
    }
    /** Adds a supplied native layer without replacing the original native model selection. */
    public static WorldVisuals.Choice layer(WorldVisuals.Choice original,BlockMesh layer,NativeGeometry.Limits limits,BooleanSupplier cancelled) {
        Objects.requireNonNull(original);Objects.requireNonNull(layer);Objects.requireNonNull(limits);Objects.requireNonNull(cancelled);WorldProblem.check(cancelled);
        if(original instanceof WorldVisuals.ModelChoice model) {
            var combined=model.nativeLayer().isPresent()?NativeGeometry.combine(model.nativeLayer().orElseThrow(),layer,limits,cancelled):layer;
            return new WorldVisuals.ModelChoice(model.stateIndex(),model.tickets(),Optional.of(combined));
        }
        if(original instanceof WorldVisuals.NativeChoice nativeChoice)return new WorldVisuals.NativeChoice(original.stateIndex(),NativeGeometry.combine(nativeChoice.mesh(),layer,limits,cancelled));
        if(original instanceof WorldVisuals.EmptyChoice)return new WorldVisuals.NativeChoice(original.stateIndex(),layer);
        // A partial native layer cannot make an unsupported base visual complete.
        return original;
    }
}
