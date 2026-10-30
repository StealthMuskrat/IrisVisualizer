package dev.irisvisualizer.editor;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.zip.*;

final class PackLoadTests {
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static void check(boolean value) { if(!value)throw new AssertionError("Background pack load contract failed"); }
    private static void io(InspectorDrafts.Change action) throws IOException { try { action.run(); } catch(IOException expected) { return; }throw new AssertionError("Expected pack load rejection"); }
    private static PackLoads.Prepared load(Path source,PackLoads.Kind kind) throws IOException { return PackLoads.prepare(source,kind,()->false,p->{}); }
    private static PackLoads.Prepared limited(Path source,PackLoads.Kind kind,PackWorkspace.LoadLimits limits) throws IOException { return PackLoads.prepare(source,kind,()->false,p->{},limits); }
    private static void zip(Path path,Map<String,byte[]> content) throws IOException {
        try(var zip=new ZipOutputStream(Files.newOutputStream(path))) { for(var item:content.entrySet()) { zip.putNextEntry(new ZipEntry(item.getKey()));zip.write(item.getValue());zip.closeEntry(); } }
    }
    private static void exact(PackWorkspace expected,PackWorkspace actual) {
        check(expected.paths().equals(actual.paths()) && expected.directories().equals(actual.directories()));for(String key:expected.paths())check(Arrays.equals(expected.read(key),actual.read(key)));
    }
    private static AsyncPackLoad.Snapshot ready(AsyncPackLoad loader) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);AsyncPackLoad.Snapshot state;
        while((state=loader.poll()).phase()==AsyncPackLoad.Phase.LOADING && System.nanoTime()<end)Thread.sleep(1);check(state.phase()==AsyncPackLoad.Phase.READY);return state;
    }
    private static void stopped(AsyncPackLoad loader) throws Exception { long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(loader.busy() && System.nanoTime()<end)Thread.sleep(1);check(!loader.busy()); }
    private static void awaitIgnoringInterrupts(CountDownLatch latch) { while(true)try { latch.await();return; }catch(InterruptedException ignored){} }
    private static int central(byte[] zip) { for(int i=0;i<zip.length-3;i++)if(zip[i]==0x50 && zip[i+1]==0x4b && zip[i+2]==1 && zip[i+3]==2)return i;throw new AssertionError("No central directory"); }
    private record Attributes(boolean isRegularFile,boolean isDirectory,long size,FileTime lastModifiedTime,Object fileKey) implements java.nio.file.attribute.BasicFileAttributes {
        public FileTime lastAccessTime(){return lastModifiedTime;}public FileTime creationTime(){return lastModifiedTime;}public boolean isSymbolicLink(){return false;}public boolean isOther(){return false;}
    }
    static void run(Path temp) throws Exception {
        // Cached directory-enumeration sizes are not comparable with explicit Windows attribute reads.
        var time=FileTime.fromMillis(42);check(PackLoads.same(new Attributes(false,true,4096,time,null),new Attributes(false,true,0,time,null)));
        check(!PackLoads.same(new Attributes(false,true,4096,time,null),new Attributes(false,true,0,FileTime.fromMillis(43),null)));
        check(!PackLoads.same(new Attributes(true,false,4096,time,null),new Attributes(true,false,0,time,null)));
        check(!PackLoads.same(new Attributes(false,true,0,time,"old"),new Attributes(false,true,0,time,"new")));
        byte[] opaque={0,1,-1,4};byte[] unknown=bytes("invalid JSON 雪");String file="blocks/base.json";
        var seed=PackWorkspace.newPack(Map.of(file,bytes("{\r\n \"block\":\"minecraft:stone\",\"future\":9007199254740993\r\n}"),"future/unknown.json",unknown,"objects/exact.iob",opaque,"empty.bin",new byte[0]));seed.createSubfolder("blocks","empty");
        Path root=temp.resolve("pack-load-source"),archive=temp.resolve("pack-load-source.zip");seed.save(root);var original=PackWorkspace.open(root);original.exportZip(archive);
        var progress=new ArrayList<PackLoads.Progress>();var opened=PackLoads.prepare(root,PackLoads.Kind.DIRECTORY,()->false,progress::add);exact(original,opened.workspace);
        check(!opened.workspace.dirty() && opened.workspace.undoCount()==0 && opened.receipt.activeDirectory().equals(root.toRealPath()) && opened.receipt.files()==4);
        long total=original.paths().stream().mapToLong(original::size).sum();check(opened.receipt.bytes()==total && progress.getLast().bytes()==total && progress.getLast().files()==4);
        long lastBytes=-1;int lastNodes=-1;for(var p:progress) {check(p.bytes()>=lastBytes && p.nodes()>=lastNodes);lastBytes=p.bytes();lastNodes=p.nodes();}
        progress.clear();var imported=PackLoads.prepare(archive,PackLoads.Kind.ZIP,()->false,progress::add);exact(original,imported.workspace);check(imported.workspace.directory()==null && !imported.workspace.dirty() && imported.receipt.bytes()==total);
        lastBytes=-1;lastNodes=-1;for(var p:progress) {check(p.bytes()>=lastBytes && p.nodes()>=lastNodes);lastBytes=p.bytes();lastNodes=p.nodes();}
        // New/unknown/opaque/empty resources stay exact through subsequent folder and ZIP workflows.
        imported.workspace.saveCopy(temp.resolve("pack-load-copy"));exact(original,PackWorkspace.open(temp.resolve("pack-load-copy")));Path round=temp.resolve("pack-load-round.zip");imported.workspace.exportZip(round);exact(original,load(round,PackLoads.Kind.ZIP).workspace);
        // Expanded bytes, implicit folders, depth and metadata path characters are bounded during preparation.
        Path bounded=Files.createDirectory(temp.resolve("pack-load-bounded"));Files.createDirectories(bounded.resolve("a/b"));Files.write(bounded.resolve("a/b/data.bin"),new byte[]{1,2,3,4});Path boundedZip=temp.resolve("pack-load-bounded.zip");zip(boundedZip,Map.of("a/b/data.bin",new byte[]{1,2,3,4}));
        for(var kind:PackLoads.Kind.values()) {
            Path path=kind==PackLoads.Kind.DIRECTORY ? bounded : boundedZip;
            check(limited(path,kind,new PackWorkspace.LoadLimits(4,3,3,16)).workspace.size("a/b/data.bin")==4);
            io(()->limited(path,kind,new PackWorkspace.LoadLimits(3,3,3,16)));io(()->limited(path,kind,new PackWorkspace.LoadLimits(4,2,3,16)));io(()->limited(path,kind,new PackWorkspace.LoadLimits(4,3,2,16)));io(()->limited(path,kind,new PackWorkspace.LoadLimits(4,3,3,15)));
            try { PackLoads.prepare(path,kind,()->true,p->{});throw new AssertionError(); }catch(CancellationException expected){}
        }
        // Cancellation checks run between source reads and fingerprint chunks, with no candidate publication.
        Path stream=Files.createDirectory(temp.resolve("pack-load-stream"));Files.write(stream.resolve("payload.bin"),new byte[24_000]);Path streamZip=temp.resolve("pack-load-stream.zip");zip(streamZip,Map.of("payload.bin",new byte[24_000]));
        for(var kind:PackLoads.Kind.values()) {
            var cancel=new AtomicBoolean();var read=new AtomicLong();Path path=kind==PackLoads.Kind.DIRECTORY ? stream : streamZip;
            try { PackLoads.prepare(path,kind,cancel::get,p->{ read.set(p.bytes());if(p.bytes()>=8192)cancel.set(true); });throw new AssertionError(); }catch(CancellationException expected) {check(read.get()>=8192 && read.get()<24_000);}
        }
        var checks=new AtomicInteger();var monitor=new PackLoads.Monitor(()->checks.incrementAndGet()>4,p->{});
        try { PackWorkspace.readBounded(new ByteArrayInputStream(new byte[32_000]),32_000,monitor,n->{});throw new AssertionError(); }catch(CancellationException expected){}
        // Zero-returning streams still advance, enforce overflow and honor cancellation without a spin.
        var zero=new InputStream() { int remaining=4;@Override public int read(byte[] b,int o,int n){return 0;}@Override public int read(){return remaining-- >0 ? 7 : -1;} };
        check(PackWorkspace.readBounded(zero,4).length==4);io(()->PackWorkspace.readBounded(new ByteArrayInputStream(new byte[5]),4));
        // Observable file/folder/archive changes during loading fail without editing any source bytes.
        var once=new AtomicBoolean();io(()->PackLoads.prepare(stream,PackLoads.Kind.DIRECTORY,()->false,p->{if(p.bytes()>0 && once.compareAndSet(false,true))try {Files.setLastModifiedTime(stream.resolve("payload.bin"),FileTime.fromMillis(1));}catch(IOException e){throw new UncheckedIOException(e);}}));
        once.set(false);io(()->PackLoads.prepare(stream,PackLoads.Kind.DIRECTORY,()->false,p->{if(p.bytes()>0 && once.compareAndSet(false,true))try {Files.writeString(stream.resolve("late.bin"),"external");}catch(IOException e){throw new UncheckedIOException(e);}}));check(Files.readString(stream.resolve("late.bin")).equals("external"));Files.delete(stream.resolve("late.bin"));
        once.set(false);io(()->PackLoads.prepare(streamZip,PackLoads.Kind.ZIP,()->false,p->{if(once.compareAndSet(false,true))try {Files.setLastModifiedTime(streamZip,FileTime.fromMillis(1));}catch(IOException e){throw new UncheckedIOException(e);}}));
        // A valid empty ZIP is distinct from malformed/truncated bytes; local/central names and CRCs must agree.
        Path empty=temp.resolve("pack-load-empty.zip");zip(empty,Map.of());check(load(empty,PackLoads.Kind.ZIP).workspace.paths().isEmpty());
        Path bad=temp.resolve("pack-load-malformed.zip");Files.writeString(bad,"not ZIP");io(()->load(bad,PackLoads.Kind.ZIP));Files.write(bad,new byte[0]);io(()->load(bad,PackLoads.Kind.ZIP));
        byte[] raw=Files.readAllBytes(boundedZip);Files.write(bad,Arrays.copyOf(raw,raw.length-10));io(()->load(bad,PackLoads.Kind.ZIP));
        byte[] renamed=raw.clone();renamed[central(renamed)+46]='x';Files.write(bad,renamed);io(()->load(bad,PackLoads.Kind.ZIP));
        byte[] crc=raw.clone();crc[central(crc)+16]^=1;Files.write(bad,crc);io(()->load(bad,PackLoads.Kind.ZIP));
        byte[] missing=raw.clone();missing[0]=0;Files.write(bad,missing);io(()->load(bad,PackLoads.Kind.ZIP));
        zip(bad,Map.of("../escape.bin",opaque));io(()->load(bad,PackLoads.Kind.ZIP));check(!Files.exists(temp.resolve("escape.bin")));
        zip(bad,Map.of("a.bin",opaque,"A.bin",opaque));io(()->load(bad,PackLoads.Kind.ZIP));zip(bad,Map.of("folder/",opaque));io(()->load(bad,PackLoads.Kind.ZIP));
        // Directory source links, root links and archive links are rejected where creation is permitted.
        Path link=temp.resolve("pack-load-root-link");boolean linked=false;try{Files.createSymbolicLink(link,root);linked=true;}catch(IOException | UnsupportedOperationException unavailable){}
        if(linked){io(()->load(link,PackLoads.Kind.DIRECTORY));Files.delete(link);Files.createSymbolicLink(link,archive);io(()->load(link,PackLoads.Kind.ZIP));Files.delete(link);Files.createSymbolicLink(stream.resolve("link.bin"),archive);io(()->load(stream,PackLoads.Kind.DIRECTORY));Files.delete(stream.resolve("link.bin"));}
        // The worker receives paths only and never replaces/edits the currently active pack. Handoff is explicit/one-shot.
        var current=PackWorkspace.newPack(Map.of(file,bytes("current")));var drafts=new InspectorDrafts();drafts.update("row","stored","draft");var active=new AtomicReference<>(current);
        Path disposable=temp.resolve("pack-load-disposable.zip");Files.copy(archive,disposable);
        try(var loader=new AsyncPackLoad()) {
            var started=loader.start(disposable,PackLoads.Kind.ZIP);var state=ready(loader);check(state.request()==started.request() && state.receipt().activeDirectory()==null && active.get()==current && drafts.any());
            current.replace(file,bytes("late edit"));int history=current.undoCount();long revision=current.revision();Files.delete(disposable);
            check(!drafts.transition(()->false,()->active.set(loader.take(state.request(),current,active.get()))) && drafts.any() && loader.poll().phase()==AsyncPackLoad.Phase.READY);
            io(()->drafts.transition(()->true,()->active.set(loader.take(state.request()+1,current,active.get()))));check(drafts.any());
            io(()->loader.take(state.request(),current,PackWorkspace.empty()));check(loader.poll().phase()==AsyncPackLoad.Phase.READY);
            check(drafts.transition(()->true,()->active.set(loader.take(state.request(),current,active.get()))));check(!drafts.any() && active.get()!=current && loader.poll().phase()==AsyncPackLoad.Phase.OPENED);exact(original,active.get());
            check(current.undoCount()==history && current.revision()==revision && current.dirty());io(()->loader.take(state.request(),current,current));
            // A new candidate makes an older queued request unusable without consuming the new one.
            long oldRequest=state.request();loader.start(root,PackLoads.Kind.DIRECTORY);ready(loader);io(()->loader.take(oldRequest,current,current));check(loader.poll().phase()==AsyncPackLoad.Phase.READY);loader.clear();io(()->loader.take(loader.poll().request(),current,current));
        }
        // Noncooperative cancellations, clear and close cannot publish; busy requests preserve the original source.
        for(int mode=0;mode<3;mode++) {
            var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var thread=new AtomicReference<Thread>();var calls=new AtomicInteger();
            var loader=new AsyncPackLoad((path,kind,cancelled,progressSink)->{thread.set(Thread.currentThread());calls.incrementAndGet();entered.countDown();awaitIgnoringInterrupts(release);return PackLoads.prepare(path,kind,()->false,progressSink);},r->new Thread(r,"pack-load-contract"));
            try {
                loader.start(root,PackLoads.Kind.DIRECTORY);check(entered.await(5,TimeUnit.SECONDS));check(thread.get()!=Thread.currentThread() && thread.get().isDaemon());io(()->loader.start(archive,PackLoads.Kind.ZIP));check(loader.poll().source().equals(root.toAbsolutePath().normalize()) && calls.get()==1);
                if(mode==0)loader.cancel();else if(mode==1)loader.clear();else {long before=System.nanoTime();loader.close();check(System.nanoTime()-before<TimeUnit.SECONDS.toNanos(1));}
                release.countDown();stopped(loader);check(loader.poll().phase()!=AsyncPackLoad.Phase.READY);
                if(mode<2){loader.start(root,PackLoads.Kind.DIRECTORY);ready(loader);check(calls.get()==2);}
            }finally{release.countDown();loader.close();}
        }
        // Failures/OOM reports are stable and bounded; explicit retry reuses the worker without touching current edits.
        var attempts=new AtomicInteger();try(var loader=new AsyncPackLoad((path,kind,cancelled,progressSink)->{int n=attempts.incrementAndGet();if(n==1)throw new IOException("failure\n"+"x".repeat(800));if(n==2)throw new OutOfMemoryError("injected");return PackLoads.prepare(path,kind,cancelled,progressSink);},r->new Thread(r,"pack-load-retry"))) {
            loader.start(root,PackLoads.Kind.DIRECTORY);stopped(loader);var failed=loader.poll();check(failed.phase()==AsyncPackLoad.Phase.FAILED && failed.detail().length()<=515 && !failed.detail().contains("\n"));check(loader.poll()==failed && attempts.get()==1);
            loader.start(root,PackLoads.Kind.DIRECTORY);stopped(loader);check(loader.poll().phase()==AsyncPackLoad.Phase.FAILED && loader.poll().detail().contains("memory"));loader.start(root,PackLoads.Kind.DIRECTORY);ready(loader);check(attempts.get()==3 && Arrays.equals(current.read(file),bytes("late edit")));
        }
        // Captured folder baselines remain the loaded bytes, even if disk changes after READY; normal Save detects it.
        try(var loader=new AsyncPackLoad()) {
            loader.start(root,PackLoads.Kind.DIRECTORY);var state=ready(loader);byte[] disk=Files.readAllBytes(root.resolve(file));Files.writeString(root.resolve(file),"external after ready");var next=loader.take(state.request(),current,current);check(Arrays.equals(next.read(file),disk));next.replace(file,bytes("new applied"));io(()->next.save(root));check(Files.readString(root.resolve(file)).equals("external after ready"));next.saveCopy(temp.resolve("pack-load-recover-copy"));Files.write(root.resolve(file),disk);
        }
        System.out.println("PASS: background folder/ZIP pack loading with bounded streaming, metadata/entry/depth limits, cancellable SHA-256 preparation and progress, source link/change rejection, valid ZIP central/local name-size-CRC agreement and malformed/truncated archive failures, exact unknown/opaque/empty-folder preservation, private complete candidates and explicit one-shot frame-end guarded ownership handoff without rereads, retained drafts/late edits and Save conflict baselines, no queue/noncooperative stale rejection/stable errors/OOM retry/nonblocking close; no writes or native generation");
    }
}
