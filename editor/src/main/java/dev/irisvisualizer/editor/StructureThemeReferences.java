package dev.irisvisualizer.editor;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Global literal piece-tag review. Not graph membership, generation validation or mutation authorization. */
public final class StructureThemeReferences {
    public static final int MAX_FILES=256,MAX_FILE_BYTES=1_000_000,MAX_HITS=256,MAX_NOTICES=32,PAGE_SIZE=200,MAX_FILTER_CHARACTERS=256;
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
    static void validateKey(String key) { if (key==null || key.isEmpty() || key.length()>StructureThemes.MAX_KEY_CHARACTERS || key.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Review an exact 1-256-character theme key without controls"); }
    public static boolean supports(String path) { return path!=null && path.startsWith("jigsaw-pieces/") && path.endsWith(".json"); }
    private static void checkCancelled(BooleanSupplier cancelled) { if (cancelled.getAsBoolean()) throw new CancellationException("Piece-theme review cancelled"); }
    public static Review inspect(PackResourceView workspace,String key) { return inspect(workspace,key,() -> false); }
    public static Review inspect(PackResourceView workspace,String key,BooleanSupplier cancelled) { return inspect(workspace,key,new Limits(MAX_FILES,MAX_BYTES,MAX_HITS,MAX_NOTICES),cancelled); }
    static Review inspect(PackResourceView workspace,String key,Limits limits) { return inspect(workspace,key,limits,() -> false); }
    static Review inspect(PackResourceView workspace,String key,Limits limits,BooleanSupplier cancelled) {
        Objects.requireNonNull(workspace);Objects.requireNonNull(limits);Objects.requireNonNull(cancelled);validateKey(key);checkCancelled(cancelled);int eligible=0,considered=0,inspected=0,skipped=0,unfiltered=0,matched=0,omitted=0;long bytes=0;var hits=new ArrayList<Hit>();var notices=new ArrayList<Notice>();
        for (String path : workspace.paths().stream().filter(StructureThemeReferences::supports).sorted().toList()) {
            checkCancelled(cancelled);eligible++;String issue=null;int size=0;
            if (considered++>=limits.files()) issue="File inspection budget reached.";
            else { size=workspace.size(path);if (size>MAX_FILE_BYTES) issue="Piece exceeds the 1 MB inspection limit.";else if (size>limits.bytes()-bytes) issue="Byte inspection budget reached."; }
            if (issue!=null) { skipped++;if (notices.size()<limits.notices()) notices.add(new Notice(path,issue));else omitted++;continue; }
            bytes+=size;inspected++;
            try {
                var root=new LosslessJson(workspace.read(path));checkCancelled(cancelled);String token=root.token("themes");
                if (token==null || token.equals("null")) { unfiltered++;continue; }
                var tags=root.stringArrayPath("themes");checkCancelled(cancelled);if (tags.isEmpty()) { unfiltered++;continue; }
                int count=0;for (String tag : tags) { checkCancelled(cancelled);if (key.equals(tag)) count++; }
                if (count>0) { matched++;if (hits.size()<limits.hits()) hits.add(new Hit(path,count)); }
            } catch (IllegalArgumentException invalid) { if (notices.size()<limits.notices()) notices.add(new Notice(path,"Invalid/ambiguous JSON or unsupported theme-tag list; not interpreted."));else omitted++; }
        }
        checkCancelled(cancelled);return new Review(key,eligible,inspected,bytes,skipped,unfiltered,matched,hits,notices,omitted);
    }
    public record Page(int page,int matches,List<Hit> hits) {
        public Page { hits=List.copyOf(hits); }
        public boolean previous() { return page>0; }
        public boolean next() { return (long)(page+1)*PAGE_SIZE<matches; }
    }
    /** Filtering covers retained hits; a partial review cannot establish complete-pack matching counts. */
    public static final class PageCache {
        private Review review;private String filter;private List<Hit> matches=List.of();
        public void clear() { review=null;filter=null;matches=List.of(); }
        public Page page(Review current,String pathFilter,int requested) {
            Objects.requireNonNull(current);if (pathFilter==null || pathFilter.length()>MAX_FILTER_CHARACTERS || pathFilter.indexOf(0)>=0) throw new IllegalArgumentException("Use up to 256 path-filter characters without NUL");
            String query=pathFilter.strip().toLowerCase(Locale.ROOT);
            if (current!=review || !query.equals(filter)) { matches=current.hits().stream().filter(hit -> hit.path().toLowerCase(Locale.ROOT).contains(query)).toList();review=current;filter=query; }
            int page=Math.max(0,Math.min(requested,Math.max(0,(matches.size()-1)/PAGE_SIZE))),start=page*PAGE_SIZE;
            return new Page(page,matches.size(),matches.subList(start,Math.min(matches.size(),start+PAGE_SIZE)));
        }
    }
    public static final class Cache {
        private PackWorkspace workspace;private long revision=-1;private Review review;
        public void clear() { workspace=null;revision=-1;review=null; }
        public Review current(PackWorkspace current) { Objects.requireNonNull(current);if (workspace!=current || revision!=current.revision()) { clear();return null; }return review; }
        public Review refresh(PackWorkspace current,String key) { var next=inspect(current,key);workspace=current;revision=current.revision();review=next;return next; }
    }
}
