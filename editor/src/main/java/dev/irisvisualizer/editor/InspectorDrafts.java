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
    @FunctionalInterface public interface Change { void run() throws java.io.IOException; }
    /** Confirmation alone does not discard text; clear only after the transition succeeds. */
    public boolean transition(java.util.function.BooleanSupplier allowed, Change change) throws java.io.IOException {
        if (!allowed.getAsBoolean()) return false;
        change.run();
        clear();
        return true;
    }
    public void clear() { changed.clear(); }
}
