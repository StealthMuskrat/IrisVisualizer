package dev.irisvisualizer.renderer;

import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Immutable per-cell model translations; native sampling stays outside meshing and generation data. */
public final class WorldOffsets {
    public enum Mode { NONE, NATIVE }
    public record Offset(double x,double y,double z) {
        public static final Offset ZERO=new Offset(0,0,0);
        public Offset { if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z))throw new IllegalArgumentException("Nonfinite model offset");x=x==0?0:x;y=y==0?0:y;z=z==0?0:z; }
    }
    public interface Reader { Offset offset(int cell);default void complete() {} }
    public record Limits(int translations,long payloadBytes,long workingBytes,double componentBlocks) {
        public static final Limits DEFAULT=new Limits(65536,40L*1024*1024,256L*1024*1024,1);
        public Limits { if(translations<1||translations>65536||payloadBytes<0||payloadBytes>40L*1024*1024||workingBytes<0||workingBytes>512L*1024*1024||!Double.isFinite(componentBlocks)||componentBlocks<0||componentBlocks>1)throw new IllegalArgumentException("Model offset limits exceed renderer bounds"); }
    }
    private final WorldSnapshot snapshot;private final Mode mode;private final String policy;private final char[] indices;private final double[] palette;private final int translations;private final long nonzero;private final List<WorldProblem> problems;
    private WorldOffsets(WorldSnapshot snapshot,Mode mode,String policy,char[] ownedIndices,double[] ownedPalette,int translations,long nonzero,List<WorldProblem> problems) {
        this.snapshot=snapshot;this.mode=mode;this.policy=policy;indices=ownedIndices;palette=ownedPalette;this.translations=translations;this.nonzero=nonzero;this.problems=List.copyOf(problems);
        if(!problems.isEmpty()&&(indices.length!=0||palette.length!=0||translations!=0||nonzero!=0))throw new IllegalArgumentException("Partial native offsets escaped failure");
    }
    public static WorldOffsets none(WorldSnapshot snapshot) { Objects.requireNonNull(snapshot);return new WorldOffsets(snapshot,Mode.NONE,"No model translation",new char[0],new double[0],0,0,snapshot.problems()); }
    public static WorldOffsets read(WorldSnapshot snapshot,String policy,Reader reader,BooleanSupplier cancelled) { return read(snapshot,policy,reader,Limits.DEFAULT,cancelled); }
    public static WorldOffsets read(WorldSnapshot snapshot,String policy,Reader reader,Limits limits,BooleanSupplier cancelled) {
        Objects.requireNonNull(snapshot);Objects.requireNonNull(policy);Objects.requireNonNull(reader);Objects.requireNonNull(limits);Objects.requireNonNull(cancelled);WorldProblem.check(cancelled);
        if(policy.isBlank()||policy.length()>256)throw new IllegalArgumentException("Invalid native model offset policy");
        try {
            if(snapshot.requiresPlaceholder())return failed(snapshot,policy,snapshot.problems());
            long bytes=(long)snapshot.cellCount()*2+(long)limits.translations()*24;
            if(bytes>limits.payloadBytes()||snapshot.indexBytes()+bytes>limits.workingBytes())throw WorldProblem.fail(WorldProblem.Code.LIMIT,"Native indices and prospective model offset palette/index payload exceed their budget");
            char[] indices=new char[snapshot.cellCount()];double[] values=new double[limits.translations()*3];var lookup=new HashMap<Offset,Integer>();lookup.put(Offset.ZERO,0);int count=1;long nonzero=0;
            for(int cell=0;cell<indices.length;cell++) {
                WorldProblem.check(cancelled);var value=Objects.requireNonNull(reader.offset(cell));
                if(Math.max(Math.max(Math.abs(value.x()),Math.abs(value.y())),Math.abs(value.z()))>limits.componentBlocks())throw WorldProblem.at(WorldProblem.Code.COORDINATE,snapshot.extent().position(cell),"Native model offset exceeds its finite local translation bound");
                var index=lookup.get(value);
                if(index==null) { if(count>=limits.translations())throw WorldProblem.fail(WorldProblem.Code.LIMIT,"Distinct native model offset palette budget reached");index=count++;values[index*3]=value.x();values[index*3+1]=value.y();values[index*3+2]=value.z();lookup.put(value,index); }
                indices[cell]=(char)(int)index;if(index!=0)nonzero++;
            }
            reader.complete();WorldProblem.check(cancelled);return new WorldOffsets(snapshot,Mode.NATIVE,policy,indices,values,count,nonzero,List.of());
        }catch(WorldCapture.ReadFailure failure) { WorldProblem.check(cancelled);return failed(snapshot,policy,List.of(failure.problem())); }
        catch(WorldProblem.Failure failure) { WorldProblem.check(cancelled);return failed(snapshot,policy,List.of(failure.problem)); }
        catch(CancellationException cancellation) { throw cancellation; }
        catch(RuntimeException failure) { WorldProblem.check(cancelled);return failed(snapshot,policy,List.of(new WorldProblem(WorldProblem.Code.WORKER,Optional.empty(),"Native model offset reader failed: "+failure.getClass().getSimpleName()))); }
        catch(OutOfMemoryError exhausted) { WorldProblem.check(cancelled);return failed(snapshot,policy,List.of(new WorldProblem(WorldProblem.Code.MEMORY,Optional.empty(),"Not enough memory for native model offsets; no partial capture was published"))); }
    }
    private static WorldOffsets failed(WorldSnapshot snapshot,String policy,List<WorldProblem> problems) { return new WorldOffsets(snapshot,Mode.NATIVE,policy,new char[0],new double[0],0,0,problems); }
    public WorldSnapshot snapshot() { return snapshot; }
    public Mode mode() { return mode; }
    public String policy() { return policy; }
    public int translations() { return translations; }
    public long nonzeroCells() { return nonzero; }
    public long bytes() { return (long)indices.length*2+(long)palette.length*8; }
    public List<WorldProblem> problems() { return problems; }
    public boolean requiresPlaceholder() { return !problems.isEmpty(); }
    public double component(int cell,int axis) { if(requiresPlaceholder())throw new IllegalStateException("Native model offset capture failed");if(cell<0||cell>=snapshot.cellCount()||axis<0||axis>2)throw new IndexOutOfBoundsException("Offset query outside its captured volume/components");return mode==Mode.NONE?0:palette[indices[cell]*3+axis]; }
}
