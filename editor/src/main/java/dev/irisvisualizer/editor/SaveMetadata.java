package dev.irisvisualizer.editor;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.List;

/** Java-visible source security and DOS flags only; no content, timestamps or native security-descriptor promises. */
final class SaveMetadata {
    record Dos(boolean hidden,boolean system,boolean archive,boolean readOnly) {}
    record State(UserPrincipal owner,List<AclEntry> acl,Dos dos) {
        State { if(acl!=null)acl=List.copyOf(acl); }
        State forEditedFile() { return new State(owner,acl,dos==null ? null : new Dos(dos.hidden(),dos.system(),true,false)); }
    }
    static State read(Path file) throws IOException {
        PackWorkspace.rejectLinks(file);
        try {
            State first=sample(file),second=sample(file);
            if(!first.equals(second))throw new IOException("Resource permissions or attributes changed while being checked: "+file);
            PackWorkspace.rejectLinks(file);return first;
        } catch(UnsupportedOperationException | SecurityException unsupported) { throw new IOException("Cannot read resource permissions/attributes: "+file+". Use Save As/Save Copy to a fresh folder.",unsupported); }
    }
    private static State sample(Path file) throws IOException {
        var owner=Files.getFileAttributeView(file,FileOwnerAttributeView.class,LinkOption.NOFOLLOW_LINKS);
        var acl=Files.getFileAttributeView(file,AclFileAttributeView.class,LinkOption.NOFOLLOW_LINKS);
        var dos=Files.getFileAttributeView(file,DosFileAttributeView.class,LinkOption.NOFOLLOW_LINKS);
        Dos flags=null;
        if(dos!=null) { var value=dos.readAttributes();flags=new Dos(value.isHidden(),value.isSystem(),value.isArchive(),value.isReadOnly()); }
        return new State(owner==null ? null : owner.getOwner(),acl==null ? null : acl.getAcl(),flags);
    }
    static void apply(Path temporary,State source) throws IOException {
        PackWorkspace.rejectLinks(temporary);
        if(source.dos()!=null && source.dos().readOnly())throw new IOException("Resource became read-only before publication");
        try {
            if(source.owner()!=null) {
                var owner=Files.getFileAttributeView(temporary,FileOwnerAttributeView.class,LinkOption.NOFOLLOW_LINKS);
                if(owner==null)throw new IOException("Staging filesystem cannot preserve resource ownership");
                if(!source.owner().equals(owner.getOwner()))owner.setOwner(source.owner());
            }
            if(source.acl()!=null) {
                var acl=Files.getFileAttributeView(temporary,AclFileAttributeView.class,LinkOption.NOFOLLOW_LINKS);
                if(acl==null)throw new IOException("Staging filesystem cannot preserve resource ACL entries");
                // Keep the staging file's native inheritance details when its exposed entries already match.
                if(!acl.getAcl().equals(source.acl()))acl.setAcl(source.acl());
            }
            if(source.dos()!=null) {
                var dos=Files.getFileAttributeView(temporary,DosFileAttributeView.class,LinkOption.NOFOLLOW_LINKS);
                if(dos==null)throw new IOException("Staging filesystem cannot preserve DOS attributes");
                var flags=dos.readAttributes();
                if(flags.isHidden()!=source.dos().hidden())dos.setHidden(source.dos().hidden());
                if(flags.isSystem()!=source.dos().system())dos.setSystem(source.dos().system());
                if(!flags.isArchive())dos.setArchive(true);
            }
            if(!read(temporary).equals(source.forEditedFile()))throw new IOException("Staged resource permissions/attributes do not match the source");
        } catch(IOException | UnsupportedOperationException | SecurityException unsupported) { throw new IOException("Cannot preserve resource permissions/attributes on the staged file. Save has not replaced the resource; use Save As/Save Copy to a fresh folder. "+unsupported.getMessage(),unsupported); }
    }
    private SaveMetadata() {}
}
