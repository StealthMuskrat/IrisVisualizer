package dev.irisvisualizer.editor;

import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Captured selected-resource diagnostics and pages; exact presence only, never edit authority. */
public final class ReferenceInspection {
    static final long MAX_PATH_CHARACTERS=16_000_000;
    /** The workspace owns immutable arrays. Oversize sources retain only their size, never their payload. */
    static final class Source {
        final String path;
        private final int bytes;
        private final byte[] data;
        private final Set<String> paths;
        Source(String path,byte[] data,Set<String> paths){this(path,data,paths,PackWorkspace.MAX_FILES,MAX_PATH_CHARACTERS);}
        Source(String path,byte[] data,Set<String> names,int files,long characters){
            this.path=Objects.requireNonNull(path);Objects.requireNonNull(data);Objects.requireNonNull(names);
            if(!ReferenceDiagnostics.supports(path) || !names.contains(path) || files<1 || files>PackWorkspace.MAX_FILES || characters<1 || characters>MAX_PATH_CHARACTERS)throw new IllegalArgumentException("Invalid resource link snapshot");
            bytes=data.length;if(bytes>PackWorkspace.MAX_BYTES)throw new IllegalArgumentException("Resource exceeds workspace byte limit");this.data=bytes<=ReferenceDiagnostics.MAX_JSON_BYTES ? data : null;
            var captured=new TreeSet<String>();long used=0;
            for(String name:names){used+=Objects.requireNonNull(name).length();if(captured.size()>=files || used>characters)throw new IllegalArgumentException("Resource link snapshot exceeds its file/path metadata budget");captured.add(name);}
            paths=Collections.unmodifiableSet(captured);
        }
        int bytes(){return bytes;}
        int retainedBytes(){return data==null ? 0 : data.length;}
        PackResourceView view(BooleanSupplier cancelled){
            Objects.requireNonNull(cancelled);
            return new PackResourceView(){
                public Set<String> paths(){check(cancelled);return paths;}
                public int size(String selected){check(cancelled);if(!path.equals(selected))throw new IllegalArgumentException("Target content is outside the selected-resource snapshot");return bytes;}
                public byte[] read(String selected){
                    size(selected);if(data==null)throw new IllegalArgumentException("Oversize resource payload was not retained");byte[] copy=new byte[data.length];
                    for(int offset=0;offset<data.length;offset+=65536){check(cancelled);System.arraycopy(data,offset,copy,offset,Math.min(65536,data.length-offset));}check(cancelled);return copy;
                }
            };
        }
    }
    public record Report(String path,int bytes,List<ReferenceDiagnostics.Link> links){
        public Report {Objects.requireNonNull(path);if(bytes<0 || bytes>PackWorkspace.MAX_BYTES || links.size()>ReferenceDiagnostics.MAX_LINKS+1)throw new IllegalArgumentException("Invalid resource link report");links=List.copyOf(links);}
    }
    static Report inspect(Source source,BooleanSupplier cancelled){
        check(cancelled);var links=ReferenceDiagnostics.inspect(source.view(cancelled),source.path);check(cancelled);return new Report(source.path,source.bytes,links);
    }
    private static void check(BooleanSupplier cancelled){if(cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())throw new CancellationException("Resource link inspection cancelled");}
    public enum StateFilter {
        ALL("All recorded states",null),EXACT("Exact file present",ReferenceDiagnostics.State.EXACT_FILE),MISSING("No exact file",ReferenceDiagnostics.State.NO_EXACT_FILE),ABSENT("Absent required reference",ReferenceDiagnostics.State.ABSENT_REQUIRED),EMPTY("Empty required list",ReferenceDiagnostics.State.EMPTY_REQUIRED),UNSUPPORTED("Inspection unavailable",ReferenceDiagnostics.State.UNSUPPORTED),INVALID("Unsupported key",ReferenceDiagnostics.State.INVALID_KEY);
        private final String label;private final ReferenceDiagnostics.State state;
        StateFilter(String label,ReferenceDiagnostics.State state){this.label=label;this.state=state;}
        public String label(){return label;}
    }
    public record Query(String text,StateFilter state){
        public Query {if(text==null || text.length()>256 || text.chars().anyMatch(Character::isISOControl) || state==null)throw new IllegalArgumentException("Use up to 256 link filter characters without controls");text=text.strip().toLowerCase(Locale.ROOT);}
        boolean matches(ReferenceDiagnostics.Link link){return (state.state==null || link.state()==state.state) && (contains(link.field()) || contains(link.key()) || contains(link.target()));}
        private boolean contains(String value){return value!=null && value.toLowerCase(Locale.ROOT).contains(text);}
    }
    /** One cached bounded view; filters never inspect the live workspace or repeat the scan. */
    public static final class PageCache {
        private Report report;private Query query;private ReviewPagination.Page<ReferenceDiagnostics.Link> page;
        public void clear(){report=null;query=null;page=null;}
        public ReviewPagination.Page<ReferenceDiagnostics.Link> page(Report current,Query next,int requested){
            Objects.requireNonNull(current);Objects.requireNonNull(next);
            if(current==report && next.equals(query) && page!=null && page.window().equals(ReviewPagination.Window.at(page.window().matches(),requested)))return page;
            var selected=ReviewPagination.select(current.links(),next::matches,requested);report=current;query=next;return page=selected;
        }
    }
    private ReferenceInspection(){}
}
