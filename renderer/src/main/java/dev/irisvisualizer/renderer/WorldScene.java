package dev.irisvisualizer.renderer;

import java.util.*;
import java.util.function.BooleanSupplier;

/** Complete immutable CPU scene. Its owner must keep the Result lease open while using its meshes. */
public final class WorldScene {
    public static final class Result implements AutoCloseable {
        private static final class Shared {
            final WorldScene scene;final List<WorldSectionCache.Ticket> tickets;int references=1;
            Shared(WorldScene scene,List<WorldSectionCache.Ticket> tickets) { this.scene=scene;this.tickets=List.copyOf(tickets); }
            void release() { boolean last;synchronized(this) { if(references<1)throw new IllegalStateException("Invalid shared scene lease");last=--references==0; }if(last)for(var ticket:tickets)ticket.close(); }
        }
        private final WorldSnapshot.Source source;private final List<WorldProblem> problems;
        private Shared shared;private boolean closed;
        private Result(WorldSnapshot.Source source,WorldScene scene,List<WorldSectionCache.Ticket> tickets,List<WorldProblem> problems) { this.source=source;shared=scene==null?null:new Shared(scene,tickets);this.problems=List.copyOf(problems); }
        private Result(WorldSnapshot.Source source,Shared shared,List<WorldProblem> problems) { this.source=source;this.shared=shared;this.problems=problems; }
        static Result failed(WorldSnapshot.Source source,List<WorldProblem> problems) { if(problems.isEmpty())throw new IllegalArgumentException("Failure requires a problem");return new Result(source,null,List.of(),problems); }
        public WorldSnapshot.Source source() { return source; }
        public List<WorldProblem> problems() { return problems; }
        public boolean requiresPlaceholder() { return !problems.isEmpty(); }
        public synchronized boolean closed() { return closed; }
        public synchronized Optional<WorldScene> scene() { if(closed)throw new IllegalStateException("Scene result lease is closed");return shared==null?Optional.empty():Optional.of(shared.scene); }
        /** Independent owner of the same exact scene. Shared cache tickets release only after all owners close. */
        public synchronized Result retain() {
            if(closed)throw new IllegalStateException("Scene result lease is closed");if(shared==null)return new Result(source,(Shared)null,problems);
            synchronized(shared) { if(shared.references>=65536)throw new IllegalStateException("Shared scene lease limit reached");var result=new Result(source,shared,problems);shared.references++;return result; }
        }
        @Override public void close() {
            Shared release;synchronized(this) { if(closed)return;closed=true;release=shared;shared=null; }if(release!=null)release.release();
        }
    }
    private static final class Work implements AutoCloseable {
        final List<WorldSectionCache.Ticket> tickets;final List<WorldMesh> sections;boolean transferred;
        Work(int size) { tickets=new ArrayList<>(size);sections=new ArrayList<>(size); }
        void own(WorldSectionCache.Ticket ticket) { try { tickets.add(ticket); }catch(RuntimeException|Error failure) { ticket.close();throw failure; } }
        @Override public void close() { if(!transferred)for(var ticket:tickets)ticket.close(); }
    }
    private final WorldScenePlan plan;private final AtlasRaster raster;private final List<WorldMesh> sections;
    private WorldScene(WorldScenePlan plan,AtlasRaster raster,List<WorldMesh> sections) { this.plan=plan;this.raster=raster;this.sections=List.copyOf(sections); }
    public WorldScenePlan plan() { return plan; }
    public WorldSnapshot snapshot() { return plan.snapshot(); }
    public AtlasRaster raster() { return raster; }
    public AtlasLayout layout() { return plan.layout(); }
    public List<WorldMesh> sections() { return sections; }
    public WorldScenePlan.Usage usage() { return plan.usage().orElseThrow(); }
    /** Failed/cancelled builds release every ticket; valid completed cache entries may remain unleased. */
    public static Result build(WorldScenePlan plan,AtlasRaster raster,WorldSectionCache cache,BooleanSupplier cancelled) {
        Objects.requireNonNull(plan);Objects.requireNonNull(raster);Objects.requireNonNull(cache);Objects.requireNonNull(cancelled);WorldProblem.check(cancelled);
        try { return execute(plan,raster,cache,cancelled); }
        catch(WorldProblem.Failure failure) { WorldProblem.check(cancelled);return Result.failed(plan.snapshot().source(),List.of(failure.problem)); }
        catch(OutOfMemoryError exhausted) { WorldProblem.check(cancelled);return Result.failed(plan.snapshot().source(),List.of(new WorldProblem(WorldProblem.Code.MEMORY,Optional.empty(),"Not enough memory to assemble the complete scene; no partial scene was published"))); }
    }
    private static Result execute(WorldScenePlan plan,AtlasRaster raster,WorldSectionCache cache,BooleanSupplier cancelled) {
        if(plan.requiresPlaceholder())return Result.failed(plan.snapshot().source(),plan.problems());
        if(raster.requiresPlaceholder()||raster.layout()!=plan.layout())throw WorldProblem.fail(WorldProblem.Code.ATLAS,"Scene raster must be successful and share the exact planned atlas epoch");
        WorldProblem.check(cancelled);cache.prepare(plan);
        try(var work=new Work(plan.sections().size())) {
            long bytes=0,covered=0;int faces=0,cells=0,markers=0;
            for(var section:plan.sections()) {
                WorldProblem.check(cancelled);var ticket=cache.reserve(section,plan.limits().workingBytes());work.own(ticket);
                if(!ticket.cached())ticket.complete(WorldMesher.assemble(section,cancelled));var mesh=ticket.mesh();
                if(mesh.models()!=plan.models()||mesh.visibility()!=plan.visibility()||!mesh.extent().equals(section.extent())||!mesh.usage().equals(section.usage()))throw WorldProblem.fail(WorldProblem.Code.ASSIGNMENT,"Cached section does not match the complete scene plan");
                var usage=mesh.usage().orElseThrow();bytes+=usage.outputBytes();covered+=mesh.extent().cells();faces+=usage.faces();cells+=usage.nonEmptyCells();markers+=usage.markers();work.sections.add(mesh);
            }
            var usage=plan.usage().orElseThrow();
            if(work.sections.size()!=usage.sections()||covered!=usage.coveredCells()||bytes!=usage.meshBytes()||faces!=usage.faces()||cells!=usage.cellRecords()||markers!=usage.markers())throw WorldProblem.fail(WorldProblem.Code.EXTENT,"Completed scene coverage or payload differs from the admitted plan");
            WorldProblem.check(cancelled);var scene=new WorldScene(plan,raster,work.sections);var result=new Result(plan.snapshot().source(),scene,work.tickets,List.of());WorldProblem.check(cancelled);work.transferred=true;return result;
        }
    }
}
