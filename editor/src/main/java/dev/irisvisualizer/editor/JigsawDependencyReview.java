package dev.irisvisualizer.editor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Bounded breadth-first review of authored jigsaw file dependencies, never native assembly or export closure. */
public final class JigsawDependencyReview {
    public static final int MAX_NODES=512,MAX_JSON_FILES=256,MAX_EDGES=4096,MAX_NOTICES=128,MAX_RESULTS=200,MAX_QUERY_CHARACTERS=256;
    public static final long MAX_BYTES=8_000_000;
    public enum Status { INSPECTED, UNAVAILABLE, OBJECT_PRESENT, SKIPPED }
    public enum Scope {
        ALL("All recorded links"), OUTGOING("From current selection"), INCOMING("To current selection");
        private final String label;Scope(String label) { this.label=label; }public String label() { return label; }
    }
    public record Node(String path,int distance,String parent,String via,Status status) {}
    public record Edge(String source,String field,String key,String target,ReferenceDiagnostics.State state) {}
    public record Notice(String path,String message) {}
    /** Members are sorted strongly connected components, not ordered cycle witnesses or native errors. */
    public record Review(String root,List<Node> nodes,int jsonFiles,long bytes,List<Edge> edges,List<List<String>> cycles,List<List<String>> fallbackCycles,List<Notice> notices,int omittedNotices,boolean incomplete) {
        public Review { nodes=List.copyOf(nodes);edges=List.copyOf(edges);cycles=cycles.stream().map(List::copyOf).toList();fallbackCycles=fallbackCycles.stream().map(List::copyOf).toList();notices=List.copyOf(notices); }
        public int count(ReferenceDiagnostics.State state) { return (int)edges.stream().filter(e -> e.state()==state).count(); }
        /** One shortest recorded path, with first recorded encounter breaking ties. Missing/omitted nodes have no path. */
        public List<Node> route(String path) {
            var byPath=new LinkedHashMap<String,Node>();for (var node : nodes) byPath.put(node.path(),node);
            var reversed=new ArrayList<Node>();var node=byPath.get(path);
            while (node!=null) { reversed.add(node);node=node.parent()==null ? null : byPath.get(node.parent()); }
            java.util.Collections.reverse(reversed);return List.copyOf(reversed);
        }
    }
    public record Query(String text,Scope scope,String selection,boolean problemsOnly) {
        public Query {
            if (text==null || text.length()>MAX_QUERY_CHARACTERS || text.indexOf(0)>=0 || scope==null || selection!=null && selection.indexOf(0)>=0) throw new IllegalArgumentException("Use up to 256 search characters without NUL and a valid scope");
            text=text.strip().toLowerCase(Locale.ROOT);
        }
    }
    public record Result(int matches,List<Edge> edges) { public Result { edges=List.copyOf(edges); }public boolean truncated() { return matches>edges.size(); } }
    record Limits(int nodes,int files,long bytes,int edges,int notices) {
        Limits { if (nodes<1 || nodes>MAX_NODES || files<1 || files>MAX_JSON_FILES || bytes<1 || bytes>MAX_BYTES || edges<1 || edges>MAX_EDGES || notices<1 || notices>MAX_NOTICES) throw new IllegalArgumentException("Invalid jigsaw dependency review limits"); }
    }
    public static boolean supports(String path) { return Set.of("structures","jigsaw-pools","jigsaw-pieces").contains(Objects.toString(ResourceTypes.base(path),"")); }
    private record Pending(String path,int distance,String parent,String via) {}
    private static final class Scan {
        final Limits limits;final List<Notice> notices=new ArrayList<>();int omitted;boolean incomplete;
        Scan(Limits limits) { this.limits=limits; }
        void notice(String path,String message) { incomplete=true;if (notices.size()<limits.notices()) notices.add(new Notice(path,message));else omitted++; }
    }
    private static boolean included(String base,String field) {
        if (field.equals("JSON") || field.equals("References")) return true;
        return switch (base) {
            case "structures" -> field.equals("startPool");
            case "jigsaw-pools" -> field.equals("fallback") || field.equals("pieces") || field.matches("pieces\\[[0-9]+\\]\\.piece");
            case "jigsaw-pieces" -> field.equals("object") || field.equals("connectors") || field.matches("connectors\\[[0-9]+\\]\\.pool");
            default -> false;
        };
    }
    public static Review inspect(PackResourceView workspace,String root) { return inspect(workspace,root,() -> false); }
    public static Review inspect(PackResourceView workspace,String root,BooleanSupplier cancelled) { return inspect(workspace,root,new Limits(MAX_NODES,MAX_JSON_FILES,MAX_BYTES,MAX_EDGES,MAX_NOTICES),cancelled); }
    static Review inspect(PackResourceView workspace,String root,Limits limits) { return inspect(workspace,root,limits,() -> false); }
    private static void checkCancelled(BooleanSupplier cancelled) { if (cancelled.getAsBoolean()) throw new CancellationException("Jigsaw dependency review cancelled"); }
    static Review inspect(PackResourceView workspace,String root,Limits limits,BooleanSupplier cancelled) {
        Objects.requireNonNull(workspace);Objects.requireNonNull(limits);Objects.requireNonNull(cancelled);checkCancelled(cancelled);
        if (!supports(root) || !workspace.paths().contains(root)) throw new IllegalArgumentException("Select an existing structure, jigsaw pool or jigsaw piece JSON resource");
        var scan=new Scan(limits);var nodes=new ArrayList<Node>();var edges=new ArrayList<Edge>();var queued=new java.util.LinkedHashSet<String>();var queue=new ArrayDeque<Pending>();
        queue.add(new Pending(root,0,null,null));queued.add(root);int files=0;long bytes=0;
        while (!queue.isEmpty()) {
            checkCancelled(cancelled);var next=queue.remove();String path=next.path();Status status;
            if ("objects".equals(ResourceTypes.fileFamily(path))) status=Status.OBJECT_PRESENT; // Metadata only: never read/decode the payload.
            else if (files>=limits.files()) { status=Status.SKIPPED;scan.notice(path,"JSON file budget reached; outgoing dependencies are unknown."); }
            else if (workspace.size(path)>ReferenceDiagnostics.MAX_JSON_BYTES) { status=Status.SKIPPED;scan.notice(path,"Above the 1 MB JSON limit; outgoing dependencies are unknown."); }
            else if (workspace.size(path)>limits.bytes()-bytes) { status=Status.SKIPPED;scan.notice(path,"Total JSON byte budget reached; outgoing dependencies are unknown."); }
            else if (workspace instanceof JigsawScanSource source && !source.captured(path)) { status=Status.SKIPPED;scan.notice(path,"JSON was not copied within the snapshot limits (4,096 files / 16 MB; root first, then lexical order); outgoing dependencies are unknown."); }
            else {
                files++;bytes+=workspace.size(path);status=Status.INSPECTED;
                String base=ResourceTypes.base(path);
                var links=ReferenceDiagnostics.inspect(workspace,path);checkCancelled(cancelled);
                for (var link : links) {
                    checkCancelled(cancelled);if (!included(base,link.field())) continue;
                    if (edges.size()>=limits.edges()) { scan.notice(path,"Edge budget reached; remaining dependencies from this resource are unknown.");break; }
                    edges.add(new Edge(path,link.field(),link.key(),link.target(),link.state()));
                    if (link.state()==ReferenceDiagnostics.State.UNSUPPORTED) { scan.incomplete=true;if (link.field().equals("JSON")) status=Status.UNAVAILABLE; }
                    if (link.state()!=ReferenceDiagnostics.State.EXACT_FILE || queued.contains(link.target())) continue;
                    if (queued.size()>=limits.nodes()) { scan.notice(link.target(),"Resource-node budget reached; this exact target was not visited.");continue; }
                    queued.add(link.target());queue.add(new Pending(link.target(),next.distance()+1,path,link.field()));
                }
            }
            nodes.add(new Node(path,next.distance(),next.parent(),next.via(),status));
        }
        checkCancelled(cancelled);var filesInGraph=nodes.stream().map(Node::path).toList();
        var graph=edges.stream().filter(e -> e.state()==ReferenceDiagnostics.State.EXACT_FILE).map(e -> new LiteralReferenceGraph.Link(e.source(),e.target())).toList();
        var fallbacks=edges.stream().filter(e -> e.field().equals("fallback") && e.state()==ReferenceDiagnostics.State.EXACT_FILE).map(e -> new LiteralReferenceGraph.Link(e.source(),e.target())).toList();
        var cycles=LiteralReferenceGraph.cycles(filesInGraph,graph);checkCancelled(cancelled);
        var fallbackCycles=LiteralReferenceGraph.cycles(filesInGraph,fallbacks);checkCancelled(cancelled);
        return new Review(root,nodes,files,bytes,edges,cycles,fallbackCycles,scan.notices,scan.omitted,scan.incomplete);
    }
    private static boolean contains(String value,String query) { return value!=null && value.toLowerCase(Locale.ROOT).contains(query); }
    public static Result filter(Review review,Query query) {
        Objects.requireNonNull(review);Objects.requireNonNull(query);var edges=new ArrayList<Edge>();int matches=0;
        for (var edge : review.edges()) {
            if (query.scope()==Scope.OUTGOING && !Objects.equals(query.selection(),edge.source()) || query.scope()==Scope.INCOMING && (query.selection()==null || !query.selection().equals(edge.target())) || query.problemsOnly() && edge.state()==ReferenceDiagnostics.State.EXACT_FILE) continue;
            String text=query.text();if (!text.isEmpty() && !contains(edge.source(),text) && !contains(edge.field(),text) && !contains(edge.key(),text) && !contains(edge.target(),text)) continue;
            matches++;if (edges.size()<MAX_RESULTS) edges.add(edge);
        }
        return new Result(matches,edges);
    }
    /** Explicit refresh only; selection changes do not replace the pinned root or trigger scans. */
    public static final class Cache {
        private PackWorkspace workspace;private long revision=-1;private String root;private Review review;private Query previous;private Result result;
        public void clear() { workspace=null;revision=-1;root=null;review=null;previous=null;result=null; }
        public Review current(PackWorkspace current,String selectedRoot) { Objects.requireNonNull(current);if (workspace!=current || revision!=current.revision() || !Objects.equals(root,selectedRoot)) { clear();return null; }return review; }
        public Review refresh(PackWorkspace current,String selectedRoot) { var next=inspect(current,selectedRoot);workspace=current;revision=current.revision();root=selectedRoot;review=next;previous=null;result=null;return next; }
        public Result query(PackWorkspace current,String selectedRoot,Query query) { Objects.requireNonNull(query);var active=current(current,selectedRoot);if (active==null) return null;if (result==null || !query.equals(previous)) { result=filter(active,query);previous=query; }return result; }
    }
}
