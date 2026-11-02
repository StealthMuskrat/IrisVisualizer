package dev.irisvisualizer.editor;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;

/** Prepared contracts for ImGui's deferred tab acknowledgement; no native UI is started. */
final class PreviewModeTabsTests {
    private static void check(boolean value,String message){if(!value)throw new AssertionError("Preview tabs: "+message);}
    private static boolean requested(PreviewModeTabs tabs){return tabs.request(PreviewSession.Mode.TWO_D) || tabs.request(PreviewSession.Mode.THREE_D);}
    static void run()throws Exception{
        var files=new LinkedHashMap<String,byte[]>();
        for(String path:new String[]{"dimensions/main.json","biomes/one.json","biomes/two.json","objects/one.iob","images/one.png","future/unknown.json"})files.put(path,"{}".getBytes(StandardCharsets.UTF_8));
        var pack=PackWorkspace.newPack(files);long revision=pack.revision();boolean dirty=pack.dirty();var session=new PreviewSession();var tabs=new PreviewModeTabs();
        var view=session.select(pack,"biomes/one.json");tabs.prepare(pack,view,EditorShell.Toolbar.ROW);
        check(tabs.request(PreviewSession.Mode.TWO_D) && !tabs.request(PreviewSession.Mode.THREE_D),"new resource requests its meaningful default exactly");
        check(tabs.visible(PreviewSession.Mode.THREE_D)==null && tabs.request(PreviewSession.Mode.TWO_D),"old visible native tab cannot replace a pending resource choice");
        tabs.prepare(pack,view,EditorShell.Toolbar.ROW);check(tabs.request(PreviewSession.Mode.TWO_D),"request survives clipped/missing native tab content");
        check(tabs.visible(PreviewSession.Mode.TWO_D)==PreviewSession.Mode.TWO_D && !requested(tabs),"native acknowledgement consumes the programmatic request");
        // A real click is acknowledged next frame. No old SetSelected flag may queue a switch back.
        for(var selected:new PreviewSession.Mode[]{PreviewSession.Mode.THREE_D,PreviewSession.Mode.TWO_D,PreviewSession.Mode.THREE_D}){
            tabs.prepare(pack,view,EditorShell.Toolbar.ROW);check(!requested(tabs),"ordinary frame does not reassert an old tab");
            check(tabs.visible(selected)==selected && !requested(tabs),"user selection is accepted without scheduling another native selection");
            view=session.mode(selected);
            for(int frame=0;frame<8;frame++){tabs.prepare(pack,view,EditorShell.Toolbar.ROW);check(!requested(tabs) && tabs.visible(selected)==selected,"user choice stays stable over unchanged frames");}
        }
        session.configure("9007199254740993","12","-13");view=session.select(pack,"biomes/one.json");tabs.prepare(pack,view,EditorShell.Toolbar.ROW);check(!requested(tabs),"location changes do not select another tab");
        for(var surface:new EditorShell.Toolbar[]{EditorShell.Toolbar.COMPACT,EditorShell.Toolbar.STACKED,EditorShell.Toolbar.ROW}){
            tabs.prepare(pack,view,surface);check(tabs.request(view.mode()),"responsive table/scope changes restore the current mode once");
            check(tabs.visible(PreviewSession.Mode.TWO_D)==null,"stale default from a recreated tab bar is ignored");
            tabs.visible(view.mode());tabs.prepare(pack,view,surface);check(!requested(tabs),"surface acknowledgement stays consumed");
        }
        view=session.select(pack,"biomes/two.json");tabs.prepare(pack,view,EditorShell.Toolbar.ROW);check(view.mode()==PreviewSession.Mode.TWO_D && tabs.request(PreviewSession.Mode.TWO_D),"a different biome keeps the session's documented default");tabs.visible(view.mode());
        view=session.select(pack,"objects/one.iob");tabs.prepare(pack,view,EditorShell.Toolbar.ROW);check(tabs.request(PreviewSession.Mode.THREE_D) && tabs.visible(PreviewSession.Mode.TWO_D)==null,"object's disabled 2D tab cannot become active");tabs.visible(PreviewSession.Mode.THREE_D);
        view=session.select(pack,"images/one.png");tabs.prepare(pack,view,EditorShell.Toolbar.ROW);check(tabs.request(PreviewSession.Mode.TWO_D) && tabs.visible(PreviewSession.Mode.THREE_D)==null,"new image never inherits stale 3D tab content");tabs.visible(PreviewSession.Mode.TWO_D);
        view=session.select(pack,"future/unknown.json");tabs.prepare(pack,view,EditorShell.Toolbar.ROW);check(!requested(tabs) && tabs.visible(PreviewSession.Mode.TWO_D)==null && tabs.visible(PreviewSession.Mode.THREE_D)==null,"unknown resource has two disabled tabs and no forced content");
        view=session.select(pack,"biomes/one.json");tabs.prepare(pack,view,EditorShell.Toolbar.ROW);tabs.visible(view.mode());view=session.mode(PreviewSession.Mode.THREE_D);tabs.prepare(pack,view,EditorShell.Toolbar.ROW);check(tabs.request(PreviewSession.Mode.THREE_D),"explicit external session-mode changes request native synchronization");tabs.visible(view.mode());
        var other=PackWorkspace.newPack(files);var another=new PreviewSession();view=another.select(other,"biomes/one.json");tabs.prepare(other,view,EditorShell.Toolbar.ROW);check(tabs.request(view.mode()),"equal path/revision in another workspace cannot reuse native acknowledgement");tabs.visible(view.mode());tabs.clear();tabs.prepare(other,view,EditorShell.Toolbar.ROW);check(tabs.request(view.mode()),"explicit Clear releases tab ownership");
        check(pack.revision()==revision && pack.undoCount()==0 && pack.dirty()==dirty && pack.paths().equals(files.keySet()),"tab synchronization has no workspace/history/storage authority");
        for(var entry:files.entrySet())check(java.util.Arrays.equals(pack.read(entry.getKey()),entry.getValue()),"all original bytes remain exact");
        System.out.println("PASS: one-shot native preview-tab synchronization, deferred stale-tab acknowledgement, stable repeated mouse/keyboard mode changes, resource/type/workspace and responsive table scope changes, meaningful disabled tabs and no stale image mode, hidden/clipped frames and explicit Clear without pack changes or native generation");
    }
}
