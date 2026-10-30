package dev.irisvisualizer.editor;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Read-only background preparation. A prepared workspace is handed to the UI once, without a source reread. */
public final class PackLoads {
    public enum Kind { DIRECTORY, ZIP }
    public record Progress(int files,int directories,long bytes,int nodes,int pathCharacters) {}
    public record Receipt(Kind kind,Path source,Path activeDirectory,int files,int directories,long bytes) {}
    static final class Prepared {
        final PackWorkspace workspace;final Receipt receipt;
        Prepared(PackWorkspace workspace,Receipt receipt) { this.workspace=Objects.requireNonNull(workspace);this.receipt=Objects.requireNonNull(receipt); }
    }
    @FunctionalInterface interface ProgressSink { void update(Progress progress); }
    static final class Monitor {
        private final BooleanSupplier cancelled;private final ProgressSink progress;
        Monitor(BooleanSupplier cancelled,ProgressSink progress) { this.cancelled=Objects.requireNonNull(cancelled);this.progress=Objects.requireNonNull(progress); }
        void check() { if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new CancellationException("Pack loading cancelled"); }
        void update(int files,int directories,long bytes,int nodes,int characters) { check();progress.update(new Progress(files,directories,bytes,nodes,characters));check(); }
    }
    static Monitor monitor() { return new Monitor(()->false,p->{}); }
    static Prepared prepare(Path source,Kind kind,BooleanSupplier cancelled,ProgressSink progress) throws IOException {
        return prepare(source,kind,cancelled,progress,new PackWorkspace.LoadLimits(PackWorkspace.MAX_BYTES,PackWorkspace.MAX_FILES,PackWorkspace.MAX_PATH_SEGMENTS));
    }
    static Prepared prepare(Path source,Kind kind,BooleanSupplier cancelled,ProgressSink progress,PackWorkspace.LoadLimits limits) throws IOException {
        Objects.requireNonNull(kind);Path path=Objects.requireNonNull(source).toAbsolutePath().normalize();var monitor=new Monitor(cancelled,progress);monitor.check();
        PackWorkspace workspace=kind==Kind.DIRECTORY ? PackWorkspace.open(path,limits,monitor) : PackWorkspace.importZip(path,limits,monitor);
        monitor.check();long bytes=0;for (String key : workspace.paths()) { monitor.check();bytes+=workspace.size(key); }
        return new Prepared(workspace,new Receipt(kind,path,workspace.directory(),workspace.paths().size(),workspace.directories().size(),bytes));
    }
    static BasicFileAttributes attributes(Path path) throws IOException { return Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS); }
    static boolean same(BasicFileAttributes before,BasicFileAttributes after) {
        // Directory size is provider-specific; compare file lengths only for regular files.
        return before.isRegularFile()==after.isRegularFile() && before.isDirectory()==after.isDirectory()
                && (!before.isRegularFile() || before.size()==after.size())
                && before.lastModifiedTime().equals(after.lastModifiedTime()) && Objects.equals(before.fileKey(),after.fileKey());
    }
    static void unchanged(Path path,BasicFileAttributes before) throws IOException {
        PackWorkspace.rejectLinks(path);var after=attributes(path);
        if (!same(before,after)) throw new IOException("Pack source changed during loading: "+path);
    }
    private PackLoads() {}
}
