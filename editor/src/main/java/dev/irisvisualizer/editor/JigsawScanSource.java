package dev.irisvisualizer.editor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.ToIntFunction;
import java.util.function.BooleanSupplier;
import java.util.concurrent.CancellationException;

/** Immutable bounded applied JSON and exact target presence, without retaining a live workspace. */
final class JigsawScanSource implements JigsawDependencyReview.CapturedSource {
    static final int MAX_FILES=4096;
    static final long MAX_BYTES=16_000_000,MAX_PATH_CHARACTERS=16_000_000;
    record Limits(int files,long bytes,long characters) {
        Limits { if (files<1 || files>MAX_FILES || bytes<0 || bytes>MAX_BYTES || characters<1 || characters>MAX_PATH_CHARACTERS) throw new IllegalArgumentException("Invalid jigsaw snapshot limits"); }
    }
    private final Set<String> paths;
    private final Map<String,Integer> sizes;
    private final Map<String,byte[]> content;
    private final long bytes;
    private final boolean borrowed;
    private JigsawScanSource(Set<String> paths,Map<String,Integer> sizes,Map<String,byte[]> content,long bytes,boolean borrowed) {
        this.paths=Collections.unmodifiableSet(paths);this.sizes=Map.copyOf(sizes);this.content=Map.copyOf(content);this.bytes=bytes;this.borrowed=borrowed;
    }
    static JigsawScanSource capture(PackResourceView current,String root) { return capture(current,root,new Limits(MAX_FILES,MAX_BYTES,MAX_PATH_CHARACTERS)); }
    static JigsawScanSource capture(PackResourceView current,String root,Limits limits) {
        Objects.requireNonNull(current);return capture(current.paths(),current::size,current::read,root,limits,false);
    }
    /** Only workspace-owned immutable arrays may be pinned; no map or capture callback is retained. */
    static JigsawScanSource pin(Map<String,byte[]> owned,String root) { return pin(owned,root,new Limits(MAX_FILES,MAX_BYTES,MAX_PATH_CHARACTERS)); }
    static JigsawScanSource pin(Map<String,byte[]> owned,String root,Limits limits) {
        Objects.requireNonNull(owned);return capture(owned.keySet(),path->Objects.requireNonNull(owned.get(path)).length,owned::get,root,limits,true);
    }
    private static JigsawScanSource capture(Set<String> names,ToIntFunction<String> sizeOf,Function<String,byte[]> payload,String root,Limits limits,boolean borrowed) {
        Objects.requireNonNull(limits);
        var paths=new TreeSet<String>();long characters=0;
        // Fail before payload copies if the complete exact-presence catalog cannot be represented.
        for (String path : names) {
            characters+=path.length();if (paths.size()>=PackWorkspace.MAX_FILES || characters>limits.characters()) throw new IllegalArgumentException("Jigsaw snapshot path catalog exceeds 100,000 files / "+limits.characters()+" UTF-16 characters");
            paths.add(path);
        }
        if (!JigsawDependencyReview.supports(root) || !paths.contains(root)) throw new IllegalArgumentException("Select an existing structure, jigsaw pool or jigsaw piece JSON resource");
        var order=new ArrayList<String>();order.add(root);for (String path : paths) if (!path.equals(root) && JigsawDependencyReview.supports(path)) order.add(path);
        var sizes=new HashMap<String,Integer>();var content=new HashMap<String,byte[]>();long bytes=0;
        // Root first, then lexical order. No parsing on the caller thread and no object/image payload reads.
        for (String path : order) {
            int size=sizeOf.applyAsInt(path);if (size<0) throw new IllegalArgumentException("Negative resource size");sizes.put(path,size);
            if (content.size()>=limits.files() || size>ReferenceDiagnostics.MAX_JSON_BYTES || size>limits.bytes()-bytes) continue;
            byte[] raw=Objects.requireNonNull(payload.apply(path));if (raw.length!=size) throw new IllegalArgumentException("Resource changed while capturing jigsaw source");
            content.put(path,raw);bytes+=size;
        }
        return new JigsawScanSource(paths,sizes,content,bytes,borrowed);
    }
    public Set<String> paths() { return paths; }
    public int size(String path) { return Objects.requireNonNull(sizes.get(path),"Resource outside captured jigsaw families"); }
    public byte[] read(String path) { return Objects.requireNonNull(content.get(path),"Resource outside captured jigsaw payload budget").clone(); }
    public boolean captured(String path) { return content.containsKey(path); }
    long retainedBytes() { return bytes; }
    int retainedFiles() { return content.size(); }
    long copiedBytes() { return borrowed ? 0 : bytes; }
    int copiedFiles() { return borrowed ? 0 : content.size(); }
    /** The worker prepares defensive copies in cancellable 64-KiB chunks. */
    JigsawDependencyReview.CapturedSource view(BooleanSupplier cancelled) {
        Objects.requireNonNull(cancelled);
        return new JigsawDependencyReview.CapturedSource() {
            private void check() { if(cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())throw new CancellationException("Jigsaw source read cancelled"); }
            public Set<String> paths(){check();return JigsawScanSource.this.paths();}
            public int size(String path){check();return JigsawScanSource.this.size(path);}
            public boolean captured(String path){check();return JigsawScanSource.this.captured(path);}
            public byte[] read(String path){
                check();var original=Objects.requireNonNull(content.get(path),"Resource outside captured jigsaw payload budget");var copy=new byte[original.length];
                for(int offset=0;offset<original.length;offset+=65536){check();System.arraycopy(original,offset,copy,offset,Math.min(65536,original.length-offset));}check();return copy;
            }
        };
    }
}
