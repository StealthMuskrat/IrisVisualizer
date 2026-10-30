package dev.irisvisualizer.editor;

import java.util.Objects;

/** Exact applied-source navigation only; bounded context, no text editing or native interpretation. */
final class SourceMatch {
    static final int MAX_SIDE_CHARACTERS=512;
    record Request(PackWorkspace workspace,String path,long contentRevision,int offset,int line,int column,String literal) {}
    record View(Request request,String before,String matched,String after,boolean clippedBefore,boolean clippedAfter) {}
    static Request request(PackWorkspace workspace,WorkspaceTextSearch.Query query,WorkspaceTextSearch.Hit hit) {
        Objects.requireNonNull(workspace);Objects.requireNonNull(query);Objects.requireNonNull(hit);
        if (!JsonStructure.supports(hit.path()) || !workspace.paths().contains(hit.path()) || !query.family().matches(hit.path())) throw new IllegalArgumentException("Search source is no longer available in this workspace");
        if (hit.offset()<0 || hit.line()<1 || hit.column()<1 || (long)hit.offset()+query.text().length()>WorkspaceTextSearch.MAX_FILE_BYTES) throw new IllegalArgumentException("Invalid source match location");
        if (workspace.size(hit.path())>WorkspaceTextSearch.MAX_FILE_BYTES) throw new IllegalArgumentException("Source match context is limited to 1 MiB; search again after reducing the source");
        return new Request(workspace,hit.path(),workspace.contentRevision(hit.path()),hit.offset(),hit.line(),hit.column(),query.text());
    }
    static void validate(PackWorkspace workspace,Request request) {
        if (request==null || request.workspace()!=workspace || !workspace.paths().contains(request.path()) || workspace.contentRevision(request.path())!=request.contentRevision()) throw new IllegalArgumentException("Source bytes changed; search again before opening this match");
    }
    static View inspect(PackWorkspace workspace,Request request) {
        validate(workspace,request);
        if (!JsonStructure.supports(request.path()) || workspace.size(request.path())>WorkspaceTextSearch.MAX_FILE_BYTES || request.offset()<0 || request.line()<1 || request.column()<1) throw new IllegalArgumentException("Unsupported source match location");
        // Also validate package-local requests constructed without the search-result factory.
        new WorkspaceTextSearch.Query(request.literal(),ResourceFinder.Family.ALL,false);
        String text;
        try { text=WorkspaceTextSearch.decode(workspace.read(request.path())); }
        catch (java.nio.charset.CharacterCodingException invalid) { throw new IllegalArgumentException("Source is not strict UTF-8; search again",invalid); }
        if (text.chars().anyMatch(c -> Character.isISOControl(c) && c!='\r' && c!='\n' && c!='\t')) throw new IllegalArgumentException("Source contains control text; match context is unavailable");
        long end=(long)request.offset()+request.literal().length();
        if (end>text.length() || !text.startsWith(request.literal(),request.offset())) throw new IllegalArgumentException("Literal match is no longer at this offset; search again");
        int offset=request.offset(),stop=(int)end;
        if (split(text,offset) || split(text,stop)) throw new IllegalArgumentException("Match splits a Unicode character; search for the complete character");
        int cursor=0,line=1,lineStart=0;
        while (cursor<offset) {
            char c=text.charAt(cursor++);
            if (c=='\r') { if (cursor<offset && text.charAt(cursor)=='\n') cursor++;line++;lineStart=cursor; }
            else if (c=='\n') { line++;lineStart=cursor; }
        }
        if (line!=request.line() || offset-lineStart+1!=request.column()) throw new IllegalArgumentException("Search line/column no longer matches the source; search again");
        int start=Math.max(0,offset-MAX_SIDE_CHARACTERS),finish=Math.min(text.length(),stop+MAX_SIDE_CHARACTERS);
        if (split(text,start)) start++;if (split(text,finish)) finish--;
        return new View(request,text.substring(start,offset),text.substring(offset,stop),text.substring(stop,finish),start>0,finish<text.length());
    }
    private static boolean split(String text,int position) { return position>0 && position<text.length() && Character.isHighSurrogate(text.charAt(position-1)) && Character.isLowSurrogate(text.charAt(position)); }
    /** Deepest indexed value/container containing the whole raw-source match; member names belong to a container. */
    static int containing(JsonStructure.Snapshot snapshot,View view) {
        Objects.requireNonNull(snapshot);Objects.requireNonNull(view);int offset=view.request().offset();long end=(long)offset+view.matched().length();
        if (!snapshot.indexed() || offset<0 || end>snapshot.source().length() || !snapshot.source().startsWith(view.matched(),offset)) return -1;
        int selected=-1,depth=-1;
        for (var node : snapshot.nodes()) if (node.start()<=offset && node.end()>=end && node.depth()>depth) { selected=node.id();depth=node.depth(); }
        return selected;
    }
    static final class Focus {
        private View view;
        void show(PackWorkspace current,View next) { Objects.requireNonNull(next);validate(current,next.request());view=next; }
        View current(PackWorkspace current,String selected) {
            if (view!=null) {
                var request=view.request();
                if (request.workspace()!=current || !Objects.equals(selected,request.path()) || !current.paths().contains(request.path()) || current.contentRevision(request.path())!=request.contentRevision()) clear();
            }
            return view;
        }
        void clear() { view=null; }
    }
}
