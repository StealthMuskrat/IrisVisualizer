package dev.irisvisualizer.editor;

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

/** Immutable eligible piece JSON only. No live workspace, unrelated catalog or binary payload is retained. */
final class ThemeReferenceSource implements PackResourceView {
    static final long MAX_PATH_CHARACTERS=16_000_000;
    record Limits(int files,long bytes,long characters) {
        Limits { if (files<1 || files>StructureThemeReferences.MAX_FILES || bytes<0 || bytes>StructureThemeReferences.MAX_BYTES || characters<1 || characters>MAX_PATH_CHARACTERS) throw new IllegalArgumentException("Invalid piece-theme snapshot limits"); }
    }
    private final Set<String> paths;
    private final Map<String,Integer> sizes;
    private final Map<String,byte[]> content;
    private final long bytes;
    private final boolean borrowed;
    private ThemeReferenceSource(Set<String> paths,Map<String,Integer> sizes,Map<String,byte[]> content,long bytes,boolean borrowed) {
        this.paths=Collections.unmodifiableSet(paths);this.sizes=Map.copyOf(sizes);this.content=Map.copyOf(content);this.bytes=bytes;this.borrowed=borrowed;
    }
    static ThemeReferenceSource capture(PackResourceView current) { return capture(current,new Limits(StructureThemeReferences.MAX_FILES,StructureThemeReferences.MAX_BYTES,MAX_PATH_CHARACTERS)); }
    static ThemeReferenceSource capture(PackResourceView current,Limits limits) {
        Objects.requireNonNull(current);return capture(current.paths(),current::size,current::read,limits,false);
    }
    /** PackWorkspace alone supplies immutable-by-ownership arrays. No live map or callback is retained. */
    static ThemeReferenceSource pin(Map<String,byte[]> owned) { return pin(owned,new Limits(StructureThemeReferences.MAX_FILES,StructureThemeReferences.MAX_BYTES,MAX_PATH_CHARACTERS)); }
    static ThemeReferenceSource pin(Map<String,byte[]> owned,Limits limits) {
        Objects.requireNonNull(owned);return capture(owned.keySet(),path->Objects.requireNonNull(owned.get(path)).length,owned::get,limits,true);
    }
    private static ThemeReferenceSource capture(Set<String> names,ToIntFunction<String> sizeOf,Function<String,byte[]> payload,Limits limits,boolean borrowed) {
        Objects.requireNonNull(limits);var paths=new TreeSet<String>();long characters=0;
        for (String path : names) {
            if (!StructureThemeReferences.supports(path)) continue;
            characters+=path.length();if (paths.size()>=PackWorkspace.MAX_FILES || characters>limits.characters()) throw new IllegalArgumentException("Piece-theme snapshot exceeds 100,000 eligible paths / "+limits.characters()+" UTF-16 characters");
            paths.add(path);
        }
        var sizes=new HashMap<String,Integer>();var content=new HashMap<String,byte[]>();int considered=0;long bytes=0;
        for (String path : paths) {
            if (considered++>=limits.files()) break;
            int size=sizeOf.applyAsInt(path);if (size<0) throw new IllegalArgumentException("Negative piece size");sizes.put(path,size);
            if (size>StructureThemeReferences.MAX_FILE_BYTES || size>limits.bytes()-bytes) continue;
            byte[] raw=Objects.requireNonNull(payload.apply(path));if (raw.length!=size) throw new IllegalArgumentException("Resource changed while capturing piece-theme source");content.put(path,raw);bytes+=size;
        }
        return new ThemeReferenceSource(paths,sizes,content,bytes,borrowed);
    }
    public Set<String> paths() { return paths; }
    public int size(String path) { return Objects.requireNonNull(sizes.get(path),"Piece outside captured file budget"); }
    public byte[] read(String path) { return Objects.requireNonNull(content.get(path),"Piece outside captured payload budget").clone(); }
    long retainedBytes() { return bytes; }
    int retainedFiles() { return content.size(); }
    long copiedBytes() { return borrowed ? 0 : bytes; }
    int copiedFiles() { return borrowed ? 0 : content.size(); }
    /** Defensive copies are prepared on the worker, with cancellation between 64-KiB chunks. */
    PackResourceView view(BooleanSupplier cancelled) {
        Objects.requireNonNull(cancelled);
        return new PackResourceView() {
            private void check() { if(cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())throw new CancellationException("Piece-theme source read cancelled"); }
            public Set<String> paths(){check();return ThemeReferenceSource.this.paths();}
            public int size(String path){check();return ThemeReferenceSource.this.size(path);}
            public byte[] read(String path){
                check();var original=Objects.requireNonNull(content.get(path),"Piece outside captured payload budget");var copy=new byte[original.length];
                for(int offset=0;offset<original.length;offset+=65536){check();System.arraycopy(original,offset,copy,offset,Math.min(65536,original.length-offset));}check();return copy;
            }
        };
    }
}
