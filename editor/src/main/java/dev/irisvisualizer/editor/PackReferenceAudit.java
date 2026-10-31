package dev.irisvisualizer.editor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Bounded aggregation of existing typed link diagnostics; never authorizes rename, move or deletion. */
public final class PackReferenceAudit {
    public static final int MAX_FILES=512, MAX_FINDINGS=4096, MAX_NOTICES=256, MAX_RESULTS=ReviewPagination.PAGE_SIZE, MAX_QUERY_CHARACTERS=256;
    public static final long MAX_BYTES=8_000_000;
    public static final int MAX_DISPLAY_CHARACTERS=256;
    public record Finding(String source,String field,String key,String target,ReferenceDiagnostics.State state) {}
    public record Notice(String path,String message) {}
    public record Audit(int eligibleFiles,int inspectedFiles,long bytes,int skippedFiles,List<Finding> findings,List<Notice> notices,int omittedNotices,boolean incomplete) {
        public Audit { findings=List.copyOf(findings);notices=List.copyOf(notices); }
        public int count(ReferenceDiagnostics.State state) { return (int)findings.stream().filter(f -> f.state()==state).count(); }
    }
    public enum View {
        ALL("All recorded links"), PROBLEMS("Missing, required or invalid"), EXACT("Exact file links"), UNSUPPORTED("Unsupported inspection");
        private final String label;
        View(String label) { this.label=label; }
        public String label() { return label; }
        boolean matches(ReferenceDiagnostics.State state) { return switch (this) {
            case ALL -> true;case EXACT -> state==ReferenceDiagnostics.State.EXACT_FILE;case UNSUPPORTED -> state==ReferenceDiagnostics.State.UNSUPPORTED;
            case PROBLEMS -> state!=ReferenceDiagnostics.State.EXACT_FILE && state!=ReferenceDiagnostics.State.UNSUPPORTED;
        }; }
    }
    /** Incoming target is an exact workspace-relative path, not an Iris key or an alias. */
    public record Query(String text,View view,String incomingTarget) {
        public Query {
            if (text==null || text.length()>MAX_QUERY_CHARACTERS || text.indexOf(0)>=0 || view==null
                    || incomingTarget!=null && incomingTarget.indexOf(0)>=0)
                throw new IllegalArgumentException("Use up to 256 search characters without NUL and a valid view/target");
            text=text.strip().toLowerCase(Locale.ROOT);
        }
    }
    public record Result(int matches,List<Finding> findings) {
        public Result { findings=List.copyOf(findings); }
        public boolean truncated() { return matches>findings.size(); }
    }
    public record Page(ReviewPagination.Window window,Result result) {}
    record Limits(int files,long bytes,int findings,int notices) {
        Limits { if (files<1 || files>MAX_FILES || bytes<1 || bytes>MAX_BYTES || findings<1 || findings>MAX_FINDINGS || notices<1 || notices>MAX_NOTICES) throw new IllegalArgumentException("Invalid reference-review limits"); }
    }
    private static final class Scan {
        final Limits limits;
        final List<Finding> findings=new ArrayList<>();
        final List<Notice> notices=new ArrayList<>();
        int omitted;
        boolean incomplete;
        Scan(Limits limits) { this.limits=limits; }
        void notice(String path,String message) { incomplete=true;if (notices.size()<limits.notices()) notices.add(new Notice(path,message));else omitted++; }
    }
    public static Audit inspect(PackResourceView workspace) { return inspect(workspace,new Limits(MAX_FILES,MAX_BYTES,MAX_FINDINGS,MAX_NOTICES)); }
    static Audit inspect(PackResourceView workspace,java.util.function.BooleanSupplier cancelled) { return inspect(workspace,new Limits(MAX_FILES,MAX_BYTES,MAX_FINDINGS,MAX_NOTICES),cancelled); }
    static Audit inspect(PackResourceView workspace,Limits limits) { return inspect(workspace,limits,() -> false); }
    private static Audit inspect(PackResourceView workspace,Limits limits,java.util.function.BooleanSupplier cancelled) {
        Objects.requireNonNull(workspace);Objects.requireNonNull(limits);var scan=new Scan(limits);
        int eligible=0,considered=0,inspected=0,skipped=0;long bytes=0;
        for (String path : workspace.paths()) {
            if (cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException();
            if (!ReferenceDiagnostics.supports(path)) continue;
            eligible++;
            if (considered++>=limits.files()) { skipped++;scan.notice(path,"File budget reached; this resource was not inspected.");continue; }
            if (scan.findings.size()>=limits.findings()) { skipped++;scan.notice(path,"Finding budget reached; this resource was not inspected.");continue; }
            int size=workspace.size(path);
            if (size>ReferenceDiagnostics.MAX_JSON_BYTES) { skipped++;scan.notice(path,"Resource exceeds the 1 MB per-file inspection limit.");continue; }
            if (size>limits.bytes()-bytes) { skipped++;scan.notice(path,"Byte budget reached; this resource was not inspected.");continue; }
            bytes+=size;inspected++;
            var links=ReferenceDiagnostics.inspect(workspace,path);
            if (cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException();
            int remaining=limits.findings()-scan.findings.size();
            for (int i=0;i<Math.min(remaining,links.size());i++) {
                var link=links.get(i);scan.findings.add(new Finding(path,link.field(),link.key(),link.target(),link.state()));
                if (link.state()==ReferenceDiagnostics.State.UNSUPPORTED) scan.incomplete=true;
            }
            if (links.size()>remaining) scan.notice(path,"Finding budget reached within this resource; remaining link diagnostics were omitted.");
        }
        if (cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException();
        return new Audit(eligible,inspected,bytes,skipped,scan.findings,scan.notices,scan.omitted,scan.incomplete);
    }
    private static boolean contains(String value,String query) { return value!=null && value.toLowerCase(Locale.ROOT).contains(query); }
    /** Bounded GUI labels; control/unpaired surrogate characters are shown as literal escapes. */
    public static String display(String value) {
        return InspectorText.label(value,MAX_DISPLAY_CHARACTERS);
    }
    private static boolean matches(Finding finding,Query query) {
        if (!query.view().matches(finding.state()) || query.incomingTarget()!=null && !query.incomingTarget().equals(finding.target())) return false;
        String text=query.text();return text.isEmpty() || contains(finding.source(),text) || contains(finding.field(),text) || contains(finding.key(),text) || contains(finding.target(),text);
    }
    /** Compatibility entry point: first bounded page of the recorded audit. */
    public static Result filter(Audit audit,Query query) { return page(audit,query,0).result(); }
    public static Page page(Audit audit,Query query,int requested) {
        Objects.requireNonNull(audit);Objects.requireNonNull(query);
        var selected=ReviewPagination.select(audit.findings(),finding->matches(finding,query),requested);
        return new Page(selected.window(),new Result(selected.window().matches(),selected.entries()));
    }
    /** Scan only on explicit refresh. Applied edits invalidate the result without automatic parsing/disk work. */
    public static final class Cache {
        private PackWorkspace workspace;
        private long revision=-1;
        private Audit audit;
        private Query previous;
        private Page page;
        public void clear() { workspace=null;revision=-1;audit=null;previous=null;page=null; }
        public Audit current(PackWorkspace current) {
            Objects.requireNonNull(current);
            if (workspace!=current || revision!=current.revision()) { clear();return null; }
            return audit;
        }
        public Audit refresh(PackWorkspace current) { var next=inspect(current);workspace=current;revision=current.revision();audit=next;previous=null;page=null;return next; }
        public Result query(PackWorkspace current,Query query) { var selected=page(current,query,0);return selected==null ? null : selected.result(); }
        public Page page(PackWorkspace current,Query query,int requested) {
            Objects.requireNonNull(query);var active=current(current);if (active==null) return null;
            if (page==null || !query.equals(previous) || page.window().page()!=ReviewPagination.Window.at(page.result().matches(),requested).page()) { page=PackReferenceAudit.page(active,query,requested);previous=query; }
            return page;
        }
    }
}
