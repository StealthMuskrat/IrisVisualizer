package dev.irisvisualizer.editor;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Detached saved-baseline metadata only. Reviews never write or authorize a later Save. */
public final class PendingDiskReview {
    public static final int MAX_ENTRIES=100_000,MAX_PATH_CHARACTERS=16_000_000,MAX_DIRECTORY_CHILDREN=100_000;
    public static final long MAX_TOTAL_BYTES=256L*1024*1024;
    record Limits(long fileBytes,long totalBytes,int children) {
        Limits { if (fileBytes<0 || fileBytes>WorkspaceChanges.MAX_CHECK_BYTES || totalBytes<0 || totalBytes>MAX_TOTAL_BYTES || children<0 || children>MAX_DIRECTORY_CHILDREN) throw new IllegalArgumentException("Invalid pending disk review limits"); }
    }
    private static final Limits LIMITS=new Limits(WorkspaceChanges.MAX_CHECK_BYTES,MAX_TOTAL_BYTES,MAX_DIRECTORY_CHILDREN);
    @FunctionalInterface interface Progress { void update(int entries,long bytes); }
    /** Private hashes and copied sets; contains neither applied payloads nor a workspace reference. */
    public static final class Source {
        private final Path directory;
        private final List<WorkspaceChanges.Entry> entries;
        private final Map<String,byte[]> hashes;
        private final Set<String> removed,relocated;
        Source(Path directory,List<WorkspaceChanges.Entry> entries,Map<String,byte[]> hashes,Set<String> removed,Set<String> relocated) throws IOException {
            if (entries.size()>MAX_ENTRIES || removed.size()>MAX_ENTRIES || relocated.size()>MAX_ENTRIES) throw new IOException("Pending disk review exceeds 100,000 entries per metadata collection");
            long characters=0;for (var entry : entries) characters+=entry.path().length();for (String path : removed) characters+=path.length();for (String path : relocated) characters+=path.length();
            if (characters>MAX_PATH_CHARACTERS) throw new IOException("Pending disk review exceeds 16 million metadata path characters");
            this.directory=directory;this.entries=List.copyOf(entries);this.removed=Set.copyOf(removed);this.relocated=Set.copyOf(relocated);
            var copies=new HashMap<String,byte[]>();for (var entry : entries) if (entry.kind()==WorkspaceChanges.Kind.MODIFIED_FILE) {
                byte[] hash=hashes.get(entry.path());if (hash==null || hash.length!=32) throw new IOException("Pending file has no saved baseline: "+entry.path());copies.put(entry.path(),hash.clone());
            }
            this.hashes=Collections.unmodifiableMap(copies);
        }
        public Path directory() { return directory; }
        public int entries() { return entries.size(); }
    }
    public record Report(Path directory,List<WorkspaceChanges.Check> checks,long bytesRead,Map<WorkspaceChanges.Status,Integer> counts) {
        public Report { checks=List.copyOf(checks);counts=Map.copyOf(counts); }
        public int count(WorkspaceChanges.Status status) { return counts.getOrDefault(status,0); }
    }
    public record Page(int matches,List<WorkspaceChanges.Check> checks) {
        public Page { checks=List.copyOf(checks); }
        public boolean truncated() { return matches>checks.size(); }
    }
    public static boolean attention(WorkspaceChanges.Status status) {
        return status==WorkspaceChanges.Status.CONFLICT || status==WorkspaceChanges.Status.IO_ERROR || status==WorkspaceChanges.Status.TOO_LARGE || status==WorkspaceChanges.Status.REVIEW_LIMIT || status==WorkspaceChanges.Status.NO_ACTIVE_FOLDER;
    }
    public static Page filter(Report report,WorkspaceChanges.Query query,boolean attentionOnly) {
        int matches=0;var shown=new ArrayList<WorkspaceChanges.Check>();
        for (var check : report.checks()) {
            var entry=check.entry();if (!query.filter().matches(entry.kind()) || !entry.path().toLowerCase(Locale.ROOT).contains(query.text()) || (attentionOnly && !attention(check.status()))) continue;
            matches++;if (shown.size()<WorkspaceChanges.MAX_RESULTS) shown.add(check);
        }
        return new Page(matches,shown);
    }
    private static final class Budget {
        final Limits limits;final BooleanSupplier cancelled;final Progress progress;long bytes;int children,entries;
        Budget(Limits limits,BooleanSupplier cancelled,Progress progress) { this.limits=limits;this.cancelled=cancelled;this.progress=progress; }
        void check() { if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new CancellationException("Pending disk review cancelled"); }
        void progress() { progress.update(entries,bytes); }
    }
    static Report inspect(Source source,BooleanSupplier cancelled,Progress progress) { return inspect(source,cancelled,progress,LIMITS); }
    static Report inspect(Source source,BooleanSupplier cancelled,Progress progress,Limits limits) {
        Objects.requireNonNull(source);var budget=new Budget(limits,Objects.requireNonNull(cancelled),Objects.requireNonNull(progress));
        var checks=new ArrayList<WorkspaceChanges.Check>();var counts=new EnumMap<WorkspaceChanges.Status,Integer>(WorkspaceChanges.Status.class);
        for (var entry : source.entries) {
            budget.check();var check=check(source,entry,budget);budget.check();checks.add(check);counts.merge(check.status(),1,Integer::sum);budget.entries++;budget.progress();
        }
        budget.check();return new Report(source.directory,checks,budget.bytes,counts);
    }
    private static WorkspaceChanges.Check result(Source source,WorkspaceChanges.Entry entry,WorkspaceChanges.Status status,long bytes,String detail) {
        return new WorkspaceChanges.Check(entry,source.directory,status,bytes,InspectorText.label(detail,512));
    }
    private static boolean same(BasicFileAttributes before,BasicFileAttributes after) {
        return before.isRegularFile()==after.isRegularFile() && before.isDirectory()==after.isDirectory() && before.size()==after.size() && before.lastModifiedTime().equals(after.lastModifiedTime()) && Objects.equals(before.fileKey(),after.fileKey());
    }
    private static WorkspaceChanges.Check check(Source source,WorkspaceChanges.Entry entry,Budget budget) {
        var kind=entry.kind();Path root=source.directory;
        if (root==null) return result(source,entry,WorkspaceChanges.Status.NO_ACTIVE_FOLDER,-1,"No active folder. Save As chooses a fresh destination; imported ZIPs have no active folder.");
        try {
            budget.check();PackWorkspace.rejectLinks(root);
            if (!Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS)) return result(source,entry,WorkspaceChanges.Status.CONFLICT,-1,"The active pack folder is missing or is no longer a directory.");
            Path file=PackWorkspace.checkedTarget(root,PackWorkspace.safe(entry.path()));BasicFileAttributes attributes;
            try { attributes=Files.readAttributes(file,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS); }
            catch (NoSuchFileException missing) { return result(source,entry,kind==WorkspaceChanges.Kind.MODIFIED_FILE ? WorkspaceChanges.Status.CONFLICT : WorkspaceChanges.Status.AVAILABLE,-1,kind==WorkspaceChanges.Kind.MODIFIED_FILE ? "The resource was removed outside the editor." : "The destination is absent at this check. Permissions and future races are unchecked."); }
            if (kind==WorkspaceChanges.Kind.NEW_FOLDER) return result(source,entry,source.relocated.contains(entry.path()) || !attributes.isDirectory() ? WorkspaceChanges.Status.CONFLICT : WorkspaceChanges.Status.FOLDER_EXISTS,-1,source.relocated.contains(entry.path()) ? "The relocated-folder destination became occupied outside the workspace." : attributes.isDirectory() ? "The destination is already a directory; its contents were not inspected." : "The folder destination is occupied by a non-directory.");
            if (kind==WorkspaceChanges.Kind.REMOVED_FOLDER) {
                if (!attributes.isDirectory()) return result(source,entry,WorkspaceChanges.Status.CONFLICT,-1,"Folder removal destination is no longer a directory.");
                try (var children=Files.newDirectoryStream(file)) {
                    for (Path child : children) {
                        budget.check();if (budget.children>=budget.limits.children()) return result(source,entry,WorkspaceChanges.Status.REVIEW_LIMIT,-1,"The shared 100,000 directory-child review budget was exhausted; removal readiness is unchecked.");budget.children++;
                        String relative=root.relativize(child).toString().replace('\\','/');
                        if (!source.removed.contains(relative) || !Files.isDirectory(child,LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(child)) return result(source,entry,WorkspaceChanges.Status.CONFLICT,-1,"Unexpected contents block empty-folder removal: "+relative);
                    }
                }
                PackWorkspace.rejectLinks(root);PackWorkspace.checkedTarget(root,entry.path());
                if (!same(attributes,Files.readAttributes(file,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS))) return result(source,entry,WorkspaceChanges.Status.CONFLICT,-1,"The folder changed during its check. Refresh explicitly.");
                return result(source,entry,WorkspaceChanges.Status.REMOVAL_READY,-1,"The folder is empty or contains only pending folder removals. Save repeats its own full preflight and deletes nonrecursively.");
            }
            if (kind==WorkspaceChanges.Kind.NEW_FILE) return result(source,entry,WorkspaceChanges.Status.CONFLICT,attributes.size(),"A new-file destination is already occupied; Save refuses overwrite even when its bytes are identical.");
            if (!attributes.isRegularFile()) return result(source,entry,WorkspaceChanges.Status.CONFLICT,attributes.size(),"The resource destination is no longer a regular file.");
            if (attributes.size()>budget.limits.fileBytes()) return result(source,entry,WorkspaceChanges.Status.TOO_LARGE,attributes.size(),"Disk file exceeds the per-file review limit. Save performs its own full conflict check.");
            if (attributes.size()>budget.limits.totalBytes()-budget.bytes) return result(source,entry,WorkspaceChanges.Status.REVIEW_LIMIT,attributes.size(),"The shared 256 MiB disk-read budget cannot cover this file. Narrow the review or check it separately.");
            MessageDigest digest=MessageDigest.getInstance("SHA-256");long total=0;
            try (var input=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)) {
                byte[] buffer=new byte[8192];
                while (true) {
                    budget.check();long remaining=Math.min(budget.limits.fileBytes()-total,budget.limits.totalBytes()-budget.bytes);
                    if (remaining==0) {
                        // Read one overflow probe at an exact boundary. It is included in the reported read count.
                        int value=input.read();if (value<0) break;budget.bytes++;total++;budget.progress();budget.check();
                        return result(source,entry,total>budget.limits.fileBytes() ? WorkspaceChanges.Status.TOO_LARGE : WorkspaceChanges.Status.REVIEW_LIMIT,total,"Disk file grew past the bounded review limit while reading.");
                    }
                    int count=input.read(buffer,0,(int)Math.min(buffer.length,remaining));if (count<0) break;if (count==0) continue;
                    total+=count;budget.bytes+=count;digest.update(buffer,0,count);budget.progress();
                }
            }
            budget.check();PackWorkspace.rejectLinks(root);PackWorkspace.checkedTarget(root,entry.path());
            if (!same(attributes,Files.readAttributes(file,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS)) || total!=attributes.size()) return result(source,entry,WorkspaceChanges.Status.CONFLICT,total,"The file changed during its disk check. Refresh explicitly.");
            boolean matches=Arrays.equals(source.hashes.get(entry.path()),digest.digest());
            return result(source,entry,matches ? WorkspaceChanges.Status.BASELINE_MATCH : WorkspaceChanges.Status.CONFLICT,total,matches ? "Disk bytes match the captured saved baseline at this check; later Save still performs its own full preflight." : "Disk bytes differ from the captured saved baseline. Reopen to review disk changes, or use Save As/Save Copy to a fresh folder.");
        } catch (IOException | SecurityException failure) { return result(source,entry,WorkspaceChanges.Status.IO_ERROR,-1,"Disk check could not complete: "+Objects.toString(failure.getMessage(),failure.getClass().getSimpleName())); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private PendingDiskReview() {}
}
