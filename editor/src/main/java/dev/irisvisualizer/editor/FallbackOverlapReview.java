package dev.irisvisualizer.editor;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Read-only search and paging of recorded overlaps, never a native winner or complete-pack claim. */
public final class FallbackOverlapReview {
    public static final int PAGE_SIZE=200,MAX_QUERY_CHARACTERS=256;
    public record Query(String text,String dimension) {
        public Query {
            if (text==null || text.length()>MAX_QUERY_CHARACTERS || text.indexOf(0)>=0 || dimension!=null && dimension.indexOf(0)>=0) throw new IllegalArgumentException("Use up to 256 search characters without NUL");
            text=text.strip().toLowerCase(Locale.ROOT);
        }
    }
    public record Page<T>(int page,int matches,List<T> entries) {
        public Page { entries=List.copyOf(entries); }
        public boolean previous() { return page>0; }
        public boolean next() { return (long)(page+1)*PAGE_SIZE<matches; }
    }
    private static <T> Page<T> page(List<T> values,int requested) {
        int page=Math.max(0,Math.min(requested,Math.max(0,(values.size()-1)/PAGE_SIZE))),start=page*PAGE_SIZE;
        return new Page<>(page,values.size(),values.subList(start,Math.min(values.size(),start+PAGE_SIZE)));
    }
    private static boolean contains(String value,String query) { return value.toLowerCase(Locale.ROOT).contains(query); }
    private static boolean matches(BlockFallbacks.Overlap overlap,Query query) {
        if (query.dimension()!=null && overlap.definitions().stream().noneMatch(d -> d.path().equals(query.dimension()))) return false;
        return query.text().isEmpty() || contains(overlap.normalizedSource(),query.text()) || overlap.definitions().stream().anyMatch(d -> contains(d.path(),query.text()) || contains(d.source(),query.text()) || contains(d.target(),query.text()));
    }
    public static Page<BlockFallbacks.Definition> definitions(BlockFallbacks.Overlap overlap,int page) { return page(Objects.requireNonNull(overlap).definitions(),page); }
    public static final class Cache {
        private BlockFallbacks.Audit audit;
        private Query query;
        private List<BlockFallbacks.Overlap> matches=List.of();
        public void clear() { audit=null;query=null;matches=List.of(); }
        public Page<BlockFallbacks.Overlap> page(BlockFallbacks.Audit current,Query next,int requested) {
            Objects.requireNonNull(current);Objects.requireNonNull(next);
            if (current!=audit || !next.equals(query)) { matches=current.overlaps().stream().filter(group -> matches(group,next)).toList();audit=current;query=next; }
            return FallbackOverlapReview.page(matches,requested);
        }
    }
}
