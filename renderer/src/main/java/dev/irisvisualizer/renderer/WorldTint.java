package dev.irisvisualizer.renderer;

import java.util.*;
import java.util.function.BooleanSupplier;

/** Detached native per-vertex RGB samples, bound to one exact immutable CPU scene. */
public final class WorldTint {
    public record Limits(long colorBytes,long workingBytes) {
        public static final Limits DEFAULT=new Limits(32L*1024*1024,512L*1024*1024);
        public Limits { if(colorBytes<0||colorBytes>32L*1024*1024||workingBytes<0||workingBytes>512L*1024*1024)throw new IllegalArgumentException("Native tint limits exceed renderer bounds"); }
    }
    private final WorldScene scene;private final String policy;private final List<int[]> colors;private final long bytes;private final List<WorldProblem> problems;
    private WorldTint(WorldScene scene,String policy,List<int[]> owned,long bytes,List<WorldProblem> problems) { this.scene=scene;this.policy=policy;colors=List.copyOf(owned);this.bytes=bytes;this.problems=List.copyOf(problems);if(!problems.isEmpty()&&(!owned.isEmpty()||bytes!=0))throw new IllegalArgumentException("Partial native tint escaped failure"); }
    /** Caller freezes the source list/arrays throughout capture; output owns detached copies. */
    public static WorldTint capture(WorldScene scene,String nativePolicy,List<int[]> sectionVertexRgb,BooleanSupplier cancelled) { return capture(scene,nativePolicy,sectionVertexRgb,Limits.DEFAULT,cancelled); }
    public static WorldTint capture(WorldScene scene,String nativePolicy,List<int[]> sectionVertexRgb,Limits limits,BooleanSupplier cancelled) {
        Objects.requireNonNull(scene);Objects.requireNonNull(nativePolicy);Objects.requireNonNull(sectionVertexRgb);Objects.requireNonNull(limits);Objects.requireNonNull(cancelled);WorldProblem.check(cancelled);
        if(nativePolicy.isBlank()||nativePolicy.length()>256)throw new IllegalArgumentException("Native tint producer label is required");
        try { return execute(scene,nativePolicy,sectionVertexRgb,limits,cancelled); }
        catch(WorldProblem.Failure failure) { WorldProblem.check(cancelled);return new WorldTint(scene,nativePolicy,List.of(),0,List.of(failure.problem)); }
        catch(OutOfMemoryError exhausted) { WorldProblem.check(cancelled);return new WorldTint(scene,nativePolicy,List.of(),0,List.of(new WorldProblem(WorldProblem.Code.MEMORY,Optional.empty(),"Not enough memory to capture native tint; no partial colors were published"))); }
    }
    private static WorldTint execute(WorldScene scene,String policy,List<int[]> input,Limits limits,BooleanSupplier cancelled) {
        long bytes=(long)scene.usage().faces()*16;
        if(bytes>limits.colorBytes()||scene.usage().knownPayloadBytes()+bytes>limits.workingBytes())throw WorldProblem.fail(WorldProblem.Code.LIMIT,"Native tint color or combined CPU payload budget exceeded");
        if(input.size()!=scene.sections().size())throw WorldProblem.fail(WorldProblem.Code.ASSIGNMENT,"Supply one native tint array per scene section");
        var output=new ArrayList<int[]>(input.size());
        for(int section=0;section<scene.sections().size();section++) {
            WorldProblem.check(cancelled);var mesh=scene.sections().get(section);var source=Objects.requireNonNull(input.get(section));
            if(source.length!=(long)mesh.faces().size()*4)throw WorldProblem.fail(WorldProblem.Code.ASSIGNMENT,"Native tint requires four RGB samples per emitted face in source vertex order");int[] copy=new int[source.length];
            for(int face=0;face<mesh.faces().size();face++) {
                if((face&1023)==0)WorldProblem.check(cancelled);boolean tinted=mesh.faces().get(face).source().source().tintIndex()>=0;
                for(int vertex=0;vertex<4;vertex++) { int at=face*4+vertex,rgb=source[at];if((rgb&0xff000000)!=0||!tinted&&rgb!=0xffffff)throw WorldProblem.at(WorldProblem.Code.ASSIGNMENT,mesh.positionForFace(face),"Native colors must be RGB24; faces without a tint index must stay white");copy[at]=rgb; }
            }
            output.add(copy);
        }
        WorldProblem.check(cancelled);return new WorldTint(scene,policy,output,bytes,List.of());
    }
    public WorldScene scene() { return scene; }
    public String policy() { return policy; }
    public long bytes() { return bytes; }
    public int rgb(int section,int face,int vertex) { if(requiresPlaceholder())throw new IllegalStateException("Native tint capture failed");if(vertex<0||vertex>=4)throw new IndexOutOfBoundsException("Invalid tint vertex");return colors.get(section)[Math.addExact(Math.multiplyExact(face,4),vertex)]; }
    public int[] copySectionColors(int section) { if(requiresPlaceholder())throw new IllegalStateException("Native tint capture failed");return colors.get(section).clone(); }
    public List<WorldProblem> problems() { return problems; }
    public boolean requiresPlaceholder() { return !problems.isEmpty(); }
}
