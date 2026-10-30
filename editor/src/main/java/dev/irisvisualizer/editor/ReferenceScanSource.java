package dev.irisvisualizer.editor;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** Immutable bounded JSON copies plus exact target-path presence; never retains the live workspace. */
final class ReferenceScanSource implements PackResourceView {
    static final long MAX_PATH_CHARACTERS=16_000_000;
    record Limits(int files,long bytes,long characters) {
        Limits { if (files<1 || files>PackReferenceAudit.MAX_FILES || bytes<0 || bytes>PackReferenceAudit.MAX_BYTES || characters<1 || characters>MAX_PATH_CHARACTERS) throw new IllegalArgumentException("Invalid reference snapshot limits"); }
    }
    private final Set<String> paths;
    private final Map<String,Integer> sizes;
    private final Map<String,byte[]> content;
    private final long bytes;
    private ReferenceScanSource(Set<String> paths,Map<String,Integer> sizes,Map<String,byte[]> content,long bytes) {
        this.paths=Collections.unmodifiableSet(paths);this.sizes=Map.copyOf(sizes);this.content=Map.copyOf(content);this.bytes=bytes;
    }
    static ReferenceScanSource capture(PackResourceView current) { return capture(current,new Limits(PackReferenceAudit.MAX_FILES,PackReferenceAudit.MAX_BYTES,MAX_PATH_CHARACTERS)); }
    static ReferenceScanSource capture(PackResourceView current,Limits limits) {
        Objects.requireNonNull(current);Objects.requireNonNull(limits);
        var paths=new TreeSet<String>();long characters=0;
        // Check the complete presence catalog before copying any source bytes. Binary assets are names only.
        for (String path : current.paths()) {
            characters+=path.length();if (paths.size()>=PackWorkspace.MAX_FILES || characters>limits.characters()) throw new IllegalArgumentException("Reference snapshot path catalog exceeds 100,000 files / "+limits.characters()+" UTF-16 characters");
            paths.add(path);
        }
        var sizes=new HashMap<String,Integer>();var content=new HashMap<String,byte[]>();int considered=0;long bytes=0;
        for (String path : paths) {
            if (!ReferenceDiagnostics.supports(path) || considered++>=limits.files()) continue;
            int size=current.size(path);if (size<0) throw new IllegalArgumentException("Negative resource size");sizes.put(path,size);
            if (size>ReferenceDiagnostics.MAX_JSON_BYTES || size>limits.bytes()-bytes) continue;
            byte[] raw=current.read(path);if (raw.length!=size) throw new IllegalArgumentException("Resource changed while capturing reference source");
            content.put(path,raw);bytes+=size;
        }
        return new ReferenceScanSource(paths,sizes,content,bytes);
    }
    public Set<String> paths() { return paths; }
    public int size(String path) { return Objects.requireNonNull(sizes.get(path),"Resource outside captured reference file budget"); }
    public byte[] read(String path) { return Objects.requireNonNull(content.get(path),"Resource outside captured reference byte budget").clone(); }
    long copiedBytes() { return bytes; }
    int copiedFiles() { return content.size(); }
}
