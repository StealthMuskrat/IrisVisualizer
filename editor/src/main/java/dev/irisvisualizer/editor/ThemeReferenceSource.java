package dev.irisvisualizer.editor;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

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
    private ThemeReferenceSource(Set<String> paths,Map<String,Integer> sizes,Map<String,byte[]> content,long bytes) {
        this.paths=Collections.unmodifiableSet(paths);this.sizes=Map.copyOf(sizes);this.content=Map.copyOf(content);this.bytes=bytes;
    }
    static ThemeReferenceSource capture(PackResourceView current) { return capture(current,new Limits(StructureThemeReferences.MAX_FILES,StructureThemeReferences.MAX_BYTES,MAX_PATH_CHARACTERS)); }
    static ThemeReferenceSource capture(PackResourceView current,Limits limits) {
        Objects.requireNonNull(current);Objects.requireNonNull(limits);var paths=new TreeSet<String>();long characters=0;
        for (String path : current.paths()) {
            if (!StructureThemeReferences.supports(path)) continue;
            characters+=path.length();if (paths.size()>=PackWorkspace.MAX_FILES || characters>limits.characters()) throw new IllegalArgumentException("Piece-theme snapshot exceeds 100,000 eligible paths / "+limits.characters()+" UTF-16 characters");
            paths.add(path);
        }
        var sizes=new HashMap<String,Integer>();var content=new HashMap<String,byte[]>();int considered=0;long bytes=0;
        for (String path : paths) {
            if (considered++>=limits.files()) break;
            int size=current.size(path);if (size<0) throw new IllegalArgumentException("Negative piece size");sizes.put(path,size);
            if (size>StructureThemeReferences.MAX_FILE_BYTES || size>limits.bytes()-bytes) continue;
            byte[] raw=current.read(path);if (raw.length!=size) throw new IllegalArgumentException("Resource changed while capturing piece-theme source");content.put(path,raw);bytes+=size;
        }
        return new ThemeReferenceSource(paths,sizes,content,bytes);
    }
    public Set<String> paths() { return paths; }
    public int size(String path) { return Objects.requireNonNull(sizes.get(path),"Piece outside captured file budget"); }
    public byte[] read(String path) { return Objects.requireNonNull(content.get(path),"Piece outside captured payload budget").clone(); }
    long copiedBytes() { return bytes; }
    int copiedFiles() { return content.size(); }
}
