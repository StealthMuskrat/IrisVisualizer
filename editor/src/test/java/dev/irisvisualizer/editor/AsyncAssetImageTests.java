package dev.irisvisualizer.editor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

final class AsyncAssetImageTests {
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static void check(boolean ok,String detail) { if (!ok) throw new AssertionError("Async image loading: "+detail); }
    private static void rejects(Runnable action) { try { action.run(); } catch (IllegalArgumentException | IllegalStateException expected) { return; }throw new AssertionError("Expected rejected image-loader call"); }
    private static void await(CountDownLatch signal,String detail) throws InterruptedException { check(signal.await(5,TimeUnit.SECONDS),detail); }
    private static AssetImage.Decoded pixel(int marker) { return new AssetImage.Decoded(1,1,new byte[]{(byte)marker,0,0,(byte)255}); }
    private static AsyncAssetImage.Snapshot terminal(AsyncAssetImage loader) {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime()<deadline) { var next=loader.poll();if (next.phase()!=AsyncAssetImage.Phase.LOADING) return next;LockSupport.parkNanos(1_000_000); }
        throw new AssertionError("Image decode did not publish a terminal state");
    }
    private static final class Gate {
        final CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1);
    }
    private static final class Controlled implements AsyncAssetImage.Decoder {
        final Map<Integer,Gate> gates;final Thread caller=Thread.currentThread();
        final AtomicInteger active=new AtomicInteger(),maximum=new AtomicInteger();
        final List<Integer> calls=java.util.Collections.synchronizedList(new ArrayList<>());
        Controlled(Map<Integer,Gate> gates) { this.gates=gates; }
        public AssetImage.Decoded decode(byte[] raw) throws IOException {
            int marker=raw[0]&255;Gate gate=gates.get(marker);if (gate==null) throw new IOException("Unexpected marker "+marker);
            check(Thread.currentThread()!=caller,"decode stays off the request thread");int count=active.incrementAndGet();maximum.accumulateAndGet(count,Math::max);calls.add(marker);gate.started.countDown();
            try {
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);boolean ready=false;
                while (!ready && System.nanoTime()<deadline) {
                    try { ready=gate.release.await(20,TimeUnit.MILLISECONDS); }
                    catch (InterruptedException ignored) { /* Model a codec that does not stop on interruption. */ }
                }
                if (!ready) throw new IOException("Test decoder was not released");
                raw[0]=(byte)99; // Only this job's private copy is visible to the worker.
                return pixel(marker);
            } finally { active.decrementAndGet(); }
        }
    }
    private static java.util.concurrent.ThreadFactory threads(AtomicReference<Thread> reference) {
        return runnable -> { var worker=new Thread(runnable,"test-image-decoder");reference.set(worker);return worker; };
    }
    private static void stop(Thread thread) throws InterruptedException { thread.join(5000);check(!thread.isAlive(),"worker terminates after its active codec returns"); }
    static void run(Path temp) throws Exception {
        String a="images/a.png",b="images/b.png",c="images/c.png",other="blocks/base.json";
        // Selected-file stamps distinguish actual byte changes from saves, structural edits and unrelated Undo/Redo.
        var stamps=PackWorkspace.newPack(Map.of(a,new byte[]{1},other,bytes("{}")));long initial=stamps.contentRevision(a);Path stampRoot=temp.resolve("image-content-stamps");stamps.save(stampRoot);
        stamps.replace(a,new byte[]{1});check(stamps.contentRevision(a)==initial,"identical replacement is a no-op");
        stamps.replace(other,bytes("{\"value\":1}"));stamps.undo();stamps.redo();check(stamps.contentRevision(a)==initial,"unrelated history does not alter selected bytes");
        String folder=stamps.createSubfolder("images","empty");folder=stamps.renameEmptyFolder(folder,"renamed");stamps.deleteEmptyFolder(folder);String copy=stamps.duplicate(a,"copy");check(stamps.contentRevision(a)==initial && stamps.contentRevision(copy)!=initial,"catalog and folder changes retain source stamp");
        stamps.saveCopy(temp.resolve("image-content-stamps-copy"));stamps.saveAs(temp.resolve("image-content-stamps-as"));stamps.save(stamps.directory());stamps.exportZip(temp.resolve("image-content-stamps.zip"));check(stamps.contentRevision(a)==initial,"all persistence paths retain content stamp");
        stamps.replace(a,new byte[]{2});long changed=stamps.contentRevision(a);check(changed>initial,"actual replacement advances stamp");stamps.undo();long restored=stamps.contentRevision(a);check(restored>changed && Arrays.equals(stamps.read(a),new byte[]{1}),"selected Undo advances stamp");stamps.redo();check(stamps.contentRevision(a)>restored,"selected Redo advances stamp");
        String removed=stamps.createSubfolder("images","removed");stamps.save(stamps.directory());stamps.deleteEmptyFolder(removed);long beforeFailure=stamps.contentRevision(a);
        try { stamps.duplicate(a,"removed/new");throw new AssertionError(); } catch (IOException expected) { check(stamps.contentRevision(a)==beforeFailure,"failed structural operation preserves existing stamps"); }
        rejects(() -> stamps.contentRevision("images/removed/new.png"));rejects(() -> stamps.contentRevision("missing"));rejects(() -> stamps.contentRevision(null));
        var netZero=PackWorkspace.newPack(Map.of(a,new byte[]{1}));netZero.replace(a,new byte[]{2});netZero.replace(a,new byte[]{1});var history=netZero.historySteps(false);long equal=netZero.contentRevision(a);netZero.undoThrough(history.getLast().id());check(netZero.contentRevision(a)==equal,"multi-step Undo ending at identical bytes preserves stamp");netZero.redoThrough(history.getFirst().id());check(netZero.contentRevision(a)==equal,"multi-step Redo ending at identical bytes preserves stamp");
        // A active + B pending + C selected means only A and C decode, with no overlapping worker calls.
        var one=new Gate();var two=new Gate();var three=new Gate();var codec=new Controlled(Map.of(1,one,2,two,3,three));var thread=new AtomicReference<Thread>();
        var pack=PackWorkspace.newPack(Map.of(a,new byte[]{1},b,new byte[]{2},c,new byte[]{3},other,bytes("{}"),"objects/opaque.iob",new byte[]{0,-1,3},"future/unknown.json",bytes("bad JSON")));Path root=temp.resolve("async-images-source");pack.save(root);long revision=pack.revision();var drafts=new InspectorDrafts();drafts.update("late","stored","draft");
        try (var loader=new AsyncAssetImage(codec,threads(thread))) {
            var first=loader.select(pack,a);check(first.phase()==AsyncAssetImage.Phase.LOADING && first.image()==null,"request returns before blocked decoder");await(one.started,"first job started");
            pack.saveCopy(temp.resolve("async-images-loading-copy"));var whileLoading=PackWorkspace.open(temp.resolve("async-images-loading-copy"));check(loader.poll().phase()==AsyncAssetImage.Phase.LOADING && Arrays.equals(whileLoading.read(a),new byte[]{1}) && Arrays.equals(whileLoading.read(other),bytes("{}")),"Save Copy remains independent while decode is blocked");
            var second=loader.select(pack,b);var latest=loader.select(pack,c);check(latest.request()>second.request() && latest.path().equals(c) && latest.image()==null,"latest selection immediately hides stale pixels");
            one.release.countDown();await(three.started,"latest pending job replaces the intermediate one");check(two.started.getCount()==1 && loader.poll().request()==latest.request() && loader.poll().phase()==AsyncAssetImage.Phase.LOADING,"stale completed image is discarded");
            three.release.countDown();var ready=terminal(loader);check(ready.phase()==AsyncAssetImage.Phase.READY && ready.image().argbAt(0,0)==0xff030000 && codec.calls.equals(List.of(1,3)) && codec.maximum.get()==1,"only latest result publishes with one active decoder");
            check(thread.get().isDaemon() && loader.select(pack,c)==ready && pack.revision()==revision && !pack.dirty() && drafts.any() && Arrays.equals(pack.read(a),new byte[]{1}) && Arrays.equals(pack.read(c),new byte[]{3}),"private encoded copies, immutable snapshot reuse and zero workspace/draft mutation");
            byte[] rgba=ready.image().rgba();rgba[0]=0;check(ready.image().argbAt(0,0)==0xff030000,"published pixels are immutable");
            pack.replace(other,bytes("{\"changed\":true}"));pack.createSubfolder("images","new_folder");check(loader.select(pack,c)==ready,"unrelated changes retain loaded image/request");
            pack.saveCopy(temp.resolve("async-images-copy"));pack.saveAs(temp.resolve("async-images-as"));pack.save(pack.directory());pack.exportZip(temp.resolve("async-images.zip"));check(loader.select(pack,c)==ready && codec.calls.equals(List.of(1,3)),"Save and Save As do not restart decoding");
            var retry=loader.retry(pack,c);check(retry.request()>ready.request() && retry.phase()==AsyncAssetImage.Phase.LOADING && retry.image()==null,"retry creates a fresh generation");check(terminal(loader).image().argbAt(0,0)==0xff030000 && codec.calls.equals(List.of(1,3,3)),"retry decodes exactly once");
            var none=loader.select(pack,"objects/opaque.iob");check(none.phase()==AsyncAssetImage.Phase.NONE && none.image()==null,"unsupported selection clears decoded result");
            var missing=loader.select(pack,"images/missing.png");check(missing.phase()==AsyncAssetImage.Phase.FAILED && missing.image()==null && loader.select(pack,"images/missing.png")==missing,"missing resource fails once without a worker call");
            var oversized=PackWorkspace.newPack(Map.of("images/large.png",new byte[AssetImage.MAX_ENCODED_BYTES+1]));check(loader.select(oversized,"images/large.png").phase()==AsyncAssetImage.Phase.FAILED && codec.calls.size()==3,"encoded-size guard runs before decoding");
        }
        stop(thread.get());
        for (var reopened : List.of(PackWorkspace.open(temp.resolve("async-images-copy")),PackWorkspace.open(temp.resolve("async-images-as")),PackWorkspace.importZip(temp.resolve("async-images.zip")))) for (String path : pack.paths()) check(Arrays.equals(pack.read(path),reopened.read(path)),"exact persistence while using asynchronous image loading");
        // A changed selected file gets a new generation even if its workspace and path stay the same.
        one=new Gate();two=new Gate();codec=new Controlled(Map.of(1,one,2,two));thread=new AtomicReference<>();
        var changedPack=PackWorkspace.newPack(Map.of(a,new byte[]{1}));try (var loader=new AsyncAssetImage(codec,threads(thread))) {
            var old=loader.select(changedPack,a);await(one.started,"changed-file first decode started");changedPack.replace(a,new byte[]{2});var next=loader.select(changedPack,a);check(next.request()>old.request() && next.image()==null,"selected byte change invalidates immediately");
            one.release.countDown();await(two.started,"changed-file replacement started");check(loader.poll().phase()==AsyncAssetImage.Phase.LOADING,"old bytes cannot publish at the same path");two.release.countDown();check(terminal(loader).image().argbAt(0,0)==0xff020000,"replacement pixels publish");
            var otherWorkspace=PackWorkspace.newPack(Map.of(a,new byte[]{1}));var replacement=loader.select(otherWorkspace,a);check(replacement.phase()==AsyncAssetImage.Phase.LOADING && replacement.image()==null,"workspace identity invalidates same-path results");check(terminal(loader).image().argbAt(0,0)==0xff010000,"new workspace gets its own pixels");
            var sameStamp=PackWorkspace.newPack(Map.of(a,new byte[]{2}));check(otherWorkspace.contentRevision(a)==sameStamp.contentRevision(a),"separate packs can have the same local stamp");loader.select(sameStamp,a);check(terminal(loader).image().argbAt(0,0)==0xff020000,"workspace identity distinguishes equal path/stamp pairs");
        }stop(thread.get());
        // Exceptions publish once, remain stable until explicit retry/change, and expose bounded display-safe messages.
        var calls=new AtomicInteger();thread=new AtomicReference<>();var errors=PackWorkspace.newPack(Map.of(a,new byte[]{0}));
        try (var loader=new AsyncAssetImage(raw -> { calls.incrementAndGet();return switch (raw[0]) { case 0 -> throw new IOException("bad\n"+"x".repeat(600));case 2 -> null;case 3 -> throw new IllegalArgumentException();default -> pixel(1); }; },threads(thread))) {
            loader.select(errors,a);var failure=terminal(loader);check(failure.phase()==AsyncAssetImage.Phase.FAILED && failure.error().length()<=515 && !failure.error().contains("\n") && failure.error().contains("\\u000a"),"bounded escaped errors");
            check(loader.select(errors,a)==failure && calls.get()==1,"failure does not automatically retry each frame");loader.retry(errors,a);check(terminal(loader).phase()==AsyncAssetImage.Phase.FAILED && calls.get()==2,"explicit retry after failure");
            errors.replace(a,new byte[]{2});loader.select(errors,a);check(terminal(loader).phase()==AsyncAssetImage.Phase.FAILED,"null decoder output becomes a failure");errors.replace(a,new byte[]{3});loader.select(errors,a);check(terminal(loader).error().contains("IllegalArgumentException"),"empty exception message has a fallback");errors.replace(a,new byte[]{1});loader.select(errors,a);check(terminal(loader).phase()==AsyncAssetImage.Phase.READY,"changed valid content recovers from decode failure");
        }stop(thread.get());
        // Closing invalidates pending/completed work immediately and does not join an uninterruptible active codec.
        var blocked=new Gate();var waiting=new Gate();var closingCodec=new Controlled(Map.of(1,blocked,2,waiting));var closingThread=new AtomicReference<Thread>();var closing=new AsyncAssetImage(closingCodec,threads(closingThread));
        closing.select(changedPack,a);await(waiting.started,"close test active job started");closing.select(PackWorkspace.newPack(Map.of(a,new byte[]{1})),a);closing.close();closing.close();check(closing.poll().phase()==AsyncAssetImage.Phase.CLOSED && closing.poll().image()==null && closingThread.get().isAlive() && blocked.started.getCount()==1,"close returns while active codec is still blocked and discards pending work");rejects(() -> closing.select(pack,a));rejects(() -> closing.retry(pack,a));waiting.release.countDown();stop(closingThread.get());check(blocked.started.getCount()==1 && closing.poll().phase()==AsyncAssetImage.Phase.CLOSED,"closed worker cannot publish or start pending work");
        // Production PNG/JPEG decoding uses the same bounded first-frame decoder without modifying encoded assets.
        var raster=new java.awt.image.BufferedImage(2,1,java.awt.image.BufferedImage.TYPE_INT_ARGB);raster.setRGB(0,0,0x80402010);raster.setRGB(1,0,0xff00ff00);var png=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(raster,"png",png);
        var rgb=new java.awt.image.BufferedImage(2,1,java.awt.image.BufferedImage.TYPE_INT_RGB);rgb.setRGB(0,0,0xff102030);var jpg=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(rgb,"jpeg",jpg);
        var real=PackWorkspace.newPack(Map.of(a,png.toByteArray(),"images/photo.jpg",jpg.toByteArray(),b,new byte[]{1,2,3}));revision=real.revision();
        try (var loader=new AsyncAssetImage()) {
            loader.select(real,a);var decoded=terminal(loader);check(decoded.phase()==AsyncAssetImage.Phase.READY && decoded.image().argbAt(0,0)==0x80402010 && decoded.image().argbAt(1,0)==0xff00ff00,"actual PNG channel fidelity");loader.select(real,"images/photo.jpg");check(terminal(loader).image().width()==2,"actual JPEG first frame");loader.select(real,b);check(terminal(loader).phase()==AsyncAssetImage.Phase.FAILED,"actual invalid image error");check(real.revision()==revision && Arrays.equals(real.read(a),png.toByteArray()),"actual decoding does not write the pack");
        }
        System.out.println("PASS: single-worker/latest-pending asynchronous PNG/JPEG loading, UI-thread-independent decode, selected-byte stamps and unrelated Save/history isolation, stale selection/content/workspace rejection, explicit retry/error bounds, private immutable pixels, nonblocking close and exact persistence without native preview generation");
    }
}
