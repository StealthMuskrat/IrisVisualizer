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
    /** Caller freezes source arrays/list during capture. Samples are RGB triples in source vertex order. */
    public static WorldLighting capture(WorldScene scene,String nativePolicy,List<float[]> sectionVertexRgb,BooleanSupplier cancelled) { return capture(scene,nativePolicy,sectionVertexRgb,Limits.DEFAULT,cancelled); }
    public static WorldLighting capture(WorldScene scene,String nativePolicy,List<float[]> sectionVertexRgb,Limits limits,BooleanSupplier cancelled) {
        Objects.requireNonNull(scene);Objects.requireNonNull(nativePolicy);Objects.requireNonNull(sectionVertexRgb);Objects.requireNonNull(limits);Objects.requireNonNull(cancelled);WorldProblem.check(cancelled);
        if(nativePolicy.isBlank()||nativePolicy.length()>256)throw new IllegalArgumentException("Native lighting producer label is required");
        try { return execute(scene,nativePolicy,sectionVertexRgb,limits,cancelled); }
        catch(WorldProblem.Failure failure) { WorldProblem.check(cancelled);return new WorldLighting(scene,nativePolicy,List.of(),0,List.of(failure.problem)); }
        catch(OutOfMemoryError exhausted) { WorldProblem.check(cancelled);return new WorldLighting(scene,nativePolicy,List.of(),0,List.of(new WorldProblem(WorldProblem.Code.MEMORY,Optional.empty(),"Not enough memory to capture lighting; no partial samples were published"))); }
    }
    private static WorldLighting execute(WorldScene scene,String policy,List<float[]> input,Limits limits,BooleanSupplier cancelled) {
        long bytes=(long)scene.usage().faces()*48;
        if(bytes>limits.sampleBytes()||scene.usage().knownPayloadBytes()+bytes>limits.workingBytes())throw WorldProblem.fail(WorldProblem.Code.LIMIT,"Lighting samples or combined CPU payload budget exceeded");
        if(input.size()!=scene.sections().size())throw WorldProblem.fail(WorldProblem.Code.ASSIGNMENT,"Supply one lighting array per scene section");
        // Preflight every shape before allocating any sample output.
        for(int section=0;section<input.size();section++) {
            WorldProblem.check(cancelled);var source=input.get(section);
            if(source==null||source.length!=(long)scene.sections().get(section).faces().size()*12)throw WorldProblem.fail(WorldProblem.Code.ASSIGNMENT,"Lighting requires four RGB triples per emitted face in source vertex order");
        }
        var output=new ArrayList<float[]>(input.size());
        for(int section=0;section<input.size();section++) {
            WorldProblem.check(cancelled);var mesh=scene.sections().get(section);float[] source=input.get(section),copy=new float[source.length];
            for(int face=0;face<mesh.faces().size();face++) {
                if((face&1023)==0)WorldProblem.check(cancelled);
                for(int component=0;component<12;component++) { int at=face*12+component;float value=source[at];if(!Float.isFinite(value)||value<0||value>1)throw WorldProblem.at(WorldProblem.Code.ASSIGNMENT,mesh.positionForFace(face),"Final native lighting modulation must be finite RGB in [0,1]; HDR values require an unsupported output path");copy[at]=value; }
            }
            output.add(copy);
        }
        WorldProblem.check(cancelled);return new WorldLighting(scene,policy,output,bytes,List.of());
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
