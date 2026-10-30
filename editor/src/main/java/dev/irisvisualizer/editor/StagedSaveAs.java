package dev.irisvisualizer.editor;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.file.Path;
import java.util.Objects;

/** UI-owned authority to adopt only the exact captured workspace and successfully published snapshot. */
public final class StagedSaveAs {
    public static final class Target {
        private final WeakReference<PackWorkspace> owner;
        private final Object sourceIdentity;
        private final long revision,saveRevision;
        private final Path originalDirectory,destination;
        private final ZipExportSource.Summary source;
        private Target(PackWorkspace current,ZipExportSource source,Path destination) { owner=new WeakReference<>(current);sourceIdentity=source.identity();revision=current.revision();saveRevision=current.saveRevision();originalDirectory=current.directory();this.destination=destination;this.source=source.summary(); }
        public long revision() { return revision; }
        public Path destination() { return destination; }
        public ZipExportSource.Summary source() { return source; }
    }
    static final class Capture {
        final Target target;final ZipExportSource source;
        private Capture(Target target,ZipExportSource source) { this.target=target;this.source=source; }
    }
    static Capture capture(PackWorkspace current,Path destination) throws IOException {
        Objects.requireNonNull(current);Objects.requireNonNull(destination);var source=current.captureZipExport();return new Capture(new Target(current,source,destination.toAbsolutePath().normalize()),source);
    }
    public static boolean current(PackWorkspace current,Target target) { return current!=null && target!=null && target.owner.get()==current && current.revision()==target.revision && current.saveRevision()==target.saveRevision && Objects.equals(current.directory(),target.originalDirectory); }
    static void validateReceipt(Target target,FolderCopies.Receipt receipt) throws IOException {
        if (target==null || receipt==null || receipt.directory()==null || receipt.source()==null || receipt.baseline()==null || !receipt.baseline().belongsTo(target.sourceIdentity) || !receipt.baseline().publication(target.destination,receipt.directory()) || !receipt.source().equals(target.source) || !receipt.baseline().summary().equals(target.source)) throw new IOException("Save As receipt does not belong to the captured source and approved destination");
    }
    public static void adopt(PackWorkspace current,Target target,FolderCopies.Receipt receipt) throws IOException {
        if (!current(current,target)) throw new IOException("Workspace or save baseline changed; keep the published snapshot as a separate copy");validateReceipt(target,receipt);current.adoptCopiedBaseline(receipt.directory(),receipt.baseline());
    }
}
