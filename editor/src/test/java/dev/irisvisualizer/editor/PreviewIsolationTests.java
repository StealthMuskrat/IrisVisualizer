package dev.irisvisualizer.editor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Prepared for the end-of-UI run. Never initializes the native engine. */
final class PreviewIsolationTests {
    private static byte[] bytes(String text){return text.getBytes(StandardCharsets.UTF_8);}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static void rejects(Runnable action){try{action.run();}catch(IllegalArgumentException expected){return;}throw new AssertionError("Expected invalid isolation choice");}
    static void run(Path temp)throws Exception{
        var files=new LinkedHashMap<String,byte[]>();
        files.put("dimensions/one.json",bytes("{ \"focus\":\"nested/a\", \"focusRegion\":\"r\", \"regions\":[\"r\"] }"));
        files.put("dimensions/two.json",bytes("{}"));files.put("biomes/nested/a.json",bytes("{\"name\":\"a\"}"));files.put("biomes/b.json",bytes("{}"));files.put("regions/r.json",bytes("{\"landBiomes\":[\"nested/a\"]}"));
        files.put("images/a.png",new byte[]{0,-1});files.put("objects/a.iob",new byte[]{1,-1});files.put("generators/a.json",bytes("{}"));files.put("structures/a.json",bytes("{}"));files.put("expressions/a.json",bytes("{}"));
        var pack=PackWorkspace.newPack(files);var session=new PreviewSession();var drafts=new InspectorDrafts();drafts.update("unrelated","before","typing");
        long revision=pack.revision(),saved=pack.saveRevision(),resources=pack.resourceRevision();int history=pack.undoCount();
        var unresolved=session.select(pack,"biomes/nested/a.json");check(unresolved.isolationAvailable() && unresolved.isolationRequest()==null,"context must be selected before producing an isolation request");rejects(()->session.placement(PreviewIsolation.Placement.DIMENSION_SETTINGS));
        session.dimension("dimensions/one.json");session.configure(Long.toString(Long.MIN_VALUE),"-345","678");session.mapScale("2.5");
        var biome=session.select(pack,"biomes/nested/a.json");var focus=biome.isolationRequest();
        check(focus.placement()==PreviewIsolation.Placement.ISOLATED && focus.dimension().equals("dimensions/one.json") && focus.resourceType().equals("biomes") && focus.resourceKey().equals("nested/a"),"selected biome produces exact typed identity with selected dimension");
        check(focus.nativeFields().equals(Map.of("focus","nested/a","focusRegion","")),"biome request explicitly clears competing region focus");
        try{focus.nativeFields().put("focus","other");throw new AssertionError("Mutable focus descriptor");}catch(UnsupportedOperationException expected){}
        check(PreviewIsolation.help(true,PreviewIsolation.Placement.ISOLATED).contains("LAND") && PreviewIsolation.help(true,PreviewIsolation.Placement.ISOLATED).contains("buffet") && PreviewIsolation.help(true,PreviewIsolation.Placement.ISOLATED).contains("Version-excluded"),"source-audited placement and precedence limitations are visible");
        var natural=session.placement(PreviewIsolation.Placement.DIMENSION_SETTINGS);check(natural.isolationRequest().nativeFields().isEmpty() && natural.coordinates().equals(biome.coordinates()) && natural.framing().equals(biome.framing()),"dimension-settings choice applies no focus overrides and preserves coordinates/framing");
        check(natural.title().equals(PreviewSession.Kind.DIMENSION.title(natural.mode())) && biome.title().equals(PreviewSession.Kind.BIOME.title(biome.mode())),"content labels distinguish dimension settings from forced biome output");
        check(PreviewIsolation.help(true,natural.placement()).contains("stored focus") && PreviewIsolation.help(true,natural.placement()).contains("may not occur"),"dimension settings never claims natural placement when the pack stores focus");
        session.mode(PreviewSession.Mode.THREE_D);check(session.select(pack,"biomes/nested/a.json").placement()==natural.placement(),"placement survives 2D/3D switching");session.dimension("dimensions/two.json");check(session.select(pack,"biomes/nested/a.json").placement()==natural.placement(),"resource placement choice is independent of selected dimension");session.dimension("dimensions/one.json");
        var region=session.select(pack,"regions/r.json");check(region.placement()==PreviewIsolation.Placement.ISOLATED && region.isolationRequest().nativeFields().equals(Map.of("focus","","focusRegion","r")),"region isolation clears stored biome focus so native priority cannot silently defeat it");
        check(PreviewIsolation.help(false,region.placement()).contains("native biome distribution"),"region focus retains native biome selection");
        check(session.select(pack,"biomes/b.json").placement()==PreviewIsolation.Placement.ISOLATED && session.select(pack,"biomes/nested/a.json").placement()==natural.placement(),"resource navigation retains independent choices");
        for(String path:List.of("dimensions/one.json","generators/a.json","expressions/a.json","structures/a.json","objects/a.iob","images/a.png","images/missing.png")){
            var other=session.select(pack,path);check(!other.isolationAvailable() && other.isolationRequest()==null,"unrelated resources cannot borrow stale isolation: "+path);rejects(()->session.placement(PreviewIsolation.Placement.ISOLATED));
        }
        check(pack.revision()==revision && pack.resourceRevision()==resources && pack.saveRevision()==saved && pack.undoCount()==history && drafts.any(),"isolation choice never edits pack revisions/history/drafts");for(String path:files.keySet())check(Arrays.equals(pack.read(path),files.get(path)),"original source preserved: "+path);
        session.select(pack,"biomes/nested/a.json");String renamed=ResourceOperationTests.apply(pack,ResourceOperation.Mode.RENAME,"biomes/nested/a.json","renamed");session.resourceApplied(pack,"biomes/nested/a.json",renamed);
        check(session.select(pack,renamed).placement()==natural.placement(),"applied biome rename transfers its UI choice");
        session.resourceApplied(pack,renamed,"biomes/missing.json");check(session.select(pack,renamed).placement()==natural.placement(),"unapplied file operation cannot discard an isolation choice");
        var isolated=session.placement(PreviewIsolation.Placement.ISOLATED);check(isolated.isolationRequest().resourceKey().equals("nested/renamed"),"renamed exact identity feeds the request rather than the previous key");
        session.placement(PreviewIsolation.Placement.DIMENSION_SETTINGS);String moved=ResourceOperationTests.apply(pack,ResourceOperation.Mode.MOVE,renamed,"biomes");session.resourceApplied(pack,renamed,moved);renamed=moved;
        check(session.select(pack,renamed).placement()==natural.placement() && session.select(pack,renamed).isolationRequest().resourceKey().equals("renamed"),"applied move retains choice and updates the complete key");
        session.select(pack,"regions/r.json");session.placement(PreviewIsolation.Placement.DIMENSION_SETTINGS);String regionName=ResourceOperationTests.apply(pack,ResourceOperation.Mode.RENAME,"regions/r.json","region");session.resourceApplied(pack,"regions/r.json",regionName);
        check(session.select(pack,regionName).placement()==natural.placement(),"region rename retains its independent choice");session.select(pack,renamed);
        String dimension=ResourceOperationTests.apply(pack,ResourceOperation.Mode.RENAME,"dimensions/one.json","context");session.resourceApplied(pack,"dimensions/one.json",dimension);
        check(session.select(pack,renamed).isolationRequest().dimension().equals(dimension) && session.select(pack,renamed).coordinates().seed()==Long.MIN_VALUE,"dimension rename retains placement and exact location");
        session.placement(PreviewIsolation.Placement.DIMENSION_SETTINGS);pack.replace("biomes/b.json",bytes("{\"name\":\"edited\"}"));pack.undo();check(session.select(pack,renamed).placement()==natural.placement(),"ordinary edits/Undo retain UI-only choice");
        session.select(pack,"biomes/b.json");session.placement(PreviewIsolation.Placement.DIMENSION_SETTINGS);ResourceOperationTests.apply(pack,ResourceOperation.Mode.DELETE,"biomes/b.json",null);session.resourceApplied(pack,"biomes/b.json",null);pack.duplicate(renamed,"b");
        check(session.select(pack,"biomes/b.json").placement()==PreviewIsolation.Placement.ISOLATED,"deleted resource choices cannot reappear when a path is reused");session.select(pack,renamed);
        var captured=new LinkedHashMap<String,byte[]>();for(String path:pack.paths())captured.put(path,pack.read(path));
        pack.save(temp.resolve("isolation-save"));pack.saveCopy(temp.resolve("isolation-copy"));pack.saveAs(temp.resolve("isolation-as"));pack.exportZip(temp.resolve("isolation.zip"));
        for(var reopened:List.of(PackWorkspace.open(temp.resolve("isolation-save")),PackWorkspace.open(temp.resolve("isolation-copy")),PackWorkspace.open(temp.resolve("isolation-as")),PackWorkspace.importZip(temp.resolve("isolation.zip")))){
            check(reopened.paths().equals(pack.paths()),"no isolation override files saved");for(String path:captured.keySet())check(Arrays.equals(reopened.read(path),captured.get(path)),"Save/Copy/As/ZIP includes only independently applied pack edits: "+path);
        }
        check(session.select(pack,renamed).placement()==natural.placement() && drafts.any(),"persistence retains preview choice and unrelated drafts");
        var replacement=PackWorkspace.newPack(captured);var fresh=session.select(replacement,renamed);check(fresh.placement()==PreviewIsolation.Placement.ISOLATED,"replacement pack with the same paths cannot inherit choices");session.dimension(dimension);session.placement(PreviewIsolation.Placement.DIMENSION_SETTINGS);session.resourceApplied(pack,renamed,null);check(session.select(replacement,renamed).placement()==natural.placement(),"foreign operation receipt cannot clear current choice");
        session.clear();check(session.select(replacement,renamed).placement()==PreviewIsolation.Placement.ISOLATED,"clear releases placement state");
        rejects(()->new PreviewIsolation(PreviewIsolation.Placement.ISOLATED,dimension,"images","a",Map.of()));
        rejects(()->new PreviewIsolation(PreviewIsolation.Placement.DIMENSION_SETTINGS,dimension,"biomes","a",Map.of("focus","a")));
        System.out.println("PASS: UI-only selected biome/region isolation versus stored dimension settings, exact typed native focus requests, competing focus clearing, immutable descriptors, audited LAND/compatibility/buffet guidance, per-resource navigation/rename/owner isolation and byte-exact persistence without overrides");
    }
}
