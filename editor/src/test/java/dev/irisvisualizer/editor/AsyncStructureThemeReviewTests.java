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

final class AsyncStructureThemeReviewTests {
    private static final String SOURCE="jigsaw-pieces/source.json";
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static void check(boolean valid,String reason) { if (!valid) throw new AssertionError("Async piece-theme review: "+reason); }
    private static void rejects(Runnable action) { try { action.run(); } catch (IllegalArgumentException | IllegalStateException | NullPointerException | UnsupportedOperationException expected) { return; }throw new AssertionError("Expected rejected piece-theme review operation"); }
    private static byte[] json(int marker) { return bytes("{\"marker\":"+marker+",\"themes\":[\"Hot\",\"\\u0048ot\",\"hot\",\"雪\"]}"); }
    private static PackWorkspace pack(int marker) throws Exception { return PackWorkspace.newPack(Map.of(SOURCE,json(marker),"jigsaw-pieces/target.json",bytes("{\"themes\":[\"Hot\"]}"),"images/opaque.png",new byte[]{0,-1,2},"objects/opaque.iob",new byte[]{0,1,-1},"future/unknown.json",bytes("invalid preserved"))); }
    private static void await(CountDownLatch signal,String reason) throws InterruptedException { check(signal.await(10,TimeUnit.SECONDS),reason); }
    private static AsyncStructureThemeReview.Snapshot terminal(AsyncStructureThemeReview review,PackWorkspace pack) {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime()<deadline) { var state=review.poll(pack);if (state.phase()!=AsyncStructureThemeReview.Phase.SCANNING) return state;LockSupport.parkNanos(1_000_000); }
        throw new AssertionError("Piece-theme scan did not publish a terminal state");
    }
    private static final class Gate {
        final CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1);
        volatile BooleanSupplier cancelled;
    }
    private static final class Controlled implements AsyncStructureThemeReview.Scanner {
        final Map<Integer,Gate> gates;final Thread caller=Thread.currentThread();
        final AtomicInteger active=new AtomicInteger(),maximum=new AtomicInteger();
        final List<Integer> calls=java.util.Collections.synchronizedList(new ArrayList<>());
        Controlled(Map<Integer,Gate> gates) { this.gates=gates; }
        public StructureThemeReferences.Review inspect(PackResourceView source,String key,BooleanSupplier cancelled) {
            check(Thread.currentThread()!=caller && !(source instanceof PackWorkspace),"worker receives only immutable source outside request thread");
            byte[] privateBytes=source.read(SOURCE);int marker=Integer.parseInt(new LosslessJson(privateBytes).token("marker"));privateBytes[0]=0;
            check(source.read(SOURCE)[0]=='{',"snapshot reads are defensive");var gate=gates.get(marker);check(gate!=null,"expected snapshot marker");gate.cancelled=cancelled;
            int count=active.incrementAndGet();maximum.accumulateAndGet(count,Math::max);calls.add(marker);gate.started.countDown();
            try {
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);boolean released=false;
                while (!released && System.nanoTime()<deadline) try { released=gate.release.await(20,TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) { /* Simulate a scanner that ignores cancellation/interruption. */ }
                check(released,"test scan released");return StructureThemeReferences.inspect(source,key);
            } finally { active.decrementAndGet(); }
        }
    }
    private static java.util.concurrent.ThreadFactory threads(AtomicReference<Thread> reference) { return runnable -> { var thread=new Thread(runnable,"test-theme-review");reference.set(thread);return thread; }; }
    private static void stop(Thread thread) throws InterruptedException { thread.join(10_000);check(!thread.isAlive(),"worker exits after active scanner returns"); }
    private static final class Source implements PackResourceView {
        final TreeMap<String,Integer> sizes=new TreeMap<>();final Map<String,byte[]> content=new TreeMap<>();final List<String> reads=new ArrayList<>();
        public Set<String> paths() { return java.util.Collections.unmodifiableSet(sizes.navigableKeySet()); }
        public int size(String path) { return sizes.get(path); }
        public byte[] read(String path) { reads.add(path);byte[] raw=content.get(path);if (raw==null) throw new AssertionError("Unexpected source read: "+path);return raw.clone(); }
        void json(String path,int size) { sizes.put(path,size);content.put(path,bytes(" ".repeat(size-2)+"{}")); }
    }
    private static void snapshotContracts() throws Exception {
        var source=new Source();source.json(SOURCE,60);source.json("jigsaw-pieces/t.json",70);source.json("jigsaw-pieces/u.json",30);source.sizes.put("jigsaw-pieces/v.json",1_000_001);source.json("jigsaw-pieces/z.json",2);source.sizes.put("objects/binary.iob",50_000_000);source.sizes.put("images/binary.png",50_000_000);source.sizes.put("structures/unknown.json",50_000_000);source.sizes.put("jigsaw-pieces/UPPER.JSON",50_000_000);
        var snapshot=ThemeReferenceSource.capture(source,new ThemeReferenceSource.Limits(4,100,16_000_000));
        check(snapshot.copiedBytes()==90 && snapshot.copiedFiles()==2 && source.reads.equals(List.of(SOURCE,"jigsaw-pieces/u.json")),"eligible sorted candidates only; oversize/byte-budget skip without reads; later small file fits");
        check(snapshot.paths().size()==5 && snapshot.size("jigsaw-pieces/v.json")==1_000_001 && !snapshot.paths().contains("objects/binary.iob"),"only eligible paths retained, oversize size metadata preserved");
        var limits=new StructureThemeReferences.Limits(4,100,256,32);check(StructureThemeReferences.inspect(snapshot,"Hot",limits).equals(StructureThemeReferences.inspect(source,"Hot",limits)),"exact scanner receipts without metadata reads beyond captured file budget");
        rejects(() -> snapshot.read("jigsaw-pieces/t.json"));rejects(() -> snapshot.size("jigsaw-pieces/z.json"));rejects(() -> snapshot.read("objects/binary.iob"));rejects(() -> snapshot.paths().clear());
        snapshot.read(SOURCE)[0]=0;source.content.get(SOURCE)[0]=0;source.sizes.put("jigsaw-pieces/later.json",2);check(snapshot.read(SOURCE)[0]==' ' && !snapshot.paths().contains("jigsaw-pieces/later.json"),"defensive reads and later byte/catalog isolation");
        source.reads.clear();rejects(() -> ThemeReferenceSource.capture(source,new ThemeReferenceSource.Limits(4,100,1)));check(source.reads.isEmpty(),"catalog overflow before payload copies");
        rejects(() -> new ThemeReferenceSource.Limits(257,100,100));rejects(() -> new ThemeReferenceSource.Limits(1,8_000_001,100));rejects(() -> new ThemeReferenceSource.Limits(1,0,16_000_001));
        var unrelated=new Source();unrelated.json(SOURCE,2);unrelated.sizes.put("objects/"+"x".repeat(2000)+".iob",50_000_000);check(ThemeReferenceSource.capture(unrelated,new ThemeReferenceSource.Limits(1,2,SOURCE.length())).copiedBytes()==2 && unrelated.reads.equals(List.of(SOURCE)),"unrelated catalog does not consume eligible path-character budget");rejects(() -> ThemeReferenceSource.capture(unrelated,new ThemeReferenceSource.Limits(1,2,SOURCE.length()-1)));
        var mismatch=new Source();mismatch.json(SOURCE,2);mismatch.sizes.put(SOURCE,3);rejects(() -> ThemeReferenceSource.capture(mismatch));var negative=new Source();negative.sizes.put(SOURCE,-1);rejects(() -> ThemeReferenceSource.capture(negative));
        var boundary=new Source();boundary.json(SOURCE,1_000_000);check(ThemeReferenceSource.capture(boundary).copiedBytes()==1_000_000,"exact per-source limit");
        var bytesBound=new Source();for (int i=0;i<9;i++) bytesBound.json("jigsaw-pieces/b"+i+".json",1_000_000);check(ThemeReferenceSource.capture(bytesBound).copiedBytes()==8_000_000 && bytesBound.reads.size()==8,"exact 8 MB copied-byte boundary");
        var catalog=new Source();for (int i=0;i<PackWorkspace.MAX_FILES;i++) { String path="jigsaw-pieces/p"+String.format("%06d",i)+".json";catalog.sizes.put(path,2);if (i<256) catalog.content.put(path,bytes("{}")); }var captured=ThemeReferenceSource.capture(catalog);check(captured.paths().size()==100_000 && captured.copiedFiles()==256 && catalog.reads.size()==256,"exact catalog and copied-file boundaries");catalog.sizes.put("jigsaw-pieces/z_extra.json",2);catalog.reads.clear();rejects(() -> ThemeReferenceSource.capture(catalog));check(catalog.reads.isEmpty(),"catalog file overflow before copying");
        // Every match within the full 256-file scan is retained and reachable, including after page one.
        var many=new TreeMap<String,byte[]>();for (int i=0;i<257;i++) many.put("jigsaw-pieces/p"+String.format("%03d",i)+".json",bytes("{\"themes\":[\"Hot\",\"\\u0048ot\",\"hot\",\"雪\"]}"));var pack=PackWorkspace.newPack(many);var review=StructureThemeReferences.inspect(ThemeReferenceSource.capture(pack),"Hot");
        check(review.eligible()==257 && review.inspected()==256 && review.skipped()==1 && review.matchedFiles()==256 && review.hits().size()==256 && !review.truncated() && review.incomplete() && review.hits().getLast().path().endsWith("p255.json") && review.hits().getFirst().occurrences()==2,"256-hit retention, file overflow and decoded exact case/escape counting");
        var pages=new StructureThemeReferences.PageCache();var first=pages.page(review,"",Integer.MIN_VALUE);check(first.page()==0 && first.matches()==256 && first.hits().size()==200 && first.next() && !first.previous(),"first immutable hit page");var last=pages.page(review,"",Integer.MAX_VALUE);check(last.page()==1 && last.hits().size()==56 && !last.next() && last.previous(),"last clamped page exposes hits after 200");
        check(pages.page(review," P255.JSON ",0).matches()==1 && pages.page(review,"absent",Integer.MAX_VALUE).page()==0 && pages.page(review,"absent",0).hits().isEmpty(),"case-insensitive retained-path filtering and empty clamped pages");rejects(() -> first.hits().clear());rejects(() -> last.hits().clear());rejects(() -> pages.page(review,"bad\0",0));rejects(() -> pages.page(review,"x".repeat(257),0));
        check(StructureThemeReferences.inspect(pack,"hot").hits().getFirst().occurrences()==1 && StructureThemeReferences.inspect(pack,"雪").hits().size()==256 && StructureThemeReferences.inspect(pack," Hot ").hits().isEmpty(),"review keys remain case-sensitive, Unicode-exact and untrimmed");pages.clear();check(pages.page(StructureThemeReferences.inspect(PackWorkspace.empty(),"Hot"),"",0).matches()==0,"review identity/clear isolation");
        source.reads.clear();try { StructureThemeReferences.inspect(source,"Hot",() -> true);throw new AssertionError(); } catch (java.util.concurrent.CancellationException expected) { check(source.reads.isEmpty(),"pre-read cancellation"); }
        var cancel=new Source();cancel.json(SOURCE,2);try { StructureThemeReferences.inspect(cancel,"Hot",() -> !cancel.reads.isEmpty());throw new AssertionError(); } catch (java.util.concurrent.CancellationException expected) { check(cancel.reads.size()==1,"post-parse cancellation"); }
        var dense=PackWorkspace.newPack(Map.of(SOURCE,bytes("{\"themes\":["+String.join(",",java.util.Collections.nCopies(1000,"\"Hot\""))+"]}")));var calls=new AtomicInteger();try { StructureThemeReferences.inspect(dense,"Hot",() -> calls.incrementAndGet()>=6);throw new AssertionError(); } catch (java.util.concurrent.CancellationException expected) { check(calls.get()==6,"cooperative cancellation within a dense tag list"); }
    }
    private static void keyReplacement() throws Exception {
        var pack=pack(1);var hot=new Gate();var lower=new Gate();var unicode=new Gate();var gates=Map.of("Hot",hot,"hot",lower,"雪",unicode);var calls=java.util.Collections.synchronizedList(new ArrayList<String>());var thread=new AtomicReference<Thread>();
        try (var review=new AsyncStructureThemeReview((source,key,cancelled) -> {
            calls.add(key);var gate=gates.get(key);gate.cancelled=cancelled;gate.started.countDown();long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);boolean done=false;
            while (!done && System.nanoTime()<deadline) try { done=gate.release.await(20,TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) { }
            check(done,"key scan released");return StructureThemeReferences.inspect(source,key);
        },threads(thread))) {
            review.refresh(pack,"Hot");await(hot.started,"Hot key starts");var before=review.poll(pack);rejects(() -> review.refresh(pack,""));rejects(() -> review.refresh(pack,"bad\0"));check(review.poll(pack)==before && !hot.cancelled.getAsBoolean(),"invalid keys do not cancel/replace a valid active request");
            review.refresh(pack,"hot");review.refresh(pack,"雪");check(hot.cancelled.getAsBoolean() && "雪".equals(review.poll(pack).key()),"new exact key cancels active request and pins latest key");hot.release.countDown();await(unicode.started,"latest key starts");check(lower.started.getCount()==1 && review.poll(pack).phase()==AsyncStructureThemeReview.Phase.SCANNING,"intermediate key and stale ignored-cancellation output discarded");unicode.release.countDown();var ready=terminal(review,pack);check(ready.review().key().equals("雪") && ready.key().equals("雪") && calls.equals(List.of("Hot","雪")),"immutable job key paired with matching result");rejects(() -> review.refresh(pack,"x".repeat(257)));check(review.poll(pack)==ready,"invalid replacement keeps ready results");
        }stop(thread.get());
        try (var actual=new AsyncStructureThemeReview()) { actual.refresh(pack,"Hot");check(terminal(actual,pack).review().equals(StructureThemeReferences.inspect(pack,"Hot")),"production constructor worker parity"); }
        var huge=new TreeMap<String,byte[]>();for (int i=0;i<1200;i++) huge.put("jigsaw-pieces/"+"p".repeat(14_000)+i+".json",bytes("{}"));var oversized=PackWorkspace.newPack(huge);var count=new AtomicInteger();var captureThread=new AtomicReference<Thread>();
        try (var review=new AsyncStructureThemeReview((source,key,cancelled) -> { count.incrementAndGet();return StructureThemeReferences.inspect(source,key,cancelled); },threads(captureThread))) {
            var failed=review.refresh(oversized,"Hot");check(failed.phase()==AsyncStructureThemeReview.Phase.FAILED && failed.key().equals("Hot") && count.get()==0 && review.poll(oversized)==failed,"capture failure visible/stable without scanning");review.refresh(pack,"Hot");check(terminal(review,pack).phase()==AsyncStructureThemeReview.Phase.READY,"explicit capture retry recovers");
        }stop(captureThread.get());
    }
    static void run(Path temp) throws Exception {
        snapshotContracts();
        keyReplacement();
        // A active + B waiting + C refreshed runs A,C only, and the stale A audit cannot publish.
        var one=new Gate();var two=new Gate();var three=new Gate();var controlled=new Controlled(Map.of(1,one,2,two,3,three));var thread=new AtomicReference<Thread>();var pack=pack(1);pack.save(temp.resolve("async-theme-source"));var drafts=new InspectorDrafts();drafts.update("late","stored","draft");
        try (var review=new AsyncStructureThemeReview(controlled,threads(thread))) {
            check(review.poll(pack).phase()==AsyncStructureThemeReview.Phase.IDLE && controlled.calls.isEmpty(),"opening/polling does not scan automatically");
            var first=review.refresh(pack,"Hot");await(one.started,"first scan starts");check(first.phase()==AsyncStructureThemeReview.Phase.SCANNING && thread.get().isDaemon(),"request returns while daemon scan is blocked");
            pack.saveCopy(temp.resolve("async-theme-loading-copy"));check(review.poll(pack).phase()==AsyncStructureThemeReview.Phase.SCANNING && drafts.any(),"Save Copy and inspector drafts independent while scanning");
            pack.replace(SOURCE,json(2));check(review.poll(pack).phase()==AsyncStructureThemeReview.Phase.IDLE && "Hot".equals(review.poll(pack).key()) && one.cancelled.getAsBoolean() && controlled.calls.equals(List.of(1)),"applied edit invalidates without rescanning");var second=review.refresh(pack,"Hot");pack.replace(SOURCE,json(3));var latest=review.refresh(pack,"Hot");
            check(latest.request()>second.request() && latest.review()==null,"refresh hides prior results immediately");one.release.countDown();await(three.started,"latest waiting request replaces intermediate request");check(two.started.getCount()==1 && review.poll(pack).phase()==AsyncStructureThemeReview.Phase.SCANNING,"stale ignored-cancellation result discarded");three.release.countDown();var ready=terminal(review,pack);
            check(ready.phase()==AsyncStructureThemeReview.Phase.READY && ready.review().equals(StructureThemeReferences.inspect(pack,"Hot")) && controlled.maximum.get()==1 && controlled.calls.equals(List.of(1,3)),"same exact review with one active scanner");
            var pages=new StructureThemeReferences.PageCache();var result=pages.page(ready.review(),"target",0);check(result.matches()==1 && pages.page(review.poll(pack).review(),"target",0).equals(result),"path filtering without rescanning");
            long revision=pack.revision(),resources=pack.resourceRevision();int history=pack.undoCount();pack.saveCopy(temp.resolve("async-theme-copy"));pack.saveAs(temp.resolve("async-theme-as"));pack.save(pack.directory());pack.exportZip(temp.resolve("async-theme.zip"));
            check(review.poll(pack)==ready && pages.page(review.poll(pack).review(),"target",0).equals(result) && pack.revision()==revision && pack.resourceRevision()==resources && pack.undoCount()==history && drafts.any(),"Save/Copy/As/ZIP preserve ready scan, history, query and drafts");
            var copy=PackWorkspace.open(temp.resolve("async-theme-copy"));var zip=PackWorkspace.importZip(temp.resolve("async-theme.zip"));for (String path : pack.paths()) check(Arrays.equals(pack.read(path),copy.read(path)) && Arrays.equals(pack.read(path),zip.read(path)),"exact persistence "+path);
            pack.undo();check(review.poll(pack).phase()==AsyncStructureThemeReview.Phase.IDLE,"Undo invalidation without rescan");pack.redo();check(review.poll(pack).phase()==AsyncStructureThemeReview.Phase.IDLE && controlled.calls.size()==2,"Redo does not revive prior results");
        }stop(thread.get());
        // Same revision and same source path in another workspace still invalidate completed or active work.
        var original=pack(1);var captured=ThemeReferenceSource.capture(original);original.replace(SOURCE,json(2));original.duplicate(SOURCE,"new");check(!captured.paths().contains("jigsaw-pieces/new.json") && new LosslessJson(captured.read(SOURCE)).token("marker").equals("1"),"live workspace changes cannot alter captured bytes/catalog");
        var workspaceGate=new Gate();var workspaceThread=new AtomicReference<Thread>();var workspaceScanner=new Controlled(Map.of(1,workspaceGate));var firstPack=pack(1);var nextPack=pack(1);
        try (var review=new AsyncStructureThemeReview(workspaceScanner,threads(workspaceThread))) {
            review.refresh(firstPack,"Hot");await(workspaceGate.started,"workspace isolation active");check(firstPack.revision()==nextPack.revision() && review.poll(nextPack).phase()==AsyncStructureThemeReview.Phase.IDLE && review.poll(nextPack).key()==null && workspaceGate.cancelled.getAsBoolean(),"identity matters even at equal revisions");workspaceGate.release.countDown();
        }stop(workspaceThread.get());
        // Errors remain stable until explicit refresh, with escaped/clipped messages and null-output recovery.
        var calls=new AtomicInteger();var failureThread=new AtomicReference<Thread>();
        try (var review=new AsyncStructureThemeReview((view,key,cancelled) -> { int n=calls.incrementAndGet();if (n==1) throw new IllegalArgumentException("bad\n"+"x".repeat(2000));if (n==2) return null;if (n==3) throw new IllegalStateException();return StructureThemeReferences.inspect(view,key,cancelled); },threads(failureThread))) {
            review.refresh(nextPack,"Hot");var failed=terminal(review,nextPack);check(failed.phase()==AsyncStructureThemeReview.Phase.FAILED && !failed.error().contains("\n") && failed.error().length()<=515,"bounded escaped failure");for (int i=0;i<20;i++) check(review.poll(nextPack)==failed && calls.get()==1,"stable failure does not retry each frame");
            review.refresh(nextPack,"Hot");check(terminal(review,nextPack).phase()==AsyncStructureThemeReview.Phase.FAILED,"null result fails visibly");review.refresh(nextPack,"Hot");check(terminal(review,nextPack).error().contains("IllegalStateException"),"message fallback");review.refresh(nextPack,"Hot");check(terminal(review,nextPack).phase()==AsyncStructureThemeReview.Phase.READY,"explicit refresh recovers");review.clear();check(review.poll(nextPack).phase()==AsyncStructureThemeReview.Phase.IDLE && calls.get()==4,"clear never retries");
        }stop(failureThread.get());
        // Close and clear return immediately even when active scanning ignores cancellation and interruption.
        var blocked=new Gate();var waiting=new Gate();var closeScanner=new Controlled(Map.of(1,blocked,2,waiting));var closeThread=new AtomicReference<Thread>();var closing=new AsyncStructureThemeReview(closeScanner,threads(closeThread));
        closing.refresh(firstPack,"Hot");await(blocked.started,"close test active");closing.refresh(pack(2),"Hot");closing.clear();check(closing.poll(firstPack).phase()==AsyncStructureThemeReview.Phase.IDLE && blocked.cancelled.getAsBoolean() && closeThread.get().isAlive(),"clear cancels active/waiting without joining");closing.refresh(pack(2),"Hot");closing.close();closing.close();check(closing.poll(firstPack).phase()==AsyncStructureThemeReview.Phase.CLOSED && closeThread.get().isAlive() && waiting.started.getCount()==1,"close immediately discards pending/active result");rejects(() -> closing.refresh(firstPack,"Hot"));blocked.release.countDown();stop(closeThread.get());check(waiting.started.getCount()==1 && closing.poll(firstPack).phase()==AsyncStructureThemeReview.Phase.CLOSED,"no publication or waiting work after close");
        System.out.println("PASS: background pinned-key piece-theme review, bounded immutable eligible-only snapshots with exact file/byte/path limits and direct-scan parity, exact decoded case/escape/Unicode tags, all 256 retained hits with searchable immutable 200-row pages, cooperative cancellation and stale key/workspace/edit rejection, latest-pending/noncooperative work, stable errors/capture failures/explicit recovery, nonblocking clear/close, draft-safe navigation and exact Save/Copy/As/ZIP preservation without graph eligibility or native generation");
    }
}
