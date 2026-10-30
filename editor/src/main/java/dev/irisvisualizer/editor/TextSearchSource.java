package dev.irisvisualizer.editor;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;

/** Private applied-source copies for one search query. No live workspace or binary assets are retained. */
final class TextSearchSource implements PackResourceView {
    static final long MAX_PATH_CHARACTERS=16_000_000;
    private final Set<String> paths;
    private final Map<String,Integer> sizes;
    private final Map<String,byte[]> content;
    private final long bytes;
    private TextSearchSource(Set<String> paths,Map<String,Integer> sizes,Map<String,byte[]> content,long bytes) {
        this.paths=Collections.unmodifiableSet(paths);this.sizes=Map.copyOf(sizes);this.content=Map.copyOf(content);this.bytes=bytes;
    }
    static TextSearchSource capture(PackWorkspace workspace,WorkspaceTextSearch.Query query) {
        return capture(workspace,query,workspace::changed,new WorkspaceTextSearch.Limits(WorkspaceTextSearch.MAX_FILE_BYTES,WorkspaceTextSearch.MAX_TOTAL_BYTES,WorkspaceTextSearch.MAX_FILES,WorkspaceTextSearch.MAX_RESULTS),MAX_PATH_CHARACTERS);
    }
    static TextSearchSource capture(PackResourceView current,WorkspaceTextSearch.Query query,Predicate<String> changed,WorkspaceTextSearch.Limits limits,long maximumCharacters) {
        Objects.requireNonNull(current);Objects.requireNonNull(query);Objects.requireNonNull(changed);Objects.requireNonNull(limits);
        if (maximumCharacters<1 || maximumCharacters>MAX_PATH_CHARACTERS) throw new IllegalArgumentException("Invalid text search path budget");
        var paths=new TreeSet<String>();long characters=0;
        for (String path : current.paths()) {
            if (!WorkspaceTextSearch.eligible(path,query,changed)) continue;
            characters+=path.length();if (paths.size()>=PackWorkspace.MAX_FILES || characters>maximumCharacters) throw new IllegalArgumentException("Text search snapshot exceeds 100,000 eligible paths / "+maximumCharacters+" UTF-16 path characters; narrow the family or changed-files filter");
            paths.add(path);
        }
        var sizes=new HashMap<String,Integer>();var content=new HashMap<String,byte[]>();long bytes=0;int considered=0;
        for (String path : paths) {
            if (considered++>=limits.files()) continue;
            int size=current.size(path);if (size<0) throw new IllegalArgumentException("Negative source size");sizes.put(path,size);
            if (size>limits.fileBytes() || size>limits.totalBytes()-bytes) continue;
            byte[] raw=current.read(path);if (raw.length!=size) throw new IllegalArgumentException("Source changed during text search capture");
            content.put(path,raw);bytes+=size;
        }
        return new TextSearchSource(paths,sizes,content,bytes);
    }
    public Set<String> paths() { return paths; }
    public int size(String path) { return Objects.requireNonNull(sizes.get(path),"Source outside captured text search file budget"); }
    public byte[] read(String path) { return Objects.requireNonNull(content.get(path),"Source outside captured text search byte budget").clone(); }
    long copiedBytes() { return bytes; }
    int copiedFiles() { return content.size(); }
}
