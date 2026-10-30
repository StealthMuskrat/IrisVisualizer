package dev.irisvisualizer.editor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

final class AsyncPackReferenceReviewTests {
    private static final String SOURCE="biomes/source.json";
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static void check(boolean valid,String reason) { if (!valid) throw new AssertionError("Async resource review: "+reason); }
    private static void rejects(Runnable action) { try { action.run(); } catch (IllegalArgumentException | IllegalStateException | NullPointerException | UnsupportedOperationException expected) { return; }throw new AssertionError("Expected rejected reference scan operation"); }
    private static byte[] json(int marker) { return bytes("{\"marker\":"+marker+",\"children\":[\"target\"]}"); }
    private static PackWorkspace pack(int marker) throws Exception { return PackWorkspace.newPack(Map.of(SOURCE,json(marker),"biomes/target.json",bytes("{}"),"images/opaque.png",new byte[]{0,-1,2},"objects/opaque.iob",new byte[]{0,1,-1},"future/unknown.json",bytes("invalid preserved"))); }
    private static void await(CountDownLatch signal,String reason) throws InterruptedException { check(signal.await(10,TimeUnit.SECONDS),reason); }
    private static AsyncPackReferenceReview.Snapshot terminal(AsyncPackReferenceReview review,PackWorkspace pack) {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime()<deadline) { var state=review.poll(pack);if (state.phase()!=AsyncPackReferenceReview.Phase.SCANNING) return state;LockSupport.parkNanos(1_000_000); }
        throw new AssertionError("Reference scan did not publish a terminal state");
    }
    private static final class Gate {
        final CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1);
        volatile BooleanSupplier cancelled;
    }
    private static final class Controlled implements AsyncPackReferenceReview.Scanner {
        final Map<Integer,Gate> gates;final Thread caller=Thread.currentThread();
        final AtomicInteger active=new AtomicInteger(),maximum=new AtomicInteger();
        final List<Integer> calls=java.util.Collections.synchronizedList(new ArrayList<>());
        Controlled(Map<Integer,Gate> gates) { this.gates=gates; }
        public PackReferenceAudit.Audit inspect(PackResourceView source,BooleanSupplier cancelled) {
            check(Thread.currentThread()!=caller && !(source instanceof PackWorkspace),"worker receives only immutable source outside request thread");
            byte[] privateBytes=source.read(SOURCE);int marker=Integer.parseInt(new LosslessJson(privateBytes).token("marker"));privateBytes[0]=0;
            check(source.read(SOURCE)[0]=='{',"snapshot reads are defensive");var gate=gates.get(marker);check(gate!=null,"expected snapshot marker");gate.cancelled=cancelled;
            int count=active.incrementAndGet();maximum.accumulateAndGet(count,Math::max);calls.add(marker);gate.started.countDown();
            try {
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);boolean released=false;
                while (!released && System.nanoTime()<deadline) try { released=gate.release.await(20,TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) { /* Simulate a scanner that ignores cancellation/interruption. */ }
                check(released,"test scan released");return PackReferenceAudit.inspect(source);
            } finally { active.decrementAndGet(); }
        }
    }
    private static java.util.concurrent.ThreadFactory threads(AtomicReference<Thread> reference) { return runnable -> { var thread=new Thread(runnable,"test-resource-review");reference.set(thread);return thread; }; }
    private static void stop(Thread thread) throws InterruptedException { thread.join(10_000);check(!thread.isAlive(),"worker exits after active scanner returns"); }
    private static final class Source implements PackResourceView {
        final TreeMap<String,Integer> sizes=new TreeMap<>();final Map<String,byte[]> content=new TreeMap<>();final List<String> reads=new ArrayList<>();
        public Set<String> paths() { return java.util.Collections.unmodifiableSet(sizes.navigableKeySet()); }
        public int size(String path) { return sizes.get(path); }
        public byte[] read(String path) { reads.add(path);byte[] raw=content.get(path);if (raw==null) throw new AssertionError("Unexpected source read: "+path);return raw.clone(); }
        void json(String path,int size) { sizes.put(path,size);content.put(path,bytes(" ".repeat(size-2)+"{}")); }
    }
    static void run(Path temp) throws Exception {
        // Capture has the same source budgets/order as the scanner, while all exact targets remain visible.
        var source=new Source();source.json("biomes/a.json",60);source.json("biomes/b.json",70);source.json("biomes/c.json",30);source.sizes.put("dimensions/large.json",ReferenceDiagnostics.MAX_JSON_BYTES+1);source.json("generators/beyond.json",2);source.sizes.put("objects/binary.iob",50_000_000);source.sizes.put("images/binary.png",50_000_000);
        var limits=new ReferenceScanSource.Limits(4,100,16_000_000);var snapshot=ReferenceScanSource.capture(source,limits);
        check(snapshot.copiedBytes()==90 && snapshot.copiedFiles()==2 && source.reads.equals(List.of("biomes/a.json","biomes/c.json")),"oversized and byte-skipped files never read; later small sources still fit");
        check(snapshot.paths().equals(source.paths()) && snapshot.size("dimensions/large.json")==ReferenceDiagnostics.MAX_JSON_BYTES+1,"target presence and skipped-source sizes preserved");
        var bounded=PackReferenceAudit.inspect(snapshot,new PackReferenceAudit.Limits(4,100,4096,256));check(bounded.equals(PackReferenceAudit.inspect(source,new PackReferenceAudit.Limits(4,100,4096,256))),"snapshot scanner exactly matches live bounded review");
        rejects(() -> snapshot.read("biomes/b.json"));rejects(() -> snapshot.read("objects/binary.iob"));rejects(() -> snapshot.size("generators/beyond.json"));rejects(() -> snapshot.paths().clear());
        source.content.get("biomes/a.json")[0]='X';source.sizes.put("objects/later.iob",10);check(snapshot.read("biomes/a.json")[0]==' ' && !snapshot.paths().contains("objects/later.iob"),"bytes and path catalog isolated from later source edits");
        source.reads.clear();rejects(() -> ReferenceScanSource.capture(source,new ReferenceScanSource.Limits(4,100,1)));check(source.reads.isEmpty(),"catalog limit fails before any payload copy");
        rejects(() -> new ReferenceScanSource.Limits(513,100,100));rejects(() -> new ReferenceScanSource.Limits(1,8_000_001,100));rejects(() -> new ReferenceScanSource.Limits(1,0,16_000_001));
        var boundary=new Source();boundary.json("biomes/a.json",ReferenceDiagnostics.MAX_JSON_BYTES);check(ReferenceScanSource.capture(boundary).copiedBytes()==ReferenceDiagnostics.MAX_JSON_BYTES,"exact per-source limit accepted");
        var mismatch=new Source();mismatch.json("biomes/a.json",2);mismatch.sizes.put("biomes/a.json",3);rejects(() -> ReferenceScanSource.capture(mismatch));
        var catalog=new Source();for (int i=0;i<PackWorkspace.MAX_FILES;i++) catalog.sizes.put("objects/"+i+".iob",1);check(ReferenceScanSource.capture(catalog).paths().size()==PackWorkspace.MAX_FILES && catalog.reads.isEmpty(),"exact 100,000-path bound without reading assets");catalog.sizes.put("objects/extra.iob",1);rejects(() -> ReferenceScanSource.capture(catalog));check(catalog.reads.isEmpty(),"file catalog overflow precedes payload copying");
        var pathBoundary=new Source();pathBoundary.sizes.put("objects/a.iob",1);int characters="objects/a.iob".length();check(ReferenceScanSource.capture(pathBoundary,new ReferenceScanSource.Limits(1,0,characters)).paths().size()==1,"exact path-character limit accepted");rejects(() -> ReferenceScanSource.capture(pathBoundary,new ReferenceScanSource.Limits(1,0,characters-1)));
        // Cooperative cancellation is checked even before reading any source or after the final parse.
        source.reads.clear();try { PackReferenceAudit.inspect(source,() -> true);throw new AssertionError(); } catch (java.util.concurrent.CancellationException expected) { check(source.reads.isEmpty(),"pre-read cancellation"); }
        var cancelSource=new Source();cancelSource.json("biomes/a.json",2);try { PackReferenceAudit.inspect(cancelSource,() -> !cancelSource.reads.isEmpty());throw new AssertionError(); } catch (java.util.concurrent.CancellationException expected) { check(cancelSource.reads.size()==1,"post-parse cancellation"); }
        // A active + B waiting + C refreshed runs A,C only, and the stale A audit cannot publish.
        var one=new Gate();var two=new Gate();var three=new Gate();var controlled=new Controlled(Map.of(1,one,2,two,3,three));var thread=new AtomicReference<Thread>();var pack=pack(1);pack.save(temp.resolve("async-review-source"));var drafts=new InspectorDrafts();drafts.update("late","stored","draft");
        try (var review=new AsyncPackReferenceReview(controlled,threads(thread))) {
            check(review.poll(pack).phase()==AsyncPackReferenceReview.Phase.IDLE && controlled.calls.isEmpty(),"opening/polling does not scan automatically");
            var first=review.refresh(pack);await(one.started,"first scan starts");check(first.phase()==AsyncPackReferenceReview.Phase.SCANNING && thread.get().isDaemon(),"request returns while daemon scan is blocked");
            pack.saveCopy(temp.resolve("async-review-loading-copy"));check(review.poll(pack).phase()==AsyncPackReferenceReview.Phase.SCANNING && drafts.any(),"Save Copy and inspector drafts independent while scanning");
            pack.replace(SOURCE,json(2));check(review.poll(pack).phase()==AsyncPackReferenceReview.Phase.IDLE && one.cancelled.getAsBoolean() && controlled.calls.equals(List.of(1)),"applied edit invalidates without rescanning");var second=review.refresh(pack);pack.replace(SOURCE,json(3));var latest=review.refresh(pack);
            check(latest.request()>second.request() && latest.audit()==null,"refresh hides prior results immediately");one.release.countDown();await(three.started,"latest waiting request replaces intermediate request");check(two.started.getCount()==1 && review.poll(pack).phase()==AsyncPackReferenceReview.Phase.SCANNING,"stale ignored-cancellation result discarded");three.release.countDown();var ready=terminal(review,pack);
            check(ready.phase()==AsyncPackReferenceReview.Phase.READY && ready.audit().equals(PackReferenceAudit.inspect(pack)) && controlled.maximum.get()==1 && controlled.calls.equals(List.of(1,3)),"same exact review with one active scanner");
            var query=new PackReferenceAudit.Query("",PackReferenceAudit.View.EXACT,"biomes/target.json");var result=review.query(pack,query);check(result.matches()==1 && review.query(pack,query)==result,"incoming filter cached without rescanning");
            long revision=pack.revision(),resources=pack.resourceRevision();int history=pack.undoCount();pack.saveCopy(temp.resolve("async-review-copy"));pack.saveAs(temp.resolve("async-review-as"));pack.save(pack.directory());pack.exportZip(temp.resolve("async-review.zip"));
            check(review.poll(pack)==ready && review.query(pack,query)==result && pack.revision()==revision && pack.resourceRevision()==resources && pack.undoCount()==history && drafts.any(),"Save/Copy/As/ZIP preserve ready scan, history, query and drafts");
            var copy=PackWorkspace.open(temp.resolve("async-review-copy"));var zip=PackWorkspace.importZip(temp.resolve("async-review.zip"));for (String path : pack.paths()) check(Arrays.equals(pack.read(path),copy.read(path)) && Arrays.equals(pack.read(path),zip.read(path)),"exact persistence "+path);
            pack.undo();check(review.poll(pack).phase()==AsyncPackReferenceReview.Phase.IDLE && review.query(pack,query)==null,"Undo invalidation without rescan");pack.redo();check(review.poll(pack).phase()==AsyncPackReferenceReview.Phase.IDLE && controlled.calls.size()==2,"Redo does not revive prior results");
        }stop(thread.get());
        // Same revision and same source path in another workspace still invalidate completed or active work.
        var original=pack(1);var captured=ReferenceScanSource.capture(original);original.replace(SOURCE,json(2));original.duplicate("objects/opaque.iob","new");check(!captured.paths().contains("objects/new.iob") && new LosslessJson(captured.read(SOURCE)).token("marker").equals("1"),"live workspace changes cannot alter captured bytes/catalog");
        var workspaceGate=new Gate();var workspaceThread=new AtomicReference<Thread>();var workspaceScanner=new Controlled(Map.of(1,workspaceGate));var firstPack=pack(1);var nextPack=pack(1);
        try (var review=new AsyncPackReferenceReview(workspaceScanner,threads(workspaceThread))) {
            review.refresh(firstPack);await(workspaceGate.started,"workspace isolation active");check(firstPack.revision()==nextPack.revision() && review.poll(nextPack).phase()==AsyncPackReferenceReview.Phase.IDLE && workspaceGate.cancelled.getAsBoolean(),"identity matters even at equal revisions");workspaceGate.release.countDown();
        }stop(workspaceThread.get());
        // Errors remain stable until explicit refresh, with escaped/clipped messages and null-output recovery.
        var calls=new AtomicInteger();var failureThread=new AtomicReference<Thread>();
        try (var review=new AsyncPackReferenceReview((view,cancelled) -> { int n=calls.incrementAndGet();if (n==1) throw new IllegalArgumentException("bad\n"+"x".repeat(2000));if (n==2) return null;if (n==3) throw new IllegalStateException();return PackReferenceAudit.inspect(view,cancelled); },threads(failureThread))) {
            review.refresh(nextPack);var failed=terminal(review,nextPack);check(failed.phase()==AsyncPackReferenceReview.Phase.FAILED && !failed.error().contains("\n") && failed.error().length()<=515,"bounded escaped failure");for (int i=0;i<20;i++) check(review.poll(nextPack)==failed && calls.get()==1,"stable failure does not retry each frame");
            review.refresh(nextPack);check(terminal(review,nextPack).phase()==AsyncPackReferenceReview.Phase.FAILED,"null result fails visibly");review.refresh(nextPack);check(terminal(review,nextPack).error().contains("IllegalStateException"),"message fallback");review.refresh(nextPack);check(terminal(review,nextPack).phase()==AsyncPackReferenceReview.Phase.READY,"explicit refresh recovers");review.clear();check(review.poll(nextPack).phase()==AsyncPackReferenceReview.Phase.IDLE && calls.get()==4,"clear never retries");
        }stop(failureThread.get());
        // Close and clear return immediately even when active scanning ignores cancellation and interruption.
        var blocked=new Gate();var waiting=new Gate();var closeScanner=new Controlled(Map.of(1,blocked,2,waiting));var closeThread=new AtomicReference<Thread>();var closing=new AsyncPackReferenceReview(closeScanner,threads(closeThread));
        closing.refresh(firstPack);await(blocked.started,"close test active");closing.refresh(pack(2));closing.clear();check(closing.poll(firstPack).phase()==AsyncPackReferenceReview.Phase.IDLE && blocked.cancelled.getAsBoolean() && closeThread.get().isAlive(),"clear cancels active/waiting without joining");closing.refresh(pack(2));closing.close();closing.close();check(closing.poll(firstPack).phase()==AsyncPackReferenceReview.Phase.CLOSED && closeThread.get().isAlive() && waiting.started.getCount()==1,"close immediately discards pending/active result");rejects(() -> closing.refresh(firstPack));blocked.release.countDown();stop(closeThread.get());check(waiting.started.getCount()==1 && closing.poll(firstPack).phase()==AsyncPackReferenceReview.Phase.CLOSED,"no publication or waiting work after close");
        System.out.println("PASS: single-worker/latest-pending background resource-link review, immutable bounded JSON/path capture without binary payloads, cooperative cancellation and stale workspace/edit rejection, explicit retry/error limits, incoming filter reuse, nonblocking clear/close and exact Save/Copy/As/ZIP preservation without native preview generation");
    }
}
