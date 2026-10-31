package dev.irisvisualizer.editor;

import java.io.IOException;
import java.util.Objects;

/** UI-owned, metadata-only review of one empty-folder operation. Never reads resource bytes or disk. */
public final class ReviewedFolderOperation {
    public enum Mode { CREATE, RENAME, MOVE, DELETE }
    public enum Phase { IDLE, READY, STALE, APPLIED }
    public record Plan(Mode mode,String path,String value,String target) {
        public boolean changes() { return mode==Mode.CREATE || mode==Mode.DELETE || !path.equals(target); }
    }
    public record Snapshot(long request,Phase phase,Plan plan,String detail) {}
    public static final class Acknowledgement {
        private final ReviewedFolderOperation authority;private final PackWorkspace owner;private final long request,revision;
        private Acknowledgement(ReviewedFolderOperation authority,PackWorkspace owner,long request) { this.authority=authority;this.owner=owner;this.request=request;revision=owner.revision(); }
    }
    private PackWorkspace owner;
    private long generation,resources;
    private Snapshot snapshot=new Snapshot(0,Phase.IDLE,null,null);
    /** Invalid replacement input leaves an accepted review intact. No mutation or temporary folder installation. */
    public Snapshot prepare(PackWorkspace current,Mode mode,String path,String value) throws IOException {
        var plan=Objects.requireNonNull(current).reviewFolderOperation(mode,path,value);
        owner=current;resources=current.resourceRevision();snapshot=new Snapshot(++generation,Phase.READY,plan,null);return snapshot;
    }
    public Snapshot poll(PackWorkspace current) {
        Objects.requireNonNull(current);
        if(owner!=current) { clear();owner=current; }
        else if(snapshot.phase()==Phase.READY && resources!=current.resourceRevision())snapshot=new Snapshot(++generation,Phase.STALE,snapshot.plan(),"Workspace folders or resource paths changed; prepare this folder operation again.");
        return snapshot;
    }
    public Acknowledgement acknowledge(PackWorkspace current,long request) throws IOException {
        var state=poll(current);if(state.phase()!=Phase.READY || state.request()!=request)throw new IOException("Review the current folder operation first");return new Acknowledgement(this,current,request);
    }
    public boolean acknowledged(PackWorkspace current,long request,Acknowledgement acknowledgement) {
        return snapshot.phase()==Phase.READY && snapshot.request()==request && owner==current && acknowledgement!=null && acknowledgement.authority==this && acknowledgement.owner==current && acknowledgement.request==request && acknowledgement.revision==current.revision();
    }
    public String apply(PackWorkspace current,long request,Acknowledgement acknowledgement) throws IOException {
        var state=poll(current);if(state.phase()!=Phase.READY || state.request()!=request)throw new IOException("No current folder review is ready; prepare again");
        var plan=state.plan();if(plan.changes() && current.undoCount()+current.redoCount()>0 && !acknowledged(current,request,acknowledgement))throw new IOException("Acknowledge clearing the current applied-edit history before applying");
        var checked=current.reviewFolderOperation(plan.mode(),plan.path(),plan.value());if(!plan.equals(checked))throw new IOException("Folder operation changed; prepare again");
        String target=switch(plan.mode()) {
            case CREATE->current.createSubfolder(plan.path(),plan.value());
            case RENAME->current.renameEmptyFolder(plan.path(),plan.value());
            case MOVE->current.moveEmptyFolder(plan.path(),plan.value());
            case DELETE->{current.deleteEmptyFolder(plan.path());yield null;}
        };
        snapshot=new Snapshot(++generation,Phase.APPLIED,plan,plan.changes() ? "Folder operation applied in memory; applied-edit history cleared. Save remains explicit." : "Folder already has that location; no workspace or history change.");return target;
    }
    public void clear() { owner=null;snapshot=new Snapshot(++generation,Phase.IDLE,null,null); }
}
