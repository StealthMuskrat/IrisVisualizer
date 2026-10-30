package dev.irisvisualizer.editor;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Per-resource staging only; existing resource files are never opened for writing or truncated. */
public final class FileSaves {
    public static final String TEMPORARY_PREFIX=".iris-save-";
    public static final class Receipt {
        private final Path file;private final int bytes;private final byte[] hash;
        private Receipt(Path file,int bytes,byte[] hash) { this.file=file;this.bytes=bytes;this.hash=hash.clone(); }
        public Path file() { return file; }
        public int bytes() { return bytes; }
        public String sha256() { return HexFormat.of().formatHex(hash); }
        byte[] savedHash() { return hash.clone(); }
    }
    @FunctionalInterface interface Stager { void write(OutputStream output,byte[] source) throws IOException; }
    @FunctionalInterface interface Checkpoint { void staged(Path temporary,Path destination) throws IOException; }
    @FunctionalInterface interface Publisher { void publish(Path temporary,Path destination,boolean replacing) throws IOException; }
    @FunctionalInterface interface Writer { Receipt write(Path root,String key,byte[] source,byte[] savedHash) throws IOException; }
    @FunctionalInterface interface MetadataWriter { void apply(Path temporary,SaveMetadata.State metadata) throws IOException; }
    private record Identity(Object key,FileTime created,boolean directory) {
        static Identity of(BasicFileAttributes attributes) { return new Identity(attributes.fileKey(),attributes.creationTime(),attributes.isDirectory()); }
        boolean matches(BasicFileAttributes attributes) { return directory==attributes.isDirectory() && (directory || attributes.isRegularFile()) && !attributes.isSymbolicLink() && Objects.equals(key,attributes.fileKey()) && created.equals(attributes.creationTime()); }
    }
    private record Stamp(Identity identity,long bytes,FileTime modified,String sha256) {}
    private record Target(Stamp bytes,SaveMetadata.State metadata) {}
    private static MessageDigest digest() { try { return MessageDigest.getInstance("SHA-256"); } catch(NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); } }
    private static BasicFileAttributes attributes(Path path) throws IOException { return Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS); }
    private static boolean absent(Path path) throws IOException { try { attributes(path);return false; } catch(NoSuchFileException missing) { return true; } }
    private static Stamp fingerprint(Path file) throws IOException {
        PackWorkspace.rejectLinks(file);var before=attributes(file);
        if(!before.isRegularFile() || before.size()>PackWorkspace.MAX_BYTES) throw new IOException("Save destination must be a regular file within the 512 MiB workspace limit: "+file);
        var identity=Identity.of(before);var digest=digest();long bytes=0;byte[] buffer=new byte[64*1024];
        try(var input=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)) {
            while(true) {
                int count=input.read(buffer,0,(int)Math.min(buffer.length,before.size()-bytes+1));if(count<0)break;if(count==0)continue;
                bytes+=count;if(bytes>before.size())throw new IOException("Save destination grew while being checked: "+file);digest.update(buffer,0,count);
            }
        }
        PackWorkspace.rejectLinks(file);var after=attributes(file);
        if(!identity.matches(after) || before.size()!=after.size() || bytes!=before.size() || !before.lastModifiedTime().equals(after.lastModifiedTime())) throw new IOException("Save destination changed while being checked: "+file);
        return new Stamp(identity,bytes,after.lastModifiedTime(),HexFormat.of().formatHex(digest.digest()));
    }
    /** Shared all-resource preflight and per-resource recheck. Null baseline requires a new destination. */
    static void preflight(Path destination,byte[] savedHash) throws IOException { target(destination,savedHash); }
    private static Target target(Path destination,byte[] savedHash) throws IOException {
        PackWorkspace.rejectLinks(destination);
        if(savedHash==null) { if(!absent(destination))throw new FileAlreadyExistsException(destination.toString(),null,"Save refuses to overwrite a new-resource destination");return null; }
        if(savedHash.length!=32)throw new IllegalArgumentException("Expected a saved SHA-256 baseline");
        Stamp stamp=fingerprint(destination);
        if(!stamp.sha256().equals(HexFormat.of().formatHex(savedHash)))throw new IOException("Resource changed outside the editor: "+destination+". Reopen to review disk changes or use Save As/Save Copy.");
        if(!Files.isWritable(destination))throw new IOException("Save destination is not writable: "+destination);
        var metadata=SaveMetadata.read(destination);
        if(metadata.dos()!=null && metadata.dos().readOnly())throw new IOException("Save destination is read-only: "+destination);
        var after=attributes(destination);
        if(!stamp.identity().matches(after) || stamp.bytes()!=after.size() || !stamp.modified().equals(after.lastModifiedTime()))throw new IOException("Save destination changed during permission checks: "+destination);
        if(!Files.isWritable(destination))throw new IOException("Save destination became non-writable during permission checks: "+destination);
        return new Target(stamp,metadata);
    }
    static Receipt write(Path root,String key,byte[] source,byte[] savedHash) throws IOException {
        return write(root,key,source,savedHash,(output,bytes)->{ for(int offset=0;offset<bytes.length;offset+=64*1024)output.write(bytes,offset,Math.min(64*1024,bytes.length-offset)); },(temporary,destination)->{},(temporary,destination,replacing)->{
            if(replacing)Files.move(temporary,destination,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            else Files.move(temporary,destination);
        });
    }
    static Receipt write(Path root,String key,byte[] source,byte[] savedHash,Stager stager,Checkpoint checkpoint,Publisher publisher) throws IOException {
        return write(root,key,source,savedHash,stager,checkpoint,publisher,SaveMetadata::apply);
    }
    static Receipt write(Path root,String key,byte[] source,byte[] savedHash,Stager stager,Checkpoint checkpoint,Publisher publisher,MetadataWriter metadataWriter) throws IOException {
        Objects.requireNonNull(source);Objects.requireNonNull(stager);Objects.requireNonNull(checkpoint);Objects.requireNonNull(publisher);Objects.requireNonNull(metadataWriter);
        if(source.length>PackWorkspace.MAX_BYTES)throw new IOException("Resource exceeds the workspace byte limit");
        Path base=Objects.requireNonNull(root).toAbsolutePath().normalize();PackWorkspace.rejectLinks(base);base=base.toRealPath();
        Path destination=PackWorkspace.checkedTarget(base,PackWorkspace.safe(key));Path parent=destination.getParent();var parentAttributes=attributes(parent);
        if(!parentAttributes.isDirectory())throw new IOException("Save parent is not a directory: "+parent);var parentIdentity=Identity.of(parentAttributes);
        byte[] expected=savedHash==null ? null : savedHash.clone();Target baseline=target(destination,expected);byte[] sourceHash=digest().digest(source);
        Path temporary=null;Identity owned=null;IOException failure=null;
        try {
            temporary=Files.createTempFile(parent,TEMPORARY_PREFIX,".part");owned=Identity.of(attributes(temporary));
            // This newly created staging file holds only the new bytes, never an old-file backup.
            try(var output=Files.newOutputStream(temporary,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)) { stager.write(output,source); }
            checkpoint.staged(temporary,destination);
            Stamp staged=fingerprint(temporary);
            if(!owned.equals(staged.identity()) || staged.bytes()!=source.length || !staged.sha256().equals(HexFormat.of().formatHex(sourceHash)))throw new IOException("Staged resource differs from the applied bytes: "+key);
            if(baseline!=null) {
                metadataWriter.apply(temporary,baseline.metadata());
                if(!SaveMetadata.read(temporary).equals(baseline.metadata().forEditedFile()))throw new IOException("Staged permissions/attributes were not preserved: "+key);
                staged=fingerprint(temporary);
                if(!owned.equals(staged.identity()) || staged.bytes()!=source.length || !staged.sha256().equals(HexFormat.of().formatHex(sourceHash)))throw new IOException("Staged resource changed while preserving permissions: "+key);
            }
            PackWorkspace.rejectLinks(base);PackWorkspace.checkedTarget(base,key);
            if(!parentIdentity.matches(attributes(parent)))throw new IOException("Save parent changed during staging: "+key);
            Target current=target(destination,expected);
            if(!Objects.equals(baseline,current))throw new IOException("Save destination changed after staging began: "+key);
            if(!owned.matches(attributes(temporary)) || !staged.equals(fingerprint(temporary)))throw new IOException("Staged resource changed before publication: "+key);
            if(baseline!=null && !SaveMetadata.read(temporary).equals(baseline.metadata().forEditedFile()))throw new IOException("Staged permissions/attributes changed before publication: "+key);
            publisher.publish(temporary,destination,expected!=null);temporary=null;
            return new Receipt(destination,source.length,sourceHash);
        } catch(IOException problem) {
            failure=problem instanceof AtomicMoveNotSupportedException ? new IOException("Atomic resource replacement is unavailable: "+destination+". Use Save As/Save Copy to a fresh folder.",problem) : problem;
            throw failure;
        }
        finally {
            if(temporary!=null) {
                try {
                    if(!absent(temporary)) {
                        PackWorkspace.rejectLinks(temporary);
                        if(owned==null || !owned.matches(attributes(temporary)))throw new IOException("Temporary save entry was replaced; preserving it: "+temporary);
                        Files.delete(temporary);
                    }
                } catch(IOException | SecurityException cleanup) {
                    String detail="Temporary save cleanup could not complete: "+temporary+" ("+Objects.toString(cleanup.getMessage(),cleanup.getClass().getSimpleName())+")";
                    if(failure!=null)throw new IOException(failure.getMessage()+". "+detail,failure);
                    throw new IOException(detail,cleanup);
                }
            }
        }
    }
    private FileSaves() {}
}
