package dev.irisvisualizer.editor;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Global literal piece-tag review. Not graph membership, generation validation or mutation authorization. */
public final class StructureThemeReferences {
    public static final int MAX_FILES=256,MAX_FILE_BYTES=1_000_000,MAX_HITS=200,MAX_NOTICES=32;
    public static final long MAX_BYTES=8_000_000;
    public record Hit(String path,int occurrences) {}
    public record Notice(String path,String detail) {}
    public record Review(String key,int eligible,int inspected,long bytes,int skipped,int unfiltered,int matchedFiles,List<Hit> hits,List<Notice> notices,int omittedNotices) {
        public Review { hits=List.copyOf(hits);notices=List.copyOf(notices); }
        public boolean incomplete() { return skipped>0 || !notices.isEmpty() || omittedNotices>0; }
        public boolean truncated() { return matchedFiles>hits.size(); }
    }
    record Limits(int files,long bytes,int hits,int notices) {
        Limits { if (files<1 || files>MAX_FILES || bytes<1 || bytes>MAX_BYTES || hits<1 || hits>MAX_HITS || notices<1 || notices>MAX_NOTICES) throw new IllegalArgumentException("Invalid piece-theme review limits"); }
    }
    private static void query(String key) { if (key==null || key.isEmpty() || key.length()>StructureThemes.MAX_KEY_CHARACTERS || key.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Review an exact 1-256-character theme key without controls"); }
    public static Review inspect(PackWorkspace workspace,String key) { return inspect(workspace,key,new Limits(MAX_FILES,MAX_BYTES,MAX_HITS,MAX_NOTICES)); }
    static Review inspect(PackWorkspace workspace,String key,Limits limits) {
        Objects.requireNonNull(workspace);Objects.requireNonNull(limits);query(key);int eligible=0,considered=0,inspected=0,skipped=0,unfiltered=0,matched=0,omitted=0;long bytes=0;var hits=new ArrayList<Hit>();var notices=new ArrayList<Notice>();
        for (String path : workspace.paths()) {
            if (!path.startsWith("jigsaw-pieces/") || !path.endsWith(".json")) continue;eligible++;
            String issue=null;int size=workspace.size(path);
            if (considered++>=limits.files()) issue="File inspection budget reached.";
            else if (size>MAX_FILE_BYTES) issue="Piece exceeds the 1 MB inspection limit.";
            else if (size>limits.bytes()-bytes) issue="Byte inspection budget reached.";
            if (issue!=null) { skipped++;if (notices.size()<limits.notices()) notices.add(new Notice(path,issue));else omitted++;continue; }
            bytes+=size;inspected++;
            try {
                var root=new LosslessJson(workspace.read(path));String token=root.token("themes");
                if (token==null || token.equals("null")) { unfiltered++;continue; }
                var tags=root.stringArrayPath("themes");if (tags.isEmpty()) { unfiltered++;continue; }
                int count=0;for (String tag : tags) if (key.equals(tag)) count++;
                if (count>0) { matched++;if (hits.size()<limits.hits()) hits.add(new Hit(path,count)); }
            } catch (IllegalArgumentException invalid) { if (notices.size()<limits.notices()) notices.add(new Notice(path,"Invalid/ambiguous JSON or unsupported theme-tag list; not interpreted."));else omitted++; }
        }
        return new Review(key,eligible,inspected,bytes,skipped,unfiltered,matched,hits,notices,omitted);
    }
    public static final class Cache {
        private PackWorkspace workspace;private long revision=-1;private Review review;
        public void clear() { workspace=null;revision=-1;review=null; }
        public Review current(PackWorkspace current) { Objects.requireNonNull(current);if (workspace!=current || revision!=current.revision()) { clear();return null; }return review; }
        public Review refresh(PackWorkspace current,String key) { var next=inspect(current,key);workspace=current;revision=current.revision();review=next;return next; }
    }
}
