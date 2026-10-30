package dev.irisvisualizer.editor;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.ThreadFactory;

/** UI-owned Save As completion. Copying never accesses the workspace; adoption happens explicitly on the UI. */
public final class AsyncSaveAs implements AutoCloseable {
    public enum Phase { IDLE, COPYING, CANCELLING, PUBLISHING, READY, ADOPTED, COPIED, CANCELLED, FAILED, CLOSED }
    public record Snapshot(long request,Phase phase,StagedSaveAs.Target target,int entries,long bytes,FolderCopies.Receipt receipt,String detail) {}
    private final AsyncFolderCopy copy;
    private StagedSaveAs.Target target;
    private boolean closed,kept;
    private String keepReason;
    private Snapshot snapshot=new Snapshot(0,Phase.IDLE,null,0,0,null,null);
    public AsyncSaveAs() { copy=new AsyncFolderCopy(FolderCopies::copyForSaveAs,runnable->new Thread(runnable,"iris-save-as")); }
    AsyncSaveAs(AsyncFolderCopy.Writer writer,ThreadFactory threads) { copy=new AsyncFolderCopy(writer,threads); }
    public boolean busy() { return copy.busy(); }
    public Snapshot start(PackWorkspace current,Path destination) throws IOException {
        if (closed) throw new IllegalStateException("Save As is closed");if (copy.busy()) throw new IOException("A Save As transfer is active; wait or cancel before starting another");
        var captured=StagedSaveAs.capture(current,destination);var started=copy.startSnapshot(captured.source,destination);target=captured.target;kept=false;keepReason=null;
        snapshot=new Snapshot(started.request(),Phase.COPYING,target,0,0,null,null);return snapshot;
    }
    public void keepCopy() { if (target!=null && pendingAdoption()) { kept=true;keepReason="Keeping the captured snapshot as a separate copy by request."; } }
    private boolean pendingAdoption() { return snapshot.phase()==Phase.COPYING || snapshot.phase()==Phase.CANCELLING || snapshot.phase()==Phase.PUBLISHING || snapshot.phase()==Phase.READY; }
    public Snapshot poll(PackWorkspace current) {
        Objects.requireNonNull(current);if (closed || !pendingAdoption()) return snapshot;
        if (!kept && !StagedSaveAs.current(current,target)) { kept=true;keepReason="Workspace, applied edits or save baseline changed; the completed snapshot will remain a separate copy."; }
        var state=copy.poll();Phase phase=switch(state.phase()) { case IDLE->Phase.IDLE;case COPYING->Phase.COPYING;case CANCELLING->Phase.CANCELLING;case PUBLISHING->Phase.PUBLISHING;case SUCCEEDED->kept ? Phase.COPIED : Phase.READY;case CANCELLED->Phase.CANCELLED;case FAILED->Phase.FAILED;case CLOSED->Phase.CLOSED; };
        String detail=state.detail();
        if (state.phase()==AsyncFolderCopy.Phase.SUCCEEDED) {
            try { StagedSaveAs.validateReceipt(target,state.receipt());detail=kept ? keepReason : "Snapshot published; baseline adoption awaits the end-of-frame workspace check."; }
            catch (IOException invalid) { phase=Phase.FAILED;detail=InspectorText.label(invalid.getMessage(),512); }
        } else if (kept && detail==null) detail=keepReason;
        snapshot=new Snapshot(state.request(),phase,target,state.entries(),state.bytes(),state.receipt(),detail);return snapshot;
    }
    /** Run after all frame edits, history actions and workspace navigation; no disk I/O or source hashing here. */
    public Snapshot finish(PackWorkspace current) {
        var state=poll(current);if (state.phase()!=Phase.READY) return state;
        try { StagedSaveAs.adopt(current,target,state.receipt());snapshot=new Snapshot(state.request(),Phase.ADOPTED,target,state.entries(),state.bytes(),state.receipt(),"Save As completed. The captured folder is now active; drafts and applied-edit history remain available."); }
        catch (IOException stale) { kept=true;snapshot=new Snapshot(state.request(),Phase.COPIED,target,state.entries(),state.bytes(),state.receipt(),InspectorText.label(stale.getMessage(),512)); }
        return snapshot;
    }
    public boolean cancel() { return !closed && copy.cancel(); }
    public void clear() { if (closed) return;copy.clear();target=null;kept=false;keepReason=null;snapshot=new Snapshot(copy.poll().request(),Phase.IDLE,null,0,0,null,null); }
    @Override public void close() { if (closed) return;closed=true;copy.close();target=null;snapshot=new Snapshot(copy.poll().request(),Phase.CLOSED,null,0,0,null,null); }
}
