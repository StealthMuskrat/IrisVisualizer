package dev.irisvisualizer.editor;

import java.util.Objects;

/** One-frame UI navigation request. Apply after rendering so old inspectors cannot refill cleared inputs. */
final class ResourceNavigation {
    private PackWorkspace workspace;
    private String target;
    void request(PackWorkspace current,String path) { Objects.requireNonNull(current);if (path==null || !current.paths().contains(path)) throw new IllegalArgumentException("Navigation target is not in this workspace");workspace=current;target=path; }
    String take(PackWorkspace current) { Objects.requireNonNull(current);String next=workspace==current && target!=null && current.paths().contains(target) ? target : null;workspace=null;target=null;return next; }
}
