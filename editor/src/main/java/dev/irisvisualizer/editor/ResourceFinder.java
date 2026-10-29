package dev.irisvisualizer.editor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.PriorityQueue;

/** Bounded path-only resource search. Never reads file contents or constructs a native resource. */
public final class ResourceFinder {
    public static final int MAX_RESULTS=100,MAX_QUERY_CHARACTERS=256,MAX_TOKENS=8;
    public static final long MAX_INDEX_CHARACTERS=16_000_000;
    public enum Family {
        ALL("All resources",null),DIMENSIONS("Dimensions","dimensions"),REGIONS("Regions","regions"),BIOMES("Biomes","biomes"),GENERATORS("Generators","generators"),IMAGE_MAPS("Image maps","image-maps"),BLOCKS("Block aliases","blocks"),EXPRESSIONS("Expressions","expressions"),STRUCTURES("Structures","structures"),JIGSAW_POOLS("Jigsaw pools","jigsaw-pools"),JIGSAW_PIECES("Jigsaw pieces","jigsaw-pieces"),OBJECTS("Object assets","objects"),IMAGES("Image assets","images"),OTHER("Other files",null);
        private final String label,base;
        Family(String label,String base) { this.label=label;this.base=base; }
        public String label() { return label; }
        public boolean matches(String path) { return this==ALL || this==classify(path); }
        static Family classify(String path) { String base=ResourceTypes.fileFamily(path);for (var family : values()) if (family.base!=null && family.base.equals(base)) return family;return OTHER; }
    }
    public record Query(String text,Family family,boolean changedOnly) {
        public Query {
            if (text==null || text.indexOf(0)>=0 || text.length()>MAX_QUERY_CHARACTERS || family==null) throw new IllegalArgumentException("Use up to 256 search characters without NUL");
            text=text.strip().toLowerCase(Locale.ROOT);
            if (!text.isEmpty() && text.split("\\s+").length>MAX_TOKENS) throw new IllegalArgumentException("Use at most eight space-separated search terms");
        }
    }
    public record Hit(String path,Family family,int rank) {}
    public record Result(int totalFiles,int indexedFiles,int matches,List<Hit> hits,boolean incompleteIndex) {
        public Result { hits=List.copyOf(hits); }
        public boolean truncated() { return matches>hits.size(); }
    }
    record Limits(int files,long characters,int results) {
        Limits { if (files<1 || files>PackWorkspace.MAX_FILES || characters<1 || characters>MAX_INDEX_CHARACTERS || results<1 || results>MAX_RESULTS) throw new IllegalArgumentException("Invalid resource-finder limits"); }
    }
    private record Entry(String path,String lower,Family family,int name,int extension) {}
    private record Candidate(Entry entry,int rank) {}
    private static final Comparator<Candidate> ORDER=Comparator.comparingInt(Candidate::rank).thenComparing(c -> c.entry().lower()).thenComparing(c -> c.entry().path());
    private static int rank(Entry entry,String query) {
        String lower=entry.lower();int size=query.length(),name=entry.name(),end=entry.extension();
        if (query.isEmpty()) return 6;
        if (lower.equals(query) || end==size && lower.startsWith(query)) return 0;
        if (lower.length()-name==size && lower.regionMatches(name,query,0,size)) return 1;
        if (end-name==size && lower.regionMatches(name,query,0,size)) return 2;
        if (lower.endsWith("/"+query) || end>size && lower.charAt(end-size-1)=='/' && lower.regionMatches(end-size,query,0,size)) return 3;
        if (lower.startsWith(query,name)) return 4;
        if (lower.indexOf(query,name)>=0) return 5;
        return 6;
    }
    public static final class Cache {
        private final Limits limits;
        private PackWorkspace workspace;
        private long resourceRevision=-1,appliedRevision=-1;
        private boolean dirty,incomplete;
        private List<Entry> index=List.of();
        private Query previous;
        private Result result;
        public Cache() { this(new Limits(PackWorkspace.MAX_FILES,MAX_INDEX_CHARACTERS,MAX_RESULTS)); }
        Cache(Limits limits) { this.limits=Objects.requireNonNull(limits); }
        public void clear() { workspace=null;resourceRevision=-1;appliedRevision=-1;previous=null;result=null;index=List.of();incomplete=false; }
        public Result query(PackWorkspace current,Query query) {
            Objects.requireNonNull(current);Objects.requireNonNull(query);
            if (workspace!=current || resourceRevision!=current.resourceRevision()) {
                var entries=new ArrayList<Entry>();long characters=0;incomplete=false;
                for (String path : current.paths()) {
                    String lower=path.toLowerCase(Locale.ROOT);
                    if (entries.size()>=limits.files() || characters+lower.length()>limits.characters()) { incomplete=true;break; }
                    int name=lower.lastIndexOf('/')+1,extension=lower.lastIndexOf('.');if (extension<=name) extension=lower.length();
                    entries.add(new Entry(path,lower,Family.classify(path),name,extension));characters+=lower.length();
                }
                index=List.copyOf(entries);workspace=current;resourceRevision=current.resourceRevision();result=null;
            }
            if (result!=null && query.equals(previous) && (!query.changedOnly() || appliedRevision==current.revision() && dirty==current.dirty())) return result;
            var best=new PriorityQueue<Candidate>(ORDER.reversed());int matches=0;String[] tokens=query.text().isEmpty() ? new String[0] : query.text().split("\\s+");
            for (var entry : index) {
                if (query.family()!=Family.ALL && query.family()!=entry.family() || query.changedOnly() && !current.changed(entry.path())) continue;
                boolean match=true;for (String token : tokens) if (!entry.lower().contains(token)) { match=false;break; }if (!match) continue;
                matches++;var candidate=new Candidate(entry,rank(entry,query.text()));
                if (best.size()<limits.results()) best.add(candidate);else if (ORDER.compare(candidate,best.peek())<0) { best.remove();best.add(candidate); }
            }
            var hits=best.stream().sorted(ORDER).map(c -> new Hit(c.entry().path(),c.entry().family(),c.rank())).toList();
            result=new Result(current.paths().size(),index.size(),matches,hits,incomplete);previous=query;appliedRevision=current.revision();dirty=current.dirty();return result;
        }
    }
}
