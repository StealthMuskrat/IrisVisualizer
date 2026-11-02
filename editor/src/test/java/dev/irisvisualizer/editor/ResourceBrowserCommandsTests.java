package dev.irisvisualizer.editor;

import java.util.EnumSet;
import java.util.Set;

/** Prepared for the end-of-UI run; exercises ownership of destructive browser shortcuts. */
final class ResourceBrowserCommandsTests {
    private static final ResourceBrowserCommands.Modifiers PLAIN=new ResourceBrowserCommands.Modifiers(false,false,false,false);
    private static void check(boolean value,String message){if(!value)throw new AssertionError("Browser commands: "+message);}
    private static ResourceBrowserCommands.Action keyboard(ResourceBrowserCommands.Modifiers modifiers,boolean folder,ResourceBrowserCommands.Key...keys){
        return ResourceBrowserCommands.keyboard(new ResourceBrowserCommands.Input(true,false,false,false,modifiers,Set.of(keys)),folder);
    }
    static void run(){
        for(int bits=0;bits<16;bits++){
            var modifiers=new ResourceBrowserCommands.Modifiers((bits&1)!=0,(bits&2)!=0,(bits&4)!=0,(bits&8)!=0);
            boolean plain=bits==0,control=bits==1,shift=bits==2;
            for(boolean folder:new boolean[]{false,true}){
                check(keyboard(modifiers,folder,ResourceBrowserCommands.Key.F2)==(plain?ResourceBrowserCommands.Action.RENAME:null),"F2 rejects every extra modifier for files and folders");
                check(keyboard(modifiers,folder,ResourceBrowserCommands.Key.DELETE)==(plain?ResourceBrowserCommands.Action.DELETE:null),"Delete rejects every extra modifier for files and folders");
                check(keyboard(modifiers,folder,ResourceBrowserCommands.Key.MENU)==(plain?ResourceBrowserCommands.Action.CONTEXT:null),"dedicated Menu key is an exact chord");
                check(keyboard(modifiers,folder,ResourceBrowserCommands.Key.F10)==(shift?ResourceBrowserCommands.Action.CONTEXT:null),"Shift+F10 does not capture Alt/OS/Control chords");
                check(keyboard(modifiers,folder,ResourceBrowserCommands.Key.ENTER)==(plain && folder?ResourceBrowserCommands.Action.TOGGLE:null),"Enter only toggles a focused folder without modifiers");
                check(ResourceBrowserCommands.pointer(modifiers,ResourceBrowserCommands.Button.LEFT,folder,false,false)==(plain?ResourceBrowserCommands.Action.SELECT:control && !folder?ResourceBrowserCommands.Action.DUPLICATE:null),"Ctrl-left duplicates only files; Ctrl+Shift/AltGr/OS never duplicate");
                check(ResourceBrowserCommands.pointer(modifiers,ResourceBrowserCommands.Button.RIGHT,folder,false,false)==(plain?ResourceBrowserCommands.Action.CONTEXT:control && folder?ResourceBrowserCommands.Action.CREATE_RESOURCE:null),"Ctrl-right creates only in folders; plain right opens actions");
                check(ResourceBrowserCommands.pointer(modifiers,ResourceBrowserCommands.Button.MIDDLE,folder,false,false)==(control && folder?ResourceBrowserCommands.Action.CREATE_SUBFOLDER:null),"Ctrl-middle creates subfolders only");
            }
        }
        var keys=EnumSet.allOf(ResourceBrowserCommands.Key.class);
        for(int ownership=1;ownership<16;ownership++){
            boolean focused=(ownership&1)==0,editing=(ownership&2)!=0,popup=(ownership&4)!=0,dragging=(ownership&8)!=0;
            for(boolean folder:new boolean[]{false,true})check(ResourceBrowserCommands.keyboard(new ResourceBrowserCommands.Input(focused,editing,popup,dragging,PLAIN,keys),folder)==null,"unfocused rows, editing, any popup and pointer ownership suppress all row shortcuts");
        }
        for(var button:ResourceBrowserCommands.Button.values())for(boolean folder:new boolean[]{false,true}){
            check(ResourceBrowserCommands.pointer(PLAIN,button,folder,true,false)==null && ResourceBrowserCommands.pointer(PLAIN,button,folder,false,true)==null,"popup and drag never leak pointer actions to underlying rows");
            var control=new ResourceBrowserCommands.Modifiers(true,false,false,false);
            check(ResourceBrowserCommands.pointer(control,button,folder,true,false)==null && ResourceBrowserCommands.pointer(control,button,folder,false,true)==null,"popup and drag suppress modified creation/duplication too");
        }
        var copied=new ResourceBrowserCommands.Input(true,false,false,false,PLAIN,keys);keys.clear();
        check(ResourceBrowserCommands.keyboard(copied,true)==ResourceBrowserCommands.Action.CONTEXT,"input is immutable and simultaneous Menu wins over mutation keys");
        check(keyboard(PLAIN,false,ResourceBrowserCommands.Key.F2,ResourceBrowserCommands.Key.DELETE)==ResourceBrowserCommands.Action.RENAME,"only one destructive command per row frame");
        check(keyboard(PLAIN,true)==null && keyboard(PLAIN,false)==null,"unchanged frames do not repeat commands");
        System.out.println("PASS: browser-row keyboard and pointer ownership, exhaustive exact modifier chords for rename/delete/create/duplicate, focused-row and active-editor/popup/drag guards, Menu and Shift+F10 accessibility, folder Enter, immutable input and deterministic one-action priority; no resource mutations or previews");
    }
}
