package dev.irisvisualizer.editor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Deferred end-of-UI contracts. Errors are injected; these cases never exhaust the process heap. */
final class AsyncWorkerFailureTests {
    private static byte[] bytes(String text){return text.getBytes(StandardCharsets.UTF_8);}
    private static void check(boolean value,String message){if(!value)throw new AssertionError("UI worker failure: "+message);}
    private static OutOfMemoryError exhausted(){return new OutOfMemoryError("Injected worker failure");}
    private static final class Probe implements ThreadFactory {
        private Thread thread;private final AtomicReference<Throwable> uncaught=new AtomicReference<>();
        @Override public Thread newThread(Runnable task){thread=new Thread(task,"test-ui-worker-failure");thread.setUncaughtExceptionHandler((t,error)->uncaught.set(error));return thread;}
        void alive(){check(thread.isAlive() && uncaught.get()==null,"handled failure leaves the same worker available");}
        void stopped()throws InterruptedException{thread.join(5000);check(!thread.isAlive() && uncaught.get()==null,"closed worker exits without an uncaught error");}
    }
    private static <T> T terminal(Supplier<T> poll,Predicate<T> running){
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        do{T state=poll.get();if(!running.test(state)){T latest=poll.get();if(!running.test(latest))return latest;}LockSupport.parkNanos(1_000_000);}while(System.nanoTime()<end);
        throw new AssertionError("Worker did not publish a terminal result");
    }
    static void run(Path temp)throws Exception{
        Path source=temp.resolve("worker-incoming.iob");byte[] incoming={-1,2,3};Files.write(source,incoming);
        assetReaders(temp,source,incoming);imageAndDisk(temp);copyAndZip(temp);saveAs(temp);obsoleteFailure(source);
        System.out.println("PASS: injected asset capture/image analysis/disk review memory failures publish stable terminal errors without worker death, explicit retry and same-worker recovery, no stale failure overriding a newer request, copy/ZIP prepublication and completed-publication failure diagnostics, no false receipt or Save As baseline adoption, exact preserved and retried bytes, retained drafts/history and nonblocking cancellation/close; no actual heap exhaustion or native generation");
    }
    private static void assetReaders(Path temp,Path source,byte[] incoming)throws Exception{
        var pack=PackWorkspace.newPack(Map.of("objects/original.iob",new byte[]{0},"unknown/future.json",bytes("malformed future data")));
        var probe=new Probe();var calls=new AtomicInteger();long revision=pack.revision();
        try(var worker=new AsyncAssetImport((path,target,budget,cancelled)->{if(calls.incrementAndGet()==1)throw exhausted();return AssetReplacement.prepare(path,target,budget,cancelled);},probe)){
            worker.request(pack,source,"added");var failed=terminal(()->worker.poll(pack),s->s.phase()==AsyncAssetImport.Phase.CAPTURING);
            check(failed.phase()==AsyncAssetImport.Phase.FAILED && failed.receipt()==null && failed.detail().contains("memory") && pack.revision()==revision,"new-asset memory failure installs nothing");probe.alive();
            for(int frame=0;frame<8;frame++)check(worker.poll(pack)==failed && calls.get()==1,"polling a failed capture does not retry");
            pack.saveCopy(temp.resolve("worker-failed-copy"));pack.exportZip(temp.resolve("worker-failed.zip"));check(worker.poll(pack)==failed,"Save Copy/ZIP remain available and do not retry asset capture");
            worker.request(pack,source,"added");check(terminal(()->worker.poll(pack),s->s.phase()==AsyncAssetImport.Phase.CAPTURING).phase()==AsyncAssetImport.Phase.READY && calls.get()==2,"same import worker recovers on explicit source choice");
            check(worker.apply(pack,false).equals("objects/added.iob") && Arrays.equals(pack.read("objects/added.iob"),incoming),"retried import applies exact captured bytes");
        }probe.stopped();
        var replacementPack=PackWorkspace.newPack(Map.of("objects/original.iob",new byte[]{0}));probe=new Probe();var replacementCalls=new AtomicInteger();
        try(var worker=new AsyncAssetReplacement((path,target,budget,cancelled)->{if(replacementCalls.incrementAndGet()==1)throw exhausted();return AssetReplacement.prepare(path,target,budget,cancelled);},probe)){
            worker.request(replacementPack,"objects/original.iob",source);var failed=terminal(()->worker.poll(replacementPack),s->s.phase()==AsyncAssetReplacement.Phase.CAPTURING);
            check(failed.phase()==AsyncAssetReplacement.Phase.FAILED && failed.receipt()==null && Arrays.equals(replacementPack.read("objects/original.iob"),new byte[]{0}),"replacement memory failure preserves old opaque bytes");probe.alive();
            check(worker.poll(replacementPack)==failed && replacementCalls.get()==1,"replacement failure stays stable");worker.request(replacementPack,"objects/original.iob",source);
            check(terminal(()->worker.poll(replacementPack),s->s.phase()==AsyncAssetReplacement.Phase.CAPTURING).phase()==AsyncAssetReplacement.Phase.READY,"same replacement worker recovers");worker.apply(replacementPack,false);check(Arrays.equals(replacementPack.read("objects/original.iob"),incoming),"replacement retry is explicit and byte-exact");
        }probe.stopped();
        var batchPack=PackWorkspace.newPack(Map.of("objects/original.iob",new byte[]{0}));probe=new Probe();var batchCalls=new AtomicInteger();
        try(var worker=new AsyncAssetBatchImport((inputs,budget,cancelled)->{if(batchCalls.incrementAndGet()==1)throw exhausted();return StagedAssetBatchImport.prepare(inputs,budget,cancelled);},probe)){
            worker.request(batchPack,List.of(source),"batch");var failed=terminal(()->worker.poll(batchPack),s->s.phase()==AsyncAssetBatchImport.Phase.CAPTURING);
            check(failed.phase()==AsyncAssetBatchImport.Phase.FAILED && failed.receipts().isEmpty() && batchPack.paths().size()==1,"batch failure installs no partial files");probe.alive();
            check(worker.poll(batchPack)==failed && batchCalls.get()==1,"batch failure does not automatically recapture");worker.request(batchPack,List.of(source),"batch");
            check(terminal(()->worker.poll(batchPack),s->s.phase()==AsyncAssetBatchImport.Phase.CAPTURING).phase()==AsyncAssetBatchImport.Phase.READY,"batch capture recovers on its existing worker");String installed=worker.apply(batchPack,false).getFirst();check(Arrays.equals(batchPack.read(installed),incoming),"retried batch preserves opaque bytes");
        }probe.stopped();
    }
    private static void imageAndDisk(Path temp)throws Exception{
        var image=new AssetImage.Decoded(1,1,new byte[]{1,2,3,-1});var region=ImageStatistics.Region.whole(image);var calls=new AtomicInteger();var probe=new Probe();
        try(var worker=new AsyncImageStatistics((pixels,area,cancelled,progress)->{if(calls.incrementAndGet()==1)throw exhausted();return ImageStatistics.analyze(pixels,area,cancelled,progress);},probe)){
            worker.start(image,region);var failed=terminal(()->worker.poll(image),s->s.phase()==AsyncImageStatistics.Phase.ANALYZING);check(failed.phase()==AsyncImageStatistics.Phase.FAILED && failed.result()==null && failed.error().contains("memory"),"pixel analysis memory error is terminal");probe.alive();
            check(worker.poll(image)==failed && calls.get()==1,"pixel analysis waits for explicit retry");worker.start(image,region);check(terminal(()->worker.poll(image),s->s.phase()==AsyncImageStatistics.Phase.ANALYZING).phase()==AsyncImageStatistics.Phase.READY,"same pixel-analysis worker recovers");worker.clear();check(worker.poll(null).phase()==AsyncImageStatistics.Phase.IDLE,"clear releases image analysis ownership");
        }probe.stopped();
        var pack=PackWorkspace.newPack(Map.of("blocks/a.json",bytes("before")));pack.save(temp.resolve("worker-disk-source"));pack.replace("blocks/a.json",bytes("after"));var diskCalls=new AtomicInteger();probe=new Probe();long revision=pack.revision(),saved=pack.saveRevision();int history=pack.undoCount();
        try(var worker=new AsyncPendingDiskReview((source,cancelled,progress)->{if(diskCalls.incrementAndGet()==1)throw exhausted();return PendingDiskReview.inspect(source,cancelled,progress);},probe)){
            worker.start(pack,null);var failed=terminal(()->worker.poll(pack),s->s.phase()==AsyncPendingDiskReview.Phase.CHECKING);check(failed.phase()==AsyncPendingDiskReview.Phase.FAILED && failed.report()==null && !worker.busy() && failed.detail().contains("memory"),"disk memory failure clears busy state and grants no report");probe.alive();
            check(worker.poll(pack)==failed && diskCalls.get()==1,"disk review is not automatically rescanned");worker.start(pack,null);check(terminal(()->worker.poll(pack),s->s.phase()==AsyncPendingDiskReview.Phase.CHECKING).phase()==AsyncPendingDiskReview.Phase.READY,"same disk-review worker recovers");
            check(pack.revision()==revision && pack.saveRevision()==saved && pack.undoCount()==history && Arrays.equals(pack.read("blocks/a.json"),bytes("after")),"advisory review changes no data/baseline/history");
        }probe.stopped();
    }
    private static void copyAndZip(Path temp)throws Exception{
        var pack=PackWorkspace.newPack(Map.of("blocks/a.json",bytes("{\"future\":9007199254740993}"),"objects/a.iob",new byte[]{0,-1}));var probe=new Probe();var calls=new AtomicInteger();
        try(var worker=new AsyncFolderCopy((source,path,control,progress)->{int attempt=calls.incrementAndGet();if(attempt==1)throw exhausted();var receipt=FolderCopies.copy(source,path,control,progress);if(attempt==2)throw exhausted();return receipt;},probe)){
            Path absent=temp.resolve("worker-copy-absent"),published=temp.resolve("worker-copy-published"),retry=temp.resolve("worker-copy-retry");
            worker.start(pack,absent);var failed=terminal(worker::poll,s->worker.busy());check(failed.phase()==AsyncFolderCopy.Phase.FAILED && failed.receipt()==null && !Files.exists(absent),"copy exhaustion before publication produces no success receipt");probe.alive();check(worker.poll()==failed && calls.get()==1,"copy failure is stable without retry");
            worker.start(pack,published);failed=terminal(worker::poll,s->worker.busy());check(failed.phase()==AsyncFolderCopy.Phase.FAILED && failed.receipt()==null && failed.detail().contains("Publication had begun") && failed.detail().contains("inspect the destination"),"copy failure after writer completion still reports publication uncertainty");
            check(Arrays.equals(PackWorkspace.open(published).read("blocks/a.json"),pack.read("blocks/a.json")),"published folder is preserved for inspection after receipt failure");
            worker.start(pack,retry);check(terminal(worker::poll,s->worker.busy()).phase()==AsyncFolderCopy.Phase.SUCCEEDED && calls.get()==3,"same copy worker succeeds on a separately chosen destination");
        }probe.stopped();
        probe=new Probe();var zipCalls=new AtomicInteger();
        try(var worker=new AsyncZipExport((source,path,replace,control,progress)->{int attempt=zipCalls.incrementAndGet();if(attempt==1)throw exhausted();var receipt=ZipExports.export(source,path,replace,control,progress);if(attempt==2)throw exhausted();return receipt;},probe)){
            Path absent=temp.resolve("worker-zip-absent.zip"),published=temp.resolve("worker-zip-published.zip"),retry=temp.resolve("worker-zip-retry.zip");
            worker.start(pack,absent,false);var failed=terminal(worker::poll,s->worker.busy());check(failed.phase()==AsyncZipExport.Phase.FAILED && failed.receipt()==null && !Files.exists(absent),"ZIP exhaustion before publication produces no archive receipt");probe.alive();check(worker.poll()==failed && zipCalls.get()==1,"failed ZIP is not automatically retried");
            worker.start(pack,published,false);failed=terminal(worker::poll,s->worker.busy());check(failed.phase()==AsyncZipExport.Phase.FAILED && failed.receipt()==null && failed.detail().contains("Publication had begun"),"completed ZIP publication is retained in later memory-failure diagnostics");
            check(Arrays.equals(PackWorkspace.importZip(published).read("objects/a.iob"),pack.read("objects/a.iob")),"published ZIP remains intact after receipt failure");
            worker.start(pack,retry,false);check(terminal(worker::poll,s->worker.busy()).phase()==AsyncZipExport.Phase.SUCCEEDED && zipCalls.get()==3,"same ZIP worker succeeds after explicit retry");
        }probe.stopped();check(pack.directory()==null && pack.dirty(),"copy/export never mark the captured workspace saved");
    }
    private static void saveAs(Path temp)throws Exception{
        var pack=PackWorkspace.newPack(Map.of("blocks/a.json",bytes("before")));Path original=temp.resolve("worker-save-as-source");pack.save(original);pack.replace("blocks/a.json",bytes("after"));long saved=pack.saveRevision(),revision=pack.revision();int history=pack.undoCount();var probe=new Probe();var calls=new AtomicInteger();
        try(var worker=new AsyncSaveAs((source,path,control,progress)->{var receipt=FolderCopies.copyForSaveAs(source,path,control,progress);if(calls.incrementAndGet()==1)throw exhausted();return receipt;},probe)){
            Path failedDirectory=temp.resolve("worker-save-as-failed"),retry=temp.resolve("worker-save-as-retry");worker.start(pack,failedDirectory);var failed=terminal(()->worker.poll(pack),s->worker.busy());
            check(failed.phase()==AsyncSaveAs.Phase.FAILED && failed.receipt()==null && failed.detail().contains("Publication had begun"),"Save As exposes postpublication memory failure without a receipt");probe.alive();
            check(worker.finish(pack).phase()==AsyncSaveAs.Phase.FAILED && pack.directory().equals(original.toAbsolutePath().normalize()) && pack.saveRevision()==saved && pack.revision()==revision && pack.undoCount()==history && pack.dirty(),"failed Save As never adopts a destination or clears its old baseline/history");
            check(Arrays.equals(PackWorkspace.open(failedDirectory).read("blocks/a.json"),bytes("after")),"failed adoption retains the published snapshot for manual inspection");
            worker.start(pack,retry);check(terminal(()->worker.poll(pack),s->worker.busy()).phase()==AsyncSaveAs.Phase.READY,"same Save As worker can publish a fresh explicit retry");
            check(worker.finish(pack).phase()==AsyncSaveAs.Phase.ADOPTED && pack.directory().equals(retry.toAbsolutePath().normalize()) && !pack.dirty() && pack.undoCount()==history,"only successful current receipt adopts its baseline");
        }probe.stopped();
    }
    private static void obsoleteFailure(Path source)throws Exception{
        var pack=PackWorkspace.newPack(Map.of("objects/original.iob",new byte[]{0}));var started=new CountDownLatch(1);var release=new CountDownLatch(1);var calls=new AtomicInteger();var active=new AtomicInteger();var maximum=new AtomicInteger();var probe=new Probe();
        try(var worker=new AsyncAssetImport((path,target,budget,cancelled)->{int concurrent=active.incrementAndGet();maximum.accumulateAndGet(concurrent,Math::max);try{if(calls.incrementAndGet()==1){started.countDown();try{if(!release.await(5,TimeUnit.SECONDS))throw new IOException("Test release timed out");}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IOException(interrupted);}throw exhausted();}return AssetReplacement.prepare(path,target,budget,cancelled);}finally{active.decrementAndGet();}},probe)){
            worker.request(pack,source,"old");check(started.await(5,TimeUnit.SECONDS),"old request is active");worker.request(pack,source,"latest");release.countDown();var ready=terminal(()->worker.poll(pack),s->s.phase()==AsyncAssetImport.Phase.CAPTURING);
            check(ready.phase()==AsyncAssetImport.Phase.READY && ready.receipt().target().equals("objects/latest.iob") && calls.get()==2 && maximum.get()==1,"obsolete memory failure cannot replace latest request or overlap capture");probe.alive();
        }finally{release.countDown();}probe.stopped();
    }
}
