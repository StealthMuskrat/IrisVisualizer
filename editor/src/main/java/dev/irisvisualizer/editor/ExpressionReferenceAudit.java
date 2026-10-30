package dev.irisvisualizer.editor;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Bounded literal references in expression source styles. Never parses a formula or loads a native source. */
public final class ExpressionReferenceAudit {
    public static final int MAX_DEFINITIONS=512,MAX_EDGES=4096,MAX_NOTICES=256;
    public static final long MAX_TOTAL_BYTES=8_000_000;
    public record Edge(String source,String field,String key,String target,boolean present,boolean higherPriorityConfigured) {}
    public record Notice(String path,String field,String message) {}
    public record Audit(int definitions,List<String> inspected,int parsed,long bytes,List<Edge> edges,List<List<String>> cycles,List<Notice> notices,boolean incomplete) {
        public Audit { inspected=List.copyOf(inspected);edges=List.copyOf(edges);cycles=cycles.stream().map(List::copyOf).toList();notices=List.copyOf(notices); }
        public List<Edge> outgoing(String path) { return edges.stream().filter(e -> e.source().equals(path)).toList(); }
        public List<Edge> incoming(String path) { return edges.stream().filter(e -> e.target().equals(path)).toList(); }
        public List<List<String>> cycles(String path) { return cycles.stream().filter(c -> c.contains(path)).toList(); }
    }
    private static boolean configured(LosslessJson row,String field) { String token=row.token(field);return token!=null && !token.equals("null"); }
    private static final class Scan {
        final Set<String> paths;
        final List<Edge> edges=new ArrayList<>();
        final List<Notice> notices=new ArrayList<>();
        boolean incomplete,noticeOverflow;
        final BooleanSupplier cancelled;
        Scan(PackResourceView workspace,BooleanSupplier cancelled) { paths=Set.copyOf(workspace.paths());this.cancelled=cancelled; }
        void notice(String path,String field,String message,boolean partial) { incomplete|=partial;if (notices.size()<MAX_NOTICES) notices.add(new Notice(path,field,message));else noticeOverflow=true; }
        void definition(String file,LosslessJson root) {
            checkCancelled(cancelled);var names=new LinkedHashMap<String,String>();
            for (String list : ExpressionDefinitions.LISTS) {
                List<LosslessJson> rows;
                try { rows=ExpressionDefinitions.entries(root,list);if (rows==null) continue;if (rows.size()>ExpressionDefinitions.MAX_ENTRIES) throw new IllegalArgumentException(); }
                catch (IllegalArgumentException bad) { notice(file,list,"Source list is null, mixed, non-inline or above the 64-entry inspection limit; declarations and links are unknown.",true);continue; }
                for (int i=0;i<rows.size();i++) {
                    checkCancelled(cancelled);
                    var context=new ExpressionDefinitions.Context(list,i);var row=rows.get(i);String prefix=list+"["+i+"].";
                    try {
                        String name=ExpressionDefinitions.name(row,context);
                        if (name==null || name.isBlank()) notice(file,prefix+"name","Missing or blank source name; formula registration is unchecked.",false);
                        else {
                            String first=names.putIfAbsent(name,prefix+"name");if (first!=null) notice(file,prefix+"name","Duplicate literal source name; first declared at "+first+". Parser behavior is unchecked.",false);
                            if (List.of("x","y","z").contains(name)) notice(file,prefix+"name","Source name matches an inherited coordinate name; parser behavior is unchecked.",false);
                            else if (!name.matches("[A-Za-z_][A-Za-z0-9_]*")) notice(file,prefix+"name","Stored name is outside the editor's ASCII identifier subset; native validity is unchecked.",false);
                        }
                    } catch (IllegalArgumentException bad) { notice(file,prefix+"name","Name has unsupported type or NUL; this declaration is unknown.",true); }
                    for (String field : context.variable() ? List.of("engineValue","engineStreamValue") : List.of("engineStreamValue")) {
                        try { String value=ExpressionDefinitions.source(row,context,field);if (value!=null && !ExpressionDefinitions.choices(context,field).contains(value)) notice(file,prefix+field,"Unknown engine source choice; runtime behavior is unchecked.",true); }
                        catch (IllegalArgumentException bad) { notice(file,prefix+field,"Engine source has unsupported type or NUL; runtime behavior is unknown.",true); }
                    }
                    boolean engine=configured(row,"engineStreamValue") || context.variable() && configured(row,"engineValue");
                    if (!context.variable() && !engine && !configured(row,"styleValue")) notice(file,prefix+"styleValue","Function has no provider and is ignored by the pinned native registration path.",false);
                    if (engine && configured(row,"styleValue")) notice(file,prefix+"styleValue","A higher-priority engine source is configured; style data is retained. No runtime applicability is evaluated.",false);
                    String token=row.token("styleValue");if (token==null || token.equals("null")) continue;
                    try { style(file,new LosslessJson(token.getBytes(StandardCharsets.UTF_8)),prefix+"styleValue.",0,engine); }
                    catch (IllegalArgumentException bad) { notice(file,prefix+"styleValue","Style is not an inline object; references are unknown.",true); }
                }
            }
        }
        void style(String file,LosslessJson style,String prefix,int depth,boolean higherPriority) {
            checkCancelled(cancelled);for (String field : NoiseStyleSources.REFERENCES) {
                checkCancelled(cancelled);
                String token=style.token(field);if (token==null || token.equals("null")) continue;
                try {
                    String key=style.stringToken(field);
                    if (key.indexOf(0)>=0) throw new IllegalArgumentException();
                    if (!ResourceTypes.referenceKey(key)) { notice(file,prefix+field,"Stored text is outside the exact relative file-key subset; native loader aliases are unchecked.",false);continue; }
                    String target=NoiseStyleSources.family(field)+"/"+key+".json";boolean present=paths.contains(target);
                    if (edges.size()<MAX_EDGES) edges.add(new Edge(file,prefix+field,key,target,present,higherPriority || field.equals("imageMap") && configured(style,"expression")));
                    else notice(file,prefix+field,"Reference limit reached; remaining links are not recorded.",true);
                    if (!present) notice(file,prefix+field,"Exact file is absent: "+target+". Native loader aliases/fallbacks are unchecked.",false);
                } catch (IllegalArgumentException bad) { notice(file,prefix+field,"Reference has unsupported type or NUL; this link is unknown.",true); }
            }
            String token=style.token("fracture");if (token==null || token.equals("null")) return;
            if (depth>=StyleFractures.MAXIMUM_DEPTH) { notice(file,prefix+"fracture","Beyond the eight-level style-fracture inspection limit; deeper references are unknown.",true);return; }
            try { style(file,new LosslessJson(token.getBytes(StandardCharsets.UTF_8)),prefix+"fracture.",depth+1,higherPriority); }
            catch (IllegalArgumentException bad) { notice(file,prefix+"fracture","Fracture is not an inline object; references are unknown.",true); }
        }
    }
    public static Audit inspect(PackResourceView workspace) { return inspect(workspace,() -> false); }
    private static void checkCancelled(BooleanSupplier cancelled) { if (cancelled.getAsBoolean()) throw new CancellationException("Definition audit cancelled"); }
    public static Audit inspect(PackResourceView workspace,BooleanSupplier cancelled) {
        Objects.requireNonNull(workspace);Objects.requireNonNull(cancelled);checkCancelled(cancelled);var definitions=workspace.paths().stream().filter(ExpressionDefinitions::supports).sorted().toList();var scan=new Scan(workspace,cancelled);var inspected=new ArrayList<String>();int parsed=0;long bytes=0;
        if (definitions.size()>MAX_DEFINITIONS) scan.notice("expressions/","","Definition limit reached; only the first 512 sorted files are considered.",true);
        for (int i=0;i<Math.min(definitions.size(),MAX_DEFINITIONS);i++) {
            checkCancelled(cancelled);String path=definitions.get(i);int size=workspace.size(path);
            if (size>ReferenceDiagnostics.MAX_JSON_BYTES) { scan.notice(path,"","Above the 1 MB JSON inspection limit; this definition is skipped.",true);continue; }
            if (bytes+size>MAX_TOTAL_BYTES) { scan.notice("expressions/","","The 8 MB total inspection budget is exhausted; remaining definitions are skipped.",true);break; }
            bytes+=size;inspected.add(path);
            try { var root=new LosslessJson(workspace.read(path));checkCancelled(cancelled);parsed++;scan.definition(path,root); }
            catch (IllegalArgumentException bad) { scan.notice(path,"","Invalid UTF-8/object JSON or duplicate keys; this definition cannot be inspected.",true); }
        }
        checkCancelled(cancelled);if (scan.noticeOverflow) { scan.incomplete=true;scan.notices.set(MAX_NOTICES-1,new Notice("expressions/","","Additional notices omitted after the 256-notice limit.")); }
        var links=scan.edges.stream().filter(Edge::present).map(e -> new LiteralReferenceGraph.Link(e.source(),e.target())).toList();
        var cycles=LiteralReferenceGraph.cycles(inspected,links);checkCancelled(cancelled);
        return new Audit(definitions.size(),inspected,parsed,bytes,scan.edges,cycles,scan.notices,scan.incomplete);
    }
    public static final class Cache {
        private PackWorkspace workspace;
        private long revision=-1;
        private Audit audit;
        public Audit inspect(PackWorkspace current) { Objects.requireNonNull(current);if (workspace!=current || revision!=current.revision()) { audit=ExpressionReferenceAudit.inspect(current);workspace=current;revision=current.revision(); }return audit; }
    }
}
