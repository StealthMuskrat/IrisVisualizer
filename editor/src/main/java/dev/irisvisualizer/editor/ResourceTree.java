package dev.irisvisualizer.editor;

import java.util.*;

/** Immutable folder-first resource index; filtering keeps matching ancestors visible. */
public final class ResourceTree {
    public record Node(String name, String path, boolean folder, List<Node> children) {}
    private static final class Entry {
        final String name, path;
        final Map<String, Entry> children = new TreeMap<>();
        boolean folder;
        Entry(String name, String path, boolean folder) { this.name=name; this.path=path; this.folder=folder; }
        Node freeze() {
            return new Node(name, path, folder, children.values().stream()
                    .sorted(Comparator.comparing((Entry e) -> !e.folder).thenComparing(e -> e.name))
                    .map(Entry::freeze).toList());
        }
    }
    public static List<Node> build(Collection<String> files, Collection<String> directories, String query) {
        Entry root = new Entry("", "", true);
        String filter = query.strip().toLowerCase(Locale.ROOT);
        for (String path : directories) if (path.toLowerCase(Locale.ROOT).contains(filter)) add(root, path, true);
        for (String path : files) if (path.toLowerCase(Locale.ROOT).contains(filter)) add(root, path, false);
        return root.freeze().children();
    }
    private static void add(Entry root, String path, boolean directory) {
        String[] parts = path.split("/"); Entry parent = root; String current = "";
        for (int i=0;i<parts.length;i++) {
            current += (current.isEmpty() ? "" : "/") + parts[i];
            boolean folder = i < parts.length-1 || directory;
            Entry next = parent.children.get(parts[i]);
            if (next == null) { next = new Entry(parts[i], current, folder); parent.children.put(parts[i], next); }
            next.folder |= folder; parent = next;
        }
    }
}
