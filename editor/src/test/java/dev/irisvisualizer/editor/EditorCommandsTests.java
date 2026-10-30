package dev.irisvisualizer.editor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;

final class EditorCommandsTests {
    private static byte[] bytes(String text){return text.getBytes(StandardCharsets.UTF_8);}
    private static void check(boolean condition,String reason){if(!condition)throw new AssertionError("Editor commands: "+reason);}
    private static EditorCommands.Action shortcut(boolean ctrl,boolean shift,boolean alt,boolean superKey,boolean editing,boolean popup,EditorCommands.Key...keys){return EditorCommands.shortcut(new EditorCommands.Input(ctrl,shift,alt,superKey,editing,popup,Set.of(keys)));}
    private static void rejected(Runnable action){try{action.run();}catch(IllegalArgumentException|NullPointerException expected){return;}throw new AssertionError("Expected invalid command input");}
    static void run(Path temp)throws Exception{
        var ctrl=Map.of(EditorCommands.Key.N,EditorCommands.Action.NEW,EditorCommands.Key.O,EditorCommands.Action.OPEN,EditorCommands.Key.S,EditorCommands.Action.SAVE,EditorCommands.Key.P,EditorCommands.Action.QUICK_OPEN,EditorCommands.Key.Z,EditorCommands.Action.UNDO,EditorCommands.Key.Y,EditorCommands.Action.REDO);
        for(var entry:ctrl.entrySet()){
            check(shortcut(true,false,false,false,false,false,entry.getKey())==entry.getValue(),"exact control chord");
            check(shortcut(false,false,false,false,false,false,entry.getKey())==null,"ordinary typing is not a command");
            check(shortcut(true,false,true,false,false,false,entry.getKey())==null,"Ctrl+Alt/AltGr does not trigger a control chord");
            check(shortcut(true,false,false,true,false,false,entry.getKey())==null,"OS/Super chords retain ownership");
            check(shortcut(true,false,false,false,false,true,entry.getKey())==null,"open popups retain keyboard ownership");
        }
        var shifted=Map.of(EditorCommands.Key.S,EditorCommands.Action.SAVE_AS,EditorCommands.Key.I,EditorCommands.Action.IMPORT,EditorCommands.Key.E,EditorCommands.Action.EXPORT,EditorCommands.Key.F,EditorCommands.Action.SEARCH_TEXT,EditorCommands.Key.Z,EditorCommands.Action.REDO);
        for(var entry:shifted.entrySet()){
            check(shortcut(true,true,false,false,false,false,entry.getKey())==entry.getValue(),"exact shifted control chord");
            check(shortcut(true,true,true,false,false,false,entry.getKey())==null,"extra modifiers never broaden shifted chords");
            check(shortcut(true,true,false,false,false,true,entry.getKey())==null,"popup suppresses shifted file/search/edit commands");
        }
        check(shortcut(false,false,true,false,false,false,EditorCommands.Key.LEFT)==EditorCommands.Action.BACK && shortcut(false,false,true,false,false,false,EditorCommands.Key.RIGHT)==EditorCommands.Action.FORWARD,"resource navigation chords");
        check(shortcut(true,false,true,false,false,false,EditorCommands.Key.LEFT)==null && shortcut(false,true,true,false,false,false,EditorCommands.Key.RIGHT)==null,"exact navigation modifiers");
        check(shortcut(false,false,true,false,true,false,EditorCommands.Key.LEFT)==null && shortcut(false,false,true,false,true,false,EditorCommands.Key.RIGHT)==null,"text/active widgets retain Alt-arrow navigation");
        check(shortcut(true,false,false,false,true,false,EditorCommands.Key.Z)==null && shortcut(true,false,false,false,true,false,EditorCommands.Key.Y)==null && shortcut(true,true,false,false,true,false,EditorCommands.Key.Z)==null,"native text Undo/Redo never triggers applied-edit history");
        check(shortcut(true,false,false,false,true,false,EditorCommands.Key.S)==EditorCommands.Action.SAVE && shortcut(true,false,false,false,true,false,EditorCommands.Key.P)==EditorCommands.Action.QUICK_OPEN && shortcut(true,true,false,false,true,false,EditorCommands.Key.F)==EditorCommands.Action.SEARCH_TEXT,"file/finder/search commands remain explicit while a text widget is active");
        check(shortcut(true,false,false,false,false,false)==null,"held keys without a new edge do not schedule work");
        check(shortcut(true,false,false,false,false,false,EditorCommands.Key.S,EditorCommands.Key.N)==EditorCommands.Action.SAVE,"simultaneous edges have deterministic single-command priority");
        var mutable=new HashSet<EditorCommands.Key>();mutable.add(EditorCommands.Key.S);var input=new EditorCommands.Input(true,false,false,false,false,false,mutable);mutable.clear();check(EditorCommands.shortcut(input)==EditorCommands.Action.SAVE,"key snapshot is immutable");
        try{input.pressed().clear();throw new AssertionError();}catch(UnsupportedOperationException expected){}
        for(var label:List.of("New","Open","Save","Save As","Save Copy","Import","Export"))check(EditorCommands.Action.file(label).fileAction().equals(label),"menu labels share file command identity");
        check(EditorCommands.shortcutLabel(EditorCommands.Action.REDO).equals("Ctrl+Y / Ctrl+Shift+Z") && EditorCommands.shortcutLabel(EditorCommands.Action.SAVE_COPY).isEmpty() && EditorCommands.shortcutLabel(EditorCommands.Action.CLOSE).isEmpty(),"shared menu labels include aliases and no invented Save Copy/close binding");
        rejected(()->EditorCommands.Action.file("save"));rejected(()->new EditorCommands.Input(false,false,false,false,false,false,null));rejected(()->EditorCommands.shortcut(null));
        var queue=new EditorCommands.Queue();var pack=PackWorkspace.newPack(Map.of("blocks/a.json",bytes("original"),"objects/exact.iob",new byte[]{0,1,-1},"future/unknown.json",bytes("malformed JSON 雪")));var foreign=PackWorkspace.newPack(Map.of("blocks/a.json",bytes("other")));check(pack.revision()==foreign.revision(),"fixture has equal revisions across distinct owners");
        check(queue.take(pack)==null,"polling never schedules work");
        queue.request(pack,EditorCommands.Action.SAVE,EditorCommands.Origin.SHORTCUT);queue.request(pack,EditorCommands.Action.SAVE,EditorCommands.Origin.SHORTCUT);check(queue.take(pack)==EditorCommands.Action.SAVE && queue.take(pack)==null,"duplicate edges are consumed once");
        queue.request(pack,EditorCommands.Action.NEW,EditorCommands.Origin.UI);queue.request(pack,EditorCommands.Action.SAVE,EditorCommands.Origin.SHORTCUT);check(queue.take(pack)==EditorCommands.Action.NEW,"explicit UI action wins over same-frame key events");
        queue.request(pack,EditorCommands.Action.SAVE,EditorCommands.Origin.SHORTCUT);queue.request(pack,EditorCommands.Action.OPEN,EditorCommands.Origin.UI);queue.request(pack,EditorCommands.Action.SAVE_AS,EditorCommands.Origin.UI);check(queue.take(pack)==EditorCommands.Action.SAVE_AS,"latest explicit action replaces the single pending action");
        queue.request(pack,EditorCommands.Action.CLOSE,EditorCommands.Origin.WINDOW_CLOSE);queue.request(pack,EditorCommands.Action.NEW,EditorCommands.Origin.UI);queue.request(pack,EditorCommands.Action.SAVE,EditorCommands.Origin.SHORTCUT);check(queue.take(pack)==EditorCommands.Action.CLOSE,"native close request has priority");
        queue.request(pack,EditorCommands.Action.SAVE,EditorCommands.Origin.UI);check(queue.take(foreign)==null && queue.take(pack)==null,"equal-revision foreign owner discards request without a hidden retry");
        queue.request(pack,EditorCommands.Action.SAVE,EditorCommands.Origin.UI);rejected(()->queue.request(pack,EditorCommands.Action.CLOSE,EditorCommands.Origin.UI));rejected(()->queue.request(pack,EditorCommands.Action.SAVE,EditorCommands.Origin.WINDOW_CLOSE));rejected(()->queue.request(null,EditorCommands.Action.NEW,EditorCommands.Origin.UI));check(queue.take(pack)==EditorCommands.Action.SAVE,"invalid requests preserve accepted pending work");
        queue.request(pack,EditorCommands.Action.SAVE,EditorCommands.Origin.UI);queue.clear();check(queue.take(pack)==null,"explicit clear drops pending work only");
        // Save captures the last applied bytes of the frame and retains unapplied inspector text/unknown data.
        Path root=temp.resolve("editor-command-save");pack.save(root);queue.request(pack,EditorCommands.Action.SAVE,EditorCommands.Origin.UI);pack.replace("blocks/a.json",bytes("late frame apply"));
        var drafts=new InspectorDrafts();drafts.update("late","stored","still typing");long revision=pack.revision(),content=pack.contentRevision("blocks/a.json");var history=pack.historySteps(false);
        var command=queue.take(pack);check(command==EditorCommands.Action.SAVE,"save survives later same-owner edits");pack.save(root);
        check(Files.readString(root.resolve("blocks/a.json")).equals("late frame apply") && drafts.any() && pack.revision()==revision && pack.contentRevision("blocks/a.json")==content && pack.historySteps(false).equals(history) && !pack.dirty(),"deferred Save includes latest apply without clearing drafts/history/content stamps");
        check(Arrays.equals(Files.readAllBytes(root.resolve("objects/exact.iob")),new byte[]{0,1,-1}) && Files.readString(root.resolve("future/unknown.json")).equals("malformed JSON 雪"),"opaque/unknown disk bytes remain exact");
        // Late drafts participate in Undo/close guards, and cancellations/failures do not auto-repeat commands.
        pack.replace("blocks/a.json",bytes("another edit"));queue.request(pack,EditorCommands.Action.UNDO,EditorCommands.Origin.SHORTCUT);int count=pack.undoCount();command=queue.take(pack);check(command==EditorCommands.Action.UNDO,"applied Undo command selected");
        check(!drafts.transition(()->false,pack::undo) && pack.undoCount()==count && drafts.any() && queue.take(pack)==null,"cancelled late-draft Undo retains state and consumes request");
        queue.request(pack,EditorCommands.Action.UNDO,EditorCommands.Origin.UI);check(queue.take(pack)==EditorCommands.Action.UNDO && drafts.transition(()->true,pack::undo) && !drafts.any() && !pack.dirty(),"explicit retry restores exact published baseline");
        drafts.update("close","stored","late close draft");queue.request(pack,EditorCommands.Action.CLOSE,EditorCommands.Origin.WINDOW_CLOSE);var closed=new AtomicBoolean();check(queue.take(pack)==EditorCommands.Action.CLOSE && !drafts.transition(()->false,()->closed.set(true)) && !closed.get() && drafts.any() && queue.take(pack)==null,"native close cancellation preserves late text and workspace");
        queue.request(pack,EditorCommands.Action.OPEN,EditorCommands.Origin.UI);check(queue.take(pack)==EditorCommands.Action.OPEN,"open command selected");try{drafts.transition(()->true,()->{throw new IOException("failed load/save guard");});throw new AssertionError();}catch(IOException expected){check(drafts.any() && queue.take(pack)==null,"failed command retains drafts and cannot repeat");}
        // Native close is queued against the current workspace at frame end, after a separately accepted replacement.
        queue.request(pack,EditorCommands.Action.SAVE,EditorCommands.Origin.UI);queue.request(foreign,EditorCommands.Action.CLOSE,EditorCommands.Origin.WINDOW_CLOSE);check(queue.take(foreign)==EditorCommands.Action.CLOSE,"window close guards the final active workspace");
        System.out.println("PASS: edge-triggered editor command routing with exact Windows modifiers and popup/OS/text-undo ownership, immutable input and deterministic single-command priority, menu/shortcut labels, one-frame UI/close precedence and workspace identity guards, latest applied-byte Save capture, late-draft Undo/close cancellation and failed-action single consumption, retained history/content stamps and exact opaque/unknown bytes; no new file operations or native generation");
    }
}
