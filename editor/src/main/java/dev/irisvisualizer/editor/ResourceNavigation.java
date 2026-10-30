package dev.irisvisualizer.editor;

import java.util.Objects;

/** One-frame UI navigation request. Apply after rendering so old inspectors cannot refill cleared inputs. */
final class ResourceNavigation {
    private PackWorkspace workspace;
    private String target;
    private NavigationHistory.Plan plan;
    private SourceMatch.Request match;
    record Request(String target,NavigationHistory.Plan plan,SourceMatch.Request match) {
        Request(String target,NavigationHistory.Plan plan) { this(target,plan,null); }
    }
    void request(PackWorkspace current,String path) { Objects.requireNonNull(current);if (path==null || !current.paths().contains(path)) throw new IllegalArgumentException("Navigation target is not in this workspace");workspace=current;target=path;plan=null;match=null; }
    void request(PackWorkspace current,NavigationHistory.Plan next) { Objects.requireNonNull(next);next.owner().validate(next,current);workspace=current;target=next.location().path();plan=next;match=null; }
    void request(PackWorkspace current,SourceMatch.Request next) { SourceMatch.validate(current,next);workspace=current;target=next.path();plan=null;match=next; }
    Request takeRequest(PackWorkspace current) { Objects.requireNonNull(current);var next=workspace==current && target!=null && current.paths().contains(target) ? new Request(target,plan,match) : null;clear();return next; }
    String take(PackWorkspace current) { var next=takeRequest(current);return next==null ? null : next.target(); }
    void clear() { workspace=null;target=null;plan=null;match=null; }
}
