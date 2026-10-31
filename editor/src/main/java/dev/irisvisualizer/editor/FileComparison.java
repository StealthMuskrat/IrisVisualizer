package dev.irisvisualizer.editor;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Point-in-time read-only disk/applied comparison. No receipt from this class authorizes Save. */
public final class FileComparison {
    public static final long MAX_DISK_BYTES=16L*1024*1024;
    public enum Disk { NO_FOLDER, ABSENT, FILE, TOO_LARGE }
    public enum Baseline { UNBOUND, NEW_AVAILABLE, NEW_OCCUPIED, MATCH, CHANGED, MISSING, NOT_CHECKED }
    @FunctionalInterface interface Progress {void update(long applied,long disk);}
    @FunctionalInterface interface Checkpoint {void read(Path file)throws IOException;}
    static final class Source {
        final Path directory;final String path;private final byte[] applied,savedHash;private final boolean created;
        Source(Path directory,String path,byte[] applied,byte[] savedHash,boolean created)throws IOException{
            this.directory=directory;this.path=PackWorkspace.safe(path);this.applied=Objects.requireNonNull(applied);this.created=created;
            if(applied.length>PackWorkspace.MAX_BYTES || !created && (savedHash==null || savedHash.length!=32))throw new IOException("Invalid applied resource or saved baseline");this.savedHash=created ? null : savedHash.clone();
        }
    }
    public record Report(Path directory,String path,Disk disk,Baseline baseline,int appliedBytes,long diskBytes,String appliedSha256,String diskSha256,String expectedSha256,AppliedEditReview.Text applied,AppliedEditReview.Text before,AppliedEditDiff.Diff diff,String detail) {}
    private record Identity(Object key,java.nio.file.attribute.FileTime created,boolean directory) {
        static Identity of(BasicFileAttributes a){return new Identity(a.fileKey(),a.creationTime(),a.isDirectory());}
        boolean matches(BasicFileAttributes a){return !a.isSymbolicLink() && directory==a.isDirectory() && (directory || a.isRegularFile()) && Objects.equals(key,a.fileKey()) && created.equals(a.creationTime());}
    }
    static Report compare(Source source,BooleanSupplier cancelled,Progress progress)throws IOException{return compare(source,cancelled,progress,MAX_DISK_BYTES,file->{});}
    static Report compare(Source source,BooleanSupplier cancelled,Progress progress,long maximum,Checkpoint checkpoint)throws IOException{
        Objects.requireNonNull(source);Objects.requireNonNull(cancelled);Objects.requireNonNull(progress);Objects.requireNonNull(checkpoint);if(maximum<0 || maximum>MAX_DISK_BYTES)throw new IllegalArgumentException("Invalid disk comparison budget");
        var digest=digest();check(cancelled);
        for(int offset=0;offset<source.applied.length;offset+=65536){check(cancelled);int count=Math.min(65536,source.applied.length-offset);digest.update(source.applied,offset,count);progress.update((long)offset+count,0);}
        check(cancelled);String appliedHash=HexFormat.of().formatHex(digest.digest()),expected=source.savedHash==null ? null : HexFormat.of().formatHex(source.savedHash);var applied=AppliedEditReview.text(source.applied);progress.update(source.applied.length,0);
        if(source.directory==null)return report(source,Disk.NO_FOLDER,Baseline.UNBOUND,-1,appliedHash,null,expected,applied,null,"No active disk folder. Save or Save As first chooses a destination; no disk source was read.");
        PackWorkspace.rejectLinks(source.directory);var root=attributes(source.directory);if(!root.isDirectory())throw new IOException("The active pack folder is no longer a directory");var rootIdentity=Identity.of(root);Path file=PackWorkspace.checkedTarget(source.directory,source.path);BasicFileAttributes before;
        try{before=attributes(file);}catch(NoSuchFileException absent){
            checkpoint.read(file);check(cancelled);if(!absent(file))throw new IOException("Disk destination appeared during comparison");verifyRoot(source.directory,rootIdentity);
            return report(source,Disk.ABSENT,source.created ? Baseline.NEW_AVAILABLE : Baseline.MISSING,-1,appliedHash,null,expected,applied,AppliedEditReview.text(new byte[0]),"Disk destination is absent. The comparison uses an empty left side; no existing empty file is asserted. Save repeats its own checks.");
        }
        if(!before.isRegularFile() || before.isSymbolicLink())throw new IOException("Disk comparison requires a regular non-linked resource file");var identity=Identity.of(before);
        if(before.size()>maximum){checkpoint.read(file);check(cancelled);verifyFile(file,before,identity);verifyRoot(source.directory,rootIdentity);return report(source,Disk.TOO_LARGE,source.created ? Baseline.NEW_OCCUPIED : Baseline.NOT_CHECKED,before.size(),appliedHash,null,expected,applied,null,"Disk source exceeds the review read limit ("+maximum+" bytes); no disk bytes or line comparison were captured. Save remains independent.");}
        byte[] text=before.size()<=AppliedEditReview.MAX_TEXT_BYTES ? new byte[(int)before.size()] : null;byte[] buffer=new byte[65536];digest=digest();long total=0;
        try(var input=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)){
            while(true){check(cancelled);int count=input.read(buffer,0,(int)Math.min(buffer.length,before.size()-total+1));if(count<0)break;if(count==0)continue;
                total+=count;if(total>before.size() || total>maximum)throw new IOException("Disk source grew during comparison");digest.update(buffer,0,count);if(text!=null)System.arraycopy(buffer,0,text,(int)total-count,count);progress.update(source.applied.length,total);
            }
        }
        checkpoint.read(file);check(cancelled);if(total!=before.size())throw new IOException("Disk source shrank during comparison");verifyFile(file,before,identity);verifyRoot(source.directory,rootIdentity);String diskHash=HexFormat.of().formatHex(digest.digest());
        var diskText=text==null ? new AppliedEditReview.Text((int)total,"",AppliedEditReview.Kind.TOO_LARGE,false) : AppliedEditReview.text(text);
        return report(source,Disk.FILE,source.created ? Baseline.NEW_OCCUPIED : diskHash.equals(expected) ? Baseline.MATCH : Baseline.CHANGED,total,appliedHash,diskHash,expected,applied,diskText,"Captured disk bytes versus captured applied bytes. Hash equality describes this read only; external edits require Refresh, and Save rechecks conflicts independently. This is not an interprocess lock.");
    }
    private static Report report(Source source,Disk disk,Baseline baseline,long bytes,String appliedHash,String diskHash,String expected,AppliedEditReview.Text applied,AppliedEditReview.Text before,String detail){
        AppliedEditDiff.Diff diff=before==null ? null : AppliedEditDiff.compare(new AppliedEditReview.Comparison(0,source.path,before,applied));
        if(diff!=null)diff=new AppliedEditDiff.Diff(diff.status(),diff.beforeLines(),diff.afterLines(),diff.added(),diff.removed(),diff.unchanged(),diff.rows(),diff.detail().replace("Counts describe this edit only","Counts describe these captured disk/applied sources only"));
        return new Report(source.directory,source.path,disk,baseline,source.applied.length,bytes,appliedHash,diskHash,expected,applied,before,diff,detail);
    }
    private static MessageDigest digest(){try{return MessageDigest.getInstance("SHA-256");}catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}}
    private static BasicFileAttributes attributes(Path path)throws IOException{return Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);}
    private static boolean absent(Path path)throws IOException{try{attributes(path);return false;}catch(NoSuchFileException absent){return true;}}
    private static void verifyRoot(Path root,Identity identity)throws IOException{PackWorkspace.rejectLinks(root);if(!identity.matches(attributes(root)))throw new IOException("Active pack folder identity changed during comparison");}
    private static void verifyFile(Path file,BasicFileAttributes before,Identity identity)throws IOException{PackWorkspace.rejectLinks(file);var after=attributes(file);if(!identity.matches(after) || before.size()!=after.size() || !before.lastModifiedTime().equals(after.lastModifiedTime()))throw new IOException("Disk source identity, size or modification time changed during comparison");}
    private static void check(BooleanSupplier cancelled){if(cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())throw new CancellationException("File comparison cancelled");}
    private FileComparison(){}
}
