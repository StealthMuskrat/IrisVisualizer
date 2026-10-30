package dev.irisvisualizer.editor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** Immutable applied JSON copies and exact target presence, without retaining a live workspace. */
final class JigsawScanSource implements PackResourceView {
    static final int MAX_FILES=4096;
    static final long MAX_BYTES=16_000_000,MAX_PATH_CHARACTERS=16_000_000;
    record Limits(int files,long bytes,long characters) {
        Limits { if (files<1 || files>MAX_FILES || bytes<0 || bytes>MAX_BYTES || characters<1 || characters>MAX_PATH_CHARACTERS) throw new IllegalArgumentException("Invalid jigsaw snapshot limits"); }
    }
    private final Set<String> paths;
    private final Map<String,Integer> sizes;
    private final Map<String,byte[]> content;
    private final long bytes;
    private JigsawScanSource(Set<String> paths,Map<String,Integer> sizes,Map<String,byte[]> content,long bytes) {
        this.paths=Collections.unmodifiableSet(paths);this.sizes=Map.copyOf(sizes);this.content=Map.copyOf(content);this.bytes=bytes;
    }
    static JigsawScanSource capture(PackResourceView current,String root) { return capture(current,root,new Limits(MAX_FILES,MAX_BYTES,MAX_PATH_CHARACTERS)); }
    static JigsawScanSource capture(PackResourceView current,String root,Limits limits) {
        Objects.requireNonNull(current);Objects.requireNonNull(limits);
        var paths=new TreeSet<String>();long characters=0;
        // Fail before payload copies if the complete exact-presence catalog cannot be represented.
        for (String path : current.paths()) {
            characters+=path.length();if (paths.size()>=PackWorkspace.MAX_FILES || characters>limits.characters()) throw new IllegalArgumentException("Jigsaw snapshot path catalog exceeds 100,000 files / "+limits.characters()+" UTF-16 characters");
            paths.add(path);
        }
        if (!JigsawDependencyReview.supports(root) || !paths.contains(root)) throw new IllegalArgumentException("Select an existing structure, jigsaw pool or jigsaw piece JSON resource");
        var order=new ArrayList<String>();order.add(root);for (String path : paths) if (!path.equals(root) && JigsawDependencyReview.supports(path)) order.add(path);
        var sizes=new HashMap<String,Integer>();var content=new HashMap<String,byte[]>();long bytes=0;
        // Root first, then lexical order. No parsing on the caller thread and no object/image payload reads.
        for (String path : order) {
            int size=current.size(path);if (size<0) throw new IllegalArgumentException("Negative resource size");sizes.put(path,size);
            if (content.size()>=limits.files() || size>ReferenceDiagnostics.MAX_JSON_BYTES || size>limits.bytes()-bytes) continue;
            byte[] raw=current.read(path);if (raw.length!=size) throw new IllegalArgumentException("Resource changed while capturing jigsaw source");
            content.put(path,raw);bytes+=size;
        }
        return new JigsawScanSource(paths,sizes,content,bytes);
    }
    public Set<String> paths() { return paths; }
    public int size(String path) { return Objects.requireNonNull(sizes.get(path),"Resource outside captured jigsaw families"); }
    public byte[] read(String path) { return Objects.requireNonNull(content.get(path),"Resource outside captured jigsaw payload budget").clone(); }
    boolean captured(String path) { return content.containsKey(path); }
    long copiedBytes() { return bytes; }
    int copiedFiles() { return content.size(); }
}
