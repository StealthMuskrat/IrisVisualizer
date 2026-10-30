package dev.irisvisualizer.editor;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Read-only pending-write inventory and explicit, bounded active-folder checks. */
public final class WorkspaceChanges {
    public static final int MAX_RESULTS=200, MAX_QUERY_CHARACTERS=256;
    public static final long MAX_CHECK_BYTES=16L*1024*1024;
    public enum Kind {
        NEW_FILE("New file"), MODIFIED_FILE("Modified file"), NEW_FOLDER("New folder"), REMOVED_FOLDER("Removed empty folder");
        private final String label;
        Kind(String label) { this.label=label; }
        public String label() { return label; }
    }
    public enum Filter {
        ALL("All pending changes"), FILES("Files"), NEW_FILES("New files"), MODIFIED_FILES("Modified files"), FOLDERS("Folder changes");
        private final String label;
        Filter(String label) { this.label=label; }
        public String label() { return label; }
        boolean matches(Kind kind) { return switch (this) { case ALL -> true;case FILES -> kind==Kind.NEW_FILE || kind==Kind.MODIFIED_FILE;case NEW_FILES -> kind==Kind.NEW_FILE;case MODIFIED_FILES -> kind==Kind.MODIFIED_FILE;case FOLDERS -> kind==Kind.NEW_FOLDER || kind==Kind.REMOVED_FOLDER; }; }
    }
    public record Entry(String path, Kind kind, int bytes) {}
    public record Query(String text, Filter filter) {
        public Query {
            if (text==null || text.length()>MAX_QUERY_CHARACTERS || text.indexOf(0)>=0 || filter==null)
                throw new IllegalArgumentException("Use up to 256 path-search characters without NUL");
            text=text.strip().toLowerCase(Locale.ROOT);
        }
    }
    public record Result(int newFiles, int modifiedFiles, int newFolders, int removedFolders, long fileBytes, int matches, List<Entry> entries) {
        public Result(int newFiles,int modifiedFiles,int newFolders,long fileBytes,int matches,List<Entry> entries) { this(newFiles,modifiedFiles,newFolders,0,fileBytes,matches,entries); }
        public Result { entries=List.copyOf(entries); }
        public int total() { return newFiles+modifiedFiles+newFolders+removedFolders; }
        public boolean truncated() { return matches>entries.size(); }
    }
    public enum Status { NO_ACTIVE_FOLDER, AVAILABLE, FOLDER_EXISTS, BASELINE_MATCH, REMOVAL_READY, CONFLICT, TOO_LARGE, REVIEW_LIMIT, IO_ERROR }
    /** Point-in-time advisory only. Save always performs its own full preflight. */
    public record Check(Entry entry, Path folder, Status status, long diskBytes, String detail) {}
    public static final class Cache {
        private PackWorkspace workspace;
        private long revision=-1,saveRevision=-1;
        private List<Entry> inventory=List.of();
        private Query previous;
        private Result result;
        private Check checked;
        public void clear() { workspace=null;revision=-1;saveRevision=-1;inventory=List.of();previous=null;result=null;checked=null; }
        private void ensure(PackWorkspace current) {
            Objects.requireNonNull(current);
            if (workspace!=current || revision!=current.revision() || saveRevision!=current.saveRevision()) {
                inventory=current.pendingChanges();workspace=current;revision=current.revision();saveRevision=current.saveRevision();previous=null;result=null;checked=null;
            }
        }
        public Result query(PackWorkspace current, Query query) {
            Objects.requireNonNull(query);ensure(current);
            if (result!=null && query.equals(previous)) return result;
            int added=0,modified=0,folders=0,removed=0,matches=0;long bytes=0;var entries=new java.util.ArrayList<Entry>();
            for (var entry : inventory) {
                switch (entry.kind()) { case NEW_FILE -> added++;case MODIFIED_FILE -> modified++;case NEW_FOLDER -> folders++;case REMOVED_FOLDER -> removed++; }
                bytes+=entry.bytes();
                if (!query.filter().matches(entry.kind()) || !entry.path().toLowerCase(Locale.ROOT).contains(query.text())) continue;
                matches++;if (entries.size()<MAX_RESULTS) entries.add(entry);
            }
            previous=query;result=new Result(added,modified,folders,removed,bytes,matches,entries);return result;
        }
        public Check check(PackWorkspace current, Entry entry) {
            ensure(current);
            if (!inventory.contains(Objects.requireNonNull(entry))) throw new IllegalArgumentException("This pending change is no longer available");
            checked=current.checkPendingChange(entry,MAX_CHECK_BYTES);return checked;
        }
        public Check checked(PackWorkspace current, Entry entry) { ensure(current);return checked!=null && checked.entry().equals(entry) ? checked : null; }
    }
}
