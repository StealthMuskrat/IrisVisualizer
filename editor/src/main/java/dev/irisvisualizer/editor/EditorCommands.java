package dev.irisvisualizer.editor;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/** UI-only key routing and one-frame command ownership; never reads resource bytes or performs file operations. */
public final class EditorCommands {
    public enum Action {
        NEW("New"),OPEN("Open"),SAVE("Save"),SAVE_AS("Save As"),SAVE_COPY("Save Copy"),IMPORT("Import"),EXPORT("Export"),
        UNDO(null),REDO(null),QUICK_OPEN(null),SEARCH_TEXT(null),BACK(null),FORWARD(null),PALETTE(null),INSPECT_JSON(null),REVIEW_CHANGES(null),REVIEW_HISTORY(null),REVIEW_LOAD(null),REVIEW_SAVE_AS(null),REVIEW_COPY(null),REVIEW_EXPORT(null),REVIEW_LINKS(null),REVIEW_JIGSAW(null),REVIEW_THEMES(null),REVIEW_ASSET_IMPORT(null),REVIEW_ASSET_BATCH(null),REVIEW_ASSET_REPLACEMENT(null),REVIEW_RESOURCE_ADDITION(null),REVIEW_FOLDER_OPERATION(null),CLOSE(null);
        private final String fileAction;
        Action(String fileAction){this.fileAction=fileAction;}
        public String fileAction(){return fileAction;}
        public static Action file(String label){return java.util.Arrays.stream(values()).filter(a->a.fileAction!=null && a.fileAction.equals(label)).findFirst().orElseThrow(()->new IllegalArgumentException("Unknown file command"));}
    }
    public enum Key { N,O,S,I,E,Z,Y,P,F,LEFT,RIGHT }
    public enum Origin { SHORTCUT,UI,WINDOW_CLOSE }
    public record Input(boolean ctrl,boolean shift,boolean alt,boolean superKey,boolean editing,boolean popup,Set<Key> pressed) {
        public Input { pressed=Set.copyOf(Objects.requireNonNull(pressed)); }
    }
    private record Binding(Action action,Key key,boolean ctrl,boolean shift,boolean alt,boolean editingAllowed,String label) {}
    private static final List<Binding> BINDINGS=List.of(
            new Binding(Action.SAVE,Key.S,true,false,false,true,"Ctrl+S"),
            new Binding(Action.SAVE_AS,Key.S,true,true,false,true,"Ctrl+Shift+S"),
            new Binding(Action.NEW,Key.N,true,false,false,true,"Ctrl+N"),
            new Binding(Action.OPEN,Key.O,true,false,false,true,"Ctrl+O"),
            new Binding(Action.IMPORT,Key.I,true,true,false,true,"Ctrl+Shift+I"),
            new Binding(Action.EXPORT,Key.E,true,true,false,true,"Ctrl+Shift+E"),
            new Binding(Action.QUICK_OPEN,Key.P,true,false,false,true,"Ctrl+P"),
            new Binding(Action.PALETTE,Key.P,true,true,false,true,"Ctrl+Shift+P"),
            new Binding(Action.SEARCH_TEXT,Key.F,true,true,false,true,"Ctrl+Shift+F"),
            new Binding(Action.UNDO,Key.Z,true,false,false,false,"Ctrl+Z"),
            new Binding(Action.REDO,Key.Y,true,false,false,false,"Ctrl+Y"),
            new Binding(Action.REDO,Key.Z,true,true,false,false,"Ctrl+Shift+Z"),
            new Binding(Action.BACK,Key.LEFT,false,false,true,false,"Alt+Left"),
            new Binding(Action.FORWARD,Key.RIGHT,false,false,true,false,"Alt+Right"));
    public static String shortcutLabel(Action action){return BINDINGS.stream().filter(b->b.action()==action).map(Binding::label).collect(java.util.stream.Collectors.joining(" / "));}
    /** Exactly one command for edge-triggered key events. Native text undo/navigation and popup/OS chords retain ownership. */
    public static Action shortcut(Input input){
        Objects.requireNonNull(input);if(input.popup() || input.superKey())return null;
        for(var binding:BINDINGS)if(input.ctrl()==binding.ctrl() && input.shift()==binding.shift() && input.alt()==binding.alt()
                && (!input.editing() || binding.editingAllowed()) && input.pressed().contains(binding.key()))return binding.action();
        return null;
    }
    /** An open palette owns global shortcuts through its dismissal/dispatch frame. */
    public static Action shortcut(Input input,boolean paletteOpen) {
        Action action=shortcut(input);return paletteOpen && action!=Action.PALETTE ? null : action;
    }
    public static final class Queue {
        private record Pending(PackWorkspace owner,Action action,Origin origin) {}
        private Pending pending;
        /** Explicit UI actions outrank keyboard events; native window close outranks both. */
        public void request(PackWorkspace owner,Action action,Origin origin){
            Objects.requireNonNull(owner);Objects.requireNonNull(action);Objects.requireNonNull(origin);
            if((origin==Origin.WINDOW_CLOSE)!=(action==Action.CLOSE))throw new IllegalArgumentException("Window-close origin is reserved for closing");
            if(pending==null || origin.ordinal()>=pending.origin().ordinal())pending=new Pending(owner,action,origin);
        }
        /** Always consume once, including stale requests and later cancelled/failed actions. */
        public Action take(PackWorkspace current){Objects.requireNonNull(current);var next=pending;pending=null;return next!=null && next.owner()==current ? next.action() : null;}
        public void clear(){pending=null;}
    }
    private EditorCommands(){}
}
