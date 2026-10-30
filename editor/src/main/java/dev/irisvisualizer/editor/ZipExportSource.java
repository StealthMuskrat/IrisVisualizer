package dev.irisvisualizer.editor;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Full applied snapshot. Workspace arrays are immutable and privately shared, never exposed or read from disk. */
public final class ZipExportSource {
    public static final long MAX_PATH_CHARACTERS=16_000_000;
    public record Summary(int files,int directories,long bytes,long revision,Path activeDirectory) { public int entries() { return files+directories; } }
    record Limits(int entries,long bytes,long pathCharacters) {
        Limits { if (entries<0 || entries>PackWorkspace.MAX_FILES || bytes<0 || bytes>PackWorkspace.MAX_BYTES || pathCharacters<0 || pathCharacters>MAX_PATH_CHARACTERS) throw new IllegalArgumentException("Invalid Applied snapshot bounds"); }
    }
    @FunctionalInterface public interface Progress { void update(int completedEntries,long completedBytes); }
    /** Opaque fingerprints of bytes actually captured for a later published Save As. No payload arrays escape. */
    public static final class Baseline {
        private final Object identity;
        private final Summary summary;
        private final Map<String,byte[]> hashes;
        private final java.util.Set<String> directories;
        private final Path requested,published;
        private Baseline(Object identity,Summary summary,Map<String,byte[]> hashes,java.util.Set<String> directories,Path requested,Path published) { this.identity=identity;this.summary=summary;this.hashes=hashes;this.directories=directories;this.requested=requested;this.published=published; }
        public Summary summary() { return summary; }
        boolean belongsTo(Object identity) { return this.identity==identity; }
        boolean publication(Path request,Path directory) { return requested!=null && requested.equals(request) && published.equals(directory); }
        boolean matches(java.util.Set<String> files,java.util.Set<String> directories) { return hashes.keySet().equals(files) && this.directories.equals(directories); }
        Map<String,byte[]> copyHashes() { var copy=new TreeMap<String,byte[]>();for (var entry : hashes.entrySet()) copy.put(entry.getKey(),entry.getValue().clone());return copy; }
        Baseline publishedAt(Path request,Path directory) { if (requested!=null) throw new IllegalStateException("Saved baseline is already bound to a publication");return new Baseline(identity,summary,hashes,directories,request.toAbsolutePath().normalize(),directory); }
    }
    private final Object identity=new Object();
    Object identity() { return identity; }
    Baseline prepareBaseline(BooleanSupplier cancelled) {
        var hashes=new TreeMap<String,byte[]>();check(cancelled);
        for (var entry : files.entrySet()) {
            check(cancelled);java.security.MessageDigest hash;
            try { hash=java.security.MessageDigest.getInstance("SHA-256"); } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
            byte[] data=entry.getValue();for (int offset=0;offset<data.length;) { check(cancelled);int count=Math.min(64*1024,data.length-offset);hash.update(data,offset,count);offset+=count; }
            hashes.put(entry.getKey(),hash.digest());
        }
        check(cancelled);return new Baseline(identity,summary,Collections.unmodifiableMap(hashes),java.util.Set.copyOf(directories),null,null);
    }
    private final Map<String,byte[]> files;
    private final List<String> directories;
    private final Summary summary;
    ZipExportSource(Map<String,byte[]> ownedFiles,java.util.Set<String> directories,long revision,Path activeDirectory) throws IOException { this(ownedFiles,directories,revision,activeDirectory,new Limits(PackWorkspace.MAX_FILES,PackWorkspace.MAX_BYTES,MAX_PATH_CHARACTERS)); }
    ZipExportSource(Map<String,byte[]> ownedFiles,java.util.Set<String> directories,long revision,Path activeDirectory,Limits limits) throws IOException {
        if ((long)ownedFiles.size()+directories.size()>limits.entries()) throw new IOException("Applied snapshot exceeds the entry limit");
        long bytes=0,characters=0;
        for (var entry : ownedFiles.entrySet()) { bytes+=entry.getValue().length;characters+=entry.getKey().length();if (bytes>limits.bytes() || characters>limits.pathCharacters()) throw new IOException("Applied snapshot exceeds the byte/path-character limit"); }
        for (String directory : directories) { characters+=directory.length()+1L;if (characters>limits.pathCharacters()) throw new IOException("Applied snapshot exceeds the path-character limit"); }
        // Only PackWorkspace supplies these already-owned immutable arrays. Copy the containers, not the complete pack.
        files=Collections.unmodifiableMap(new TreeMap<>(ownedFiles));this.directories=List.copyOf(new TreeSet<>(directories));summary=new Summary(files.size(),directories.size(),bytes,revision,activeDirectory);
    }
    public Summary summary() { return summary; }
    private static void check(BooleanSupplier cancelled) { if (cancelled.getAsBoolean()) throw new CancellationException("Snapshot transfer cancelled"); }
    void writeTo(ZipOutputStream zip,BooleanSupplier cancelled,Progress progress) throws IOException {
        int entries=0;long bytes=0;check(cancelled);progress.update(entries,bytes);
        for (String directory : directories) { check(cancelled);zip.putNextEntry(new ZipEntry(directory+"/"));zip.closeEntry();progress.update(++entries,bytes); }
        for (var entry : files.entrySet()) {
            check(cancelled);zip.putNextEntry(new ZipEntry(entry.getKey()));byte[] data=entry.getValue();
            for (int offset=0;offset<data.length;) { check(cancelled);int count=Math.min(64*1024,data.length-offset);zip.write(data,offset,count);offset+=count;bytes+=count;progress.update(entries,bytes); }
            zip.closeEntry();progress.update(++entries,bytes);
        }
        check(cancelled);
    }
    /** Shares the exact captured bytes with the folder writer; no live workspace or source-disk access. */
    void writeDirectory(FolderCopies.Ownership output,BooleanSupplier cancelled,Progress progress) throws IOException {
        int entries=0;long bytes=0;check(cancelled);progress.update(entries,bytes);
        for (String directory : directories) { check(cancelled);output.directory(directory);progress.update(++entries,bytes); }
        for (var entry : files.entrySet()) {
            check(cancelled);byte[] data=entry.getValue();
            try (var stream=output.open(entry.getKey())) {
                for (int offset=0;offset<data.length;) { check(cancelled);int count=Math.min(64*1024,data.length-offset);stream.write(data,offset,count);offset+=count;bytes+=count;progress.update(entries,bytes); }
            }
            progress.update(++entries,bytes);
        }
        check(cancelled);
    }

}
