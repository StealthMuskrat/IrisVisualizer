package dev.irisvisualizer.renderer;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.*;

/** Detached captured asset bytes. This catalog grants no generation or rendering readiness. */
public final class ClientAssetSnapshot {
    public enum Kind { BLOCKSTATE, MODEL, TEXTURE, TEXTURE_METADATA }
    public record Key(Kind kind,AssetId id) implements Comparable<Key> {
        public Key { Objects.requireNonNull(kind);Objects.requireNonNull(id); }
        @Override public int compareTo(Key other) { int order=kind.compareTo(other.kind);return order==0?id.compareTo(other.id):order; }
    }
    public record Receipt(Path source,String archiveSha256,long archiveBytes,String expectedVersion,String version,
                          int archiveEntries,int ignoredEntries,long capturedAssetBytes) {
        public Receipt { Objects.requireNonNull(source);Objects.requireNonNull(archiveSha256);Objects.requireNonNull(expectedVersion);Objects.requireNonNull(version); }
    }
    public static final class Asset {
        private final byte[] bytes;private final String archivePath,sha256;
        private Asset(String archivePath,byte[] owned,String sha256) { this.archivePath=archivePath;bytes=owned;this.sha256=sha256; }
        static Asset owned(String archivePath,byte[] owned,String sha256) { return new Asset(archivePath,owned,sha256); }
        public String archivePath() { return archivePath; }
        public int bytes() { return bytes.length; }
        public String sha256() { return sha256; }
        /** Reads only immutable captured bytes; never reopens the user's client archive. */
        public InputStream openStream() {
            // Do not expose a ByteArrayInputStream whose transferTo may pass its backing array to a caller.
            return new InputStream() {
                private int at;
                @Override public int read() { return at<bytes.length?bytes[at++]&255:-1; }
                @Override public int read(byte[] target,int offset,int length) {
                    Objects.checkFromIndexSize(offset,length,target.length);if(length==0)return 0;if(at==bytes.length)return -1;
                    int count=Math.min(length,bytes.length-at);System.arraycopy(bytes,at,target,offset,count);at+=count;return count;
                }
                @Override public long skip(long requested) { int count=(int)Math.min(Math.max(0,requested),bytes.length-at);at+=count;return count; }
                @Override public int available() { return bytes.length-at; }
            };
        }
        public byte[] copyBytes() { return bytes.clone(); }
    }
    private final Receipt receipt;private final NavigableMap<Key,Asset> assets;
    ClientAssetSnapshot(Receipt receipt,Map<Key,Asset> owned) { this.receipt=receipt;assets=Collections.unmodifiableNavigableMap(new TreeMap<>(owned)); }
    public Receipt receipt() { return receipt; }
    public NavigableSet<Key> keys() { return Collections.unmodifiableNavigableSet(assets.navigableKeySet()); }
    public Optional<Asset> find(Kind kind,AssetId id) { return Optional.ofNullable(assets.get(new Key(kind,id))); }
    public long count(Kind kind) { return assets.keySet().stream().filter(key->key.kind()==kind).count(); }
}
