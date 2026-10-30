package dev.irisvisualizer.editor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

final class PendingDiskReviewTests {
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static void check(boolean value) { if (!value) throw new AssertionError("Background pending disk review contract failed"); }
    private static void io(InspectorDrafts.Change action) throws IOException { try { action.run(); } catch (IOException expected) { return; }throw new AssertionError("Expected guarded disk review request"); }
    private static WorkspaceChanges.Entry entry(PackWorkspace pack,String path) { return pack.pendingChanges().stream().filter(e->e.path().equals(path)).findFirst().orElseThrow(); }
    private static PendingDiskReview.Report inspect(PackWorkspace pack) throws IOException { return PendingDiskReview.inspect(pack.capturePendingDiskReview(null),()->false,(n,b)->{}); }
    private static WorkspaceChanges.Check find(PendingDiskReview.Report report,String path) { return report.checks().stream().filter(c->c.entry().path().equals(path)).findFirst().orElseThrow(); }
    private static void status(PendingDiskReview.Report report,String path,WorkspaceChanges.Status status) { check(find(report,path).status()==status); }
    private static final WorkspaceChanges.Query ALL=new WorkspaceChanges.Query("",WorkspaceChanges.Filter.ALL);
    private static AsyncPendingDiskReview.Snapshot ready(AsyncPendingDiskReview review,PackWorkspace pack) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);AsyncPendingDiskReview.Snapshot state;
        while ((state=review.poll(pack)).phase()==AsyncPendingDiskReview.Phase.CHECKING && System.nanoTime()<end) Thread.sleep(1);
        check(state.phase()==AsyncPendingDiskReview.Phase.READY);return state;
    }
    private static void stopped(AsyncPendingDiskReview review) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(review.busy() && System.nanoTime()<end) Thread.sleep(1);check(!review.busy());
    }
    private static void latch(CountDownLatch latch) throws Exception { check(latch.await(5,TimeUnit.SECONDS)); }
    private static void awaitIgnoringInterrupts(CountDownLatch latch) { while (true) try { latch.await();return; } catch (InterruptedException ignored) { } }
    static void run(Path temp) throws Exception {
        String a="blocks/a.json",b="blocks/b.json",opaque="objects/exact.iob";
        byte[] original=bytes("{\"block\":\"minecraft:stone\",\"future\":9007199254740993}"),unknown=bytes("invalid JSON 雪"),binary={0,1,-1};
        var pack=PackWorkspace.newPack(Map.of(a,original,b,original,"future/unknown.json",unknown,opaque,binary));
        var noFolder=inspect(pack);check(noFolder.checks().size()==4 && noFolder.count(WorkspaceChanges.Status.NO_ACTIVE_FOLDER)==4);
        Path root=temp.resolve("background-disk-source");pack.createSubfolder("blocks","old");pack.save(root);
        pack.replace(a,bytes("edited a"));pack.replace(b,bytes("edited b"));String added=pack.duplicate(opaque,"new");pack.createSubfolder("blocks","new");pack.renameEmptyFolder("blocks/old","moved");
        long revision=pack.revision(),saved=pack.saveRevision(),stamp=pack.contentRevision(a);int history=pack.undoCount();
        var source=pack.capturePendingDiskReview(null);var report=PendingDiskReview.inspect(source,()->false,(n,v)->{});
        check(report.checks().size()==6 && report.bytesRead()==original.length*2L);
        status(report,a,WorkspaceChanges.Status.BASELINE_MATCH);status(report,b,WorkspaceChanges.Status.BASELINE_MATCH);status(report,added,WorkspaceChanges.Status.AVAILABLE);status(report,"blocks/old",WorkspaceChanges.Status.REMOVAL_READY);status(report,"blocks/moved",WorkspaceChanges.Status.AVAILABLE);
        check(pack.revision()==revision && pack.saveRevision()==saved && pack.contentRevision(a)==stamp && pack.undoCount()==history && pack.dirty());
        check(Arrays.equals(Files.readAllBytes(root.resolve(a)),original) && Arrays.equals(pack.read(opaque),binary) && Arrays.equals(pack.read("future/unknown.json"),unknown));
        try { report.checks().clear();throw new AssertionError(); } catch (UnsupportedOperationException expected) { }
        try { report.counts().clear();throw new AssertionError(); } catch (UnsupportedOperationException expected) { }
        // Disk receipts are explicit point-in-time checks. Same-size/same-mtime changes still fail SHA-256.
        var time=Files.getLastModifiedTime(root.resolve(a));byte[] changed=original.clone();changed[1]='x';Files.write(root.resolve(a),changed);Files.setLastModifiedTime(root.resolve(a),time);
        check(find(report,a).status()==WorkspaceChanges.Status.BASELINE_MATCH);status(inspect(pack),a,WorkspaceChanges.Status.CONFLICT);io(()->pack.save(root));Files.write(root.resolve(a),original);
        Files.write(root.resolve(added),binary);Files.createDirectory(root.resolve("blocks/moved"));Files.writeString(root.resolve("blocks/old/foreign"),"keep");
        report=inspect(pack);status(report,added,WorkspaceChanges.Status.CONFLICT);status(report,"blocks/moved",WorkspaceChanges.Status.CONFLICT);status(report,"blocks/old",WorkspaceChanges.Status.CONFLICT);check(Files.readString(root.resolve("blocks/old/foreign")).equals("keep"));
        Files.delete(root.resolve(added));Files.delete(root.resolve("blocks/moved"));Files.delete(root.resolve("blocks/old/foreign"));
        Files.createDirectory(root.resolve("blocks/new"));status(inspect(pack),"blocks/new",WorkspaceChanges.Status.FOLDER_EXISTS);Files.delete(root.resolve("blocks/new"));
        Files.delete(root.resolve(b));status(inspect(pack),b,WorkspaceChanges.Status.CONFLICT);Files.createDirectory(root.resolve(b));status(inspect(pack),b,WorkspaceChanges.Status.CONFLICT);Files.delete(root.resolve(b));Files.write(root.resolve(b),original);
        Path moved=temp.resolve("background-disk-moved");Files.move(root,moved);status(inspect(pack),a,WorkspaceChanges.Status.CONFLICT);check(!Files.exists(root));Files.move(moved,root);
        // Selected capture rejects stale entries and never silently captures the current whole inventory.
        var selected=entry(pack,a);check(pack.capturePendingDiskReview(selected).entries()==1);io(()->pack.capturePendingDiskReview(new WorkspaceChanges.Entry(a,WorkspaceChanges.Kind.NEW_FILE,pack.size(a))));
        // A source keeps its captured saved hashes after the live workspace saves a later baseline.
        pack.saveCopy(temp.resolve("background-disk-copy"));pack.saveAs(temp.resolve("background-disk-as"));
        report=PendingDiskReview.inspect(source,()->false,(n,v)->{});status(report,a,WorkspaceChanges.Status.BASELINE_MATCH);check(report.directory().equals(root.toRealPath()));
        // Independent metadata sources exercise exact shared/per-file limits without large allocations.
        var small=PackWorkspace.newPack(Map.of("a.bin",bytes("1234"),"b.bin",bytes("5678"),"zero.bin",new byte[0]));Path smallRoot=temp.resolve("background-disk-budgets");small.save(smallRoot);for(String p:small.paths()) small.replace(p,bytes("edited"));
        var smallSource=small.capturePendingDiskReview(null);
        var limited=PendingDiskReview.inspect(smallSource,()->false,(n,v)->{},new PendingDiskReview.Limits(4,4,0));status(limited,"a.bin",WorkspaceChanges.Status.BASELINE_MATCH);status(limited,"b.bin",WorkspaceChanges.Status.REVIEW_LIMIT);status(limited,"zero.bin",WorkspaceChanges.Status.BASELINE_MATCH);check(limited.bytesRead()==4);
        limited=PendingDiskReview.inspect(smallSource,()->false,(n,v)->{},new PendingDiskReview.Limits(3,16,0));check(limited.count(WorkspaceChanges.Status.TOO_LARGE)==2 && limited.bytesRead()==0);
        var cancel=new AtomicBoolean();var cancelledBytes=new AtomicLong();try { PendingDiskReview.inspect(smallSource,cancel::get,(n,v)->{ cancelledBytes.set(v);cancel.set(true); });throw new AssertionError(); } catch(CancellationException expected) { check(cancelledBytes.get()==4); }
        try { PendingDiskReview.inspect(smallSource,()->true,(n,v)->{});throw new AssertionError(); } catch(CancellationException expected) { }
        // Streaming cancellation and changes during reads never publish a partial baseline-match receipt.
        var large=PackWorkspace.newPack(Map.of("payload.bin",new byte[24_000]));Path largeRoot=temp.resolve("background-disk-stream");large.save(largeRoot);large.replace("payload.bin",bytes("edit"));var largeSource=large.capturePendingDiskReview(null);
        cancel.set(false);try { PendingDiskReview.inspect(largeSource,cancel::get,(n,v)->{ if(v>=8192) cancel.set(true); });throw new AssertionError(); } catch(CancellationException expected) { }
        var once=new AtomicBoolean();var growing=PendingDiskReview.inspect(largeSource,()->false,(n,v)->{ if(v>0 && once.compareAndSet(false,true)) try { Files.write(largeRoot.resolve("payload.bin"),new byte[]{1},StandardOpenOption.APPEND); } catch(IOException e) { throw new RuntimeException(e); } },new PendingDiskReview.Limits(24_000,24_000,10));
        status(growing,"payload.bin",WorkspaceChanges.Status.TOO_LARGE);check(growing.bytesRead()==24_001);
        Files.write(largeRoot.resolve("payload.bin"),new byte[24_000]);once.set(false);
        var during=PendingDiskReview.inspect(largeSource,()->false,(n,v)->{ if(v>0 && once.compareAndSet(false,true)) try { Files.setLastModifiedTime(largeRoot.resolve("payload.bin"),java.nio.file.attribute.FileTime.fromMillis(1)); } catch(IOException e) { throw new RuntimeException(e); } });status(during,"payload.bin",WorkspaceChanges.Status.CONFLICT);
        // Removed parents may contain only captured pending-removal folders; child iteration is shared/bounded.
        Path removalRoot=Files.createDirectory(temp.resolve("background-disk-removal"));Files.createDirectories(removalRoot.resolve("parent/child"));
        var removal=new PendingDiskReview.Source(removalRoot,List.of(new WorkspaceChanges.Entry("parent",WorkspaceChanges.Kind.REMOVED_FOLDER,0)),Map.of(),Set.of("parent","parent/child"),Set.of());
        status(PendingDiskReview.inspect(removal,()->false,(n,v)->{},new PendingDiskReview.Limits(0,0,0)),"parent",WorkspaceChanges.Status.REVIEW_LIMIT);status(PendingDiskReview.inspect(removal,()->false,(n,v)->{}),"parent",WorkspaceChanges.Status.REMOVAL_READY);
        // Symlinks are refused where this platform permits creating them; no target writes occur.
        Path link=smallRoot.resolve("a.bin"),linkTarget=temp.resolve("background-disk-link-target");Files.writeString(linkTarget,"external");Files.delete(link);boolean linked=false;
        try { Files.createSymbolicLink(link,linkTarget);linked=true; } catch(IOException | UnsupportedOperationException unavailable) { Files.writeString(link,"1234"); }
        if(linked) { status(inspect(small),"a.bin",WorkspaceChanges.Status.IO_ERROR);check(Files.readString(linkTarget).equals("external"));Files.delete(link);Files.writeString(link,"1234"); }
        // Filtering is bounded to 200 but retains full counts and reaches later paths without a disk rescan.
        var many=new LinkedHashMap<String,byte[]>();for(int i=0;i<240;i++)many.put(String.format("future/n%03d.json",i),bytes("opaque"));var manyReport=inspect(PackWorkspace.newPack(many));
        var page=PendingDiskReview.filter(manyReport,ALL,false);check(page.matches()==240 && page.checks().size()==200 && page.truncated());check(PendingDiskReview.filter(manyReport,new WorkspaceChanges.Query("N239",WorkspaceChanges.Filter.NEW_FILES),true).checks().getFirst().entry().path().equals("future/n239.json"));
        // Real worker: off-UI daemon, point-in-time cache, drafts/Save Copy retain receipts; edits/saves/owners do not.
        try(var review=new AsyncPendingDiskReview()) {
            var drafts=new InspectorDrafts();drafts.update("row","stored","draft");review.start(small,null);var state=ready(review,small);check(state.report().checks().size()==3 && drafts.any());
            var cached=review.query(small,ALL,false);Files.writeString(smallRoot.resolve("a.bin"),"disk");check(review.query(small,ALL,false)==cached);small.saveCopy(temp.resolve("background-review-retained-copy"));check(review.query(small,ALL,false)==cached);
            small.replace("a.bin",bytes("later"));check(review.poll(small).phase()==AsyncPendingDiskReview.Phase.IDLE);small.undo();check(review.poll(small).phase()==AsyncPendingDiskReview.Phase.IDLE);
            review.start(small,entry(small,"a.bin"));check(ready(review,small).report().checks().size()==1);io(()->review.start(small,new WorkspaceChanges.Entry("absent",WorkspaceChanges.Kind.NEW_FILE,0)));check(review.poll(small).phase()==AsyncPendingDiskReview.Phase.READY);
            Files.writeString(smallRoot.resolve("a.bin"),"1234");small.save(smallRoot);check(review.poll(small).phase()==AsyncPendingDiskReview.Phase.IDLE);review.start(small,null);check(ready(review,small).report().checks().isEmpty());check(review.poll(PackWorkspace.empty()).phase()==AsyncPendingDiskReview.Phase.IDLE);
        }
        // Noncooperative work cannot publish after cancellation, an edit, owner switch, clear or close; no hidden queue.
        for(int mode=0;mode<5;mode++) {
            var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var workerThread=new AtomicReference<Thread>();var scans=new AtomicInteger();
            var current=PackWorkspace.newPack(Map.of("blocks/a.json",original));var review=new AsyncPendingDiskReview((captured,cancelled,progress)->{ workerThread.set(Thread.currentThread());scans.incrementAndGet();entered.countDown();awaitIgnoringInterrupts(release);return PendingDiskReview.inspect(captured,()->false,progress); },r->new Thread(r,"disk-review-contract"));
            try {
                review.start(current,null);latch(entered);check(workerThread.get()!=Thread.currentThread() && workerThread.get().isDaemon());io(()->review.start(current,null));check(scans.get()==1);
                if(mode==0)review.cancel();else if(mode==1){current.replace("blocks/a.json",bytes("later"));review.poll(current);}else if(mode==2)review.poll(PackWorkspace.empty());else if(mode==3)review.clear();else {long before=System.nanoTime();review.close();check(System.nanoTime()-before<TimeUnit.SECONDS.toNanos(1));}
                release.countDown();stopped(review);check(review.poll(current).phase()!=AsyncPendingDiskReview.Phase.READY);
                if(mode<4){review.start(current,null);ready(review,current);check(scans.get()==2);}
            } finally {release.countDown();review.close();}
        }
        // Failed reviews remain bounded/stable and retry only explicitly.
        var calls=new AtomicInteger();try(var review=new AsyncPendingDiskReview((captured,cancelled,progress)->{ if(calls.incrementAndGet()==1)throw new IllegalArgumentException("failure\n"+"x".repeat(800));return PendingDiskReview.inspect(captured,cancelled,progress); },r->new Thread(r,"disk-review-retry"))) {
            review.start(small,null);stopped(review);var failed=review.poll(small);check(failed.phase()==AsyncPendingDiskReview.Phase.FAILED && failed.detail().length()<=515 && !failed.detail().contains("\n"));check(review.poll(small)==failed && calls.get()==1);review.start(small,null);ready(review,small);check(calls.get()==2);
        }
        // Save-revision-only partial failure invalidates a receipt even when applied revision stays unchanged.
        var partial=PackWorkspace.newPack(Map.of("blocks/base.json",original));Path partialRoot=temp.resolve("background-disk-partial");partial.save(partialRoot);partial.duplicate("blocks/base.json","a_new");partial.duplicate("blocks/base.json","z_later");
        try(var review=new AsyncPendingDiskReview()) { review.start(partial,null);ready(review,partial);revision=partial.revision();saved=partial.saveRevision();io(()->partial.save(partialRoot,(base,path,data,baseline)->{if(path.endsWith("/z_later.json"))throw new IOException("Injected later staging failure");return FileSaves.write(base,path,data,baseline);}));check(partial.revision()==revision && partial.saveRevision()>saved && review.poll(partial).phase()==AsyncPendingDiskReview.Phase.IDLE); }
        var tooMany=new ArrayList<WorkspaceChanges.Entry>();for(int i=0;i<=PendingDiskReview.MAX_ENTRIES;i++)tooMany.add(new WorkspaceChanges.Entry("x"+i,WorkspaceChanges.Kind.NEW_FOLDER,0));io(()->new PendingDiskReview.Source(null,tooMany,Map.of(),Set.of(),Set.of()));
        io(()->new PendingDiskReview.Source(null,List.of(new WorkspaceChanges.Entry("x".repeat(PendingDiskReview.MAX_PATH_CHARACTERS+1),WorkspaceChanges.Kind.NEW_FOLDER,0)),Map.of(),Set.of(),Set.of()));
        System.out.println("PASS: detached background pending-destination disk review with private saved-hash metadata, bounded per-file/shared streaming and directory-child budgets, exact opaque/unknown preservation, occupancy/removal/type/link and SHA-256 conflicts, changes-during-read detection, filtered bounded point-in-time receipts, progress/cancellation/no hidden queue, late edit/Undo/Save/partial-save/workspace invalidation, stable errors/explicit retry and nonblocking close; no writes, baseline adoption or native generation");
    }
}
