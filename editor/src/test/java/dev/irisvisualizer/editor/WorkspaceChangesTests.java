package dev.irisvisualizer.editor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

final class WorkspaceChangesTests {
    private static byte[] bytes(String s) { return s.getBytes(StandardCharsets.UTF_8); }
    private static void check(boolean ok) { if (!ok) throw new AssertionError("Pending workspace changes contract failed"); }
    private static void rejects(Runnable op) { try { op.run(); } catch (IllegalArgumentException expected) { return; }throw new AssertionError("Expected rejected pending review input"); }
    private static void rejectsIo(InspectorDrafts.Change op) throws IOException { try { op.run(); } catch (IOException expected) { return; }throw new AssertionError("Expected guarded save failure"); }
    private static final WorkspaceChanges.Query ALL=new WorkspaceChanges.Query("",WorkspaceChanges.Filter.ALL);
    private static WorkspaceChanges.Entry entry(PackWorkspace pack,String path) { return pack.pendingChanges().stream().filter(e -> e.path().equals(path)).findFirst().orElseThrow(); }
    private static void status(WorkspaceChanges.Check check,WorkspaceChanges.Status status) { check(check.status()==status); }
    static void run(Path temp) throws Exception {
        String block="blocks/base.json",opaque="objects/opaque.iob";
        byte[] original=bytes("{\r\n \"block\":\"minecraft:stone\",\"future\":9007199254740993\r\n}"),unknown=bytes("bad unknown JSON 雪"),binary=new byte[]{0,1,-1};
        var pack=PackWorkspace.newPack(Map.of(block,original,"future/unknown.json",unknown,opaque,binary));var cache=new WorkspaceChanges.Cache();var fresh=cache.query(pack,ALL);
        check(fresh.newFiles()==3 && fresh.modifiedFiles()==0 && fresh.newFolders()==0 && fresh.total()==3 && fresh.fileBytes()==original.length+unknown.length+binary.length && cache.query(pack,ALL)==fresh);
        var freshEntry=entry(pack,block);status(cache.check(pack,freshEntry),WorkspaceChanges.Status.NO_ACTIVE_FOLDER);check(pack.directory()==null && pack.dirty());
        Path root=temp.resolve("pending-source");pack.save(root);check(cache.query(pack,ALL).total()==0 && cache.checked(pack,freshEntry)==null);rejects(() -> cache.check(pack,freshEntry));
        pack.replace(block,bytes("{\"block\":\"minecraft:dirt\",\"future\":9007199254740993}"));pack.duplicate(block,"new_copy");pack.createSubfolder("blocks","empty_new");
        var pending=cache.query(pack,ALL);check(pending.newFiles()==1 && pending.modifiedFiles()==1 && pending.newFolders()==1 && pending.matches()==3 && pending.entries().stream().map(WorkspaceChanges.Entry::path).toList().equals(List.of(block,"blocks/empty_new","blocks/new_copy.json")));
        var modified=entry(pack,block);var folder=entry(pack,"blocks/empty_new");var added=entry(pack,"blocks/new_copy.json");
        long revision=pack.revision(),resources=pack.resourceRevision(),saved=pack.saveRevision(),history=pack.historyBytes();int undo=pack.undoCount();var steps=pack.historySteps(false);byte[] current=pack.read(block);
        var match=cache.check(pack,modified);status(match,WorkspaceChanges.Status.BASELINE_MATCH);check(match.diskBytes()==original.length && match.folder().equals(root.toRealPath()) && cache.checked(pack,modified)==match);
        check(pack.revision()==revision && pack.resourceRevision()==resources && pack.saveRevision()==saved && pack.historyBytes()==history && pack.undoCount()==undo && pack.historySteps(false).equals(steps) && pack.dirty() && Arrays.equals(pack.read(block),current) && Arrays.equals(Files.readAllBytes(root.resolve(block)),original));
        status(cache.check(pack,added),WorkspaceChanges.Status.AVAILABLE);check(!Files.exists(root.resolve(added.path())));status(cache.check(pack,folder),WorkspaceChanges.Status.AVAILABLE);check(!Files.exists(root.resolve(folder.path())));
        Files.write(root.resolve(added.path()),pack.read(added.path()));status(cache.check(pack,added),WorkspaceChanges.Status.CONFLICT);rejectsIo(() -> pack.save(root));check(pack.dirty() && Arrays.equals(pack.read(block),current));Files.delete(root.resolve(added.path()));
        Files.createDirectory(root.resolve(folder.path()));status(cache.check(pack,folder),WorkspaceChanges.Status.FOLDER_EXISTS);Files.delete(root.resolve(folder.path()));Files.writeString(root.resolve(folder.path()),"occupied");status(cache.check(pack,folder),WorkspaceChanges.Status.CONFLICT);Files.delete(root.resolve(folder.path()));
        // Disk receipts stay point-in-time until explicitly refreshed; no automatic hashing on cache/query access.
        match=cache.check(pack,modified);Files.writeString(root.resolve(block),"external");check(cache.checked(pack,modified)==match && cache.query(pack,ALL).total()==3);status(cache.check(pack,modified),WorkspaceChanges.Status.CONFLICT);rejectsIo(() -> pack.save(root));check(Files.readString(root.resolve(block)).equals("external"));Files.delete(root.resolve(block));status(cache.check(pack,modified),WorkspaceChanges.Status.CONFLICT);Files.createDirectory(root.resolve(block));status(cache.check(pack,modified),WorkspaceChanges.Status.CONFLICT);Files.delete(root.resolve(block));Files.write(root.resolve(block),original);
        // Oversized destinations are skipped before hashing. Small injected limits cover exact boundaries and empty files.
        try (var channel=Files.newByteChannel(root.resolve(block),StandardOpenOption.WRITE,StandardOpenOption.TRUNCATE_EXISTING)) { channel.position(WorkspaceChanges.MAX_CHECK_BYTES);channel.write(java.nio.ByteBuffer.wrap(new byte[]{1})); }
        status(cache.check(pack,modified),WorkspaceChanges.Status.TOO_LARGE);Files.write(root.resolve(block),original);status(pack.checkPendingChange(modified,original.length),WorkspaceChanges.Status.BASELINE_MATCH);status(pack.checkPendingChange(modified,original.length-1),WorkspaceChanges.Status.TOO_LARGE);rejects(() -> pack.checkPendingChange(modified,-1));rejects(() -> pack.checkPendingChange(modified,WorkspaceChanges.MAX_CHECK_BYTES+1));
        var zero=PackWorkspace.newPack(Map.of("empty.json",new byte[0]));zero.save(temp.resolve("pending-zero"));zero.replace("empty.json",bytes("x"));status(zero.checkPendingChange(entry(zero,"empty.json"),0),WorkspaceChanges.Status.BASELINE_MATCH);
        // Missing active folders and non-directory ancestors are reported without creating or following anything.
        Path moved=temp.resolve("pending-source-moved");Files.move(root,moved);status(cache.check(pack,modified),WorkspaceChanges.Status.CONFLICT);check(!Files.exists(root));Files.move(moved,root);
        pack.createSubfolder("blocks","parent");pack.createResource("blocks/parent","child",null);var child=entry(pack,"blocks/parent/child.json");Files.writeString(root.resolve("blocks/parent"),"parent file");status(cache.check(pack,child),WorkspaceChanges.Status.IO_ERROR);Files.delete(root.resolve("blocks/parent"));
        Path real=Files.createDirectory(temp.resolve("pending-link-target")),link=root.resolve("blocks/parent");boolean linked=false;try { Files.createSymbolicLink(link,real);linked=true; } catch (UnsupportedOperationException | IOException unavailable) { }
        if (linked) { status(cache.check(pack,child),WorkspaceChanges.Status.IO_ERROR);check(!Files.exists(real.resolve("child.json")));Files.delete(link); }
        // Filtered totals still describe the entire inventory; shown matches and collections remain bounded and immutable.
        check(cache.query(pack,new WorkspaceChanges.Query("BASE",WorkspaceChanges.Filter.MODIFIED_FILES)).entries().equals(List.of(modified)));
        check(cache.query(pack,new WorkspaceChanges.Query("",WorkspaceChanges.Filter.FOLDERS)).matches()==2 && cache.query(pack,new WorkspaceChanges.Query("",WorkspaceChanges.Filter.NEW_FILES)).matches()==2 && cache.query(pack,new WorkspaceChanges.Query("",WorkspaceChanges.Filter.FILES)).matches()==3);
        try { pending.entries().clear();throw new AssertionError(); } catch (UnsupportedOperationException expected) { }
        var many=new LinkedHashMap<String,byte[]>();for (int i=0;i<240;i++) many.put("future/n"+String.format("%03d",i)+".json",bytes("not JSON"));var manyPack=PackWorkspace.newPack(many);var capped=cache.query(manyPack,ALL);check(capped.newFiles()==240 && capped.matches()==240 && capped.entries().size()==200 && capped.truncated() && capped.entries().getLast().path().equals("future/n199.json"));check(cache.query(manyPack,new WorkspaceChanges.Query("n239",WorkspaceChanges.Filter.ALL)).entries().getFirst().path().equals("future/n239.json"));check(cache.checked(manyPack,modified)==null);
        // Save Copy keeps receipts; Save As and ordinary Save clear pending state without changing the edit revision.
        cache.check(pack,modified);var receipt=cache.checked(pack,modified);var beforeCopy=cache.query(pack,ALL);pack.saveCopy(temp.resolve("pending-copy"));check(cache.query(pack,ALL)==beforeCopy && cache.checked(pack,modified)==receipt);revision=pack.revision();pack.saveAs(temp.resolve("pending-as"));check(pack.revision()==revision && cache.query(pack,ALL).total()==0 && cache.checked(pack,modified)==null);
        pack.replace(block,original);check(cache.query(pack,ALL).modifiedFiles()==1);pack.undo();check(cache.query(pack,ALL).total()==0);pack.redo();check(cache.query(pack,ALL).modifiedFiles()==1);revision=pack.revision();pack.save(pack.directory());check(pack.revision()==revision && cache.query(pack,ALL).total()==0);
        // A partial ordinary Save can advance a file baseline without changing revision/dirty: cache must still refresh.
        var failing=PackWorkspace.newPack(Map.of(block,original));Path failureRoot=temp.resolve("pending-partial");failing.save(failureRoot);String first=failing.duplicate(block,"a_new");failing.duplicate(block,"z_later");var firstEntry=entry(failing,first);check(cache.query(failing,ALL).newFiles()==2);status(cache.check(failing,firstEntry),WorkspaceChanges.Status.AVAILABLE);revision=failing.revision();saved=failing.saveRevision();rejectsIo(() -> failing.save(failureRoot,(base,path,data,baseline)->{ if(path.endsWith("/z_later.json"))throw new IOException("Injected later staging failure");return FileSaves.write(base,path,data,baseline); }));check(failing.revision()==revision && failing.dirty() && failing.saveRevision()>saved && Files.exists(failureRoot.resolve(first)));var partial=cache.query(failing,ALL);check(partial.newFiles()==1 && partial.modifiedFiles()==1 && cache.checked(failing,firstEntry)==null);rejects(() -> cache.check(failing,firstEntry));status(cache.check(failing,entry(failing,first)),WorkspaceChanges.Status.BASELINE_MATCH);
        // Review and deferred resource selection cannot discard late drafts or mutate saved bytes.
        pack.replace(block,bytes("{\"block\":\"minecraft:gold_block\"}"));var selectedEntry=entry(pack,block);var drafts=new InspectorDrafts();drafts.update("late","stored","draft");var navigation=new ResourceNavigation();var selected=new AtomicReference<>(opaque);navigation.request(pack,selectedEntry.path());var request=navigation.takeRequest(pack);check(!drafts.transition(() -> false,() -> selected.set(request.target())) && drafts.any() && selected.get().equals(opaque));try { drafts.transition(() -> true,() -> { throw new IOException("failed"); });throw new AssertionError(); } catch (IOException expected) { check(drafts.any()); }check(drafts.transition(() -> true,() -> selected.set(request.target())) && !drafts.any() && selected.get().equals(block));navigation.request(pack,block);check(navigation.takeRequest(manyPack)==null);
        rejects(() -> new WorkspaceChanges.Query(null,WorkspaceChanges.Filter.ALL));rejects(() -> new WorkspaceChanges.Query("x\u0000",WorkspaceChanges.Filter.ALL));rejects(() -> new WorkspaceChanges.Query("x".repeat(257),WorkspaceChanges.Filter.ALL));rejects(() -> new WorkspaceChanges.Query("x",null));check(new WorkspaceChanges.Query("x".repeat(256),WorkspaceChanges.Filter.ALL).text().length()==256);
        pack.exportZip(temp.resolve("pending.zip"));var imported=PackWorkspace.importZip(temp.resolve("pending.zip"));check(cache.query(imported,ALL).total()==0 && imported.directory()==null);imported.replace(block,original);status(cache.check(imported,entry(imported,block)),WorkspaceChanges.Status.NO_ACTIVE_FOLDER);for (String p : pack.paths()) if (!p.equals(block)) check(Arrays.equals(pack.read(p),imported.read(p)));check(Arrays.equals(pack.read(opaque),binary) && Arrays.equals(pack.read("future/unknown.json"),unknown));
        cache.clear();check(cache.query(PackWorkspace.empty(),ALL).total()==0);
        System.out.println("PASS: bounded pending file/folder inventory, explicit read-only disk baseline/occupancy/removal/type/link checks, point-in-time receipts and oversized skips, Save/Undo/workspace/partial-save cache invalidation, guarded deferred opening and exact unknown/opaque Save/ZIP without native evaluation");
    }
}
