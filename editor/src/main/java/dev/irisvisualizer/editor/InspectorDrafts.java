package dev.irisvisualizer.editor;

import java.util.HashSet;
import java.util.Set;

/** Transient text drafts only; no persistence, recovery, history or workspace mutation. */
public final class InspectorDrafts {
    private final Set<String> changed = new HashSet<>();
    public void update(String key, String stored, String input) {
        if (stored.equals(input)) changed.remove(key); else changed.add(key);
    }
    public int count() { return changed.size(); }
    public boolean any() { return !changed.isEmpty(); }
    public boolean contains(String key) { return changed.contains(key); }
    public boolean anyUnder(String prefix) { return changed.stream().anyMatch(key -> key.startsWith(prefix)); }
    public void applied(String key) { changed.remove(key); }
    public void clearUnder(String prefix) { changed.removeIf(key -> key.startsWith(prefix)); }
    public void clear() { changed.clear(); }
}
