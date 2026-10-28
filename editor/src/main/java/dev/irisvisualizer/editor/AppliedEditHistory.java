package dev.irisvisualizer.editor;

import java.util.ArrayDeque;
import java.util.Arrays;

/** Workspace-owned byte snapshots only. No disk writes, drafts or native model objects. */
final class AppliedEditHistory {
    static final int MAX_EDITS=128;
    static final long MAX_BYTES=32L*1024*1024;
    static final class Edit {
        final String path;
        private final byte[] before,after;
        Edit(String path,byte[] before,byte[] after) { this.path=path;this.before=before.clone();this.after=after.clone(); }
        long bytes() { return (long)before.length+after.length; }
        boolean matches(byte[] current,boolean forward) { return Arrays.equals(current,forward ? before : after); }
        byte[] restore(boolean forward) { return (forward ? after : before).clone(); }
    }
    private final int limit;
    private final long budget;
    private final ArrayDeque<Edit> undo=new ArrayDeque<>(),redo=new ArrayDeque<>();
    private long bytes;
    private String note="History starts with the first applied edit.";
    AppliedEditHistory() { this(MAX_EDITS,MAX_BYTES); }
    AppliedEditHistory(int limit,long budget) {
        if (limit<1 || limit>MAX_EDITS || budget<0 || budget>MAX_BYTES) throw new IllegalArgumentException("Invalid applied-edit history limits");
        this.limit=limit;this.budget=budget;
    }
    int undoCount() { return undo.size(); }
    int redoCount() { return redo.size(); }
    long bytes() { return bytes; }
    String note() { return note; }
    Edit next(boolean forward) { return (forward ? redo : undo).peekLast(); }
    void record(String path,byte[] before,byte[] after) {
        if (Arrays.equals(before,after)) return;
        long cost=(long)before.length+after.length;
        if (cost>budget) { clear("Latest edit exceeded the history byte budget; earlier history was cleared. The applied edit is retained.");return; }
        for (var entry : redo) bytes-=entry.bytes();redo.clear();boolean evicted=false;
        while (!undo.isEmpty() && (undo.size()>=limit || bytes+cost>budget)) { bytes-=undo.removeFirst().bytes();evicted=true; }
        undo.addLast(new Edit(path,before,after));bytes+=cost;
        note=evicted ? "Older applied edits were discarded at the history limit." : "History records applied byte changes; unapplied text stays separate.";
    }
    void moved(boolean forward) {
        var from=forward ? redo : undo;var to=forward ? undo : redo;
        if (from.isEmpty()) throw new IllegalStateException("No applied edit in that direction");
        to.addLast(from.removeLast());
    }
    void clear(String reason) { undo.clear();redo.clear();bytes=0;note=reason; }
}
