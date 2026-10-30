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

final class AsyncJigsawDependencyReviewTests {
    private static final String SOURCE="structures/source.json";
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static void check(boolean valid,String reason) { if (!valid) throw new AssertionError("Async jigsaw review: "+reason); }
    private static void rejects(Runnable action) { try { action.run(); } catch (IllegalArgumentException | IllegalStateException | NullPointerException | UnsupportedOperationException expected) { return; }throw new AssertionError("Expected rejected jigsaw scan operation"); }
    private static byte[] json(int marker) { return bytes("{\"marker\":"+marker+",\"startPool\":\"target\"}"); }
    private static PackWorkspace pack(int marker) throws Exception { return PackWorkspace.newPack(Map.of(SOURCE,json(marker),"jigsaw-pools/target.json",bytes("{\"pieces\":[{\"empty\":true}]}"),"images/opaque.png",new byte[]{0,-1,2},"objects/opaque.iob",new byte[]{0,1,-1},"future/unknown.json",bytes("invalid preserved"))); }
    private static void await(CountDownLatch signal,String reason) throws InterruptedException { check(signal.await(10,TimeUnit.SECONDS),reason); }
    private static AsyncJigsawDependencyReview.Snapshot terminal(AsyncJigsawDependencyReview review,PackWorkspace pack) {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime()<deadline) { var state=review.poll(pack,SOURCE);if (state.phase()!=AsyncJigsawDependencyReview.Phase.SCANNING) return state;LockSupport.parkNanos(1_000_000); }
        throw new AssertionError("Jigsaw scan did not publish a terminal state");
    }
    private static final class Gate {
        final CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1);
        volatile BooleanSupplier cancelled;
    }
    private static final class Controlled implements AsyncJigsawDependencyReview.Scanner {
        final Map<Integer,Gate> gates;final Thread caller=Thread.currentThread();
        final AtomicInteger active=new AtomicInteger(),maximum=new AtomicInteger();
        final List<Integer> calls=java.util.Collections.synchronizedList(new ArrayList<>());
        Controlled(Map<Integer,Gate> gates) { this.gates=gates; }
        public JigsawDependencyReview.Review inspect(PackResourceView source,String root,BooleanSupplier cancelled) {
            check(Thread.currentThread()!=caller && !(source instanceof PackWorkspace),"worker receives only immutable source outside request thread");
            byte[] privateBytes=source.read(SOURCE);int marker=Integer.parseInt(new LosslessJson(privateBytes).token("marker"));privateBytes[0]=0;
            check(source.read(SOURCE)[0]=='{',"snapshot reads are defensive");var gate=gates.get(marker);check(gate!=null,"expected snapshot marker");gate.cancelled=cancelled;
            int count=active.incrementAndGet();maximum.accumulateAndGet(count,Math::max);calls.add(marker);gate.started.countDown();
            try {
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);boolean released=false;
                while (!released && System.nanoTime()<deadline) try { released=gate.release.await(20,TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) { /* Simulate a scanner that ignores cancellation/interruption. */ }
                check(released,"test scan released");return JigsawDependencyReview.inspect(source,root);
            } finally { active.decrementAndGet(); }
        }
    }
    private static java.util.concurrent.ThreadFactory threads(AtomicReference<Thread> reference) { return runnable -> { var thread=new Thread(runnable,"test-jigsaw-review");reference.set(thread);return thread; }; }
    private static void stop(Thread thread) throws InterruptedException { thread.join(10_000);check(!thread.isAlive(),"worker exits after active scanner returns"); }
    private static final class Source implements PackResourceView {
        final TreeMap<String,Integer> sizes=new TreeMap<>();final Map<String,byte[]> content=new TreeMap<>();final List<String> reads=new ArrayList<>();
        public Set<String> paths() { return java.util.Collections.unmodifiableSet(sizes.navigableKeySet()); }
        public int size(String path) { return sizes.get(path); }
        public byte[] read(String path) { reads.add(path);byte[] raw=content.get(path);if (raw==null) throw new AssertionError("Unexpected source read: "+path);return raw.clone(); }
        void json(String path,int size) { sizes.put(path,size);content.put(path,bytes(" ".repeat(size-2)+"{}")); }
    }
    private static void snapshotContracts() throws Exception {
        var source=new Source();source.json(SOURCE,30);source.json("jigsaw-pools/a.json",70);source.json("jigsaw-pools/b.json",20);source.sizes.put("jigsaw-pools/oversized.json",ReferenceDiagnostics.MAX_JSON_BYTES+1);source.sizes.put("objects/opaque.iob",50_000_000);source.sizes.put("images/opaque.png",50_000_000);source.sizes.put("biomes/unrelated.json",50_000_000);
        var snapshot=JigsawScanSource.capture(source,SOURCE,new JigsawScanSource.Limits(2,60,16_000_000));
        check(snapshot.copiedBytes()==50 && snapshot.copiedFiles()==2 && source.reads.equals(List.of(SOURCE,"jigsaw-pools/b.json")),"root first; later small JSON fits after skip; assets/unrelated/oversized not read");
        check(snapshot.paths().equals(source.paths()) && snapshot.size("jigsaw-pools/oversized.json")==ReferenceDiagnostics.MAX_JSON_BYTES+1 && !snapshot.captured("jigsaw-pools/a.json"),"exact presence and skipped source sizes retained");
        rejects(() -> snapshot.read("jigsaw-pools/a.json"));rejects(() -> snapshot.read("objects/opaque.iob"));rejects(() -> snapshot.paths().clear());
        var raw=snapshot.read(SOURCE);raw[0]=0;check(snapshot.read(SOURCE)[0]==' ',"defensive snapshot reads");source.content.get(SOURCE)[0]=0;source.sizes.put("objects/later.iob",1);check(snapshot.read(SOURCE)[0]==' ' && !snapshot.paths().contains("objects/later.iob"),"capture isolates later byte/catalog edits");
        source.reads.clear();rejects(() -> JigsawScanSource.capture(source,SOURCE,new JigsawScanSource.Limits(1,0,1)));check(source.reads.isEmpty(),"catalog overflow fails before payload copy");
        rejects(() -> JigsawScanSource.capture(source,"structures/missing.json"));rejects(() -> JigsawScanSource.capture(source,"objects/opaque.iob"));
        rejects(() -> new JigsawScanSource.Limits(4097,1,1));rejects(() -> new JigsawScanSource.Limits(1,16_000_001,1));rejects(() -> new JigsawScanSource.Limits(1,0,16_000_001));
        var mismatch=new Source();mismatch.json(SOURCE,2);mismatch.sizes.put(SOURCE,3);rejects(() -> JigsawScanSource.capture(mismatch,SOURCE));
        var boundary=new Source();boundary.json(SOURCE,ReferenceDiagnostics.MAX_JSON_BYTES);check(JigsawScanSource.capture(boundary,SOURCE).copiedBytes()==ReferenceDiagnostics.MAX_JSON_BYTES,"exact 1 MB source accepted");
        var fileBound=new Source();fileBound.json(SOURCE,2);for (int i=0;i<4096;i++) fileBound.json("jigsaw-pools/p"+String.format("%04d",i)+".json",2);
        var files=JigsawScanSource.capture(fileBound,SOURCE);check(files.copiedFiles()==4096 && files.captured(SOURCE) && !files.captured("jigsaw-pools/p4095.json") && fileBound.reads.size()==4096,"exact copy file boundary with root priority");
        var byteBound=new Source();byteBound.json(SOURCE,ReferenceDiagnostics.MAX_JSON_BYTES);for (int i=0;i<16;i++) byteBound.json("jigsaw-pools/p"+i+".json",ReferenceDiagnostics.MAX_JSON_BYTES);
        var bytes=JigsawScanSource.capture(byteBound,SOURCE);check(bytes.copiedBytes()==16_000_000 && bytes.copiedFiles()==16 && byteBound.reads.size()==16,"exact total copy byte boundary");
        var negative=new Source();negative.sizes.put(SOURCE,-1);rejects(() -> JigsawScanSource.capture(negative,SOURCE));
        var catalog=new Source();catalog.json(SOURCE,2);for (int i=1;i<PackWorkspace.MAX_FILES;i++) catalog.sizes.put("objects/"+i+".iob",1);check(JigsawScanSource.capture(catalog,SOURCE).paths().size()==PackWorkspace.MAX_FILES,"exact 100,000-path bound");catalog.sizes.put("objects/extra.iob",1);catalog.reads.clear();rejects(() -> JigsawScanSource.capture(catalog,SOURCE));check(catalog.reads.isEmpty(),"catalog file overflow before copy");
        var character=new Source();character.json(SOURCE,2);check(JigsawScanSource.capture(character,SOURCE,new JigsawScanSource.Limits(1,2,SOURCE.length())).copiedBytes()==2,"exact path character budget");rejects(() -> JigsawScanSource.capture(character,SOURCE,new JigsawScanSource.Limits(1,2,SOURCE.length()-1)));
        // Omitted reached JSON stays an exact-file link and a visited skipped node, never a false missing target.
        var pack=pack(1);var omitted=JigsawDependencyReview.inspect(JigsawScanSource.capture(pack,SOURCE,new JigsawScanSource.Limits(1,1000,16_000_000)),SOURCE);
        check(omitted.incomplete() && omitted.jsonFiles()==1 && omitted.nodes().size()==2 && omitted.nodes().getLast().status()==JigsawDependencyReview.Status.SKIPPED && omitted.edges().getFirst().state()==ReferenceDiagnostics.State.EXACT_FILE && omitted.notices().getFirst().message().contains("snapshot"),"explicit snapshot omission without partial target parsing or false absence");
        check(JigsawDependencyReview.inspect(JigsawScanSource.capture(pack,SOURCE),SOURCE).equals(JigsawDependencyReview.inspect(pack,SOURCE)),"complete snapshot equals direct graph");
        var graph=PackWorkspace.newPack(Map.of(SOURCE,bytes("{\"startPool\":\"target\"}"),"jigsaw-pools/target.json",bytes("{\"pieces\":[{\"piece\":\"p\"}]}"),"jigsaw-pieces/p.json",bytes("{\"object\":\"opaque\"}"),"objects/opaque.iob",new byte[]{0,-1}));
        var graphReview=JigsawDependencyReview.inspect(JigsawScanSource.capture(graph,SOURCE),SOURCE);check(graphReview.nodes().getLast().status()==JigsawDependencyReview.Status.OBJECT_PRESENT && graphReview.nodes().size()==4,"object exact presence without payload capture");
        source.reads.clear();try { JigsawDependencyReview.inspect(source,SOURCE,() -> true);throw new AssertionError(); } catch (java.util.concurrent.CancellationException expected) { check(source.reads.isEmpty(),"cancel before any source read"); }
        var cancel=new Source();cancel.json(SOURCE,2);try { JigsawDependencyReview.inspect(cancel,SOURCE,() -> !cancel.reads.isEmpty());throw new AssertionError(); } catch (java.util.concurrent.CancellationException expected) { check(cancel.reads.size()==1,"cancel after final parse"); }
    }
    static void run(Path temp) throws Exception {
        snapshotContracts();
        // Default background implementation preserves the established BFS, cycle groups and routes.
        var cyclic=PackWorkspace.newPack(JigsawDependencyReviewTests.fixture());
        for (String root : List.of("structures/root.json","jigsaw-pools/main.json","jigsaw-pieces/start.json")) {
            try (var review=new AsyncJigsawDependencyReview()) {
                review.refresh(cyclic,root);long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);AsyncJigsawDependencyReview.Snapshot state;
                do { state=review.poll(cyclic,root);if (state.phase()==AsyncJigsawDependencyReview.Phase.SCANNING) LockSupport.parkNanos(1_000_000); } while (state.phase()==AsyncJigsawDependencyReview.Phase.SCANNING && System.nanoTime()<deadline);
                check(state.phase()==AsyncJigsawDependencyReview.Phase.READY && state.review().equals(JigsawDependencyReview.inspect(cyclic,root)),"actual worker preserves complete cyclic graph for "+root);
            }
        }
        // A active + B waiting + C refreshed runs A,C only, and the stale A audit cannot publish.
        var one=new Gate();var two=new Gate();var three=new Gate();var controlled=new Controlled(Map.of(1,one,2,two,3,three));var thread=new AtomicReference<Thread>();var pack=pack(1);pack.save(temp.resolve("async-jigsaw-source"));var drafts=new InspectorDrafts();drafts.update("late","stored","draft");
        try (var review=new AsyncJigsawDependencyReview(controlled,threads(thread))) {
            check(review.poll(pack,SOURCE).phase()==AsyncJigsawDependencyReview.Phase.IDLE && controlled.calls.isEmpty(),"opening/polling does not scan automatically");
            var first=review.refresh(pack,SOURCE);await(one.started,"first scan starts");check(first.phase()==AsyncJigsawDependencyReview.Phase.SCANNING && thread.get().isDaemon(),"request returns while daemon scan is blocked");
            pack.saveCopy(temp.resolve("async-jigsaw-loading-copy"));check(review.poll(pack,SOURCE).phase()==AsyncJigsawDependencyReview.Phase.SCANNING && drafts.any(),"Save Copy and inspector drafts independent while scanning");
            pack.replace(SOURCE,json(2));check(review.poll(pack,SOURCE).phase()==AsyncJigsawDependencyReview.Phase.IDLE && one.cancelled.getAsBoolean() && controlled.calls.equals(List.of(1)),"applied edit invalidates without rescanning");var second=review.refresh(pack,SOURCE);pack.replace(SOURCE,json(3));var latest=review.refresh(pack,SOURCE);
            check(latest.request()>second.request() && latest.review()==null,"refresh hides prior results immediately");one.release.countDown();await(three.started,"latest waiting request replaces intermediate request");check(two.started.getCount()==1 && review.poll(pack,SOURCE).phase()==AsyncJigsawDependencyReview.Phase.SCANNING,"stale ignored-cancellation result discarded");three.release.countDown();var ready=terminal(review,pack);
            check(ready.phase()==AsyncJigsawDependencyReview.Phase.READY && ready.review().equals(JigsawDependencyReview.inspect(pack,SOURCE)) && controlled.maximum.get()==1 && controlled.calls.equals(List.of(1,3)),"same exact review with one active scanner");
            var query=new JigsawDependencyReview.Query("",JigsawDependencyReview.Scope.INCOMING,"jigsaw-pools/target.json",false);var result=review.query(pack,SOURCE,query);check(result.matches()==1 && review.query(pack,SOURCE,query)==result,"incoming filter cached without rescanning");
            long revision=pack.revision(),resources=pack.resourceRevision();int history=pack.undoCount();pack.saveCopy(temp.resolve("async-jigsaw-copy"));pack.saveAs(temp.resolve("async-jigsaw-as"));pack.save(pack.directory());pack.exportZip(temp.resolve("async-jigsaw.zip"));
            check(review.poll(pack,SOURCE)==ready && review.query(pack,SOURCE,query)==result && pack.revision()==revision && pack.resourceRevision()==resources && pack.undoCount()==history && drafts.any(),"Save/Copy/As/ZIP preserve ready scan, history, query and drafts");
            var copy=PackWorkspace.open(temp.resolve("async-jigsaw-copy"));var zip=PackWorkspace.importZip(temp.resolve("async-jigsaw.zip"));for (String path : pack.paths()) check(Arrays.equals(pack.read(path),copy.read(path)) && Arrays.equals(pack.read(path),zip.read(path)),"exact persistence "+path);
            pack.undo();check(review.poll(pack,SOURCE).phase()==AsyncJigsawDependencyReview.Phase.IDLE && review.query(pack,SOURCE,query)==null,"Undo invalidation without rescan");pack.redo();check(review.poll(pack,SOURCE).phase()==AsyncJigsawDependencyReview.Phase.IDLE && controlled.calls.size()==2,"Redo does not revive prior results");
        }stop(thread.get());
        // Same revision and same source path in another workspace still invalidate completed or active work.
        var original=pack(1);var captured=JigsawScanSource.capture(original,SOURCE);original.replace(SOURCE,json(2));original.duplicate("objects/opaque.iob","new");check(!captured.paths().contains("objects/new.iob") && new LosslessJson(captured.read(SOURCE)).token("marker").equals("1"),"live workspace changes cannot alter captured bytes/catalog");
        var workspaceGate=new Gate();var workspaceThread=new AtomicReference<Thread>();var workspaceScanner=new Controlled(Map.of(1,workspaceGate));var firstPack=pack(1);var nextPack=pack(1);
        try (var review=new AsyncJigsawDependencyReview(workspaceScanner,threads(workspaceThread))) {
            review.refresh(firstPack,SOURCE);await(workspaceGate.started,"workspace isolation active");check(firstPack.revision()==nextPack.revision() && review.poll(nextPack,SOURCE).phase()==AsyncJigsawDependencyReview.Phase.IDLE && workspaceGate.cancelled.getAsBoolean(),"identity matters even at equal revisions");workspaceGate.release.countDown();
        }stop(workspaceThread.get());
        var rootGate=new Gate();var rootThread=new AtomicReference<Thread>();var rootScanner=new Controlled(Map.of(1,rootGate));
        try (var review=new AsyncJigsawDependencyReview(rootScanner,threads(rootThread))) {
            review.refresh(firstPack,SOURCE);await(rootGate.started,"root test starts");
            check(review.poll(firstPack,"jigsaw-pools/target.json").phase()==AsyncJigsawDependencyReview.Phase.IDLE && rootGate.cancelled.getAsBoolean(),"pinned root change cancels without rescanning");rootGate.release.countDown();
        }stop(rootThread.get());
        var captureThread=new AtomicReference<Thread>();var captureCalls=new AtomicInteger();
        try (var review=new AsyncJigsawDependencyReview((view,root,cancelled) -> { captureCalls.incrementAndGet();return JigsawDependencyReview.inspect(view,root,cancelled); },threads(captureThread))) {
            check(review.refresh(firstPack,"structures/missing.json").phase()==AsyncJigsawDependencyReview.Phase.FAILED && captureCalls.get()==0,"capture failure is visible without worker work");
            check(review.poll(firstPack,"structures/missing.json").phase()==AsyncJigsawDependencyReview.Phase.FAILED && captureCalls.get()==0,"capture failure stable until refresh");
            review.refresh(firstPack,SOURCE);check(terminal(review,firstPack).phase()==AsyncJigsawDependencyReview.Phase.READY,"explicit capture retry recovers");
        }stop(captureThread.get());
        // Errors remain stable until explicit refresh, with escaped/clipped messages and null-output recovery.
        var calls=new AtomicInteger();var failureThread=new AtomicReference<Thread>();
        try (var review=new AsyncJigsawDependencyReview((view,root,cancelled) -> { int n=calls.incrementAndGet();if (n==1) throw new IllegalArgumentException("bad\n"+"x".repeat(2000));if (n==2) return null;if (n==3) throw new IllegalStateException();return JigsawDependencyReview.inspect(view,root,cancelled); },threads(failureThread))) {
            review.refresh(nextPack,SOURCE);var failed=terminal(review,nextPack);check(failed.phase()==AsyncJigsawDependencyReview.Phase.FAILED && !failed.error().contains("\n") && failed.error().length()<=515,"bounded escaped failure");for (int i=0;i<20;i++) check(review.poll(nextPack,SOURCE)==failed && calls.get()==1,"stable failure does not retry each frame");
            review.refresh(nextPack,SOURCE);check(terminal(review,nextPack).phase()==AsyncJigsawDependencyReview.Phase.FAILED,"null result fails visibly");review.refresh(nextPack,SOURCE);check(terminal(review,nextPack).error().contains("IllegalStateException"),"message fallback");review.refresh(nextPack,SOURCE);check(terminal(review,nextPack).phase()==AsyncJigsawDependencyReview.Phase.READY,"explicit refresh recovers");review.clear();check(review.poll(nextPack,SOURCE).phase()==AsyncJigsawDependencyReview.Phase.IDLE && calls.get()==4,"clear never retries");
        }stop(failureThread.get());
        // Close and clear return immediately even when active scanning ignores cancellation and interruption.
        var blocked=new Gate();var waiting=new Gate();var closeScanner=new Controlled(Map.of(1,blocked,2,waiting));var closeThread=new AtomicReference<Thread>();var closing=new AsyncJigsawDependencyReview(closeScanner,threads(closeThread));
        closing.refresh(firstPack,SOURCE);await(blocked.started,"close test active");closing.refresh(pack(2),SOURCE);closing.clear();check(closing.poll(firstPack,SOURCE).phase()==AsyncJigsawDependencyReview.Phase.IDLE && blocked.cancelled.getAsBoolean() && closeThread.get().isAlive(),"clear cancels active/waiting without joining");closing.refresh(pack(2),SOURCE);closing.close();closing.close();check(closing.poll(firstPack,SOURCE).phase()==AsyncJigsawDependencyReview.Phase.CLOSED && closeThread.get().isAlive() && waiting.started.getCount()==1,"close immediately discards pending/active result");rejects(() -> closing.refresh(firstPack,SOURCE));blocked.release.countDown();stop(closeThread.get());check(waiting.started.getCount()==1 && closing.poll(firstPack,SOURCE).phase()==AsyncJigsawDependencyReview.Phase.CLOSED,"no publication or waiting work after close");
        System.out.println("PASS: single-worker/latest-pending background jigsaw dependency review, root-first bounded immutable JSON snapshots with exact target presence and explicit omitted-source notices, metadata-only objects, cooperative cancellation, stale workspace/root/edit rejection, stable errors and explicit retry, filter reuse, nonblocking clear/close and exact Save/Copy/As/ZIP preservation without native assembly");
    }
}
