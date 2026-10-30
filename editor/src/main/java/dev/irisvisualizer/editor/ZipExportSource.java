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
        Limits { if (entries<0 || entries>PackWorkspace.MAX_FILES || bytes<0 || bytes>PackWorkspace.MAX_BYTES || pathCharacters<0 || pathCharacters>MAX_PATH_CHARACTERS) throw new IllegalArgumentException("Invalid ZIP snapshot bounds"); }
    }
    @FunctionalInterface public interface Progress { void update(int completedEntries,long completedBytes); }
    private final Map<String,byte[]> files;
    private final List<String> directories;
    private final Summary summary;
    ZipExportSource(Map<String,byte[]> ownedFiles,java.util.Set<String> directories,long revision,Path activeDirectory) throws IOException { this(ownedFiles,directories,revision,activeDirectory,new Limits(PackWorkspace.MAX_FILES,PackWorkspace.MAX_BYTES,MAX_PATH_CHARACTERS)); }
    ZipExportSource(Map<String,byte[]> ownedFiles,java.util.Set<String> directories,long revision,Path activeDirectory,Limits limits) throws IOException {
        if ((long)ownedFiles.size()+directories.size()>limits.entries()) throw new IOException("ZIP snapshot exceeds the entry limit");
        long bytes=0,characters=0;
        for (var entry : ownedFiles.entrySet()) { bytes+=entry.getValue().length;characters+=entry.getKey().length();if (bytes>limits.bytes() || characters>limits.pathCharacters()) throw new IOException("ZIP snapshot exceeds the byte/path-character limit"); }
        for (String directory : directories) { characters+=directory.length()+1L;if (characters>limits.pathCharacters()) throw new IOException("ZIP snapshot exceeds the path-character limit"); }
        // Only PackWorkspace supplies these already-owned immutable arrays. Copy the containers, not the complete pack.
        files=Collections.unmodifiableMap(new TreeMap<>(ownedFiles));this.directories=List.copyOf(new TreeSet<>(directories));summary=new Summary(files.size(),directories.size(),bytes,revision,activeDirectory);
    }
    public Summary summary() { return summary; }
    private static void check(BooleanSupplier cancelled) { if (cancelled.getAsBoolean()) throw new CancellationException("ZIP export cancelled"); }
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
