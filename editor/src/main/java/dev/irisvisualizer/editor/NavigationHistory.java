package dev.irisvisualizer.editor;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** UI-owned resource locations only; independent from applied edits, drafts and disk storage. */
final class NavigationHistory {
    static final int MAX_LOCATIONS=128,MAX_RECENT=32,MAX_FILTER_CHARACTERS=256;
    record Location(String path,String filter) {
        Location {
            if (path==null || path.isEmpty() || path.indexOf(0)>=0) throw new IllegalArgumentException("Select an existing resource");
            if (filter==null || filter.indexOf(0)>=0 || filter.length()>MAX_FILTER_CHARACTERS) throw new IllegalArgumentException("Unsupported resource search text");
        }
    }
    record Plan(NavigationHistory owner,PackWorkspace workspace,long generation,int index,Location location) {}
    record Destination(int steps,Location location) {}
    private PackWorkspace workspace;
    private final List<Location> locations=new ArrayList<>();
    private final List<String> recent=new ArrayList<>();
    private int index=-1;
    private long generation;
    private static Location location(PackWorkspace workspace,String path,String filter) {
        Objects.requireNonNull(workspace);var result=new Location(path,filter);
        if (!workspace.paths().contains(path)) throw new IllegalArgumentException("Navigation target is not in this workspace");return result;
    }
    private void touch(String path) { recent.remove(path);recent.addFirst(path);if (recent.size()>MAX_RECENT) recent.removeLast(); }
    void reset(PackWorkspace current,String path,String filter) {
        Objects.requireNonNull(current);var initial=path==null ? null : location(current,path,filter);
        workspace=current;locations.clear();recent.clear();index=-1;generation++;
        if (initial!=null) { locations.add(initial);index=0;touch(path); }
    }
    /** Call only for the actual current location, after a successful selection or search edit. */
    void observe(PackWorkspace current,String path,String filter) {
        if (workspace!=current) { reset(current,path,filter);return; }
        if (path==null) return;var next=location(current,path,filter);
        if (index>=0 && locations.get(index).path().equals(path)) { locations.set(index,next);return; }
        if (index+1<locations.size()) locations.subList(index+1,locations.size()).clear();
        locations.add(next);index=locations.size()-1;if (locations.size()>MAX_LOCATIONS) { locations.removeFirst();index--; }
        touch(path);generation++;
    }
    Location current() { return index<0 ? null : locations.get(index); }
    int size() { return locations.size(); }
    List<String> recent() { return List.copyOf(recent); }
    List<Destination> back() { var result=new ArrayList<Destination>();for (int i=index-1;i>=0;i--) result.add(new Destination(index-i,locations.get(i)));return List.copyOf(result); }
    List<Destination> forward() { var result=new ArrayList<Destination>();for (int i=index+1;i<locations.size();i++) result.add(new Destination(i-index,locations.get(i)));return List.copyOf(result); }
    Plan plan(PackWorkspace current,boolean forward,int steps) {
        if (workspace!=current || steps<1) throw new IllegalArgumentException("Navigation history is unavailable for this workspace");
        long target=(long)index+(forward ? steps : -(long)steps);
        if (index<0 || target<0 || target>=locations.size()) throw new IllegalArgumentException("No resource location in that direction");
        return new Plan(this,current,generation,(int)target,locations.get((int)target));
    }
    void validate(Plan plan,PackWorkspace current) {
        if (plan==null || plan.owner()!=this || workspace!=current || plan.workspace()!=current || plan.generation()!=generation || plan.index()<0 || plan.index()>=locations.size() || !locations.get(plan.index()).equals(plan.location()) || plan.index()==index || !current.paths().contains(plan.location().path()))
            throw new IllegalArgumentException("Navigation history changed; choose the destination again");
    }
    /** Commit only inside the successful guarded UI transition, after validate(). */
    void commit(Plan plan,PackWorkspace current) { validate(plan,current);index=plan.index();touch(plan.location().path());generation++; }
    void clear() { var current=current();locations.clear();recent.clear();index=-1;generation++;if (current!=null) { locations.add(current);index=0;touch(current.path()); } }
}
