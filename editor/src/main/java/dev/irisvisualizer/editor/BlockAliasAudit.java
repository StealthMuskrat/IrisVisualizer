package dev.irisvisualizer.editor;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Bounded literal file-reference graph only. Does not load native aliases, resolve states or sample generation. */
public final class BlockAliasAudit {
    public static final int MAX_DEFINITIONS=512,MAX_EDGES=4096,MAX_NOTICES=256;
    public static final long MAX_TOTAL_BYTES=8_000_000;
    public record Edge(String source,String field,String key,String target,boolean implicit,boolean conditional) {}
    /** Sorted strongly connected component; members are not an ordered cycle witness. */
    public record Cycle(List<String> members,boolean backupLinks) { public Cycle { members=List.copyOf(members); } }
    public record Notice(String path,String field,String message) {}
    public record Audit(int definitions,List<String> inspected,int parsed,long bytes,List<Edge> edges,List<Cycle> cycles,List<Notice> notices,boolean incomplete) {
        public Audit { inspected=List.copyOf(inspected);edges=List.copyOf(edges);cycles=List.copyOf(cycles);notices=List.copyOf(notices); }
        public List<Edge> outgoing(String path) { return edges.stream().filter(e -> e.source().equals(path)).toList(); }
        public List<Edge> incoming(String path) { return edges.stream().filter(e -> e.target().equals(path)).toList(); }
        public List<Cycle> cycles(String path) { return cycles.stream().filter(c -> c.members().contains(path)).toList(); }
    }
    private static final class Scan {
        final PackWorkspace workspace;
        final List<Edge> edges=new ArrayList<>();
        final List<Notice> notices=new ArrayList<>();
        boolean incomplete,noticeOverflow;
        Scan(PackWorkspace workspace) { this.workspace=workspace; }
        void notice(String path,String field,String message,boolean partial) { incomplete|=partial;if (notices.size()<MAX_NOTICES) notices.add(new Notice(path,field,message));else noticeOverflow=true; }
        void node(String file,LosslessJson owner,int depth) {
            String prefix="backup.".repeat(depth);
            try {
                String block=BlockDefinitions.block(owner);String target=BlockDefinitions.exactAlias(workspace,block);
                if (target!=null) { if (edges.size()<MAX_EDGES) edges.add(new Edge(file,prefix+"block",block,target,owner.token("block")==null,depth>0));else notice(file,prefix+"block","Reference limit reached; remaining links are not recorded.",true); }
                else if (block.contains(":") && !block.toLowerCase(java.util.Locale.ROOT).startsWith("minecraft:")) notice(file,prefix+"block","Unsupported external block namespace; stored text is preserved.",false);
                else if (block.isBlank()) notice(file,prefix+"block","Blank block key; registry and alias validity are unchecked.",false);
            } catch (IllegalArgumentException bad) { notice(file,prefix+"block","Unsupported block type or NUL text; this reference cannot be inspected.",true); }
            String token=owner.token("backup");if (token==null || token.equals("null")) return;
            if (depth>=BlockDefinitions.MAX_DEPTH) { notice(file,prefix+"backup","Beyond the eight-level backup inspection limit; deeper references are unknown.",true);return; }
            try { node(file,new LosslessJson(token.getBytes(StandardCharsets.UTF_8)),depth+1); }
            catch (IllegalArgumentException bad) { notice(file,prefix+"backup","Backup is not an inline object; references are unknown.",true); }
        }
    }
    public static Audit inspect(PackWorkspace workspace) {
        Objects.requireNonNull(workspace);var definitions=workspace.paths().stream().filter(BlockDefinitions::supports).sorted().toList();var scan=new Scan(workspace);var inspected=new ArrayList<String>();int parsed=0;long bytes=0;
        int candidates=Math.min(definitions.size(),MAX_DEFINITIONS);
        if (definitions.size()>MAX_DEFINITIONS) scan.notice("blocks/","","Definition limit reached; only the first 512 sorted files are considered.",true);
        for (int i=0;i<candidates;i++) {
            String path=definitions.get(i);int size=workspace.size(path);
            if (size>ReferenceDiagnostics.MAX_JSON_BYTES) { scan.notice(path,"","Above the 1 MB JSON inspection limit; this definition is skipped.",true);continue; }
            if (bytes+size>MAX_TOTAL_BYTES) { scan.notice("blocks/","","The 8 MB total inspection budget is exhausted; remaining definitions are skipped.",true);break; }
            bytes+=size;inspected.add(path);
            try { var root=new LosslessJson(workspace.read(path));parsed++;scan.node(path,root,0); }
            catch (IllegalArgumentException bad) { scan.notice(path,"","Invalid UTF-8/object JSON or duplicate keys; this definition cannot be inspected.",true); }
        }
        if (scan.noticeOverflow) { scan.incomplete=true;scan.notices.set(MAX_NOTICES-1,new Notice("blocks/","","Additional notices omitted after the 256-notice limit.")); }
        return new Audit(definitions.size(),inspected,parsed,bytes,scan.edges,cycles(inspected,scan.edges),scan.notices,scan.incomplete);
    }
    private static List<Cycle> cycles(List<String> files,List<Edge> edges) {
        var links=edges.stream().map(e -> new LiteralReferenceGraph.Link(e.source(),e.target())).toList();
        return LiteralReferenceGraph.cycles(files,links).stream().map(component -> {
            var members=Set.copyOf(component);boolean backups=edges.stream().anyMatch(e -> e.conditional() && members.contains(e.source()) && members.contains(e.target()));return new Cycle(component,backups);
        }).toList();
    }
    public static final class Cache {
        private PackWorkspace workspace;
        private long revision=-1;
        private Audit audit;
        public Audit inspect(PackWorkspace current) { Objects.requireNonNull(current);if (workspace!=current || revision!=current.revision()) { audit=BlockAliasAudit.inspect(current);workspace=current;revision=current.revision(); }return audit; }
    }
}
