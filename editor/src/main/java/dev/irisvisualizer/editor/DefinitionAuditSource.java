package dev.irisvisualizer.editor;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.function.Function;
import java.util.function.ToIntFunction;
import java.util.function.BooleanSupplier;
import java.util.concurrent.CancellationException;

/** Exact target presence plus bounded immutable definition bytes; never retains a live workspace. */
final class DefinitionAuditSource implements PackResourceView {
    static final int MAX_DEFINITIONS=512;
    static final long MAX_BYTES=8_000_000,MAX_PATH_CHARACTERS=16_000_000;
    enum Family {
        BLOCK_ALIASES("block aliases",BlockDefinitions::supports), EXPRESSIONS("expression sources",ExpressionDefinitions::supports), DIMENSION_FALLBACKS("dimension fallbacks",DimensionFields::dimension);
        private final String label;
        private final Predicate<String> supports;
        Family(String label,Predicate<String> supports) { this.label=label;this.supports=supports; }
        String label() { return label; }
        boolean supports(String path) { return supports.test(path); }
    }
    private final Set<String> paths;
    private final Map<String,Integer> sizes;
    private final Map<String,byte[]> content;
    private final long bytes;
    private final boolean borrowed;
    private DefinitionAuditSource(Set<String> paths,Map<String,Integer> sizes,Map<String,byte[]> content,long bytes,boolean borrowed) {
        this.paths=Collections.unmodifiableSet(paths);this.sizes=Map.copyOf(sizes);this.content=Map.copyOf(content);this.bytes=bytes;this.borrowed=borrowed;
    }
    static DefinitionAuditSource capture(PackResourceView current,Family family) { return capture(current,family,MAX_PATH_CHARACTERS); }
    static DefinitionAuditSource capture(PackResourceView current,Family family,long characterLimit) {
        Objects.requireNonNull(current);return capture(current.paths(),current::size,current::read,family,characterLimit,false);
    }
    /** Only PackWorkspace may hand over these immutable-by-ownership arrays. No map/callback is retained. */
    static DefinitionAuditSource pin(Map<String,byte[]> owned,Family family) {
        Objects.requireNonNull(owned);return capture(owned.keySet(),path->Objects.requireNonNull(owned.get(path)).length,owned::get,family,MAX_PATH_CHARACTERS,true);
    }
    private static DefinitionAuditSource capture(Set<String> names,ToIntFunction<String> sizesOf,Function<String,byte[]> payload,Family family,long characterLimit,boolean borrowed) {
        Objects.requireNonNull(family);
        if (characterLimit<1 || characterLimit>MAX_PATH_CHARACTERS) throw new IllegalArgumentException("Invalid definition snapshot path limit");
        var paths=new TreeSet<String>();long characters=0;
        for (String path : names) {
            characters+=path.length();if (paths.size()>=PackWorkspace.MAX_FILES || characters>characterLimit) throw new IllegalArgumentException("Definition snapshot path catalog exceeds 100,000 files / "+characterLimit+" UTF-16 characters");
            paths.add(path);
        }
        var sizes=new HashMap<String,Integer>();var content=new HashMap<String,byte[]>();int considered=0;long bytes=0;
        // Match the existing audit's sorted candidate order, oversize skips and first-byte-overflow stop exactly.
        for (String path : paths) {
            if (!family.supports(path)) continue;
            if (considered++>=MAX_DEFINITIONS) break;
            int size=sizesOf.applyAsInt(path);if (size<0) throw new IllegalArgumentException("Negative definition size");sizes.put(path,size);
            if (size>ReferenceDiagnostics.MAX_JSON_BYTES) continue;
            if (size>MAX_BYTES-bytes) break;
            byte[] raw=Objects.requireNonNull(payload.apply(path));if (raw.length!=size) throw new IllegalArgumentException("Resource changed while capturing definition audit source");
            content.put(path,raw);bytes+=size;
        }
        return new DefinitionAuditSource(paths,sizes,content,bytes,borrowed);
    }
    public Set<String> paths() { return paths; }
    public int size(String path) { return Objects.requireNonNull(sizes.get(path),"Resource outside captured definition candidate budget"); }
    public byte[] read(String path) { return Objects.requireNonNull(content.get(path),"Resource outside captured definition payload budget").clone(); }
    long retainedBytes() { return bytes; }
    int retainedFiles() { return content.size(); }
    long copiedBytes() { return borrowed ? 0 : bytes; }
    int copiedFiles() { return borrowed ? 0 : content.size(); }
    /** Cancellation also covers the defensive per-file copy, before existing audit parsing starts. */
    PackResourceView view(BooleanSupplier cancelled) {
        Objects.requireNonNull(cancelled);
        return new PackResourceView() {
            private void check() { if(cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())throw new CancellationException("Definition audit source read cancelled"); }
            public Set<String> paths(){check();return DefinitionAuditSource.this.paths();}
            public int size(String path){check();return DefinitionAuditSource.this.size(path);}
            public byte[] read(String path){
                check();var original=Objects.requireNonNull(content.get(path),"Resource outside captured definition payload budget");var copy=new byte[original.length];
                for(int offset=0;offset<original.length;offset+=65536){check();System.arraycopy(original,offset,copy,offset,Math.min(65536,original.length-offset));}check();return copy;
            }
        };
    }
}
