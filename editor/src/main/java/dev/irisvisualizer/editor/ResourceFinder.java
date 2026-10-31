package dev.irisvisualizer.editor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

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
    /** One ranked page. Page size follows the existing result limit, including small contract-test limits. */
    public record Window(int page,int matches,int pageSize) {
        public Window { if(matches<0 || pageSize<1 || pageSize>MAX_RESULTS || page<0 || page>Math.max(0,(matches-1)/pageSize))throw new IllegalArgumentException("Invalid resource-finder page"); }
        public static Window at(int matches,int requested,int size) { if(matches<0 || size<1 || size>MAX_RESULTS)throw new IllegalArgumentException("Invalid resource-finder page");return new Window(Math.clamp(requested,0,Math.max(0,(matches-1)/size)),matches,size); }
        public int pages() {return Math.max(1,(matches-1)/pageSize+1);}
        public int start() {return page*pageSize;}
        public int end() {return (int)Math.min((long)matches,(long)start()+pageSize);}
        public int firstRow() {return matches==0 ? 0 : start()+1;}
        public boolean previous() {return page>0;}
        public boolean next() {return page+1<pages();}
    }
    public record Page(long request,Window window,Result result) {
        public Page {Objects.requireNonNull(window);Objects.requireNonNull(result);if(result.matches()!=window.matches() || result.hits().size()!=window.end()-window.start())throw new IllegalArgumentException("Incomplete resource-finder page");}
    }
    public record Cursor(int page,int row) {}
    /** Up/Down traverses the complete ranked set; stale/absent selection starts at the current page's first hit. */
    public static Cursor move(Page page,String selected,int direction) {
        Objects.requireNonNull(page);if(direction < -1 || direction>1)throw new IllegalArgumentException("Use a single selection step");
        var window=page.window();if(window.matches()==0)return new Cursor(0,-1);int row=-1;
        for(int i=0;i<page.result().hits().size();i++)if(page.result().hits().get(i).path().equals(selected)){row=i;break;}
        int target=row<0 ? window.start() : (int)Math.clamp((long)window.start()+row+direction,0,(long)window.matches()-1);
        return new Cursor(target/window.pageSize(),target%window.pageSize());
    }
    record Limits(int files,long characters,int results) {
        Limits { if (files<1 || files>PackWorkspace.MAX_FILES || characters<1 || characters>MAX_INDEX_CHARACTERS || results<1 || results>MAX_RESULTS) throw new IllegalArgumentException("Invalid resource-finder limits"); }
    }
    private record Entry(String path,String lower,Family family,int name,int extension) {}
    private static final Comparator<Entry> PATH_ORDER=Comparator.comparing(Entry::lower).thenComparing(Entry::path);
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
        private long resourceRevision=-1,appliedRevision=-1,savedRevision=-1,generation;
        private boolean incomplete;
        private List<Entry> index=List.of();
        private Query previous;
        private String[] tokens=new String[0];
        private int[] counts=new int[7];
        private int matches;
        private Page page;
        public Cache() {this(new Limits(PackWorkspace.MAX_FILES,MAX_INDEX_CHARACTERS,MAX_RESULTS));}
        Cache(Limits limits) {this.limits=Objects.requireNonNull(limits);}
        public void clear() {workspace=null;resourceRevision=appliedRevision=savedRevision=-1;previous=null;page=null;index=List.of();tokens=new String[0];counts=new int[7];matches=0;incomplete=false;generation++;}
        /** Compatibility first-page API with the same ranking, result cap and immutable cached result. */
        public Result query(PackWorkspace current,Query query) {return page(current,query,0).result();}
        public Page page(PackWorkspace current,Query query,int requested) {
            Objects.requireNonNull(current);Objects.requireNonNull(query);
            if(workspace!=current || resourceRevision!=current.resourceRevision()) {
                var entries=new ArrayList<Entry>();long characters=0;incomplete=false;
                for(String path:current.paths()) {
                    String lower=path.toLowerCase(Locale.ROOT);
                    if(entries.size()>=limits.files() || characters+lower.length()>limits.characters()) {incomplete=true;break;}
                    int name=lower.lastIndexOf('/')+1,extension=lower.lastIndexOf('.');if(extension<=name)extension=lower.length();
                    entries.add(new Entry(path,lower,Family.classify(path),name,extension));characters+=lower.length();
                }
                entries.sort(PATH_ORDER);index=List.copyOf(entries);workspace=current;resourceRevision=current.resourceRevision();previous=null;page=null;
            }
            if(!query.equals(previous) || query.changedOnly() && (appliedRevision!=current.revision() || savedRevision!=current.saveRevision())) {
                tokens=query.text().isEmpty() ? new String[0] : query.text().split("\\s+");counts=new int[7];matches=0;
                for(var entry:index)if(matches(current,query,entry)) {counts[rank(entry,query.text())]++;matches++;}
                previous=query;appliedRevision=current.revision();savedRevision=current.saveRevision();generation++;page=null;
            }
            var window=Window.at(matches,requested,limits.results());if(page!=null && page.window().equals(window))return page;
            int[] base=new int[7],seen=new int[7];for(int i=1;i<7;i++)base[i]=base[i-1]+counts[i-1];
            var hits=new Hit[window.end()-window.start()];int retained=0;
            if(hits.length>0)for(var entry:index)if(matches(current,query,entry)) {
                int rank=rank(entry,query.text()),row=base[rank]+seen[rank]++;
                if(row>=window.start() && row<window.end()) {hits[row-window.start()]=new Hit(entry.path(),entry.family(),rank);if(++retained==hits.length)break;}
            }
            page=new Page(generation,window,new Result(current.paths().size(),index.size(),matches,List.of(hits),incomplete));return page;
        }
        private boolean matches(PackWorkspace current,Query query,Entry entry) {
            if(query.family()!=Family.ALL && query.family()!=entry.family() || query.changedOnly() && !current.changed(entry.path()))return false;
            for(String token:tokens)if(!entry.lower().contains(token))return false;return true;
        }
    }
}
