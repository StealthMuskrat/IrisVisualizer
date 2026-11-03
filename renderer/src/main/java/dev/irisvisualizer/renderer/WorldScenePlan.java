package dev.irisvisualizer.renderer;

import java.util.*;
import java.util.function.BooleanSupplier;

/** Complete regular coverage and aggregate admission, before any world-section output allocation. */
public final class WorldScenePlan {
    public record Limits(int sectionEdge,int sections,int faces,int cellRecords,int markers,long meshBytes,long workingBytes) {
        public static final Limits DEFAULT=new Limits(16,8192,2097152,2097152,1048576,256L*1024*1024,512L*1024*1024);
        public Limits { if(sectionEdge<1||sectionEdge>32||sections<1||sections>65536||faces<1||faces>2097152||cellRecords<0||cellRecords>4194304||markers<0||markers>1048576||meshBytes<0||meshBytes>256L*1024*1024||workingBytes<0||workingBytes>512L*1024*1024)throw new IllegalArgumentException("Scene limits exceed renderer bounds"); }
    }
    public record Usage(int sections,long coveredCells,long sourcePayloadBytes,long meshBytes,int faces,int cellRecords,int markers) {
        public Usage { if(sections<1||coveredCells<1||sourcePayloadBytes<0||meshBytes<0||faces<0||cellRecords<0||markers<0)throw new IllegalArgumentException("Invalid scene usage"); }
        public long knownPayloadBytes() { return sourcePayloadBytes+meshBytes; }
    }
    private final WorldModels models;private final WorldVisibility visibility;private final Limits limits;private final WorldMesher.Limits sectionLimits;
    private final List<WorldMeshPlan> sections;private final Optional<Usage> usage;private final List<WorldProblem> problems;
    private WorldScenePlan(WorldModels models,WorldVisibility visibility,Limits limits,WorldMesher.Limits sectionLimits,List<WorldMeshPlan> sections,Optional<Usage> usage,List<WorldProblem> problems) {
        this.models=models;this.visibility=visibility;this.limits=limits;this.sectionLimits=sectionLimits;this.sections=List.copyOf(sections);this.usage=usage;this.problems=List.copyOf(problems);
        if(!problems.isEmpty()&&(!sections.isEmpty()||usage.isPresent()))throw new IllegalArgumentException("Partial scene plan escaped failure");
    }
    public static WorldScenePlan plan(WorldModels models,WorldVisibility visibility,BooleanSupplier cancelled) { return plan(models,visibility,Limits.DEFAULT,WorldMesher.Limits.DEFAULT,cancelled); }
    public static WorldScenePlan plan(WorldModels models,WorldVisibility visibility,Limits limits,WorldMesher.Limits sectionLimits,BooleanSupplier cancelled) {
        Objects.requireNonNull(models);Objects.requireNonNull(visibility);Objects.requireNonNull(limits);Objects.requireNonNull(sectionLimits);Objects.requireNonNull(cancelled);WorldProblem.check(cancelled);
        try { return execute(models,visibility,limits,sectionLimits,cancelled); }
        catch(WorldProblem.Failure failure) { WorldProblem.check(cancelled);return failed(models,visibility,limits,sectionLimits,List.of(failure.problem)); }
        catch(OutOfMemoryError exhausted) { WorldProblem.check(cancelled);return failed(models,visibility,limits,sectionLimits,List.of(new WorldProblem(WorldProblem.Code.MEMORY,Optional.empty(),"Not enough memory to plan complete scene coverage; no partial scene plan was published"))); }
    }
    private static WorldScenePlan failed(WorldModels models,WorldVisibility visibility,Limits limits,WorldMesher.Limits sectionLimits,List<WorldProblem> problems) { return new WorldScenePlan(models,visibility,limits,sectionLimits,List.of(),Optional.empty(),problems); }
    private static WorldScenePlan execute(WorldModels models,WorldVisibility visibility,Limits limits,WorldMesher.Limits sectionLimits,BooleanSupplier cancelled) {
        if(models.requiresPlaceholder())return failed(models,visibility,limits,sectionLimits,models.problems());
        if(visibility.requiresPlaceholder())return failed(models,visibility,limits,sectionLimits,visibility.problems());
        if(visibility.snapshot()!=models.snapshot()||visibility.layout()!=models.layout())throw WorldProblem.fail(WorldProblem.Code.VISIBILITY,"Scene inputs must share the exact native snapshot and atlas epoch");
        int edge=limits.sectionEdge();if(edge>sectionLimits.axis()||(long)edge*edge*edge>sectionLimits.cells())throw WorldProblem.fail(WorldProblem.Code.EXTENT,"Scene tile size exceeds the explicit section assembly limits");
        var extent=models.snapshot().extent();long nx=((long)extent.width()+edge-1)/edge,ny=((long)extent.height()+edge-1)/edge,nz=((long)extent.depth()+edge-1)/edge;
        long count=nx*ny*nz;if(count>limits.sections())throw WorldProblem.fail(WorldProblem.Code.LIMIT,"Complete scene section count exceeds its budget; no vertical coverage was truncated");
        var sections=new ArrayList<WorldMeshPlan>((int)count);long bytes=0,faces=0,cells=0,markers=0,covered=0,source=-1;
        for(long iy=0;iy<ny;iy++)for(long iz=0;iz<nz;iz++)for(long ix=0;ix<nx;ix++) {
            WorldProblem.check(cancelled);int dx=(int)(ix*edge),dy=(int)(iy*edge),dz=(int)(iz*edge);
            var tile=new WorldSnapshot.Extent(extent.minX()+dx,Math.toIntExact((long)extent.minY()+dy),extent.minZ()+dz,Math.min(edge,extent.width()-dx),Math.min(edge,extent.height()-dy),Math.min(edge,extent.depth()-dz));
            var section=WorldMesher.plan(models,visibility,tile,sectionLimits,cancelled);if(section.requiresPlaceholder())throw new WorldProblem.Failure(section.problems().getFirst());
            var usage=section.usage().orElseThrow();long common=usage.knownPayloadBytes()-usage.outputBytes();
            if(source<0)source=common;else if(source!=common)throw WorldProblem.fail(WorldProblem.Code.ASSIGNMENT,"Scene sections disagree on shared source payload");
            bytes+=usage.outputBytes();faces+=usage.faces();cells+=usage.nonEmptyCells();markers+=usage.markers();covered+=tile.cells();
            if(bytes>limits.meshBytes()||faces>limits.faces()||cells>limits.cellRecords()||markers>limits.markers()||source+bytes>limits.workingBytes())throw WorldProblem.fail(WorldProblem.Code.LIMIT,"Complete scene mesh, metadata or combined payload budget exceeded; no partial scene was published");
            sections.add(section);
        }
        if(covered!=extent.cells()||sections.size()!=count)throw WorldProblem.fail(WorldProblem.Code.EXTENT,"Scene section coverage does not equal the complete native volume");
        var usage=new Usage(sections.size(),covered,source,bytes,(int)faces,(int)cells,(int)markers);WorldProblem.check(cancelled);
        return new WorldScenePlan(models,visibility,limits,sectionLimits,sections,Optional.of(usage),List.of());
    }
    public WorldModels models() { return models; }
    public WorldVisibility visibility() { return visibility; }
    public WorldSnapshot snapshot() { return models.snapshot(); }
    public AtlasLayout layout() { return models.layout(); }
    public Limits limits() { return limits; }
    public WorldMesher.Limits sectionLimits() { return sectionLimits; }
    public List<WorldMeshPlan> sections() { return sections; }
    public Optional<Usage> usage() { return usage; }
    public List<WorldProblem> problems() { return problems; }
    public boolean requiresPlaceholder() { return !problems.isEmpty(); }
}
