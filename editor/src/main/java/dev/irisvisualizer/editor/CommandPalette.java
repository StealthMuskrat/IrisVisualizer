package dev.irisvisualizer.editor;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Fixed UI command catalog. Context contains availability metadata only, never pack payloads or drafts. */
public final class CommandPalette {
    public static final int MAX_QUERY_CHARACTERS=256,MAX_TERMS=8;
    public record Context(boolean undo,boolean redo,boolean back,boolean forward,boolean json) {}
    public record Entry(EditorCommands.Action action,String title,String description,String terms) {
        public String shortcut() { return EditorCommands.shortcutLabel(action); }
        public String unavailable(Context context) {
            Objects.requireNonNull(context);
            return switch(action) {
                case UNDO->context.undo() ? null : "No applied edit to undo";
                case REDO->context.redo() ? null : "No applied edit to redo";
                case BACK->context.back() ? null : "No earlier resource location";
                case FORWARD->context.forward() ? null : "No later resource location";
                case INSPECT_JSON->context.json() ? null : "Select a supported JSON resource";
                default->null;
            };
        }
    }
    private static Entry entry(EditorCommands.Action action,String title,String description,String terms) { return new Entry(action,title,description,terms); }
    private static final List<Entry> ENTRIES=List.of(
        entry(EditorCommands.Action.NEW,"File: New pack","Create a starter pack using current workspace guards.","create starter"),
        entry(EditorCommands.Action.OPEN,"File: Open folder","Load a folder in the background; review before opening.","directory load"),
        entry(EditorCommands.Action.SAVE,"File: Save","Save applied values using existing draft and disk-conflict guards.","write disk"),
        entry(EditorCommands.Action.SAVE_AS,"File: Save As","Write a captured snapshot to a fresh folder and adopt it when current.","directory transfer"),
        entry(EditorCommands.Action.SAVE_COPY,"File: Save Copy","Write a captured snapshot to a fresh folder; keep the active pack.","directory transfer"),
        entry(EditorCommands.Action.IMPORT,"File: Import ZIP","Load a ZIP in the background; review before opening.","archive load"),
        entry(EditorCommands.Action.EXPORT,"File: Export ZIP","Export a captured applied snapshot in the background.","archive write"),
        entry(EditorCommands.Action.UNDO,"Edit: Undo applied edit","Restore the previous applied byte edit using the draft guard.","history"),
        entry(EditorCommands.Action.REDO,"Edit: Redo applied edit","Restore the next applied byte edit using the draft guard.","history"),
        entry(EditorCommands.Action.QUICK_OPEN,"Navigate: Quick Open resource","Find resources by path or filename.","find picker"),
        entry(EditorCommands.Action.SEARCH_TEXT,"Navigate: Search JSON text","Search applied JSON source text in the background.","find literal"),
        entry(EditorCommands.Action.BACK,"Navigate: Back","Visit the previous resource using the draft guard.","history previous"),
        entry(EditorCommands.Action.FORWARD,"Navigate: Forward","Visit the next resource using the draft guard.","history next"),
        entry(EditorCommands.Action.INSPECT_JSON,"Inspect: Selected JSON structure","Inspect read-only JSON tokens in the selected resource.","source tree"),
        entry(EditorCommands.Action.REVIEW_CHANGES,"Review: Pending changes","Review applied file and folder changes and advisory disk checks.","dirty save disk"),
        entry(EditorCommands.Action.REVIEW_HISTORY,"Review: Applied edits","Review retained in-memory Undo/Redo history.","timeline"),
        entry(EditorCommands.Action.REVIEW_LOAD,"Review: Open / Import progress","Review the background pack load and guarded candidate opening.","folder zip"),
        entry(EditorCommands.Action.REVIEW_SAVE_AS,"Review: Save As progress","Review the captured Save As transfer.","folder snapshot"),
        entry(EditorCommands.Action.REVIEW_COPY,"Review: Save Copy progress","Review the captured folder copy.","folder snapshot"),
        entry(EditorCommands.Action.REVIEW_EXPORT,"Review: ZIP export progress","Review captured ZIP export progress and receipt.","archive snapshot"),
        entry(EditorCommands.Action.REVIEW_LINKS,"Review: Pack resource links","Review the bounded resource-reference audit.","references dependencies"),
        entry(EditorCommands.Action.REVIEW_JIGSAW,"Review: Jigsaw dependencies","Review bounded jigsaw dependency findings.","pool piece references"),
        entry(EditorCommands.Action.REVIEW_THEMES,"Review: Global piece theme tags","Review global structure theme-tag findings.","structures jigsaw"),
        entry(EditorCommands.Action.REVIEW_ASSET_IMPORT,"Review: Captured asset import","Review an existing captured single-asset import.","image object"),
        entry(EditorCommands.Action.REVIEW_ASSET_BATCH,"Review: Captured asset batch","Review an existing captured batch import.","image object"),
        entry(EditorCommands.Action.REVIEW_ASSET_REPLACEMENT,"Review: Captured asset replacement","Review an existing captured asset replacement.","image object"),
        entry(EditorCommands.Action.REVIEW_RESOURCE_ADDITION,"Review: Create / duplicate resource","Review a new definition or byte-exact duplicate before applying it in memory.","new template copy history"),
        entry(EditorCommands.Action.REVIEW_FOLDER_OPERATION,"Review: Empty-folder operation","Review folder creation, rename, move or deletion before applying it in memory.","directory history")
    );
    public static List<Entry> entries() { return ENTRIES; }
    public static List<Entry> search(String query) {
        if(query==null || query.length()>MAX_QUERY_CHARACTERS || query.codePoints().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Use up to 256 search characters without control characters");
        String normalized=query.strip().toLowerCase(Locale.ROOT);
        String[] terms=normalized.isEmpty() ? new String[0] : normalized.split("\\s+");
        if(terms.length>MAX_TERMS) throw new IllegalArgumentException("Use at most eight search terms");
        return ENTRIES.stream().filter(entry->{String text=(entry.title()+" "+entry.description()+" "+entry.terms()+" "+entry.shortcut()).toLowerCase(Locale.ROOT);for(String term:terms)if(!text.contains(term))return false;return true;}).toList();
    }
    private CommandPalette() {}
}
