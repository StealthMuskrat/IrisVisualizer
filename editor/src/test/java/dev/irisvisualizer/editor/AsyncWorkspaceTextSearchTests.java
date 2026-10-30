package dev.irisvisualizer.editor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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

final class AsyncWorkspaceTextSearchTests {
    private static final String SOURCE="expressions/source.json";
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static void check(boolean valid,String reason) { if (!valid) throw new AssertionError("Async text search: "+reason); }
    private static void rejects(Runnable action) { try { action.run(); } catch (IllegalArgumentException | IllegalStateException | NullPointerException | UnsupportedOperationException expected) { return; }throw new AssertionError("Expected rejected search operation"); }
    private static WorkspaceTextSearch.Query query(String text) { return new WorkspaceTextSearch.Query(text,ResourceFinder.Family.ALL,false); }
    private static byte[] json(int marker) { return bytes("{\r\n\"marker\":"+marker+",\"text\":\"雪😀 Needle Needle\",\"escape\":\"\\u004e\"\r\n}"); }
    private static PackWorkspace pack(int marker) throws Exception { return PackWorkspace.newPack(Map.of(SOURCE,json(marker),"future/unknown.JSON",bytes("malformed Needle"),"future/bad.json",new byte[]{(byte)0xc3,0x28},"objects/opaque.iob",bytes("Needle"),"images/opaque.png",new byte[]{0,-1,2})); }
    private static void await(CountDownLatch signal,String reason) throws InterruptedException { check(signal.await(10,TimeUnit.SECONDS),reason); }
    private static AsyncWorkspaceTextSearch.Snapshot terminal(AsyncWorkspaceTextSearch search) {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime()<deadline) { var state=search.poll();if (state.phase()!=AsyncWorkspaceTextSearch.Phase.SEARCHING) return state;LockSupport.parkNanos(1_000_000); }
        throw new AssertionError("Search did not publish a terminal state");
    }
    private static final class Gate {
        final CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1);
        volatile BooleanSupplier cancelled;
    }
    private static final class Controlled implements AsyncWorkspaceTextSearch.Searcher {
        final Map<Integer,Gate> gates;final Thread caller=Thread.currentThread();
        final AtomicInteger active=new AtomicInteger(),maximum=new AtomicInteger();
        final List<Integer> calls=java.util.Collections.synchronizedList(new ArrayList<>());
        Controlled(Map<Integer,Gate> gates) { this.gates=gates; }
        public WorkspaceTextSearch.Result search(PackResourceView source,WorkspaceTextSearch.Query query,BooleanSupplier cancelled) {
            check(Thread.currentThread()!=caller && !(source instanceof PackWorkspace),"worker receives immutable source outside caller thread");
            byte[] raw=source.read(SOURCE);int marker=Integer.parseInt(new LosslessJson(raw).token("marker"));raw[0]=0;check(source.read(SOURCE)[0]=='{',"defensive snapshot reads");
            var gate=gates.get(marker);check(gate!=null,"expected source marker");gate.cancelled=cancelled;int count=active.incrementAndGet();maximum.accumulateAndGet(count,Math::max);calls.add(marker);gate.started.countDown();
            try {
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);boolean released=false;
                while (!released && System.nanoTime()<deadline) try { released=gate.release.await(20,TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) { /* Simulate a noncooperative search implementation. */ }
                check(released,"test search released");return WorkspaceTextSearch.search(source,query,path -> true,() -> false);
            } finally { active.decrementAndGet(); }
        }
    }
    private static java.util.concurrent.ThreadFactory threads(AtomicReference<Thread> reference) { return runnable -> { var thread=new Thread(runnable,"test-text-search");reference.set(thread);return thread; }; }
    private static void stop(Thread worker) throws InterruptedException { worker.join(10_000);check(!worker.isAlive(),"worker terminates after active search returns"); }
    private static final class Source implements PackResourceView {
        final TreeMap<String,Integer> sizes=new TreeMap<>();final Map<String,byte[]> content=new TreeMap<>();final List<String> reads=new ArrayList<>();
        public Set<String> paths() { return java.util.Collections.unmodifiableSet(sizes.navigableKeySet()); }
        public int size(String path) { return sizes.get(path); }
        public byte[] read(String path) { reads.add(path);byte[] raw=content.get(path);if (raw==null) throw new AssertionError("Unexpected payload read "+path);return raw.clone(); }
        void text(String path,String text) { byte[] raw=bytes(text);sizes.put(path,raw.length);content.put(path,raw); }
    }
    static void run(Path temp) throws Exception {
        // Capture and search share the same eligibility/budgets, including later small files and uppercase JSON.
        var source=new Source();source.text("a.json","xxxxxx");source.text("b.json","xxxxxxxxxx");source.text("c.json","xxxxxx");source.text("d.json","xxxxxx");source.text("e.json","xx");source.text("z.JSON","x");source.sizes.put("objects/binary.iob",50_000_000);source.sizes.put("images/binary.png",50_000_000);
        var q=query("x");var limits=new WorkspaceTextSearch.Limits(8,12,4,1);var captured=TextSearchSource.capture(source,q,path -> true,limits,16_000_000);
        check(captured.copiedBytes()==12 && captured.copiedFiles()==2 && source.reads.equals(List.of("a.json","c.json")),"only fitting JSON copied; non-JSON assets never read");
        check(captured.paths().size()==6 && !captured.paths().contains("objects/binary.iob") && captured.size("b.json")==10,"eligible catalog and skipped source sizes");
        var result=WorkspaceTextSearch.search(captured,q,path -> true,limits,() -> false);check(result.equals(WorkspaceTextSearch.search(source,q,path -> true,limits,() -> false)) && result.matches()==12 && result.tooLarge()==1 && result.byteBudgetSkipped()==1 && result.fileBudgetSkipped()==2 && result.hits().size()==1,"exact live/snapshot search equivalence and skip counts");
        rejects(() -> captured.paths().clear());rejects(() -> captured.read("b.json"));rejects(() -> captured.read("objects/binary.iob"));rejects(() -> captured.size("e.json"));source.content.get("a.json")[0]='y';source.text("later.json","x");check(captured.read("a.json")[0]=='x' && !captured.paths().contains("later.json"),"isolated source bytes and eligible catalog");
        var onlyChanged=new WorkspaceTextSearch.Query("x",ResourceFinder.Family.ALL,true);var changed=TextSearchSource.capture(source,onlyChanged,Set.of("a.json","c.json")::contains,limits,16_000_000);check(changed.paths().equals(Set.of("a.json","c.json")),"changed eligibility captured before worker access");
        source.reads.clear();rejects(() -> TextSearchSource.capture(source,q,path -> true,limits,1));check(source.reads.isEmpty(),"catalog overflow fails before copying payloads");rejects(() -> TextSearchSource.capture(source,q,path -> true,limits,16_000_001));
        var family=new Source();family.text("biomes/a.JSON","x");family.text("biomes/a.json","x");family.text("expressions/b.json","x");var biomeQuery=new WorkspaceTextSearch.Query("x",ResourceFinder.Family.BIOMES,false);var familyCopy=TextSearchSource.capture(family,biomeQuery,path -> true,limits,16_000_000);check(familyCopy.paths().equals(Set.of("biomes/a.json")),"typed family semantics preserved");var otherQuery=new WorkspaceTextSearch.Query("x",ResourceFinder.Family.OTHER,false);check(TextSearchSource.capture(family,otherQuery,path -> true,limits,16_000_000).paths().equals(Set.of("biomes/a.JSON")),"uppercase JSON retains existing Other-file classification");
        var boundary=new Source();boundary.sizes.put("a.json",WorkspaceTextSearch.MAX_FILE_BYTES+1);int length="a.json".length();check(TextSearchSource.capture(boundary,q,path -> true,limits,length).paths().size()==1 && boundary.reads.isEmpty(),"exact path character bound");rejects(() -> TextSearchSource.capture(boundary,q,path -> true,limits,length-1));
        var catalog=new Source();for (int i=0;i<PackWorkspace.MAX_FILES;i++) catalog.sizes.put(i+".json",WorkspaceTextSearch.MAX_FILE_BYTES+1);check(TextSearchSource.capture(catalog,q,path -> true,limits,16_000_000).paths().size()==100_000 && catalog.reads.isEmpty(),"exact eligible file catalog bound without payload reads");catalog.sizes.put("extra.json",1);rejects(() -> TextSearchSource.capture(catalog,q,path -> true,limits,16_000_000));
        var exact=new Source();exact.text("a.json","x".repeat(WorkspaceTextSearch.MAX_FILE_BYTES));var full=new WorkspaceTextSearch.Limits(WorkspaceTextSearch.MAX_FILE_BYTES,WorkspaceTextSearch.MAX_TOTAL_BYTES,WorkspaceTextSearch.MAX_FILES,WorkspaceTextSearch.MAX_RESULTS);check(TextSearchSource.capture(exact,q,path -> true,full,16_000_000).copiedBytes()==WorkspaceTextSearch.MAX_FILE_BYTES,"exact one-MiB source accepted");
        var mismatch=new Source();mismatch.text("a.json","x");mismatch.sizes.put("a.json",2);rejects(() -> TextSearchSource.capture(mismatch,q,path -> true,limits,16_000_000));
        // Cancellation remains effective after the 200 displayed hits while counting a dense source.
        exact.reads.clear();try { WorkspaceTextSearch.search(exact,q,path -> true,() -> true);throw new AssertionError(); } catch (java.util.concurrent.CancellationException expected) { check(exact.reads.isEmpty(),"cancel before source read"); }
        var checkpoints=new AtomicInteger();try { WorkspaceTextSearch.search(exact,q,path -> true,() -> checkpoints.incrementAndGet()>=4);throw new AssertionError(); } catch (java.util.concurrent.CancellationException expected) { check(checkpoints.get()==4,"dense-match loop checks cancellation after the display cap"); }
        // Edits automatically replace pending work. Only the active A and latest C query can finish.
        var one=new Gate();var two=new Gate();var three=new Gate();var controlled=new Controlled(Map.of(1,one,2,two,3,three));var thread=new AtomicReference<Thread>();var pack=pack(1);var needle=query("Needle");pack.save(temp.resolve("async-text-source"));var drafts=new InspectorDrafts();drafts.update("late","stored","draft");
        try (var search=new AsyncWorkspaceTextSearch(controlled,threads(thread))) {
            check(search.poll().phase()==AsyncWorkspaceTextSearch.Phase.IDLE && controlled.calls.isEmpty(),"no search until a query is applied");var first=search.select(pack,needle);await(one.started,"first search starts");check(first.phase()==AsyncWorkspaceTextSearch.Phase.SEARCHING && thread.get().isDaemon(),"UI request returns during blocked worker search");
            pack.saveCopy(temp.resolve("async-text-loading-copy"));check(search.select(pack,needle)==first && drafts.any(),"Save Copy and drafts independent while searching");pack.replace(SOURCE,json(2));var second=search.select(pack,needle);pack.replace(SOURCE,json(3));var latest=search.select(pack,needle);check(one.cancelled.getAsBoolean() && latest.request()>second.request() && latest.result()==null,"automatic edit refresh hides/cancels stale work");
            one.release.countDown();await(three.started,"latest pending source selected");check(two.started.getCount()==1 && search.poll().phase()==AsyncWorkspaceTextSearch.Phase.SEARCHING,"intermediate query skipped; stale completed result cannot publish");three.release.countDown();var ready=terminal(search);
            check(ready.phase()==AsyncWorkspaceTextSearch.Phase.READY && ready.result().equals(new WorkspaceTextSearch.Cache().query(pack,needle)) && controlled.calls.equals(List.of(1,3)) && controlled.maximum.get()==1,"same literal results with only one active worker");
            long revision=pack.revision();int history=pack.undoCount();pack.saveCopy(temp.resolve("async-text-copy"));pack.saveAs(temp.resolve("async-text-as"));pack.save(pack.directory());pack.exportZip(temp.resolve("async-text.zip"));check(search.select(pack,needle)==ready && pack.revision()==revision && pack.undoCount()==history && drafts.any(),"all-files search retains results through Save/Copy/As/ZIP");
            var copy=PackWorkspace.open(temp.resolve("async-text-copy"));var zip=PackWorkspace.importZip(temp.resolve("async-text.zip"));for (String path : pack.paths()) check(Arrays.equals(pack.read(path),copy.read(path)) && Arrays.equals(pack.read(path),zip.read(path)),"exact persistence "+path);
            var phrase=query("Needle Needle");var newQuery=search.select(pack,phrase);check(newQuery.request()>ready.request() && terminal(search).result().matches()==1,"query changes replace old results");search.clear();check(search.poll().phase()==AsyncWorkspaceTextSearch.Phase.IDLE,"clear discards query/results without automatic restart");
        }stop(thread.get());
        // Production search preserves text semantics and tracks changed-only Save baselines as well as edits.
        var actual=pack(1);var changedNeedle=new WorkspaceTextSearch.Query("Needle",ResourceFinder.Family.ALL,true);
        try (var search=new AsyncWorkspaceTextSearch()) {
            search.select(actual,needle);var all=terminal(search);check(all.result().matches()==3 && all.result().invalidText()==1 && all.result().equals(new WorkspaceTextSearch.Cache().query(actual,needle)),"real UTF-8/unknown/uppercase/control policy unchanged");
            for (String text : List.of("\\u004e","雪😀","needle",".*")) { var next=query(text);search.select(actual,next);check(terminal(search).result().equals(new WorkspaceTextSearch.Cache().query(actual,next)),"literal escape/case/Unicode semantics"); }
            search.select(actual,changedNeedle);var beforeSave=terminal(search);check(beforeSave.result().matches()==3,"new unsaved JSON qualifies as changed");long revision=actual.revision();actual.save(temp.resolve("async-text-changed"));var afterSave=search.select(actual,changedNeedle);check(afterSave.request()>beforeSave.request() && actual.revision()==revision && terminal(search).result().eligibleFiles()==0,"Save baseline refresh without applied revision change");
            actual.replace(SOURCE,json(2));search.select(actual,changedNeedle);check(terminal(search).result().matches()==2,"changed source only");actual.undo();search.select(actual,changedNeedle);check(terminal(search).result().matches()==0,"Undo restores save baseline");actual.redo();search.select(actual,changedNeedle);var redone=terminal(search);check(redone.result().matches()==2,"Redo refresh");actual.saveCopy(temp.resolve("async-text-changed-copy"));check(search.select(actual,changedNeedle)==redone,"Save Copy retains changed-only baseline");actual.saveAs(temp.resolve("async-text-changed-as"));search.select(actual,changedNeedle);check(terminal(search).result().matches()==0,"Save As refreshes changed-only baseline");
            actual.duplicate(SOURCE,"copy");search.select(actual,changedNeedle);check(terminal(search).result().matches()==2,"resource creation refresh");
        }
        // Partial Save can advance a baseline while dirty remains true: saveRevision is part of changed-only keys.
        var partial=pack(1);Path partialRoot=temp.resolve("async-text-partial");partial.save(partialRoot);String folder=partial.createSubfolder("expressions","new");partial.duplicate(SOURCE,"z_later");var legacy=new WorkspaceTextSearch.Cache();var oldLegacy=legacy.query(partial,changedNeedle);
        try (var search=new AsyncWorkspaceTextSearch()) {
            search.select(partial,changedNeedle);var before=terminal(search);long saved=partial.saveRevision(),revision=partial.revision();try { partial.save(partialRoot,(base,path,data,baseline)->{ throw new java.io.IOException("Injected staging failure after folder creation"); });throw new AssertionError("Expected staged Save failure"); } catch (java.io.IOException expected) { check(partial.saveRevision()>saved && partial.revision()==revision && partial.dirty() && Files.isDirectory(partialRoot.resolve(folder)),"partial save advances baseline while still dirty"); }
            check(search.select(partial,changedNeedle).request()>before.request() && terminal(search).result().equals(legacy.query(partial,changedNeedle)) && legacy.query(partial,changedNeedle)!=oldLegacy,"both sync and async caches follow partial Save revision");
        }
        var left=pack(1);var right=pack(1);var gate=new Gate();var isolated=new Controlled(Map.of(1,gate));var isolatedThread=new AtomicReference<Thread>();
        try (var search=new AsyncWorkspaceTextSearch(isolated,threads(isolatedThread))) {
            var first=search.select(left,needle);await(gate.started,"workspace isolation active");var next=search.select(right,needle);check(left.revision()==right.revision() && next.request()>first.request() && gate.cancelled.getAsBoolean(),"workspace identity invalidates equal revisions/path/query");gate.release.countDown();check(terminal(search).phase()==AsyncWorkspaceTextSearch.Phase.READY,"latest workspace result recovers");
        }stop(isolatedThread.get());
        var failures=new AtomicInteger();var errorsThread=new AtomicReference<Thread>();
        try (var search=new AsyncWorkspaceTextSearch((view,selected,cancelled) -> { int n=failures.incrementAndGet();if (n==1) throw new IllegalArgumentException("bad\n"+"x".repeat(2000));if (n==2) return null;if (n==3) throw new IllegalStateException();return WorkspaceTextSearch.search(view,selected,path -> true,cancelled); },threads(errorsThread))) {
            search.select(right,needle);var failed=terminal(search);check(failed.phase()==AsyncWorkspaceTextSearch.Phase.FAILED && failed.error().length()<=515 && !failed.error().contains("\n"),"bounded escaped error");for (int i=0;i<20;i++) check(search.select(right,needle)==failed && failures.get()==1,"stable error does not retry per frame");search.retry(right,needle);check(terminal(search).phase()==AsyncWorkspaceTextSearch.Phase.FAILED,"null result error");search.retry(right,needle);check(terminal(search).error().contains("IllegalStateException"),"empty error fallback");search.retry(right,needle);check(terminal(search).phase()==AsyncWorkspaceTextSearch.Phase.READY,"explicit retry recovers");
        }stop(errorsThread.get());
        var blocked=new Gate();var waiting=new Gate();var closingSearcher=new Controlled(Map.of(1,blocked,2,waiting));var closingThread=new AtomicReference<Thread>();var closing=new AsyncWorkspaceTextSearch(closingSearcher,threads(closingThread));closing.select(left,needle);await(blocked.started,"close active search");closing.select(pack(2),needle);closing.clear();check(closing.poll().phase()==AsyncWorkspaceTextSearch.Phase.IDLE && blocked.cancelled.getAsBoolean() && closingThread.get().isAlive(),"clear returns while noncooperative search remains active");closing.select(pack(2),needle);closing.close();closing.close();check(closing.poll().phase()==AsyncWorkspaceTextSearch.Phase.CLOSED && waiting.started.getCount()==1 && closingThread.get().isAlive(),"nonblocking close discards waiting/results");rejects(() -> closing.select(left,needle));blocked.release.countDown();stop(closingThread.get());check(waiting.started.getCount()==1 && closing.poll().phase()==AsyncWorkspaceTextSearch.Phase.CLOSED,"no publication or pending work after close");
        System.out.println("PASS: single-worker/latest-pending background JSON text search, immutable query-filtered source capture and exact skip receipts, dense-match cancellation, stale edit/query/workspace rejection, Save/partial-Save changed-only baseline refresh, stable errors/retry, nonblocking clear/close and exact persistence without native evaluation");
    }
}
