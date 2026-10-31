package dev.irisvisualizer.editor;

import java.nio.file.Path;
import java.util.*;

final class CommandPaletteTests {
    private static void check(boolean condition,String reason) { if(!condition)throw new AssertionError("Command palette: "+reason); }
    private static void rejected(Runnable action) { try { action.run(); }catch(IllegalArgumentException|NullPointerException expected) { return; }throw new AssertionError("Expected invalid picker input"); }
    private static boolean has(String query,EditorCommands.Action action) { return CommandPalette.search(query).stream().anyMatch(e->e.action()==action); }
    static void run(Path temp)throws Exception {
        var entries=CommandPalette.entries();var all=EnumSet.allOf(EditorCommands.Action.class);all.remove(EditorCommands.Action.PALETTE);all.remove(EditorCommands.Action.CLOSE);
        check(entries.size()==26 && entries.stream().map(CommandPalette.Entry::action).distinct().count()==entries.size() && new HashSet<>(entries.stream().map(CommandPalette.Entry::action).toList()).equals(all),"each supported UI action appears exactly once, without recursive palette or native close");
        check(CommandPalette.search(" ").equals(entries),"empty query retains stable catalog order");
        check(has("sAvE cOpY",EditorCommands.Action.SAVE_COPY) && has("Ctrl+Shift+S",EditorCommands.Action.SAVE_AS) && has("references",EditorCommands.Action.REVIEW_LINKS),"case-insensitive title/shortcut/task matching");
        check(CommandPalette.search("File: Save Copy").size()==1 && has("File: Save Copy",EditorCommands.Action.SAVE_COPY),"every term must match the same command");
        check(CommandPalette.search("native terrain preview").isEmpty() && CommandPalette.search("[a-z]+").isEmpty(),"no unsupported preview action or regex interpretation");
        check(CommandPalette.search("x".repeat(256)).isEmpty() && CommandPalette.search("zz zz zz zz zz zz zz zz").isEmpty(),"exact query character and token limits");
        rejected(()->CommandPalette.search(null));rejected(()->CommandPalette.search("x".repeat(257)));rejected(()->CommandPalette.search("a a a a a a a a a"));
        for(String query:List.of("a\u0000b","a\nb","a\tb","a\u007fb"))rejected(()->CommandPalette.search(query));
        try { entries.clear();throw new AssertionError(); }catch(UnsupportedOperationException expected) {}
        try { CommandPalette.search("save").clear();throw new AssertionError(); }catch(UnsupportedOperationException expected) {}
        var none=new CommandPalette.Context(false,false,false,false,false);var ready=new CommandPalette.Context(true,true,true,true,true);
        var gated=Set.of(EditorCommands.Action.UNDO,EditorCommands.Action.REDO,EditorCommands.Action.BACK,EditorCommands.Action.FORWARD,EditorCommands.Action.INSPECT_JSON);
        for(var entry:entries) {
            check((entry.unavailable(none)!=null)==gated.contains(entry.action()),"only current-state actions require availability metadata");
            check(entry.unavailable(ready)==null,"enabled context exposes action without mutation");
            check(entry.shortcut().equals(EditorCommands.shortcutLabel(entry.action())),"menu and palette share authoritative bindings");
            rejected(()->entry.unavailable(null));
        }
        check(EditorCommands.shortcut(new EditorCommands.Input(true,true,false,false,true,false,Set.of(EditorCommands.Key.P)))==EditorCommands.Action.PALETTE,"palette shortcut works while typing");
        check(EditorCommands.shortcut(new EditorCommands.Input(true,true,false,false,false,true,Set.of(EditorCommands.Key.P)))==null && EditorCommands.shortcut(new EditorCommands.Input(true,true,true,false,false,false,Set.of(EditorCommands.Key.P)))==null,"popup/extra-modifier ownership applies to palette chord");
        var saveKey=new EditorCommands.Input(true,false,false,false,true,false,Set.of(EditorCommands.Key.S));
        check(EditorCommands.shortcut(saveKey,true)==null && EditorCommands.shortcut(saveKey,false)==EditorCommands.Action.SAVE,"palette owns file shortcuts through its dismissal frame");
        check(EditorCommands.shortcut(new EditorCommands.Input(true,true,false,false,true,false,Set.of(EditorCommands.Key.P)),true)==EditorCommands.Action.PALETTE,"palette chord can refocus an open palette");
        var visible=List.of("first","middle","last");
        check(PickerSelection.move(visible,null,0).equals("first") && PickerSelection.move(visible,"removed",1).equals("first"),"new or stale selection starts at first visible identity");
        check(PickerSelection.move(visible,"middle",-1).equals("first") && PickerSelection.move(visible,"middle",1).equals("last"),"single-step keyboard traversal");
        check(PickerSelection.move(visible,"first",-1).equals("first") && PickerSelection.move(visible,"last",1).equals("last"),"edges clamp instead of wrapping to another command");
        check(PickerSelection.move(List.of("middle"),"middle",1).equals("middle") && PickerSelection.move(List.of(),"old",-1)==null,"one result and empty results remain safe");
        check(PickerSelection.move(List.of("last","middle","first"),"middle",0).equals("middle"),"stable identity survives result reorder");
        rejected(()->PickerSelection.move(visible,"first",2));rejected(()->PickerSelection.move(visible,"first",-2));rejected(()->PickerSelection.move(null,"first",0));
        // Metadata-only availability follows late history changes; selected entries do not cache permission to run.
        var pack=PackWorkspace.newPack(Map.of("blocks/test.json",new byte[]{1},"future/opaque.bin",new byte[]{0,-1,4}));
        var undo=entries.stream().filter(e->e.action()==EditorCommands.Action.UNDO).findFirst().orElseThrow();
        check(undo.unavailable(new CommandPalette.Context(pack.undoCount()>0,false,false,false,false))!=null,"initial Undo unavailable");
        pack.replace("blocks/test.json",new byte[]{2});check(undo.unavailable(new CommandPalette.Context(pack.undoCount()>0,false,false,false,false))==null,"new applied edit enables Undo");
        pack.undo();check(undo.unavailable(new CommandPalette.Context(pack.undoCount()>0,true,false,false,false))!=null,"late restore removes stale Undo availability");
        var drafts=new InspectorDrafts();drafts.update("typing","stored","unapplied");long revision=pack.revision(),resourceRevision=pack.resourceRevision();int redo=pack.redoCount();var queue=new EditorCommands.Queue();
        for(var entry:entries) { queue.request(pack,entry.action(),EditorCommands.Origin.UI);check(queue.take(pack)==entry.action() && queue.take(pack)==null,"each palette action uses existing one-shot dispatch"); }
        check(pack.revision()==revision && pack.resourceRevision()==resourceRevision && pack.redoCount()==redo && drafts.any() && Arrays.equals(pack.read("future/opaque.bin"),new byte[]{0,-1,4}),"catalog/search/selection/queue do not edit resources, history or drafts");
        System.out.println("PASS: bounded immutable 26-command palette catalog with exact supported-action coverage, literal all-term title/task/shared-shortcut search, query/control/token boundaries and disabled reasons from current metadata, palette chord ownership, clamped visible-identity keyboard selection with empty/stale/reordered results, one-shot guarded dispatch identity and retained drafts/history/opaque bytes; unsupported previews absent and no resource payload capture or file/native operations");
    }
}
