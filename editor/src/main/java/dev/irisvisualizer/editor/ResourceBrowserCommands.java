package dev.irisvisualizer.editor;

import java.util.Objects;
import java.util.Set;

/** Browser-row input ownership only. Every resulting operation still needs its existing review. */
final class ResourceBrowserCommands {
    enum Action {SELECT,CONTEXT,DUPLICATE,CREATE_RESOURCE,CREATE_SUBFOLDER,RENAME,DELETE,TOGGLE}
    enum Key {MENU,F10,F2,DELETE,ENTER}
    enum Button {LEFT,RIGHT,MIDDLE}
    record Modifiers(boolean ctrl,boolean shift,boolean alt,boolean superKey) {
        boolean plain(){return !ctrl && !shift && !alt && !superKey;}
        boolean controlOnly(){return ctrl && !shift && !alt && !superKey;}
    }
    record Input(boolean rowFocused,boolean editing,boolean popup,boolean dragging,Modifiers modifiers,Set<Key> pressed) {
        Input { Objects.requireNonNull(modifiers);pressed=Set.copyOf(pressed); }
    }
    /** A focused row owns these keys; a selected file elsewhere in the panel grants no authority. */
    static Action keyboard(Input input,boolean folder) {
        Objects.requireNonNull(input);
        if(!input.rowFocused() || input.editing() || input.popup() || input.dragging())return null;
        var modifiers=input.modifiers();var pressed=input.pressed();
        if(modifiers.plain() && pressed.contains(Key.MENU)
                || !modifiers.ctrl() && modifiers.shift() && !modifiers.alt() && !modifiers.superKey() && pressed.contains(Key.F10))return Action.CONTEXT;
        if(!modifiers.plain())return null;
        if(pressed.contains(Key.F2))return Action.RENAME;
        if(pressed.contains(Key.DELETE))return Action.DELETE;
        return folder && pressed.contains(Key.ENTER) ? Action.TOGGLE : null;
    }
    /** Prescribed pointer chords are exact, including AltGr/OS/Shift rejection. */
    static Action pointer(Modifiers modifiers,Button button,boolean folder,boolean popup,boolean dragging) {
        Objects.requireNonNull(modifiers);Objects.requireNonNull(button);
        if(popup || dragging)return null;
        if(modifiers.plain())return switch(button){case LEFT->Action.SELECT;case RIGHT->Action.CONTEXT;case MIDDLE->null;};
        if(!modifiers.controlOnly())return null;
        return switch(button){case LEFT->folder?null:Action.DUPLICATE;case RIGHT->folder?Action.CREATE_RESOURCE:null;case MIDDLE->folder?Action.CREATE_SUBFOLDER:null;};
    }
    private ResourceBrowserCommands(){}
}
