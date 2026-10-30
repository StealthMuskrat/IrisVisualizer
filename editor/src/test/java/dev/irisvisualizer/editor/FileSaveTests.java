package dev.irisvisualizer.editor;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

final class FileSaveTests {
    private static byte[] bytes(String value){return value.getBytes(StandardCharsets.UTF_8);}
    private static void check(boolean value,String reason){if(!value)throw new AssertionError("Staged Save: "+reason);}
    @FunctionalInterface private interface Action {void run() throws Exception;}
    private static IOException rejected(Action action) throws Exception {try{action.run();}catch(IOException expected){return expected;}throw new AssertionError("Expected guarded Save failure");}
    private static byte[] hash(byte[] data) throws Exception{return MessageDigest.getInstance("SHA-256").digest(data);}
    private static final FileSaves.Stager WRITE=(output,data)->output.write(data);
    private static final FileSaves.Publisher PUBLISH=(temporary,destination,replacing)->{if(replacing)Files.move(temporary,destination,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);else Files.move(temporary,destination);};
    private static void clean(Path parent) throws Exception {try(var paths=Files.list(parent)){check(paths.noneMatch(p->p.getFileName().toString().startsWith(FileSaves.TEMPORARY_PREFIX)),"owned temporary files cleaned");}}
    private static FileSaves.Receipt write(Path root,String key,byte[] data,byte[] baseline,FileSaves.Stager stage,FileSaves.Checkpoint checkpoint,FileSaves.Publisher publisher) throws IOException {return FileSaves.write(root,key,data,baseline,stage,checkpoint,publisher);}
    private static void utility(Path temp) throws Exception {
        Path root=Files.createDirectory(temp.resolve("file-save-utility"));String key="resource.bin";Path target=root.resolve(key);
        byte[] before=bytes("old bytes 雪"),after=bytes("new applied bytes\r\n");Files.write(target,before);byte[] baseline=hash(before);
        var receipt=write(root,key,after,baseline,WRITE,(stage,file)->check(Arrays.equals(Files.readAllBytes(file),before),"destination unchanged throughout staging"),PUBLISH);
        check(Arrays.equals(Files.readAllBytes(target),after) && receipt.file().equals(target.toRealPath()) && receipt.bytes()==after.length && receipt.sha256().equals(HexFormat.of().formatHex(hash(after))),"exact atomic replacement and captured-byte receipt");
        byte[] escaped=receipt.savedHash();escaped[0]^=1;check(Arrays.equals(receipt.savedHash(),hash(after)),"private defensive digest metadata");clean(root);
        Path fresh=root.resolve("new.bin");FileSaves.write(root,"new.bin",before,null);check(Arrays.equals(Files.readAllBytes(fresh),before),"create-only new file");FileSaves.write(root,"empty.bin",new byte[0],null);check(Files.size(root.resolve("empty.bin"))==0,"empty payload supported");
        rejected(()->FileSaves.write(root,"new.bin",after,null));check(Arrays.equals(Files.readAllBytes(fresh),before),"occupied new destination preserved even with equal bytes");clean(root);
        // Real partial staging writes and explicit failure never open/truncate the original target.
        var time=Files.getLastModifiedTime(target);byte[] current=Files.readAllBytes(target);byte[] expected=hash(current);
        rejected(()->write(root,key,before,expected,(output,data)->{output.write(data,0,3);check(Arrays.equals(Files.readAllBytes(target),current),"partial staging left original intact");throw new IOException("injected disk write failure");},(a,b)->{},PUBLISH));
        check(Arrays.equals(Files.readAllBytes(target),current) && Files.getLastModifiedTime(target).equals(time),"stage failure retains exact old bytes and mtime");clean(root);
        rejected(()->write(root,key,before,expected,(output,data)->output.write(new byte[data.length]),(a,b)->{},PUBLISH));check(Arrays.equals(Files.readAllBytes(target),current),"staged digest mismatch refuses publication");clean(root);
        rejected(()->write(root,key,before,expected,(output,data)->output.write(data,0,data.length-1),(a,b)->{},PUBLISH));check(Arrays.equals(Files.readAllBytes(target),current),"short staging output refuses publication");clean(root);
        // A competing same-size/same-mtime modification is caught by the repeated saved-baseline hash check.
        byte[] external=current.clone();external[0]^=1;
        rejected(()->write(root,key,before,expected,WRITE,(stage,file)->{Files.write(file,external);Files.setLastModifiedTime(file,time);},PUBLISH));check(Arrays.equals(Files.readAllBytes(target),external),"external hash conflict preserved");clean(root);Files.write(target,current);
        // Changed staged bytes also fail verification, even if the intended source data remains unchanged.
        rejected(()->write(root,key,before,expected,WRITE,(stage,file)->Files.write(stage,bytes("foreign stage bytes")),PUBLISH));check(Arrays.equals(Files.readAllBytes(target),current),"changed staged bytes not published");clean(root);
        Path appeared=root.resolve("appeared.bin");rejected(()->write(root,"appeared.bin",before,null,WRITE,(stage,file)->Files.write(file,bytes("competitor")),PUBLISH));check(Files.readString(appeared).equals("competitor"),"new path appearing during staging preserved");clean(root);
        Path boundary=root.resolve("boundary.bin");rejected(()->write(root,"boundary.bin",before,null,WRITE,(a,b)->{},(stage,file,replacing)->{Files.writeString(file,"late competitor");Files.move(stage,file);}));check(Files.readString(boundary).equals("late competitor"),"create-only publication refuses occupied final path");clean(root);
        var unsupported=rejected(()->write(root,key,before,expected,WRITE,(a,b)->{},(stage,file,replacing)->{throw new AtomicMoveNotSupportedException(stage.toString(),file.toString(),"injected unsupported replacement");}));
        check(unsupported.getMessage().contains("Save As/Save Copy") && Arrays.equals(Files.readAllBytes(target),current),"unsupported atomic replacement fails closed with recovery guidance");clean(root);
        // A directory or another staging identity must survive cleanup; cleanup never scans/deletes a namespace.
        var retained=new AtomicReference<Path>();var cleanup=rejected(()->write(root,key,before,expected,WRITE,(stage,file)->{retained.set(stage);Files.delete(stage);Files.createDirectory(stage);Files.writeString(stage.resolve("foreign"),"keep");},PUBLISH));
        check(cleanup.getMessage().contains("cleanup") && Files.readString(retained.get().resolve("foreign")).equals("keep") && Arrays.equals(Files.readAllBytes(target),current),"foreign replacement and child retained with cleanup error");Files.delete(retained.get().resolve("foreign"));Files.delete(retained.get());clean(root);
        Path unrelated=Files.writeString(root.resolve(FileSaves.TEMPORARY_PREFIX+"unrelated.part"),"unrelated");FileSaves.write(root,"another.bin",before,null);check(Files.readString(unrelated).equals("unrelated"),"unrelated temporary-looking files never enumerated for cleanup");Files.delete(unrelated);clean(root);
        Path directory=root.resolve("directory.bin");Files.createDirectory(directory);rejected(()->FileSaves.write(root,"directory.bin",before,baseline));check(Files.isDirectory(directory),"non-file replacement refused");
        Path huge=root.resolve("huge.bin");try(var file=new RandomAccessFile(huge.toFile(),"rw")){file.setLength(PackWorkspace.MAX_BYTES+1);}check(rejected(()->FileSaves.preflight(huge,baseline)).getMessage().contains("512 MiB"),"oversized existing destinations rejected from metadata");Files.delete(huge);
        rejected(()->FileSaves.write(root,"missing.bin",before,baseline));rejected(()->FileSaves.write(root,"../outside.bin",before,null));check(!Files.exists(temp.resolve("outside.bin")),"missing modified files and traversal refused");clean(root);
        // Links are never followed for publication/cleanup when this environment permits creating them.
        Path link=root.resolve("linked.bin"),real=Files.write(root.resolve("real.bin"),current);boolean linked=false;try{Files.createSymbolicLink(link,real);linked=true;}catch(IOException | UnsupportedOperationException unavailable){}
        if(linked){rejected(()->FileSaves.write(root,"linked.bin",before,expected));check(Arrays.equals(Files.readAllBytes(real),current),"linked source destination preserved");Files.delete(link);}
        if(System.getProperty("os.name").startsWith("Windows")) {
            Files.setAttribute(target,"dos:readonly",true);try{rejected(()->FileSaves.write(root,key,before,expected));check(Arrays.equals(Files.readAllBytes(target),current),"Windows read-only file is not bypassed by replacement");}finally{Files.setAttribute(target,"dos:readonly",false);}clean(root);
        }
    }
    private static void parentChanges(Path temp) throws Exception {
        Path root=Files.createDirectory(temp.resolve("file-save-parent")),parent=Files.createDirectory(root.resolve("parent")),held=root.resolve("held");Path target=Files.writeString(parent.resolve("resource.bin"),"original");byte[] baseline=hash(bytes("original"));
        rejected(()->write(root,"parent/resource.bin",bytes("new bytes"),baseline,WRITE,(stage,file)->{Files.move(parent,held);Files.createDirectory(parent);Files.writeString(parent.resolve("foreign.bin"),"keep");},PUBLISH));
        check(Files.readString(held.resolve("resource.bin")).equals("original") && Files.readString(parent.resolve("foreign.bin")).equals("keep") && !Files.exists(parent.resolve("resource.bin")),"moved parent and competing contents preserved");
        // A moved staging path is deliberately not rediscovered/deleted by a recursive cleanup search.
        try(var paths=Files.list(held)){check(paths.anyMatch(p->p.getFileName().toString().startsWith(FileSaves.TEMPORARY_PREFIX)),"moved staging file remains outside the tracked path");}
    }
    private static void workspace(Path temp) throws Exception {
        String first="blocks/a.json",second="blocks/b.json",opaque="objects/exact.iob";byte[] original=bytes("{\"block\":\"minecraft:stone\",\"future\":9007199254740993}"),binary={0,1,-1};
        var pack=PackWorkspace.newPack(Map.of(first,original,second,original,opaque,binary,"future/malformed.json",bytes("invalid unknown JSON 雪")));Path root=temp.resolve("file-save-workspace");pack.save(root);pack.createSubfolder("blocks","empty");pack.save(root);
        var drafts=new InspectorDrafts();drafts.update("late","stored","draft");var time=Files.getLastModifiedTime(root.resolve(second));var untouchedKey=Files.readAttributes(root.resolve(second),java.nio.file.attribute.BasicFileAttributes.class).fileKey();
        byte[] changed=bytes("{\"block\":\"minecraft:gold_block\",\"future\":9007199254740993}");pack.replace(first,changed);var history=pack.historySteps(false);long revision=pack.revision(),resource=pack.resourceRevision(),content=pack.contentRevision(first),saved=pack.saveRevision();var cache=new WorkspaceChanges.Cache();var all=new WorkspaceChanges.Query("",WorkspaceChanges.Filter.ALL);var pending=cache.query(pack,all);
        rejected(()->pack.save(root,(base,path,data,hash)->write(base,path,data,hash,(out,bytes)->{out.write(bytes,0,2);throw new IOException("injected active Save write failure");},(a,b)->{},PUBLISH)));
        check(Arrays.equals(Files.readAllBytes(root.resolve(first)),original) && pack.dirty() && pack.saveRevision()==saved && pack.historySteps(false).equals(history) && drafts.any() && cache.query(pack,all)==pending,"failed staged Save retains bytes/baselines/history/drafts/cache");clean(root.resolve("blocks"));
        rejected(()->pack.save(root,(base,path,data,hash)->write(base,path,data,hash,WRITE,(a,b)->{},(stage,file,replacing)->{throw new AtomicMoveNotSupportedException(stage.toString(),file.toString(),"unsupported");})));
        check(pack.saveRevision()==saved && Arrays.equals(Files.readAllBytes(root.resolve(first)),original),"unsupported replacement does not advance workspace baseline");clean(root.resolve("blocks"));
        pack.save(root);check(!pack.dirty() && pack.revision()==revision && pack.resourceRevision()==resource && pack.contentRevision(first)==content && pack.historySteps(false).equals(history) && drafts.any() && cache.query(pack,all).total()==0,"success installs receipt baseline without changing edit/content/history identities");
        check(Files.getLastModifiedTime(root.resolve(second)).equals(time) && Objects.equals(Files.readAttributes(root.resolve(second),java.nio.file.attribute.BasicFileAttributes.class).fileKey(),untouchedKey),"untouched resources keep bytes, mtime and available identity");
        pack.undo();check(pack.dirty() && Arrays.equals(pack.read(first),original),"Undo compares against published saved hash");pack.redo();check(!pack.dirty() && Arrays.equals(pack.read(first),changed),"Redo to published bytes becomes clean");
        // Earlier successful resources advance their baselines if a later stage fails; failed resources remain old.
        pack.replace(first,bytes("first later"));pack.replace(second,bytes("second later"));history=pack.historySteps(false);revision=pack.revision();saved=pack.saveRevision();var beforePartial=cache.query(pack,all);
        rejected(()->pack.save(root,(base,path,data,hash)->{if(path.equals(second))return write(base,path,data,hash,(out,bytes)->{out.write(bytes,0,1);throw new IOException("second staged resource failed");},(a,b)->{},PUBLISH);return FileSaves.write(base,path,data,hash);}));
        check(Files.readString(root.resolve(first)).equals("first later") && Arrays.equals(Files.readAllBytes(root.resolve(second)),original) && pack.saveRevision()>saved && pack.revision()==revision && pack.historySteps(false).equals(history) && cache.query(pack,all)!=beforePartial,"partial Save retains committed prefix with per-file baseline/cache revision");
        for(var entry:pack.pendingChanges())if(entry.path().equals(first)||entry.path().equals(second))check(pack.checkPendingChange(entry,WorkspaceChanges.MAX_CHECK_BYTES).status()==WorkspaceChanges.Status.BASELINE_MATCH,"each partial-save baseline describes its actual successful write state");
        pack.save(root);check(!pack.dirty() && Files.readString(root.resolve(second)).equals("second later"),"explicit retry uses updated per-file baselines");clean(root.resolve("blocks"));
        // Global preflight still prevents an earlier pending write when any later target already conflicts.
        pack.replace(first,bytes("do not publish"));pack.replace(second,bytes("do not publish either"));Files.writeString(root.resolve(second),"external conflict");byte[] savedFirst=Files.readAllBytes(root.resolve(first));saved=pack.saveRevision();
        rejected(()->pack.save(root));check(Arrays.equals(Files.readAllBytes(root.resolve(first)),savedFirst) && pack.saveRevision()==saved && Files.readString(root.resolve(second)).equals("external conflict"),"all-resource conflict preflight precedes any file publication");
        // Fresh-folder saves remain create-only even for previously saved resources whose created flag is false.
        Path fresh=temp.resolve("file-save-fresh-race");var raced=new AtomicBoolean();rejected(()->pack.save(fresh,(base,path,data,hash)->{if(raced.compareAndSet(false,true))return write(base,path,data,hash,WRITE,(stage,file)->Files.writeString(file,"competing new path"),PUBLISH);return FileSaves.write(base,path,data,hash);}));
        check(pack.directory().equals(root.toRealPath()) && pack.dirty() && Files.readString(fresh.resolve(first)).equals("competing new path"),"fresh-folder collision never reuses active replacement authority");clean(fresh.resolve("blocks"));
        Path copy=temp.resolve("file-save-fresh-copy");pack.saveCopy(copy);check(Arrays.equals(PackWorkspace.open(copy).read(first),pack.read(first)) && Arrays.equals(Files.readAllBytes(copy.resolve(opaque)),binary),"Save Copy remains a fresh exact snapshot after active conflicts");
        Path zip=temp.resolve("file-save-preservation.zip");pack.exportZip(zip);var imported=PackWorkspace.importZip(zip);for(String path:pack.paths())check(Arrays.equals(imported.read(path),pack.read(path)),"exact unknown/opaque ZIP preservation");check(imported.directories().contains("blocks/empty"),"explicit empty folder preserved");
        Path as=temp.resolve("file-save-new-baseline");pack.saveAs(as);check(!pack.dirty() && pack.directory().equals(as.toRealPath()) && drafts.any(),"Save As adopts only the completed fresh-folder baseline");pack.replace(first,bytes("after As"));pack.save(as);check(Files.readString(as.resolve(first)).equals("after As"),"adopted folder accepts ordinary atomic Save");
        // A publisher error after its rename is an uncertain outcome: no unconfirmed baseline is adopted.
        pack.replace(first,bytes("published then error"));saved=pack.saveRevision();rejected(()->pack.save(as,(base,path,data,hash)->write(base,path,data,hash,WRITE,(a,b)->{},(stage,file,replacing)->{PUBLISH.publish(stage,file,replacing);throw new IOException("injected post-publication report failure");})));
        check(Files.readString(as.resolve(first)).equals("published then error") && pack.dirty() && pack.saveRevision()==saved,"post-publication uncertainty keeps original baseline and dirty state");rejected(()->pack.save(as));clean(as.resolve("blocks"));
    }
    private static void overlappingLoads(Path temp) throws Exception {
        Path root=temp.resolve("file-save-load-overlap");var pack=PackWorkspace.newPack(Map.of("resource.bin",bytes("original")));pack.save(root);pack.replace("resource.bin",bytes("new bytes"));
        for(var kind:PackLoads.Kind.values()) {
            Path source=kind==PackLoads.Kind.DIRECTORY ? root : root.resolve("inside.zip");var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
            try(var loader=new AsyncPackLoad((path,type,cancelled,progress)->{entered.countDown();while(true)try{release.await();break;}catch(InterruptedException ignored){}return PackLoads.prepare(root,PackLoads.Kind.DIRECTORY,()->false,progress);},r->new Thread(r,"save-load-overlap"))) {
                loader.start(source,kind);check(entered.await(5,TimeUnit.SECONDS),"overlapping loader entered");check(PackLoads.cancelForSave(loader,root),"overlapping in-progress folder/ZIP load cancelled before staging");pack.save(root);release.countDown();long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(loader.busy() && System.nanoTime()<end)Thread.sleep(1);check(!loader.busy() && loader.poll().phase()==AsyncPackLoad.Phase.CANCELLED,"noncooperative overlap completion cannot publish a candidate");
            }finally{release.countDown();}
        }
        try(var loader=new AsyncPackLoad()) {
            loader.start(root,PackLoads.Kind.DIRECTORY);long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(loader.busy() && System.nanoTime()<end)Thread.sleep(1);check(loader.poll().phase()==AsyncPackLoad.Phase.READY && !PackLoads.cancelForSave(loader,root),"complete candidates retain their explicit earlier bytes");
        }
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var loader=new AsyncPackLoad((path,type,cancelled,progress)->{entered.countDown();try{release.await();}catch(InterruptedException e){throw new IOException(e);}return PackLoads.prepare(root,PackLoads.Kind.DIRECTORY,cancelled,progress);},r->new Thread(r,"save-unrelated-load"))) {
            loader.start(root,PackLoads.Kind.DIRECTORY);check(entered.await(5,TimeUnit.SECONDS),"unrelated test loader entered");check(!PackLoads.cancelForSave(loader,temp.resolve("other-save-root")),"unrelated source loading retained");check(PackLoads.cancelForSave(loader,temp),"loading below a saved ancestor is cancelled");
        }finally{release.countDown();}
    }
    static void run(Path temp) throws Exception {utility(temp);parentChanges(temp);workspace(temp);overlappingLoads(temp);System.out.println("PASS: staged per-resource Save with private new-byte temporary files, bounded SHA-256 destination/staging verification and repeated parent/path/identity guards, atomic existing-file replacement and create-only new/fresh-folder publication, stage/short-write/digest/unsupported-atomic failure isolation, late same-size/same-mtime conflicts, tracked-only cleanup and foreign/moved staging preservation, receipt-bound per-file/partial-save baselines and explicit retry, overlapping in-progress load cancellation without stale publication, unchanged resources/history/drafts/content stamps and exact unknown/opaque/empty-folder Save/Copy/As/ZIP; no backups, recovery or native generation");}
}
