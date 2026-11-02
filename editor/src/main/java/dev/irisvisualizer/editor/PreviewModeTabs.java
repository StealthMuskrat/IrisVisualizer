package dev.irisvisualizer.editor;

import java.util.Objects;

/** Synchronizes session choices with ImGui's deferred tab selection, without reasserting old tabs. */
final class PreviewModeTabs {
    private PackWorkspace owner;
    private String path;
    private PreviewSession.Kind kind;
    private EditorShell.Toolbar surface;
    private PreviewSession.Mode last,pending;

    void prepare(PackWorkspace current,PreviewSession.View view,EditorShell.Toolbar toolbar){
        Objects.requireNonNull(current);Objects.requireNonNull(view);Objects.requireNonNull(toolbar);
        if(owner!=current || !Objects.equals(path,view.path()) || kind!=view.kind() || surface!=toolbar || last!=view.mode()){
            owner=current;path=view.path();kind=view.kind();surface=toolbar;last=view.mode();
            pending=view.meaningful(last)?last:null;
        }
    }
    /** Only a pending programmatic request emits SetSelected; ordinary user switches emit none. */
    boolean request(PreviewSession.Mode mode){return pending==Objects.requireNonNull(mode);}
    /** A previous native tab may remain visible for one frame after a resource/layout change. */
    PreviewSession.Mode visible(PreviewSession.Mode mode){
        Objects.requireNonNull(mode);
        if(kind==null || !kind.meaningful(mode) || pending!=null && pending!=mode)return null;
        pending=null;last=mode;return mode;
    }
    void clear(){owner=null;path=null;kind=null;surface=null;last=pending=null;}
}
