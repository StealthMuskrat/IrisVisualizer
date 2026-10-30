package dev.irisvisualizer.editor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipOutputStream;

/** Worker-only staging, conflict preflight and publication. Existing destinations are never opened for writing. */
public final class ZipExports {
    public static final long MAX_EXISTING_BYTES=1024L*1024*1024;
    public record Receipt(Path archive,long bytes,String sha256,ZipExportSource.Summary source) {}
    public static final class Control {
        private static final int RUNNING=0,CANCELLED=1,PUBLISHING=2,DONE=3;
        private final AtomicInteger state=new AtomicInteger(RUNNING);
        /** A successful cancellation prevents publication. Once publication begins it cannot be cancelled. */
        public boolean cancel() { return state.compareAndSet(RUNNING,CANCELLED) || state.get()==CANCELLED; }
        public boolean cancelled() { return state.get()==CANCELLED; }
        public boolean publishing() { return state.get()==PUBLISHING; }
        void check() { if (cancelled()) throw new CancellationException("ZIP export cancelled before publication"); }
        void beginPublication() { if (!state.compareAndSet(RUNNING,PUBLISHING)) throw new CancellationException("ZIP publication was cancelled"); }
        void finished() { state.compareAndSet(PUBLISHING,DONE); }
    }
    @FunctionalInterface interface Publisher { void publish(Path temporary,Path destination,boolean replace) throws IOException; }
    private record Stamp(Object key,long size,FileTime modified,String sha256) {}
    private static MessageDigest digest() { try { return MessageDigest.getInstance("SHA-256"); } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); } }
    private static void rejectLinks(Path path) throws IOException { for (Path part=path;part!=null;part=part.getParent()) if (Files.isSymbolicLink(part)) throw new IOException("ZIP destination contains a symbolic link: "+part); }
    private static Path destination(ZipExportSource source,Path archive) throws IOException {
        if (archive==null) throw new IOException("Choose a destination ZIP file");Path result=archive.toAbsolutePath().normalize();rejectLinks(result);
        if (result.getFileName()==null || result.getParent()==null) throw new IOException("Choose a destination ZIP file");
        Path parent=result.getParent().toRealPath();if (!Files.isDirectory(parent,LinkOption.NOFOLLOW_LINKS)) throw new IOException("ZIP parent must be an existing directory");
        result=parent.resolve(result.getFileName());Path active=source.summary().activeDirectory();
        if (active!=null && result.startsWith(active)) throw new IOException("Export ZIP outside the captured pack directory");return result;
    }
    private static Stamp stamp(Path path,Control control) throws IOException {
        control.check();rejectLinks(path);var before=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if (!before.isRegularFile() || before.size()>MAX_EXISTING_BYTES) throw new IOException("Replacement ZIP must be a regular file no larger than 1 GiB");
        MessageDigest digest=digest();long bytes=0;byte[] buffer=new byte[64*1024];
        try (var input=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)) {
            for (int count;(count=input.read(buffer))!=-1;) { control.check();bytes+=count;if (bytes>before.size()) throw new IOException("ZIP destination changed while being checked");digest.update(buffer,0,count); }
        }
        control.check();var after=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if (!after.isRegularFile() || bytes!=before.size() || before.size()!=after.size() || !Objects.equals(before.fileKey(),after.fileKey()) || !before.lastModifiedTime().equals(after.lastModifiedTime())) throw new IOException("ZIP destination changed while being checked");
        return new Stamp(after.fileKey(),bytes,after.lastModifiedTime(),HexFormat.of().formatHex(digest.digest()));
    }
    public static Receipt export(ZipExportSource source,Path archive,boolean replace,Control control,ZipExportSource.Progress progress) throws IOException {
        return export(source,archive,replace,control,progress,(temporary,destination,replacing) -> {
            if (replacing) Files.move(temporary,destination,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            else Files.move(temporary,destination); // CREATE_NEW semantics: an occupied destination is never truncated.
        });
    }
    static Receipt export(ZipExportSource source,Path archive,boolean replace,Control control,ZipExportSource.Progress progress,Publisher publisher) throws IOException {
        Objects.requireNonNull(source);Objects.requireNonNull(control);Objects.requireNonNull(progress);Objects.requireNonNull(publisher);control.check();
        Path destination=destination(source,archive);Stamp expected=null;
        if (replace) expected=stamp(destination,control);
        else if (Files.exists(destination,LinkOption.NOFOLLOW_LINKS)) throw new IOException("ZIP destination already exists; explicit replacement approval is required");
        Path temporary=null;
        try {
            control.check();temporary=Files.createTempFile(destination.getParent(),".iris-export-",".part");MessageDigest digest=digest();
            try (var output=new DigestOutputStream(Files.newOutputStream(temporary,StandardOpenOption.WRITE,StandardOpenOption.TRUNCATE_EXISTING,LinkOption.NOFOLLOW_LINKS),digest);var zip=new ZipOutputStream(output)) { source.writeTo(zip,control::cancelled,progress); }
            control.check();long bytes=Files.size(temporary);String hash=HexFormat.of().formatHex(digest.digest());
            if (!destination(source,destination).equals(destination)) throw new IOException("ZIP destination parent changed");
            if (replace && !expected.equals(stamp(destination,control))) throw new IOException("ZIP destination changed after export started; original file was preserved");
            if (!replace && Files.exists(destination,LinkOption.NOFOLLOW_LINKS)) throw new IOException("ZIP destination appeared after export started; existing file was preserved");
            control.beginPublication();publisher.publish(temporary,destination,replace);temporary=null;control.finished();return new Receipt(destination,bytes,hash,source.summary());
        } finally { try { if (temporary!=null) Files.deleteIfExists(temporary); } finally { control.finished(); } }
    }
}
