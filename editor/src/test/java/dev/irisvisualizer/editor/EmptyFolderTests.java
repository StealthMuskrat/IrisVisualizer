package dev.irisvisualizer.editor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class EmptyFolderTests {
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static void check(boolean ok,String detail) { if (!ok) throw new AssertionError("Empty folder operations: "+detail); }
    private static void rejects(InspectorDrafts.Change action) throws IOException { try { action.run(); } catch (IOException expected) { return; }throw new AssertionError("Expected rejected empty-folder operation"); }
    private static final WorkspaceChanges.Query ALL=new WorkspaceChanges.Query("",WorkspaceChanges.Filter.ALL);
    private static WorkspaceChanges.Entry pending(PackWorkspace pack,String path) { return pack.pendingChanges().stream().filter(entry -> entry.path().equals(path)).findFirst().orElseThrow(); }
    private static void exactFiles(PackWorkspace pack,Map<String,byte[]> files) { check(pack.paths().equals(files.keySet()),"file catalog unchanged");for (var entry : files.entrySet()) check(Arrays.equals(pack.read(entry.getKey()),entry.getValue()),"exact bytes: "+entry.getKey()); }
    private static void rejectsUnchanged(PackWorkspace pack,InspectorDrafts.Change action) throws IOException {
        var directories=Set.copyOf(pack.directories());var pending=pack.pendingChanges();long revision=pack.revision(),resources=pack.resourceRevision(),saved=pack.saveRevision();var history=pack.historySteps(false);var redo=pack.historySteps(true);
        rejects(action);check(pack.directories().equals(directories) && pack.pendingChanges().equals(pending) && pack.revision()==revision && pack.resourceRevision()==resources && pack.saveRevision()==saved && pack.historySteps(false).equals(history) && pack.historySteps(true).equals(redo),"failure preserves folders, pending state, revisions and history");
    }
    static void run(Path temp) throws Exception {
        var files=Map.of("blocks/base.json",bytes("{\r\n \"block\":\"minecraft:stone\",\"future\":9007199254740993\r\n}"),"objects/keep.iob",new byte[]{0,-1,3},"future/unknown.json",bytes("opaque invalid JSON"));
        var pack=PackWorkspace.newPack(files);String old=pack.createSubfolder("blocks","old"),destination=pack.createSubfolder("blocks","destination");Path root=temp.resolve("empty-folders-source");pack.save(root);var cache=new WorkspaceChanges.Cache();
        check(pack.editableEmptyFolder(old) && !pack.editableEmptyFolder("blocks") && !pack.editableEmptyFolder("blocks/base.json") && !pack.editableEmptyFolder(null),"exact leaf-only scope");
        pack.replace("blocks/base.json",bytes("{}"));pack.undo();check(pack.redoCount()==1,"history seeded");long revision=pack.revision();
        check(pack.renameEmptyFolder(old,"old").equals(old) && pack.moveEmptyFolder(old,"blocks").equals(old) && pack.revision()==revision && pack.redoCount()==1 && !pack.dirty(),"no-op preserves history and clean state");
        var drafts=new InspectorDrafts();drafts.update("late","stored","draft");String renamed=pack.renameEmptyFolder(old,"renamed");
        check(pack.redoCount()==0 && pack.undoCount()==0 && drafts.any() && pack.directories().contains(renamed) && !pack.directories().contains(old),"folder mutation clears applied-edit history and preserves inspector drafts");
        check(Files.isDirectory(root.resolve(old)) && !Files.exists(root.resolve(renamed)),"rename staged without disk writes");
        var changes=cache.query(pack,ALL);check(changes.newFolders()==1 && changes.removedFolders()==1 && changes.total()==2 && changes.newFiles()==0 && changes.modifiedFiles()==0,"pending rename has source removal and destination creation");
        check(cache.query(pack,new WorkspaceChanges.Query("",WorkspaceChanges.Filter.FOLDERS)).matches()==2 && cache.query(pack,new WorkspaceChanges.Query("",WorkspaceChanges.Filter.FILES)).matches()==0,"folder filters exclude removals from files");
        check(cache.check(pack,pending(pack,old)).status()==WorkspaceChanges.Status.REMOVAL_READY && cache.check(pack,pending(pack,renamed)).status()==WorkspaceChanges.Status.AVAILABLE,"explicit source/destination review");
        check(pack.renameEmptyFolder(renamed,"old").equals(old) && !pack.dirty() && pack.pendingChanges().isEmpty(),"rename back restores initial directory baseline");
        renamed=pack.renameEmptyFolder(old,"renamed");String moved=pack.moveEmptyFolder(renamed,destination);check(moved.equals("blocks/destination/renamed") && pack.pendingChanges().size()==2 && !pack.editableEmptyFolder(destination),"move within base and coalesce intermediate names");
        var finalDirectories=Set.copyOf(pack.directories());revision=pack.revision();var before=cache.query(pack,ALL);pack.saveCopy(temp.resolve("empty-folders-copy"));pack.exportZip(temp.resolve("empty-folders.zip"));
        check(cache.query(pack,ALL)==before && pack.dirty() && Files.isDirectory(root.resolve(old)),"copy/export retain pending state and original disk source");
        for (var reopened : List.of(PackWorkspace.open(temp.resolve("empty-folders-copy")),PackWorkspace.importZip(temp.resolve("empty-folders.zip")))) { exactFiles(reopened,files);check(reopened.directories().contains(moved) && !reopened.directories().contains(old) && !reopened.directories().contains(renamed),"logical folder snapshot"); }
        pack.save(root);check(!pack.dirty() && cache.query(pack,ALL).total()==0 && pack.revision()==revision && !Files.exists(root.resolve(old)) && Files.isDirectory(root.resolve(moved)),"ordinary Save applies directory relocation");exactFiles(pack,files);
        // New unsaved folders can be cancelled without a removal receipt or physical deletion.
        String scratch=pack.createSubfolder("blocks","scratch");pack.deleteEmptyFolder(scratch);check(!pack.dirty() && !Files.exists(root.resolve(scratch)),"new-then-delete cancels exactly");
        pack.deleteEmptyFolder(moved);check(pack.dirty() && Files.isDirectory(root.resolve(moved)),"saved deletion staged");check(pack.createSubfolder(destination,"renamed").equals(moved) && !pack.dirty(),"explicit re-create cancels saved removal");
        // Save As adopts the logical snapshot and never removes the old source folder.
        pack.deleteEmptyFolder(moved);pack.saveAs(temp.resolve("empty-folders-as"));check(!pack.dirty() && Files.isDirectory(root.resolve(moved)) && !Files.exists(pack.directory().resolve(moved)),"Save As does not delete from the former pack");
        var imported=PackWorkspace.importZip(temp.resolve("empty-folders.zip"));imported.deleteEmptyFolder(moved);check(imported.dirty() && pending(imported,moved).kind()==WorkspaceChanges.Kind.REMOVED_FOLDER && new WorkspaceChanges.Cache().check(imported,pending(imported,moved)).status()==WorkspaceChanges.Status.NO_ACTIVE_FOLDER,"ZIP directory baseline retains removal state without an active folder");imported.save(temp.resolve("empty-folders-imported"));check(!imported.dirty() && !Files.exists(imported.directory().resolve(moved)),"first Save writes only logical ZIP folders");
        // Leaf-first staging permits removal of an empty directory tree, without recursive file deletion.
        String parent=pack.createSubfolder("blocks","parent"),child=pack.createSubfolder(parent,"child");pack.save(pack.directory());
        rejectsUnchanged(pack,() -> pack.deleteEmptyFolder(parent));pack.deleteEmptyFolder(child);pack.deleteEmptyFolder(parent);check(cache.query(pack,ALL).removedFolders()==2,"two pending leaf removals");
        Files.writeString(pack.directory().resolve(child).resolve("external.dat"),"external");rejectsUnchanged(pack,() -> pack.save(pack.directory()));check(Files.readString(pack.directory().resolve(child).resolve("external.dat")).equals("external"),"unexpected nested data preserved");Files.delete(pack.directory().resolve(child).resolve("external.dat"));pack.save(pack.directory());check(!Files.exists(pack.directory().resolve(parent)) && !pack.dirty(),"deepest-first nonrecursive removal");
        // Conflicts are preflighted before new directories or modified resources are written.
        var conflict=PackWorkspace.newPack(files);String from=conflict.createSubfolder("blocks","source");Path conflictRoot=temp.resolve("empty-folders-conflict");conflict.save(conflictRoot);String to=conflict.renameEmptyFolder(from,"target");conflict.replace("blocks/base.json",bytes("{}"));
        Files.writeString(conflictRoot.resolve(from).resolve("external.dat"),"keep");rejectsUnchanged(conflict,() -> conflict.save(conflictRoot));check(!Files.exists(conflictRoot.resolve(to)) && Arrays.equals(Files.readAllBytes(conflictRoot.resolve("blocks/base.json")),files.get("blocks/base.json")),"source conflict prevents every planned write");check(cache.check(conflict,pending(conflict,from)).status()==WorkspaceChanges.Status.CONFLICT,"nonempty source receipt");Files.delete(conflictRoot.resolve(from).resolve("external.dat"));
        Files.createDirectory(conflictRoot.resolve(to));check(cache.check(conflict,pending(conflict,to)).status()==WorkspaceChanges.Status.CONFLICT,"occupied relocation target receipt");rejectsUnchanged(conflict,() -> conflict.save(conflictRoot));Files.delete(conflictRoot.resolve(to));
        Files.delete(conflictRoot.resolve(from));Files.writeString(conflictRoot.resolve(from),"external file");rejectsUnchanged(conflict,() -> conflict.save(conflictRoot));check(Files.readString(conflictRoot.resolve(from)).equals("external file"),"a directory replaced by a file is never intentionally removed");Files.delete(conflictRoot.resolve(from));
        check(cache.check(conflict,pending(conflict,from)).status()==WorkspaceChanges.Status.AVAILABLE,"already absent source is idempotent");conflict.save(conflictRoot);check(!conflict.dirty() && Files.isDirectory(conflictRoot.resolve(to)),"save accepts already absent empty source");
        String linkedSource=conflict.createSubfolder("blocks","linked_source");conflict.save(conflictRoot);conflict.deleteEmptyFolder(linkedSource);Files.delete(conflictRoot.resolve(linkedSource));Path external=Files.createDirectory(temp.resolve("empty-folders-external"));Files.writeString(external.resolve("keep.dat"),"keep");boolean linked=false;
        try { Files.createSymbolicLink(conflictRoot.resolve(linkedSource),external);linked=true; } catch (IOException | UnsupportedOperationException unavailable) { }
        if (linked) { rejectsUnchanged(conflict,() -> conflict.save(conflictRoot));check(Files.readString(external.resolve("keep.dat")).equals("keep"),"linked source not followed");Files.delete(conflictRoot.resolve(linkedSource)); }conflict.save(conflictRoot);
        // Scope, collisions, unsafe names and pending ancestor reuse fail before changing state/history.
        String a=conflict.createSubfolder("blocks","a"),b=conflict.createSubfolder("blocks","b");conflict.save(conflictRoot);conflict.replace("blocks/base.json",bytes("{\"changed\":true}"));
        for (String name : List.of("../escape","UPPER","con","a/b","bad.","x".repeat(256))) rejectsUnchanged(conflict,() -> conflict.renameEmptyFolder(a,name));
        rejectsUnchanged(conflict,() -> conflict.renameEmptyFolder(a,"b"));rejectsUnchanged(conflict,() -> conflict.moveEmptyFolder(a,"objects"));rejectsUnchanged(conflict,() -> conflict.moveEmptyFolder(a,a));rejectsUnchanged(conflict,() -> conflict.moveEmptyFolder(a,"blocks/missing"));rejectsUnchanged(conflict,() -> conflict.deleteEmptyFolder("blocks"));rejectsUnchanged(conflict,() -> conflict.deleteEmptyFolder("future"));
        conflict.deleteEmptyFolder(a);rejectsUnchanged(conflict,() -> conflict.duplicate("blocks/base.json","a/new"));check(!conflict.paths().contains("blocks/a/new.json"),"failed resource creation rolled back");conflict.createSubfolder("blocks","a");check(conflict.directories().contains(a) && conflict.pendingChanges().stream().noneMatch(entry -> entry.path().equals(a)),"restore pending ancestor before authoring inside it");
        Files.createDirectory(conflictRoot.resolve("blocks/Upper"));var spelling=PackWorkspace.open(conflictRoot);rejectsUnchanged(spelling,() -> spelling.renameEmptyFolder("blocks/Upper","upper"));
        var collision=PackWorkspace.newPack(Map.of("blocks/taken",new byte[]{1}));String collisionSource=collision.createSubfolder("blocks","source");rejectsUnchanged(collision,() -> collision.renameEmptyFolder(collisionSource,"taken"));
        var deep=PackWorkspace.newPack(Map.of("blocks/anchor.dat",new byte[]{1}));String deepSource=deep.createSubfolder("blocks","source"),deepParent="blocks";for (int i=1;i<PackWorkspace.MAX_PATH_SEGMENTS;i++) deepParent=deep.createSubfolder(deepParent,"n");String tooDeep=deepParent;rejectsUnchanged(deep,() -> deep.moveEmptyFolder(deepSource,tooDeep));
        // Folder baselines advance after successful directory creation during an in-place partial Save.
        var partial=PackWorkspace.newPack(files);String beforeFolder=partial.createSubfolder("blocks","before");Path partialRoot=temp.resolve("empty-folders-partial");partial.save(partialRoot);String afterFolder=partial.renameEmptyFolder(beforeFolder,"after");partial.duplicate("blocks/base.json","z_later");long saved=partial.saveRevision();
        rejects(() -> partial.save(partialRoot,(base,path,data,baseline)->{throw new java.io.IOException("Injected staging failure after folder creation");}));check(Files.isDirectory(partialRoot.resolve(afterFolder)) && partial.saveRevision()>saved && partial.pendingChanges().stream().noneMatch(entry -> entry.path().equals(afterFolder)),"partial directory creation gets an advanced baseline");partial.deleteEmptyFolder(afterFolder);check(pending(partial,afterFolder).kind()==WorkspaceChanges.Kind.REMOVED_FOLDER && partial.dirty(),"subsequent deletion retains physical cleanup after partial Save");
        // Each supported family permits empty leaf relocation; dimension and unrelated folders remain read-only.
        for (String family : List.of("regions","biomes","generators","image-maps","blocks","expressions","structures","jigsaw-pools","jigsaw-pieces","objects","images")) {
            var familyPack=PackWorkspace.newPack(Map.of(family+"/anchor.dat",new byte[]{1}));String one=familyPack.createSubfolder(family,"one"),into=familyPack.createSubfolder(family,"into");String renamedOne=familyPack.renameEmptyFolder(one,"two"),movedOne=familyPack.moveEmptyFolder(renamedOne,into);familyPack.deleteEmptyFolder(movedOne);check(familyPack.paths().size()==1 && !familyPack.directories().contains(movedOne),"supported family: "+family);
        }
        exactFiles(pack,files);check(drafts.any(),"all unrelated inspector drafts retained");
        System.out.println("PASS: staged empty leaf folder rename/move/delete across supported resource families, coalesced restore/cancel baselines, guarded nonrecursive Save removals, external/type/link/target conflicts, partial-save directory receipts, pending folder filters, history/no-op/failure isolation and byte-exact Save/Copy/As/ZIP with inspector drafts retained");
    }
}
