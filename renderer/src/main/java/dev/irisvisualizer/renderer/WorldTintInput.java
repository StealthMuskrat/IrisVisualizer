package dev.irisvisualizer.renderer;

import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Immutable native RGB layers per original cell, with one stable layer schema per original state. */
public final class WorldTintInput {
    public enum Mode { NONE, NATIVE }
    public record Layers(NavigableMap<Integer,Integer> colors) {
        public static final Layers EMPTY=new Layers(new TreeMap<>());
        public Layers {
            Objects.requireNonNull(colors);if(colors.size()>64)throw new IllegalArgumentException("Native cell tint-layer count exceeds 64");var copy=new TreeMap<Integer,Integer>();
            for(var entry:colors.entrySet()) { int index=entry.getKey(),rgb=entry.getValue();if(index<0||index>65535||(rgb&0xff000000)!=0)throw new IllegalArgumentException("Native tint layers require bounded indices and RGB24 values");copy.put(index,rgb); }colors=Collections.unmodifiableNavigableMap(copy);
        }
        public static Layers of(Map<Integer,Integer> values) { return new Layers(new TreeMap<>(values)); }
    }
    public interface Reader { Layers colors(int cell);default void complete() {} }
    public record Limits(int tables,int layers,long payloadBytes,long workingBytes) {
        public static final Limits DEFAULT=new Limits(65536,65536,40L*1024*1024,512L*1024*1024);
        public Limits { if(tables<1||tables>65536||layers<0||layers>65536||payloadBytes<0||payloadBytes>40L*1024*1024||workingBytes<0||workingBytes>512L*1024*1024)throw new IllegalArgumentException("Native tint-input limits exceeded"); }
    }
    private final WorldSnapshot snapshot;private final Optional<ClientAssetSnapshot.Receipt> assets;private final Mode mode;private final String policy;private final char[] cells;private final int[] offsets,lengths,indices,rgb,schemas;private final int tables,layers;private final List<WorldProblem> problems;
    private WorldTintInput(WorldSnapshot snapshot,Optional<ClientAssetSnapshot.Receipt> assets,Mode mode,String policy,char[] cells,int[] offsets,int[] lengths,int[] indices,int[] rgb,int[] schemas,int tables,int layers,List<WorldProblem> problems) {
        this.snapshot=snapshot;this.assets=assets;this.mode=mode;this.policy=policy;this.cells=cells;this.offsets=offsets;this.lengths=lengths;this.indices=indices;this.rgb=rgb;this.schemas=schemas;this.tables=tables;this.layers=layers;this.problems=List.copyOf(problems);
        if(!problems.isEmpty()&&(cells.length!=0||offsets.length!=0||lengths.length!=0||indices.length!=0||rgb.length!=0||schemas.length!=0||tables!=0||layers!=0))throw new IllegalArgumentException("Partial native tint input escaped failure");
    }
    public static WorldTintInput none(WorldSnapshot snapshot) { Objects.requireNonNull(snapshot);return empty(snapshot,Optional.empty(),Mode.NONE,"No native tint input",snapshot.problems()); }
    private static WorldTintInput empty(WorldSnapshot snapshot,Optional<ClientAssetSnapshot.Receipt> assets,Mode mode,String policy,List<WorldProblem> problems) { return new WorldTintInput(snapshot,assets,mode,policy,new char[0],new int[0],new int[0],new int[0],new int[0],new int[0],0,0,problems); }
    public static WorldTintInput read(ClientAssetSnapshot assets,WorldSnapshot snapshot,String policy,Reader reader,Limits limits,BooleanSupplier cancelled) {
        Objects.requireNonNull(assets);Objects.requireNonNull(snapshot);Objects.requireNonNull(policy);Objects.requireNonNull(reader);Objects.requireNonNull(limits);Objects.requireNonNull(cancelled);WorldProblem.check(cancelled);
        if(policy.isBlank()||policy.length()>256)throw new IllegalArgumentException("Native tint policy requires a bounded label");var receipt=Optional.of(assets.receipt());
        try {
            if(snapshot.requiresPlaceholder())return empty(snapshot,receipt,Mode.NATIVE,policy,snapshot.problems());
            if(!snapshot.source().minecraftVersion().equals(BlockModels.PROFILE)||!assets.receipt().version().equals(BlockModels.PROFILE)||!assets.receipt().expectedVersion().equals(BlockModels.PROFILE))throw WorldProblem.fail(WorldProblem.Code.VERSION,"Native tint inputs require the pinned native/client profile");
            long bytes=(long)snapshot.cellCount()*2+(long)limits.tables()*8+(long)limits.layers()*8+(long)snapshot.states().size()*4;
            if(bytes>limits.payloadBytes()||assets.receipt().capturedAssetBytes()+snapshot.indexBytes()+bytes>limits.workingBytes())throw WorldProblem.fail(WorldProblem.Code.LIMIT,"Native tint source and prospective palette/index payload exceed their allowance");
            char[] cells=new char[snapshot.cellCount()];int[] offsets=new int[limits.tables()],lengths=new int[limits.tables()],indices=new int[limits.layers()],rgb=new int[limits.layers()],schemas=new int[snapshot.states().size()];Arrays.fill(schemas,-1);
            var lookup=new HashMap<Layers,Integer>();lookup.put(Layers.EMPTY,0);int count=1,used=0;
            for(int cell=0;cell<cells.length;cell++) {
                WorldProblem.check(cancelled);var value=Objects.requireNonNull(reader.colors(cell));Integer table=lookup.get(value);
                if(table==null) {
                    if(count>=limits.tables()||value.colors().size()>limits.layers()-used)throw WorldProblem.fail(WorldProblem.Code.LIMIT,"Native tint palette/layer budget reached");table=count++;offsets[table]=used;lengths[table]=value.colors().size();
                    for(var color:value.colors().entrySet()) { indices[used]=color.getKey();rgb[used++]=color.getValue(); }lookup.put(value,table);
                }
                int state=snapshot.stateIndex(cell),prior=schemas[state];
                if(prior<0)schemas[state]=table;
                else { if(lengths[prior]!=lengths[table])throw WorldProblem.at(WorldProblem.Code.ASSIGNMENT,snapshot.extent().position(cell),"One original native state has inconsistent tint-layer coverage");for(int at=0;at<lengths[table];at++)if(indices[offsets[prior]+at]!=indices[offsets[table]+at])throw WorldProblem.at(WorldProblem.Code.ASSIGNMENT,snapshot.extent().position(cell),"One original native state has inconsistent tint-layer indices"); }
                cells[cell]=(char)(int)table;
            }
            reader.complete();WorldProblem.check(cancelled);return new WorldTintInput(snapshot,receipt,Mode.NATIVE,policy,cells,offsets,lengths,indices,rgb,schemas,count,used,List.of());
        }catch(WorldCapture.ReadFailure failure) { WorldProblem.check(cancelled);return empty(snapshot,receipt,Mode.NATIVE,policy,List.of(failure.problem())); }
        catch(WorldProblem.Failure failure) { WorldProblem.check(cancelled);return empty(snapshot,receipt,Mode.NATIVE,policy,List.of(failure.problem)); }
        catch(CancellationException cancellation) { throw cancellation; }
        catch(RuntimeException failure) { WorldProblem.check(cancelled);return empty(snapshot,receipt,Mode.NATIVE,policy,List.of(new WorldProblem(WorldProblem.Code.WORKER,Optional.empty(),"Native tint reader failed: "+failure.getClass().getSimpleName()))); }
        catch(OutOfMemoryError exhausted) { WorldProblem.check(cancelled);return empty(snapshot,receipt,Mode.NATIVE,policy,List.of(new WorldProblem(WorldProblem.Code.MEMORY,Optional.empty(),"Not enough memory to capture native tint inputs"))); }
    }
    public WorldSnapshot snapshot() { return snapshot; }
    public Mode mode() { return mode; }
    public String policy() { return policy; }
    public boolean matchesAssets(ClientAssetSnapshot.Receipt receipt) { return mode==Mode.NONE||assets.isPresent()&&AtlasLayout.sameAssets(assets.orElseThrow(),receipt); }
    public long bytes() { return (long)cells.length*2+(long)(offsets.length+lengths.length+indices.length+rgb.length+schemas.length)*4; }
    public int tables() { return tables; }
    public int layers() { return layers; }
    public boolean hasColors() { return !requiresPlaceholder()&&layers>0; }
    public List<WorldProblem> problems() { return problems; }
    public boolean requiresPlaceholder() { return !problems.isEmpty(); }
    private int find(int table,int layer) { return Arrays.binarySearch(indices,offsets[table],offsets[table]+lengths[table],layer); }
    public boolean supports(int stateIndex,int layer) { if(requiresPlaceholder())throw new IllegalStateException("Native tint capture failed");if(stateIndex<0||stateIndex>=snapshot.states().size())throw new IndexOutOfBoundsException();return mode==Mode.NATIVE&&schemas[stateIndex]>=0&&find(schemas[stateIndex],layer)>=0; }
    public int rgb(int cell,int layer) { if(requiresPlaceholder())throw new IllegalStateException("Native tint capture failed");if(cell<0||cell>=snapshot.cellCount())throw new IndexOutOfBoundsException();if(mode!=Mode.NATIVE)throw new IllegalStateException("No native tint was captured");int at=find(cells[cell],layer);if(at<0)throw new IllegalArgumentException("Native tint layer is absent");return rgb[at]; }
}
