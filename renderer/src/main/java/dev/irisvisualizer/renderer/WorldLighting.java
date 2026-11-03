package dev.irisvisualizer.renderer;

import java.util.*;
import java.util.function.BooleanSupplier;

/** Detached final native RGB modulation per vertex, excluding texture and biome tint. */
public final class WorldLighting {
    public record Limits(long sampleBytes,long workingBytes) {
        public static final Limits DEFAULT=new Limits(96L*1024*1024,512L*1024*1024);
        public Limits { if(sampleBytes<0||sampleBytes>96L*1024*1024||workingBytes<0||workingBytes>512L*1024*1024)throw new IllegalArgumentException("Lighting limits exceed renderer bounds"); }
    }
    private final WorldScene scene;private final String policy;private final List<float[]> samples;private final long bytes;private final List<WorldProblem> problems;
    private WorldLighting(WorldScene scene,String policy,List<float[]> owned,long bytes,List<WorldProblem> problems) {
        this.scene=scene;this.policy=policy;samples=List.copyOf(owned);this.bytes=bytes;this.problems=List.copyOf(problems);
        if(!problems.isEmpty()&&(!owned.isEmpty()||bytes!=0))throw new IllegalArgumentException("Partial lighting escaped failure");
    }
    /** Frozen, scoped producer. Count methods must remain unchanged through both completion gates. */
    public interface Reader {
        default void begin() {}
        int sections();
        int components(int section);
        float component(int section,int face,int vertex,int channel);
        default void complete() {}
    }
    /** Caller freezes source arrays/list during capture. Samples are RGB triples in source vertex order. */
    public static WorldLighting capture(WorldScene scene,String nativePolicy,List<float[]> sectionVertexRgb,BooleanSupplier cancelled) { return capture(scene,nativePolicy,sectionVertexRgb,Limits.DEFAULT,cancelled); }
    public static WorldLighting capture(WorldScene scene,String nativePolicy,List<float[]> sectionVertexRgb,Limits limits,BooleanSupplier cancelled) {
        Objects.requireNonNull(sectionVertexRgb);
        return read(scene,nativePolicy,new Reader() {
            @Override public int sections() { return sectionVertexRgb.size(); }
            @Override public int components(int section) { var values=sectionVertexRgb.get(section);return values==null?-1:values.length; }
            @Override public float component(int section,int face,int vertex,int channel) { return sectionVertexRgb.get(section)[face*12+vertex*3+channel]; }
        },limits,cancelled);
    }
    /** Complete detached output without an intermediate caller-owned array per section. */
    public static WorldLighting read(WorldScene scene,String nativePolicy,Reader reader,Limits limits,BooleanSupplier cancelled) {
        Objects.requireNonNull(scene);Objects.requireNonNull(nativePolicy);Objects.requireNonNull(reader);Objects.requireNonNull(limits);Objects.requireNonNull(cancelled);WorldProblem.check(cancelled);
        if(nativePolicy.isBlank()||nativePolicy.length()>256)throw new IllegalArgumentException("Native lighting producer label is required");
        try { return execute(scene,nativePolicy,reader,limits,cancelled); }
        catch(WorldCapture.ReadFailure failure) { WorldProblem.check(cancelled);return failed(scene,nativePolicy,failure.problem()); }
        catch(WorldProblem.Failure failure) { WorldProblem.check(cancelled);return failed(scene,nativePolicy,failure.problem); }
        catch(java.util.concurrent.CancellationException cancellation) { throw cancellation; }
        catch(RuntimeException failure) { WorldProblem.check(cancelled);return failed(scene,nativePolicy,new WorldProblem(WorldProblem.Code.WORKER,Optional.empty(),"Lighting producer failed: "+failure.getClass().getSimpleName())); }
        catch(OutOfMemoryError exhausted) { WorldProblem.check(cancelled);return failed(scene,nativePolicy,new WorldProblem(WorldProblem.Code.MEMORY,Optional.empty(),"Not enough memory to capture lighting; no partial samples were published")); }
    }
    private static WorldLighting failed(WorldScene scene,String policy,WorldProblem problem) { return new WorldLighting(scene,policy,List.of(),0,List.of(problem)); }
    /** Optional shared ownership includes the exact retained CPU scene. Failure throws a detached ReadFailure. */
    public static RenderInput<WorldLighting> readOwned(WorldScene scene,String policy,Reader reader,Limits limits,RenderMemory memory,BooleanSupplier cancelled) {
        Objects.requireNonNull(scene);Objects.requireNonNull(limits);Objects.requireNonNull(reader);Objects.requireNonNull(cancelled);WorldProblem.check(cancelled);
        long peak=scene.usage().knownPayloadBytes()+(long)scene.usage().faces()*48;
        if(peak>limits.workingBytes()||(long)scene.usage().faces()*48>limits.sampleBytes())throw new WorldCapture.ReadFailure(new WorldProblem(WorldProblem.Code.LIMIT,Optional.empty(),"Owned lighting capture exceeds scene/sample working bounds"));
        var hold=memory==null?RenderMemory.Hold.none():memory.hold("Lighting capture",peak);
        try {
            var result=read(scene,policy,reader,limits,cancelled);if(result.requiresPlaceholder())throw new WorldCapture.ReadFailure(result.problems().getFirst());WorldProblem.check(cancelled);
            // execute/read have unwound their producer stack before sealing an immutable owner.
            hold.shrinkTo(scene.usage().knownPayloadBytes()+result.bytes());return RenderInput.own(result,hold);
        }finally { hold.close(); }
    }
    private static void shapes(WorldScene scene,Reader reader,BooleanSupplier cancelled) {
        if(reader.sections()!=scene.sections().size())throw WorldProblem.fail(WorldProblem.Code.ASSIGNMENT,"Supply one lighting section for every scene section");
        for(int section=0;section<scene.sections().size();section++) { WorldProblem.check(cancelled);if(reader.components(section)!=(long)scene.sections().get(section).faces().size()*12)throw WorldProblem.fail(WorldProblem.Code.ASSIGNMENT,"Lighting requires four RGB triples per emitted face in source vertex order"); }
    }
    private static WorldLighting execute(WorldScene scene,String policy,Reader reader,Limits limits,BooleanSupplier cancelled) {
        long bytes=(long)scene.usage().faces()*48;
        if(bytes>limits.sampleBytes()||scene.usage().knownPayloadBytes()+bytes>limits.workingBytes())throw WorldProblem.fail(WorldProblem.Code.LIMIT,"Lighting samples or combined CPU payload budget exceeded");
        reader.begin();WorldProblem.check(cancelled);shapes(scene,reader,cancelled);
        var output=new ArrayList<float[]>(scene.sections().size());
        for(int section=0;section<scene.sections().size();section++) {
            WorldProblem.check(cancelled);var mesh=scene.sections().get(section);float[] copy=new float[Math.multiplyExact(mesh.faces().size(),12)];
            for(int face=0;face<mesh.faces().size();face++) {
                if((face&255)==0)WorldProblem.check(cancelled);
                for(int vertex=0;vertex<4;vertex++)for(int channel=0;channel<3;channel++) {
                    WorldProblem.check(cancelled);float value=reader.component(section,face,vertex,channel);
                    if(!Float.isFinite(value)||value<0||value>1)throw WorldProblem.at(WorldProblem.Code.ASSIGNMENT,mesh.positionForFace(face),"Final native lighting modulation must be finite RGB in [0,1]; HDR values require an unsupported output path");copy[face*12+vertex*3+channel]=value;
                }
            }
            output.add(copy);
        }
        reader.complete();WorldProblem.check(cancelled);shapes(scene,reader,cancelled);var result=new WorldLighting(scene,policy,output,bytes,List.of());reader.complete();WorldProblem.check(cancelled);shapes(scene,reader,cancelled);return result;
    }
    public WorldScene scene() { return scene; }
    public String policy() { return policy; }
    public long bytes() { return bytes; }
    public float component(int section,int face,int vertex,int channel) {
        if(requiresPlaceholder())throw new IllegalStateException("Lighting capture failed");if(vertex<0||vertex>=4||channel<0||channel>=3)throw new IndexOutOfBoundsException("Invalid lighting vertex/channel");
        return samples.get(section)[Math.addExact(Math.multiplyExact(face,12),vertex*3+channel)];
    }
    public float[] copySectionSamples(int section) { if(requiresPlaceholder())throw new IllegalStateException("Lighting capture failed");return samples.get(section).clone(); }
    public List<WorldProblem> problems() { return problems; }
    public boolean requiresPlaceholder() { return !problems.isEmpty(); }
}
