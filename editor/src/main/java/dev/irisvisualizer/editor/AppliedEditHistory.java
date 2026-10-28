package dev.irisvisualizer.editor;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.io.IOException;

/** Workspace-owned byte snapshots only. No disk writes, drafts or native model objects. */
final class AppliedEditHistory {
    static final int MAX_EDITS=128;
    static final long MAX_BYTES=32L*1024*1024;
    static final class Edit {
        final long id;
        final String path;
        private final byte[] before,after;
        Edit(long id,String path,byte[] before,byte[] after) { this.id=id;this.path=path;this.before=before.clone();this.after=after.clone(); }
        long bytes() { return (long)before.length+after.length; }
        boolean matches(byte[] current,boolean forward) { return Arrays.equals(current,forward ? before : after); }
        byte[] restore(boolean forward) { return (forward ? after : before).clone(); }
        AppliedEditReview.Step step(boolean forward,int steps) { return new AppliedEditReview.Step(id,path,before.length,after.length,forward,steps); }
        AppliedEditReview.Comparison review() { return AppliedEditReview.compare(id,path,before,after); }
    }
    private final int limit;
    private final long budget;
    private final ArrayDeque<Edit> undo=new ArrayDeque<>(),redo=new ArrayDeque<>();
    private long bytes;
    private long nextId=1;
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
    Edit find(long id) { for (var edit : undo) if (edit.id==id) return edit;for (var edit : redo) if (edit.id==id) return edit;return null; }
    List<AppliedEditReview.Step> steps(boolean forward) {
        var result=new ArrayList<AppliedEditReview.Step>();var iterator=(forward ? redo : undo).descendingIterator();
        while (iterator.hasNext()) result.add(iterator.next().step(forward,result.size()+1));return List.copyOf(result);
    }
    record RestorePlan(Map<String,byte[]> restored,int steps,String target) { RestorePlan { restored=Collections.unmodifiableMap(new LinkedHashMap<>(restored)); } }
    RestorePlan plan(long id,boolean forward,Map<String,byte[]> current,long maximumBytes) throws IOException {
        var selected=new ArrayList<Edit>();var iterator=(forward ? redo : undo).descendingIterator();boolean found=false;
        while (iterator.hasNext()) { var edit=iterator.next();selected.add(edit);if (edit.id==id) { found=true;break; } }
        if (!found) throw new IOException("The selected applied edit is no longer available in that direction");
        long total=current.values().stream().mapToLong(value -> value.length).sum();var restored=new LinkedHashMap<String,byte[]>();
        for (var edit : selected) {
            byte[] value=restored.containsKey(edit.path) ? restored.get(edit.path) : current.get(edit.path);
            if (value==null || !edit.matches(value,forward)) throw new IOException("Applied-edit history no longer matches resource: "+edit.path);
            byte[] next=edit.restore(forward);total+=next.length-value.length;
            if (total>maximumBytes) throw new IOException("History restore exceeds the workspace byte limit");restored.put(edit.path,next);
        }
        return new RestorePlan(restored,selected.size(),selected.getLast().path);
    }
    void record(String path,byte[] before,byte[] after) {
        if (Arrays.equals(before,after)) return;
        long cost=(long)before.length+after.length;
        if (cost>budget) { clear("Latest edit exceeded the history byte budget; earlier history was cleared. The applied edit is retained.");return; }
        for (var entry : redo) bytes-=entry.bytes();redo.clear();boolean evicted=false;
        while (!undo.isEmpty() && (undo.size()>=limit || bytes+cost>budget)) { bytes-=undo.removeFirst().bytes();evicted=true; }
        undo.addLast(new Edit(nextId++,path,before,after));bytes+=cost;
        note=evicted ? "Older applied edits were discarded at the history limit." : "History records applied byte changes; unapplied text stays separate.";
    }
    void moved(boolean forward) {
        var from=forward ? redo : undo;var to=forward ? undo : redo;
        if (from.isEmpty()) throw new IllegalStateException("No applied edit in that direction");
        to.addLast(from.removeLast());
    }
    void clear(String reason) { undo.clear();redo.clear();bytes=0;note=reason; }
}
