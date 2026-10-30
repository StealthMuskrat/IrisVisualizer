package dev.irisvisualizer.editor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

final class SaveMetadataTests {
    private static byte[] bytes(String text){return text.getBytes(StandardCharsets.UTF_8);}
    private static byte[] hash(byte[] data)throws Exception{return MessageDigest.getInstance("SHA-256").digest(data);}
    private static void check(boolean condition,String reason){if(!condition)throw new AssertionError("Save metadata: "+reason);}
    @FunctionalInterface private interface Action{void run()throws Exception;}
    private static IOException rejected(Action action)throws Exception{try{action.run();}catch(IOException expected){return expected;}throw new AssertionError("Expected metadata-guarded Save failure");}
    private static final FileSaves.Stager WRITE=(output,data)->output.write(data);
    private static final FileSaves.Publisher PUBLISH=(temporary,destination,replacing)->{if(replacing)Files.move(temporary,destination,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);else Files.move(temporary,destination);};
    private static void clean(Path root)throws Exception{try(var files=Files.list(root)){check(files.noneMatch(p->p.getFileName().toString().startsWith(FileSaves.TEMPORARY_PREFIX)),"owned staging files cleaned");}}
    private static AclEntry extra(UserPrincipal owner){return AclEntry.newBuilder().setType(AclEntryType.DENY).setPrincipal(owner).setPermissions(AclEntryPermission.EXECUTE).build();}
    private static List<AclEntry> withoutExecuteDeny(List<AclEntry> source,UserPrincipal owner){
        var result=new ArrayList<AclEntry>();
        for(var entry:source){
            if(entry.type()==AclEntryType.DENY && entry.principal().equals(owner) && entry.permissions().contains(AclEntryPermission.EXECUTE)){
                var permissions=new HashSet<>(entry.permissions());permissions.remove(AclEntryPermission.EXECUTE);
                if(!permissions.isEmpty())result.add(AclEntry.newBuilder(entry).setPermissions(permissions).build());
            }else result.add(entry);
        }
        return result;
    }
    static void run(Path temp)throws Exception{
        Path root=Files.createDirectory(temp.resolve("save-metadata")),file=root.resolve("resource.json");byte[] original=bytes("original bytes 雪"),changed=bytes("new applied bytes\r\n");Files.write(file,original);byte[] baseline=hash(original);
        var initial=SaveMetadata.read(file);check(initial.equals(SaveMetadata.read(file)),"stable immutable metadata sampling");
        if(initial.acl()!=null){try{initial.acl().clear();throw new AssertionError();}catch(UnsupportedOperationException expected){}}
        var dos=Files.getFileAttributeView(file,DosFileAttributeView.class,LinkOption.NOFOLLOW_LINKS);
        if(dos!=null){dos.setHidden(true);dos.setSystem(true);dos.setArchive(false);}
        var acl=Files.getFileAttributeView(file,AclFileAttributeView.class,LinkOption.NOFOLLOW_LINKS);
        if(acl!=null){var entries=new ArrayList<>(acl.getAcl());entries.add(0,extra(acl.getOwner()));acl.setAcl(entries);}
        var source=SaveMetadata.read(file);
        if(acl!=null)check(source.acl().stream().anyMatch(entry->entry.type()==AclEntryType.DENY && entry.principal().equals(source.owner()) && entry.permissions().contains(AclEntryPermission.EXECUTE)),"fixture exposes a real custom execute-denial ACL entry");
        FileSaves.write(root,"resource.json",changed,baseline);var saved=SaveMetadata.read(file);
        check(saved.equals(source.forEditedFile()) && Arrays.equals(Files.readAllBytes(file),changed),"existing owner/ACL/hidden/system preserved and archive marked for edited bytes");
        if(dos!=null){var flags=Files.getFileAttributeView(file,DosFileAttributeView.class).readAttributes();check(flags.isHidden() && flags.isSystem() && flags.isArchive() && !flags.isReadOnly(),"DOS flag preservation and archive semantics");}
        if(acl!=null)check(Files.getFileAttributeView(file,AclFileAttributeView.class).getAcl().equals(source.acl()),"custom Java-visible ACL entries/order preserved");clean(root);
        // Metadata-copy failure and incomplete metadata application cannot publish even valid new bytes.
        var owner=source.owner();byte[] currentHash=hash(changed);var published=new AtomicBoolean();
        rejected(()->FileSaves.write(root,"resource.json",original,currentHash,WRITE,(a,b)->{},(a,b,c)->published.set(true),(stage,metadata)->{throw new IOException("injected owner/ACL copy failure");}));
        check(!published.get() && Arrays.equals(Files.readAllBytes(file),changed) && SaveMetadata.read(file).equals(saved),"failed metadata copy retains original bytes and metadata");clean(root);
        if(source.dos()!=null || source.acl()!=null){
            // Force a staged mismatch through the injectable applier; verification remains in the actual writer.
            rejected(()->FileSaves.write(root,"resource.json",original,currentHash,WRITE,(a,b)->{},PUBLISH,(stage,metadata)->{
                SaveMetadata.apply(stage,metadata);
                if(metadata.dos()!=null)Files.getFileAttributeView(stage,DosFileAttributeView.class).setHidden(!metadata.dos().hidden());
                else{var view=Files.getFileAttributeView(stage,AclFileAttributeView.class);view.setAcl(withoutExecuteDeny(view.getAcl(),view.getOwner()));}
            }));check(Arrays.equals(Files.readAllBytes(file),changed) && SaveMetadata.read(file).equals(saved),"staged metadata mismatch refuses publication");clean(root);
        }
        // Byte-identical, timestamp-identical source metadata changes during staging are separate conflicts.
        if(dos!=null){
            var time=Files.getLastModifiedTime(file);var was=SaveMetadata.read(file);
            rejected(()->FileSaves.write(root,"resource.json",original,currentHash,WRITE,(stage,target)->{Files.getFileAttributeView(target,DosFileAttributeView.class).setHidden(false);Files.setLastModifiedTime(target,time);},PUBLISH));
            check(Arrays.equals(Files.readAllBytes(file),changed) && !SaveMetadata.read(file).dos().hidden(),"late DOS metadata change retained rather than overwritten");clean(root);
            dos=Files.getFileAttributeView(file,DosFileAttributeView.class);dos.setHidden(was.dos().hidden());
            dos.setReadOnly(true);try{rejected(()->FileSaves.write(root,"resource.json",original,currentHash));check(Arrays.equals(Files.readAllBytes(file),changed),"DOS read-only flag never bypassed");}finally{Files.getFileAttributeView(file,DosFileAttributeView.class).setReadOnly(false);}clean(root);
        }
        if(acl!=null){
            var time=Files.getLastModifiedTime(file);var was=SaveMetadata.read(file);var competing=withoutExecuteDeny(was.acl(),was.owner());var actual=new AtomicReference<List<AclEntry>>();
            rejected(()->FileSaves.write(root,"resource.json",original,currentHash,WRITE,(stage,target)->{var view=Files.getFileAttributeView(target,AclFileAttributeView.class);view.setAcl(competing);actual.set(List.copyOf(view.getAcl()));check(!actual.get().equals(was.acl()),"late ACL fixture changes the exposed ACL rather than adding a redundant entry");Files.setLastModifiedTime(target,time);},PUBLISH));
            check(Arrays.equals(Files.readAllBytes(file),changed) && Files.getFileAttributeView(file,AclFileAttributeView.class).getAcl().equals(actual.get()),"late ACL change retained even with equal byte hash/mtime");clean(root);Files.getFileAttributeView(file,AclFileAttributeView.class).setAcl(was.acl());
        }
        // A metadata failure does not advance workspace save baselines or disturb drafts/history/content identity.
        var pack=PackWorkspace.newPack(Map.of("blocks/a.json",original,"objects/exact.iob",new byte[]{0,1,-1}));Path packRoot=temp.resolve("save-metadata-workspace");pack.save(packRoot);pack.replace("blocks/a.json",changed);
        var drafts=new InspectorDrafts();drafts.update("late","stored","draft");var steps=pack.historySteps(false);long revision=pack.revision(),content=pack.contentRevision("blocks/a.json"),save=pack.saveRevision();
        rejected(()->pack.save(packRoot,(base,key,data,expected)->FileSaves.write(base,key,data,expected,WRITE,(a,b)->{},PUBLISH,(stage,metadata)->{throw new IOException("metadata permission denied");})));
        check(pack.saveRevision()==save && pack.revision()==revision && pack.contentRevision("blocks/a.json")==content && pack.historySteps(false).equals(steps) && drafts.any() && pack.dirty() && Arrays.equals(Files.readAllBytes(packRoot.resolve("blocks/a.json")),original),"metadata failure preserves editor state and old source bytes");clean(packRoot.resolve("blocks"));
        pack.save(packRoot);check(!pack.dirty() && Arrays.equals(Files.readAllBytes(packRoot.resolve("blocks/a.json")),changed),"explicit retry succeeds with current captured permissions");pack.undo();check(pack.dirty(),"Undo remains relative to confirmed byte baseline");pack.redo();check(!pack.dirty(),"Redo to confirmed bytes becomes clean");
        // New files use destination defaults, without attempting to copy a source owner/ACL from another folder.
        var called=new AtomicBoolean();FileSaves.write(root,"new.json",original,null,WRITE,(a,b)->{},PUBLISH,(stage,metadata)->called.set(true));check(!called.get() && Arrays.equals(Files.readAllBytes(root.resolve("new.json")),original),"new-file publication keeps destination defaults");
        pack.saveCopy(temp.resolve("save-metadata-copy"));pack.saveAs(temp.resolve("save-metadata-as"));check(pack.directory().equals(temp.resolve("save-metadata-as").toRealPath()),"fresh-folder operations remain available");
        // Restore fixture flags so its test-owned directory can be removed on every supported runner.
        if(source.dos()!=null){var view=Files.getFileAttributeView(file,DosFileAttributeView.class);view.setHidden(false);view.setSystem(false);view.setReadOnly(false);}
        check(Objects.equals(SaveMetadata.read(file).owner(),owner),"owner retained after replacement and rejected operations");
        System.out.println("PASS: staged Save metadata preservation with immutable Java-visible owner/ACL/DOS snapshots, exact existing owner and ACL entries/order plus hidden/system flags, archive-on-edit/read-only guards, byte-identical late metadata conflicts and staged verification, copy failure/no publication/no baseline advance, explicit retry and retained drafts/history/content stamps/Undo, new-file destination defaults and fresh-folder Save/Copy/As; aclViewExercised="+(acl!=null)+" dosViewExercised="+(source.dos()!=null)+"; native ACL inheritance/security-descriptor and custom timestamp guarantees excluded");
    }
}
