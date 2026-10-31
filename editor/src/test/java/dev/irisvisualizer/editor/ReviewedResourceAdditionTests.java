package dev.irisvisualizer.editor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

final class ReviewedResourceAdditionTests {
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static void check(boolean value,String reason) { if(!value)throw new AssertionError("Reviewed resource addition: "+reason); }
    private static void io(InspectorDrafts.Change action) throws IOException { try { action.run(); }catch(IOException expected) { return; }throw new AssertionError("Expected rejected resource addition"); }
    private static ReviewedResourceAddition.Snapshot terminal(ReviewedResourceAddition stage,PackWorkspace pack)throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);ReviewedResourceAddition.Snapshot state;
        while((state=stage.poll(pack)).phase()==ReviewedResourceAddition.Phase.PREPARING && System.nanoTime()<until)Thread.sleep(1);
        return state;
    }
    private static ReviewedResourceAddition.Snapshot ready(ReviewedResourceAddition stage,PackWorkspace pack)throws Exception { var state=terminal(stage,pack);check(state.phase()==ReviewedResourceAddition.Phase.READY,"preparation ready: "+state.phase()+" / "+state.detail());return state; }
    private static void await(CountDownLatch gate)throws Exception { check(gate.await(10,TimeUnit.SECONDS),"gate reached"); }
    private static void uninterruptible(CountDownLatch gate) { boolean done=false;while(!done)try { done=gate.await(10,TimeUnit.SECONDS);if(!done)throw new AssertionError("Gate timed out"); }catch(InterruptedException ignored) {} }
    private static PackWorkspace fixture() throws Exception {
        var pack=StarterPack.create("reviewed");pack.importAsset(imageFile,"map");pack.importAsset(objectFile,"object");pack.createResource("jigsaw-pools","pool",null);return pack;
    }
    private static Path imageFile,objectFile;
    static void run(Path temp)throws Exception {
        imageFile=temp.resolve("reviewed-source.PNG");objectFile=temp.resolve("reviewed-source.iob");Files.write(imageFile,new byte[]{0,1,-1});Files.write(objectFile,new byte[]{0,-1,4});var templatePack=fixture();
        // Every existing creation family uses the same pinned template/dependency choices; no source disk I/O.
        for(String parent:List.of("dimensions","regions","biomes","generators","image-maps","blocks","expressions","structures","jigsaw-pools","jigsaw-pieces"))try(var stage=new ReviewedResourceAddition()) {
            String dependency=ResourceCreation.dependencyType(parent)==null ? null : ResourceCreation.choices(templatePack,parent).getFirst();String name="reviewed_"+parent.replace('-','_');byte[] expected=ResourceCreation.bytes(templatePack,parent,name,dependency);
            long revision=templatePack.revision(),resources=templatePack.resourceRevision(),saved=templatePack.saveRevision();int count=templatePack.paths().size();var drafts=new InspectorDrafts();drafts.update("late","stored","typing");
            stage.create(templatePack,parent,name,dependency);var state=ready(stage,templatePack);check(state.plan().bytes()==expected.length && state.receipt().sha256().equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(expected))) && state.receipt().excerpt()!=null,"exact template and digest");
            check(templatePack.revision()==revision && templatePack.resourceRevision()==resources && templatePack.saveRevision()==saved && templatePack.paths().size()==count && drafts.any(),"preparation has no workspace/history/draft effects");
            String path=stage.apply(templatePack,state.request(),null);check(Arrays.equals(templatePack.read(path),expected) && templatePack.revision()==revision+1 && templatePack.resourceRevision()==resources+1 && templatePack.saveRevision()==saved && templatePack.contentRevision(path)>0 && templatePack.changed(path) && drafts.any(),"one structural installation preserves drafts and baseline identity");
            io(()->stage.apply(templatePack,state.request(),null));check(stage.poll(templatePack).phase()==ReviewedResourceAddition.Phase.APPLIED,"one-shot Apply cannot repeat");
        }
        String source="blocks/source.json";var pack=PackWorkspace.newPack(Map.of(source,bytes("{\r\n\"block\":\"minecraft:stone\",\"future\":9007199254740993\r\n}"),"images/exact.PNG",new byte[]{0,1,-1},"objects/exact.iob",new byte[]{0,-1,7},"future/unknown.json",bytes("malformed 雪")));Path root=temp.resolve("reviewed-addition-root");pack.save(root);var current=pack;
        // JSON and opaque copies preserve extension/case and bytes. Opening stays a separate guarded action.
        for(String original:List.of(source,"images/exact.PNG","objects/exact.iob"))try(var stage=new ReviewedResourceAddition()) {
            byte[] expected=pack.read(original);long stamp=pack.contentRevision(original);stage.duplicate(pack,original,"nested/copy");var state=ready(stage,pack);check(state.receipt().excerpt()==null && !Files.exists(root.resolve(state.plan().target())),"duplicate is opaque and never creates a disk destination");
            String added=stage.apply(pack,state.request(),null);check(Arrays.equals(pack.read(added),expected) && pack.contentRevision(original)==stamp && added.endsWith(ResourceTypes.extension(original)),"exact duplicate with unchanged original content stamp");
            var drafts=new InspectorDrafts();drafts.update("late","stored","typing");var selected=new AtomicReference<>(source);var nav=new ResourceNavigation();nav.request(pack,added);var request=nav.takeRequest(pack);check(!drafts.transition(()->false,()->selected.set(request.target())) && drafts.any() && selected.get().equals(source),"separate Open cannot discard late drafts on cancellation");
        }
        pack.replace(source,bytes("applied one"));try(var stage=new ReviewedResourceAddition()) {
            stage.duplicate(pack,source,"history_copy");var state=ready(stage,pack);var before=pack.historySteps(false);long revision=pack.revision(),saved=pack.saveRevision();io(()->stage.apply(current,state.request(),null));check(pack.historySteps(false).equals(before) && pack.revision()==revision,"missing history acknowledgement preserves state");
            var ack=stage.acknowledge(pack,state.request());pack.replace("future/unknown.json",bytes("late edit"));io(()->stage.apply(current,state.request(),ack));check(stage.poll(pack).phase()==ReviewedResourceAddition.Phase.READY && pack.undoCount()==before.size()+1,"late edits invalidate history acknowledgement but retain reviewed source");
            var fresh=stage.acknowledge(pack,state.request());io(()->stage.apply(current,state.request(),fresh,0));check(pack.undoCount()>0 && pack.saveRevision()==saved && !pack.paths().contains(state.plan().target()),"late capacity failure does not consume bytes or clear history");
            String target=stage.apply(pack,state.request(),fresh);check(pack.undoCount()==0 && pack.redoCount()==0 && pack.historyBytes()==0 && Arrays.equals(pack.read(target),bytes("applied one")) && pack.saveRevision()==saved,"fresh acknowledgement clears history once at successful Apply");
        }
        // Actual same-source changes and Undo require recapture even if original bytes return; unrelated Save does not.
        try(var stage=new ReviewedResourceAddition()) {
            stage.duplicate(pack,source,"stale_copy");ready(stage,pack);pack.replace(source,bytes("changed source"));pack.undo();check(stage.poll(pack).phase()==ReviewedResourceAddition.Phase.STALE && stage.poll(pack).receipt()==null,"same-byte Undo still invalidates captured source identity");
            stage.duplicate(pack,source,"saved_copy");var state=ready(stage,pack);pack.save(root);check(stage.poll(pack)==state,"Save retains unchanged reviewed source/destination");String added=stage.apply(pack,state.request(),stage.acknowledge(pack,state.request()));check(!Files.exists(root.resolve(added)),"Apply remains in-memory after Save");
        }
        try(var stage=new ReviewedResourceAddition()) {
            stage.duplicate(pack,source,"collision");var state=ready(stage,pack);io(()->stage.duplicate(current,source,"../bad"));check(stage.poll(pack)==state,"invalid replacement request retains accepted work");pack.duplicate(source,"collision");check(stage.poll(pack).phase()==ReviewedResourceAddition.Phase.STALE,"newly occupied destination invalidates receipt");
        }
        pack.createSubfolder("blocks","empty");try(var stage=new ReviewedResourceAddition()) {stage.create(pack,"blocks/empty","new",null);ready(stage,pack);pack.deleteEmptyFolder("blocks/empty");check(stage.poll(pack).phase()==ReviewedResourceAddition.Phase.STALE,"lost parent invalidates template review");}
        io(()->current.reviewResourceDuplication(source,"A",PackWorkspace.MAX_BYTES));io(()->current.reviewResourceDuplication(source,"x".repeat(251),PackWorkspace.MAX_BYTES));io(()->current.reviewResourceDuplication(source,"a/".repeat(64)+"z",PackWorkspace.MAX_BYTES));io(()->current.reviewResourceCreation("dimensions","nested/name",null,PackWorkspace.MAX_BYTES));io(()->current.reviewResourceCreation("image-maps","bad","missing",PackWorkspace.MAX_BYTES));
        // Prepared authority binds source identity and target; consumption cannot be reused or rebound.
        var left=pack.reviewResourceDuplication(source,"left",PackWorkspace.MAX_BYTES);var right=pack.reviewResourceDuplication(source,"right",PackWorkspace.MAX_BYTES);var prepared=ReviewedResourceAddition.prepare(left.source,()->false);long revision=pack.revision();
        io(()->current.installReviewedAddition(right,prepared,PackWorkspace.MAX_BYTES));check(pack.revision()==revision && prepared.available(),"foreign target cannot consume prepared bytes");pack.installReviewedAddition(left,prepared,PackWorkspace.MAX_BYTES);check(!prepared.available(),"successful installation transfers private buffer once");io(()->current.installReviewedAddition(left,prepared,PackWorkspace.MAX_BYTES));
        var empty=PackWorkspace.newPack(Map.of("objects/empty.iob",new byte[0]));try(var stage=new ReviewedResourceAddition()) {stage.duplicate(empty,"objects/empty.iob","copy");var state=ready(stage,empty);check(state.receipt().bytes()==0 && stage.apply(empty,state.request(),null).equals("objects/copy.iob"),"empty opaque duplicates remain exact");}
        // One active preparation and one latest pending source; workers receive only Source, never Target/owner.
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var calls=Collections.synchronizedList(new ArrayList<String>());var active=new AtomicInteger();var maximum=new AtomicInteger();var caller=Thread.currentThread();
        try(var stage=new ReviewedResourceAddition((captured,cancelled)->{check(Thread.currentThread()!=caller && Thread.currentThread().isDaemon(),"worker off UI and daemon");calls.add(captured.plan.name());maximum.accumulateAndGet(active.incrementAndGet(),Math::max);try {if(captured.plan.name().equals("one")){entered.countDown();uninterruptible(release);}return ReviewedResourceAddition.prepare(captured,()->false);}finally{active.decrementAndGet();}},r->new Thread(r,"reviewed-resource-latest-test"))) {
            stage.duplicate(pack,source,"one");await(entered);stage.duplicate(pack,source,"two");var pending=stage.duplicate(pack,source,"three");release.countDown();var state=ready(stage,pack);check(state.request()==pending.request() && calls.equals(List.of("one","three")) && maximum.get()==1,"latest pending source and stale noncooperative outcome rejection");
            var foreign=PackWorkspace.newPack(Map.of(source,pack.read(source)));check(stage.poll(foreign).phase()==ReviewedResourceAddition.Phase.IDLE && stage.poll(pack).phase()==ReviewedResourceAddition.Phase.IDLE,"workspace identity switch never resurrects prepared data");
        }finally{release.countDown();}
        for(int mode=0;mode<3;mode++) {
            var enteredCase=new CountDownLatch(1);var released=new CountDownLatch(1);var thread=new AtomicReference<Thread>();var stop=new AtomicReference<java.util.function.BooleanSupplier>();var stage=new ReviewedResourceAddition((captured,cancelled)->{stop.set(cancelled);enteredCase.countDown();uninterruptible(released);return ReviewedResourceAddition.prepare(captured,()->false);},r->{var t=new Thread(r,"reviewed-resource-close-test");thread.set(t);return t;});
            try {stage.duplicate(pack,source,"blocked");await(enteredCase);long start=System.nanoTime();if(mode==0)stage.clear();else if(mode==1)stage.poll(PackWorkspace.empty());else stage.close();check(System.nanoTime()-start<TimeUnit.SECONDS.toNanos(1) && stop.get().getAsBoolean(),"clear/owner/close invalidation is nonblocking");released.countDown();stage.close();thread.get().join(10_000);check(!thread.get().isAlive() && stage.poll(pack).phase()==ReviewedResourceAddition.Phase.CLOSED,"noncooperative completion cannot publish after shutdown");}finally{released.countDown();stage.close();}
        }
        var attempts=new AtomicInteger();try(var stage=new ReviewedResourceAddition((captured,cancelled)->{if(attempts.incrementAndGet()==1)throw new OutOfMemoryError("injected");return ReviewedResourceAddition.prepare(captured,cancelled);},r->new Thread(r,"reviewed-resource-retry-test"))) {stage.duplicate(pack,source,"retry");var failed=terminal(stage,pack);check(failed.phase()==ReviewedResourceAddition.Phase.FAILED && failed.receipt()==null && stage.poll(pack)==failed && attempts.get()==1,"memory failure stays stable without hidden retry");stage.duplicate(pack,source,"retry");ready(stage,pack);check(attempts.get()==2,"explicit preparation retries after failure");}
        var snapshotPaths=new ArrayList<>(pack.paths());pack.saveCopy(temp.resolve("reviewed-addition-copy"));pack.saveAs(temp.resolve("reviewed-addition-as"));pack.exportZip(temp.resolve("reviewed-addition.zip"));for(var reopened:List.of(PackWorkspace.open(temp.resolve("reviewed-addition-copy")),PackWorkspace.open(temp.resolve("reviewed-addition-as")),PackWorkspace.importZip(temp.resolve("reviewed-addition.zip"))))for(String path:snapshotPaths)check(Arrays.equals(pack.read(path),reopened.read(path)),"byte-exact definitions/unknown/opaque persistence");
        System.out.println("PASS: reviewed definition creation across ten pinned template families and byte-exact JSON/image/object duplication, worker-only private copy/SHA-256 and one-shot buffer transfer, exact dependency/path/Windows/capacity/parent/source-stamp guards, current-history acknowledgement and late-edit refusal, no partial mutation or early history clear on failure, separate draft-safe opening, Save retention/source Undo and destination/parent invalidation, source/target receipt authority, one-active/latest-pending and noncooperative stale rejection, stable memory failure/explicit retry/nonblocking clear/close and exact Save/Copy/As/ZIP; no disk writes, reference rewrites or native generation until existing explicit save operations");
    }
}
