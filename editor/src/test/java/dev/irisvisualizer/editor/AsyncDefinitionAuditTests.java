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
import java.util.function.IntFunction;

final class AsyncDefinitionAuditTests {
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static void check(boolean valid,String reason) { if (!valid) throw new AssertionError("Background definition audit: "+reason); }
    private static void rejects(Runnable action) { try { action.run(); } catch (IllegalArgumentException | IllegalStateException | NullPointerException | UnsupportedOperationException expected) { return; }throw new AssertionError("Expected rejected definition audit operation"); }
    private static void await(CountDownLatch latch,String reason) throws InterruptedException { check(latch.await(10,TimeUnit.SECONDS),reason); }
    private static void stop(Thread thread) throws InterruptedException { thread.join(10_000);check(!thread.isAlive(),"worker exits after scanner returns"); }
    private static java.util.concurrent.ThreadFactory threads(AtomicReference<Thread> thread) { return runnable -> { var next=new Thread(runnable,"test-definition-audit");thread.set(next);return next; }; }
    private static <T> AsyncDefinitionAudit.Snapshot<T> terminal(AsyncDefinitionAudit<T> audit,PackWorkspace pack) {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime()<deadline) { var state=audit.poll(pack);if (state.phase()!=AsyncDefinitionAudit.Phase.SCANNING) return state;LockSupport.parkNanos(1_000_000); }
        throw new AssertionError("Definition audit did not finish");
    }
    private static PackWorkspace pack(String source,String target,IntFunction<byte[]> json,int marker,byte[] targetBytes) throws Exception {
        return PackWorkspace.newPack(Map.of(source,json.apply(marker),target,targetBytes,"image-maps/present.json",bytes("invalid opaque target content"),"images/opaque.png",new byte[]{0,-1,2},"objects/opaque.iob",new byte[]{0,1,-1},"future/unknown.json",bytes("invalid preserved")));
    }
    private static final class Gate {
        final CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1);
        volatile BooleanSupplier cancelled;
    }
    private static final class Controlled<T> implements AsyncDefinitionAudit.Scanner<T> {
        final String path;final AsyncDefinitionAudit.Scanner<T> scanner;final Map<Integer,Gate> gates;
        final Thread caller=Thread.currentThread();final AtomicInteger active=new AtomicInteger(),maximum=new AtomicInteger();
        final List<Integer> calls=java.util.Collections.synchronizedList(new ArrayList<>());
        Controlled(String path,AsyncDefinitionAudit.Scanner<T> scanner,Map<Integer,Gate> gates) { this.path=path;this.scanner=scanner;this.gates=gates; }
        public T inspect(PackResourceView source,BooleanSupplier cancelled) {
            check(Thread.currentThread()!=caller && !(source instanceof PackWorkspace),"worker receives immutable source away from UI thread");
            byte[] raw=source.read(path);int marker=Integer.parseInt(new LosslessJson(raw).token("marker"));raw[0]=0;check(source.read(path)[0]=='{',"defensive snapshot read");
            var gate=gates.get(marker);check(gate!=null,"expected marker");gate.cancelled=cancelled;int count=active.incrementAndGet();maximum.accumulateAndGet(count,Math::max);calls.add(marker);gate.started.countDown();
            try {
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);boolean released=false;
                while (!released && System.nanoTime()<deadline) try { released=gate.release.await(20,TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) { /* Deliberately noncooperative work. */ }
                check(released,"test scan released");return scanner.inspect(source,() -> false);
            } finally { active.decrementAndGet(); }
        }
    }
    private static final class Source implements PackResourceView {
        final TreeMap<String,Integer> sizes=new TreeMap<>();final Map<String,byte[]> content=new TreeMap<>();final List<String> reads=new ArrayList<>();
        public Set<String> paths() { return java.util.Collections.unmodifiableSet(sizes.navigableKeySet()); }
        public int size(String path) { return sizes.get(path); }
        public byte[] read(String path) { reads.add(path);var raw=content.get(path);if (raw==null) throw new AssertionError("Unexpected payload read: "+path);return raw.clone(); }
        void json(String path,int size) { sizes.put(path,size);content.put(path,bytes(" ".repeat(size-2)+"{}")); }
    }
    private static void snapshotContracts() throws Exception {
        for (var family : DefinitionAuditSource.Family.values()) {
            String base=family==DefinitionAuditSource.Family.BLOCK_ALIASES ? "blocks/" : "expressions/",first=base+"a.json";
            var source=new Source();source.json(first,2);source.sizes.put(base+"b.json",ReferenceDiagnostics.MAX_JSON_BYTES+1);source.json(base+"c.json",3);source.sizes.put("objects/opaque.iob",50_000_000);source.sizes.put("images/opaque.png",50_000_000);source.sizes.put("image-maps/target.json",50_000_000);source.sizes.put("biomes/unrelated.json",50_000_000);
            var snapshot=DefinitionAuditSource.capture(source,family);check(snapshot.copiedBytes()==5 && snapshot.copiedFiles()==2 && source.reads.equals(List.of(first,base+"c.json")),"only eligible small family JSON read; oversized/binary/other targets are metadata only");
            check(snapshot.paths().equals(source.paths()) && snapshot.size(base+"b.json")==ReferenceDiagnostics.MAX_JSON_BYTES+1,"exact target catalog and oversized candidate sizes");
            rejects(() -> snapshot.paths().clear());rejects(() -> snapshot.read("objects/opaque.iob"));rejects(() -> snapshot.read(base+"b.json"));snapshot.read(first)[0]=0;check(snapshot.read(first)[0]=='{',"defensive payload ownership");
            source.content.get(first)[0]=0;source.sizes.put("objects/later.iob",1);check(snapshot.read(first)[0]=='{' && !snapshot.paths().contains("objects/later.iob"),"live byte/catalog changes isolated");
            source.reads.clear();rejects(() -> DefinitionAuditSource.capture(source,family,1));check(source.reads.isEmpty(),"catalog character overflow before any payload copy");
            rejects(() -> DefinitionAuditSource.capture(source,family,0));rejects(() -> DefinitionAuditSource.capture(source,family,16_000_001));
            var boundary=new Source();boundary.json(first,ReferenceDiagnostics.MAX_JSON_BYTES);check(DefinitionAuditSource.capture(boundary,family).copiedBytes()==ReferenceDiagnostics.MAX_JSON_BYTES,"exact per-file boundary");
            var files=new Source();for (int i=0;i<513;i++) files.json(base+String.format("%03d",i)+".json",2);var fileBound=DefinitionAuditSource.capture(files,family);check(fileBound.copiedFiles()==512 && files.reads.size()==512 && fileBound.paths().contains(base+"512.json"),"512 copies with exact presence beyond candidate cap");rejects(() -> fileBound.size(base+"512.json"));
            var budget=new Source();for (int i=0;i<8;i++) budget.json(base+"a"+i+".json",1_000_000);budget.json(base+"b_overflow.json",2);budget.json(base+"z_small.json",2);
            var byteBound=DefinitionAuditSource.capture(budget,family);check(byteBound.copiedBytes()==8_000_000 && byteBound.copiedFiles()==8 && budget.reads.size()==8 && byteBound.size(base+"b_overflow.json")==2,"exact 8 MB boundary stops at first byte overflow; later small files remain uninspected");rejects(() -> byteBound.read(base+"z_small.json"));
            Object direct=family==DefinitionAuditSource.Family.BLOCK_ALIASES ? BlockAliasAudit.inspect(budget) : ExpressionReferenceAudit.inspect(budget);
            Object captured=family==DefinitionAuditSource.Family.BLOCK_ALIASES ? BlockAliasAudit.inspect(byteBound) : ExpressionReferenceAudit.inspect(byteBound);check(direct.equals(captured),"byte stop preserves exact prior audit receipts");
            var mismatch=new Source();mismatch.json(first,2);mismatch.sizes.put(first,3);rejects(() -> DefinitionAuditSource.capture(mismatch,family));var negative=new Source();negative.sizes.put(first,-1);rejects(() -> DefinitionAuditSource.capture(negative,family));
            var character=new Source();character.json(first,2);check(DefinitionAuditSource.capture(character,family,first.length()).copiedBytes()==2,"exact character boundary");rejects(() -> DefinitionAuditSource.capture(character,family,first.length()-1));
            var catalog=new Source();catalog.json(first,2);for (int i=1;i<PackWorkspace.MAX_FILES;i++) catalog.sizes.put("objects/"+i+".iob",1);check(DefinitionAuditSource.capture(catalog,family).paths().size()==PackWorkspace.MAX_FILES,"exact 100,000 paths");catalog.sizes.put("objects/extra.iob",1);catalog.reads.clear();rejects(() -> DefinitionAuditSource.capture(catalog,family));check(catalog.reads.isEmpty(),"file catalog overflow before copies");
            source.reads.clear();try { if (family==DefinitionAuditSource.Family.BLOCK_ALIASES) BlockAliasAudit.inspect(source,() -> true);else ExpressionReferenceAudit.inspect(source,() -> true);throw new AssertionError(); } catch (java.util.concurrent.CancellationException expected) { check(source.reads.isEmpty(),"cancel before read"); }
            var cancel=new Source();cancel.json(first,2);BooleanSupplier cancelled=() -> !cancel.reads.isEmpty();try { if (family==DefinitionAuditSource.Family.BLOCK_ALIASES) BlockAliasAudit.inspect(cancel,cancelled);else ExpressionReferenceAudit.inspect(cancel,cancelled);throw new AssertionError(); } catch (java.util.concurrent.CancellationException expected) { check(cancel.reads.size()==1,"cancel after final parse"); }
        }
        // Cancellation must interrupt nested work even when there is only one definition.
        var block=PackWorkspace.newPack(Map.of("blocks/a.json",bytes("{\"block\":\"a\",\"backup\":{\"block\":\"a\",\"backup\":{\"block\":\"a\",\"backup\":{\"block\":\"a\"}}}}")));
        var count=new AtomicInteger();try { BlockAliasAudit.inspect(block,() -> count.incrementAndGet()>=6);throw new AssertionError(); } catch (java.util.concurrent.CancellationException expected) { check(count.get()==6,"backup traversal cancellation"); }
        var expression=PackWorkspace.newPack(Map.of("expressions/a.json",bytes("{\"variables\":[{\"name\":\"v\",\"styleValue\":{\"expression\":\"a\",\"fracture\":{\"expression\":\"a\"}}}]}")));
        count.set(0);try { ExpressionReferenceAudit.inspect(expression,() -> count.incrementAndGet()>=7);throw new AssertionError(); } catch (java.util.concurrent.CancellationException expected) { check(count.get()==7,"style traversal cancellation"); }
    }
    private static <T> void runFamily(Path temp,DefinitionAuditSource.Family family,String source,String target,IntFunction<byte[]> json,byte[] targetBytes,AsyncDefinitionAudit.Scanner<T> scanner) throws Exception {
        String prefix="async-definition-"+family.name().toLowerCase(java.util.Locale.ROOT);var pack=pack(source,target,json,1,targetBytes);pack.save(temp.resolve(prefix+"-source"));var drafts=new InspectorDrafts();drafts.update("late","stored","draft");
        try (var actual=new AsyncDefinitionAudit<T>(family,scanner)) {
            actual.refresh(pack);var ready=terminal(actual,pack);check(ready.phase()==AsyncDefinitionAudit.Phase.READY && ready.audit().equals(scanner.inspect(pack,() -> false)),"production constructor background audit parity");
        }
        var one=new Gate();var two=new Gate();var three=new Gate();var controlled=new Controlled<>(source,scanner,Map.of(1,one,2,two,3,three));var thread=new AtomicReference<Thread>();
        try (var audit=new AsyncDefinitionAudit<>(family,controlled,threads(thread))) {
            check(audit.poll(pack).phase()==AsyncDefinitionAudit.Phase.IDLE && controlled.calls.isEmpty(),"opening/polling does not auto scan");
            var first=audit.refresh(pack);await(one.started,"first audit starts");check(first.phase()==AsyncDefinitionAudit.Phase.SCANNING && thread.get().isDaemon(),"request returns while daemon scanner blocked");
            pack.saveCopy(temp.resolve(prefix+"-loading-copy"));check(audit.poll(pack).phase()==AsyncDefinitionAudit.Phase.SCANNING && drafts.any(),"Save Copy and drafts independent while scanning");
            pack.replace(source,json.apply(2));check(audit.poll(pack).phase()==AsyncDefinitionAudit.Phase.IDLE && one.cancelled.getAsBoolean(),"edit cancels without auto queue");var second=audit.refresh(pack);pack.replace(source,json.apply(3));var latest=audit.refresh(pack);
            check(latest.request()>second.request() && latest.audit()==null,"refresh hides previous output immediately");one.release.countDown();await(three.started,"latest pending starts");check(two.started.getCount()==1 && audit.poll(pack).phase()==AsyncDefinitionAudit.Phase.SCANNING,"middle request replaced and stale ignored-cancellation result rejected");three.release.countDown();var ready=terminal(audit,pack);
            check(ready.phase()==AsyncDefinitionAudit.Phase.READY && ready.audit().equals(scanner.inspect(pack,() -> false)) && controlled.maximum.get()==1 && controlled.calls.equals(List.of(1,3)),"one active worker and exact direct-audit parity");
            long revision=pack.revision(),resources=pack.resourceRevision();int history=pack.undoCount();pack.saveCopy(temp.resolve(prefix+"-copy"));pack.saveAs(temp.resolve(prefix+"-as"));pack.save(pack.directory());pack.exportZip(temp.resolve(prefix+".zip"));
            check(audit.poll(pack)==ready && pack.revision()==revision && pack.resourceRevision()==resources && pack.undoCount()==history && drafts.any(),"Save/Copy/As/ZIP preserve audit/history/drafts");
            var copy=PackWorkspace.open(temp.resolve(prefix+"-copy"));var zip=PackWorkspace.importZip(temp.resolve(prefix+".zip"));for (String path : pack.paths()) check(Arrays.equals(pack.read(path),copy.read(path)) && Arrays.equals(pack.read(path),zip.read(path)),"exact opaque/unknown persistence "+path);
            pack.undo();check(audit.poll(pack).phase()==AsyncDefinitionAudit.Phase.IDLE,"Undo invalidates");pack.redo();check(audit.poll(pack).phase()==AsyncDefinitionAudit.Phase.IDLE && controlled.calls.size()==2,"Redo never revives stale audit or auto scans");
        }stop(thread.get());
        var firstPack=pack(source,target,json,1,targetBytes);var nextPack=pack(source,target,json,1,targetBytes);var captured=DefinitionAuditSource.capture(firstPack,family);firstPack.replace(source,json.apply(2));firstPack.duplicate("objects/opaque.iob","new");check(!captured.paths().contains("objects/new.iob") && new LosslessJson(captured.read(source)).token("marker").equals("1"),"workspace mutation cannot alter captured source");
        firstPack=pack(source,target,json,1,targetBytes);var gate=new Gate();var workspaceThread=new AtomicReference<Thread>();var workspaceScanner=new Controlled<>(source,scanner,Map.of(1,gate));
        try (var audit=new AsyncDefinitionAudit<>(family,workspaceScanner,threads(workspaceThread))) {
            audit.refresh(firstPack);await(gate.started,"identity test active");check(firstPack.revision()==nextPack.revision() && audit.poll(nextPack).phase()==AsyncDefinitionAudit.Phase.IDLE && gate.cancelled.getAsBoolean(),"equal-revision workspace replacement rejects active output");gate.release.countDown();
        }stop(workspaceThread.get());
        var calls=new AtomicInteger();var failureThread=new AtomicReference<Thread>();
        try (var audit=new AsyncDefinitionAudit<T>(family,(view,cancelled) -> { int n=calls.incrementAndGet();if (n==1) throw new IllegalArgumentException("bad\n"+"x".repeat(2000));if (n==2) return null;if (n==3) throw new IllegalStateException();if (n==4) throw new java.util.concurrent.CancellationException();return scanner.inspect(view,cancelled); },threads(failureThread))) {
            audit.refresh(nextPack);var failed=terminal(audit,nextPack);check(failed.phase()==AsyncDefinitionAudit.Phase.FAILED && !failed.error().contains("\n") && failed.error().length()<=515,"escaped bounded error");for (int i=0;i<20;i++) check(audit.poll(nextPack)==failed && calls.get()==1,"stable error without per-frame retry");
            audit.refresh(nextPack);check(terminal(audit,nextPack).phase()==AsyncDefinitionAudit.Phase.FAILED,"null output visible failure");audit.refresh(nextPack);check(terminal(audit,nextPack).error().contains("IllegalStateException"),"missing message fallback");audit.refresh(nextPack);check(terminal(audit,nextPack).error().contains("cancelled"),"current scanner cancellation visible");audit.refresh(nextPack);check(terminal(audit,nextPack).phase()==AsyncDefinitionAudit.Phase.READY,"explicit retry recovers");audit.clear();check(audit.poll(nextPack).phase()==AsyncDefinitionAudit.Phase.IDLE && calls.get()==5,"clear does not auto retry");
        }stop(failureThread.get());
        // Capture rejects a large path catalog visibly without invoking the worker, then explicit refresh recovers.
        var catalogFiles=new TreeMap<String,byte[]>();catalogFiles.put(source,json.apply(1));for (int i=0;i<1200;i++) catalogFiles.put("objects/"+"p".repeat(14_000)+i+".iob",new byte[0]);var catalogPack=PackWorkspace.newPack(catalogFiles);var captureCalls=new AtomicInteger();var captureThread=new AtomicReference<Thread>();
        try (var audit=new AsyncDefinitionAudit<T>(family,(view,cancelled) -> { captureCalls.incrementAndGet();return scanner.inspect(view,cancelled); },threads(captureThread))) {
            var failed=audit.refresh(catalogPack);check(failed.phase()==AsyncDefinitionAudit.Phase.FAILED && captureCalls.get()==0 && audit.poll(catalogPack)==failed,"capture failure stable and no worker scan");audit.refresh(nextPack);check(terminal(audit,nextPack).phase()==AsyncDefinitionAudit.Phase.READY,"capture failure explicit recovery");
        }stop(captureThread.get());
        var blocked=new Gate();var waiting=new Gate();var closeScanner=new Controlled<>(source,scanner,Map.of(1,blocked,2,waiting));var closeThread=new AtomicReference<Thread>();var closing=new AsyncDefinitionAudit<>(family,closeScanner,threads(closeThread));
        closing.refresh(nextPack);await(blocked.started,"close test active");closing.refresh(pack(source,target,json,2,targetBytes));closing.clear();check(closing.poll(nextPack).phase()==AsyncDefinitionAudit.Phase.IDLE && blocked.cancelled.getAsBoolean() && closeThread.get().isAlive(),"clear discards pending without joining active work");closing.refresh(pack(source,target,json,2,targetBytes));closing.close();closing.close();check(closing.poll(nextPack).phase()==AsyncDefinitionAudit.Phase.CLOSED && waiting.started.getCount()==1 && closeThread.get().isAlive(),"nonblocking close rejects active/pending output");rejects(() -> closing.refresh(nextPack));blocked.release.countDown();stop(closeThread.get());check(waiting.started.getCount()==1 && closing.poll(nextPack).phase()==AsyncDefinitionAudit.Phase.CLOSED,"no work/publication after close");
    }
    static void run(Path temp) throws Exception {
        snapshotContracts();
        runFamily(temp,DefinitionAuditSource.Family.BLOCK_ALIASES,"blocks/source.json","blocks/target.json",marker -> bytes("{\"marker\":"+marker+",\"block\":\"target\",\"backup\":{\"block\":\"source\"}}"),bytes("{\"block\":\"source\"}"),BlockAliasAudit::inspect);
        runFamily(temp,DefinitionAuditSource.Family.EXPRESSIONS,"expressions/source.json","expressions/target.json",marker -> bytes("{\"marker\":"+marker+",\"variables\":[{\"name\":\"v\",\"styleValue\":{\"expression\":\"target\",\"imageMap\":\"present\",\"fracture\":{\"expression\":\"source\"}}}]}"),bytes("{\"functions\":[{\"name\":\"f\",\"styleValue\":{\"expression\":\"source\"}}]}"),ExpressionReferenceAudit::inspect);
        System.out.println("PASS: explicit background block-alias and expression-source audits, bounded immutable family snapshots with exact catalog/definition/byte limits and prior budget parity, metadata-only binary/unrelated targets, recursive cooperative cancellation, latest-pending and noncooperative stale-output rejection, workspace/edit/Undo/Redo isolation, stable errors/capture failures/explicit recovery, nonblocking clear/close and exact Save/Copy/As/ZIP preservation without native alias loading or formula evaluation");
    }
}
