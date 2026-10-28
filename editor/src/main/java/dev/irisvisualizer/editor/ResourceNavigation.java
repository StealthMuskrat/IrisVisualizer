package dev.irisvisualizer.editor;

import java.util.Objects;

/** One-frame UI navigation request. Apply after rendering so old inspectors cannot refill cleared inputs. */
final class ResourceNavigation {
    private PackWorkspace workspace;
    private String target;
    private NavigationHistory.Plan plan;
    record Request(String target,NavigationHistory.Plan plan) {}
    void request(PackWorkspace current,String path) { Objects.requireNonNull(current);if (path==null || !current.paths().contains(path)) throw new IllegalArgumentException("Navigation target is not in this workspace");workspace=current;target=path;plan=null; }
    void request(PackWorkspace current,NavigationHistory.Plan next) { Objects.requireNonNull(next);next.owner().validate(next,current);workspace=current;target=next.location().path();plan=next; }
    Request takeRequest(PackWorkspace current) { Objects.requireNonNull(current);var next=workspace==current && target!=null && current.paths().contains(target) ? new Request(target,plan) : null;clear();return next; }
    String take(PackWorkspace current) { var next=takeRequest(current);return next==null ? null : next.target(); }
    void clear() { workspace=null;target=null;plan=null; }
}
