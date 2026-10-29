package dev.irisvisualizer.editor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

final class StructureMaterialTests {
    private static final String PATH="structures/nested/materials.json";
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static String text(byte[] raw) { return new String(raw,StandardCharsets.UTF_8); }
    private static LosslessJson root(byte[] raw) { return new LosslessJson(raw); }
    private static void check(boolean ok,String detail) { if (!ok) throw new AssertionError("Structure materials: "+detail); }
    private static void rejects(Runnable action) { try { action.run(); } catch (IllegalArgumentException expected) { return; }throw new AssertionError("Expected rejected structure material operation"); }
    static void run(Path temp) throws Exception {
        var owner=FloatingObjectReplacements.Context.structureRoot();var palette=owner.palette(0);var target=palette.target();
        byte[] empty=bytes("{}");
        check(owner.resourceBase().equals("structures") && owner.prefix().equals("structureEdit#") && palette.stylePath().equals("edit.0.replace.style") && !palette.stilt(),"unambiguous root context");
        check(FloatingObjectPalettes.styleContext("structures",palette.stylePath()).equals(palette) && FloatingObjectPalettes.styleContext("dimensions",palette.stylePath())==null,"exact style routing");
        for (String path : List.of("edit.-1.replace.style","edit.01.replace.style","edit.2147483648.replace.style","staticObjects.0.edit.0.replace.style","stiltSettings.palette.style")) check(FloatingObjectPalettes.styleContext("structures",path)==null,"invalid root style path");
        rejects(() -> new FloatingObjectReplacements.Context(1,null,0,null,true));rejects(() -> new FloatingObjectReplacements.Context(0,"objects",0,null,true));rejects(() -> new FloatingObjectReplacements.Context(0,null,0,new StaticObjects.Context(0),true));rejects(() -> new FloatingObjectPalettes.Context(0,null,0,-1,null,true));
        check(FloatingObjectReplacements.rules(root(empty),owner)==null && Arrays.equals(root(empty).sourceBytes(),empty),"viewing preserves implicit rules");
        byte[] enabled=FloatingObjectReplacements.enable(PATH,empty,owner);check(root(enabled).objectArray("edit").isEmpty(),"explicit empty override");
        byte[] added=FloatingObjectReplacements.change(PATH,enabled,owner,RiverProfiles.Action.ADD,-1,"minecraft:stone");var rule=root(added).objectArray("edit").getFirst();
        check(rule.fields().equals(java.util.Set.of("find")) && rule.objectArray("find").getFirst().stringToken("block").equals("minecraft:stone"),"new rule retains native palette/chance/exact defaults");
        byte[] chance=FloatingObjectReplacements.enableChance(PATH,added,owner,0);check(root(chance).objectArray("edit").getFirst().token("chance").equals("1"),"chance default");
        for (String value : List.of("0","1","0.25")) FloatingObjectReplacements.setChance(PATH,chance,owner,0,value);
        for (String value : List.of("-0.1","1.01","NaN","Infinity","bad")) rejects(() -> FloatingObjectReplacements.setChance(PATH,chance,owner,0,value));
        byte[] exact=FloatingObjectReplacements.enableExact(PATH,added,owner,0);check(root(FloatingObjectReplacements.setExact(PATH,exact,owner,0,true)).objectArray("edit").getFirst().token("exact").equals("true"),"exact matching flag");
        byte[] created=FloatingObjectPalettes.create(PATH,added,palette);byte[] entries=target.enableEntries(PATH,created);check(FloatingObjectPalettes.object(root(entries),palette).objectArray("palette").getFirst().stringToken("block").equals("STONE"),"class replacement palette seed");
        byte[] weighted=target.enableWeight(PATH,entries,0);check(FloatingObjectPalettes.object(root(target.setWeight(PATH,weighted,0,"1000")),palette).objectArray("palette").getFirst().token("weight").equals("1000"),"shared weights");rejects(() -> target.setWeight(PATH,weighted,0,"1001"));
        byte[] zoom=target.enableZoom(PATH,entries);check(FloatingObjectPalettes.object(root(zoom),palette).token("zoom").equals("5"),"palette zoom default");target.setZoom(PATH,zoom,"0.0001");rejects(() -> target.setZoom(PATH,zoom,"0"));
        byte[] state=BlockDefinitions.createData(PATH,added,InlineBlockData.Context.find(owner,0,0).scope());check(root(state).objectArray("edit").getFirst().objectArray("find").getFirst().token("data").equals("{}"),"find block details route to structure");
        byte[] withFind=FloatingObjectReplacements.changeFind(PATH,added,owner,0,RiverProfiles.Action.ADD,-1,"minecraft:oak_log[axis=y]");check(root(withFind).objectArray("edit").getFirst().objectArray("find").size()==2,"find list append");
        rejects(() -> FloatingObjectReplacements.changeFind(PATH,added,owner,0,RiverProfiles.Action.REMOVE,0,null));
        byte[] movedFind=FloatingObjectReplacements.changeFind(PATH,withFind,owner,0,RiverProfiles.Action.DOWN,0,null);check(Arrays.equals(withFind,FloatingObjectReplacements.changeFind(PATH,movedFind,owner,0,RiverProfiles.Action.UP,1,null)),"find reorder reverses exactly");
        String rawRule="{\"find\":[{\"block\":\"minecraft:st\\u006fne\",\"weight\":99,\"futureFind\":9007199254740993}],\"replace\":{\"palette\":[{\"block\":\"STONE\",\"weight\":1e0}],\"zoom\":5e0,\"style\":{\"style\":\"UNKNOWN\",\"expression\":\"x + z\"},\"futurePalette\":true},\"exact\":false,\"chance\":1e0,\"futureRule\":\"雪\\u0061\"}";
        String original="{\r\n \"edit\":[ "+rawRule+",\r\n {} ],\"vanillaSource\":\"minecraft:vill\\u0061ge\",\"loot\":[\"untouched/loot\"],\"future\":9007199254740993\r\n}";byte[] raw=bytes(original);
        check(text(FloatingObjectReplacements.setChance(PATH,raw,owner,0,"0.5")).equals(original.replace("\"chance\":1e0","\"chance\":0.5")),"one-span chance write");
        check(text(FloatingObjectReplacements.setFindBlock(PATH,raw,owner,0,0,"minecraft:dirt")).equals(original.replace("minecraft:st\\u006fne","minecraft:dirt")),"one-span block write");
        byte[] duplicate=FloatingObjectReplacements.change(PATH,raw,owner,RiverProfiles.Action.DUPLICATE,0,null);check(root(duplicate).objectArray("edit").size()==3 && Arrays.equals(root(duplicate).objectArray("edit").get(1).sourceBytes(),bytes(rawRule)),"unknown rule fields copied exactly");
        byte[] moved=FloatingObjectReplacements.change(PATH,raw,owner,RiverProfiles.Action.DOWN,0,null);check(Arrays.equals(raw,FloatingObjectReplacements.change(PATH,moved,owner,RiverProfiles.Action.UP,1,null)),"raw rule order reverses exactly");
        byte[] removed=FloatingObjectReplacements.change(PATH,added,owner,RiverProfiles.Action.REMOVE,0,null);check(root(removed).objectArray("edit").isEmpty(),"last rule may be removed");
        for (String token : List.of("null","true","{}","\"snippet/edit\"","[null]","[\"snippet/rule\"]")) {
            byte[] invalid=bytes("{\"edit\":"+token+",\"vanillaSource\":\"mod:old\"}");rejects(() -> FloatingObjectReplacements.enable(PATH,invalid,owner));rejects(() -> FloatingObjectReplacements.change(PATH,invalid,owner,RiverProfiles.Action.ADD,-1,"stone"));rejects(() -> FloatingObjectPalettes.create(PATH,invalid,palette));
            check(root(StructureDefinitions.setVanillaSource(PATH,invalid,"mod:new")).token("edit").equals(token),"source repair independent of unsupported rules");
        }
        byte[] full=bytes("{\"edit\":["+"{},".repeat(63)+"{}]}");check(FloatingObjectReplacements.rules(root(full),owner).size()==64,"64-rule limit");rejects(() -> FloatingObjectReplacements.change(PATH,full,owner,RiverProfiles.Action.ADD,-1,"stone"));rejects(() -> FloatingObjectReplacements.change(PATH,full,owner,RiverProfiles.Action.DUPLICATE,0,null));
        byte[] over=bytes("{\"edit\":["+"{},".repeat(64)+"{}]}");rejects(() -> FloatingObjectReplacements.rules(root(over),owner));rejects(() -> target.enableEntries(PATH,over));
        check(StructureDefinitions.vanillaSource(root(empty)).isEmpty() && Arrays.equals(StructureDefinitions.resetVanillaSource(PATH,empty),empty),"implicit empty source");
        check(StructureDefinitions.sourceHelp().contains("provenance") && StructureDefinitions.sourceAdvisory(root(empty)).contains("REPLACE_SOURCE"),"native generation semantics remain visible");
        for (String key : List.of("","minecraft:village/plains","mod.name-1_:path/with.dots_-","a:"+"b".repeat(254))) check(StructureDefinitions.vanillaSource(root(StructureDefinitions.setVanillaSource(PATH,empty,key))).equals(key),"source input "+key.length());
        for (String key : List.of("Minecraft:village","minecraft:Village","village",":village","minecraft:"," minecraft:village","minecraft:village ","a:b c","a:b\\c","a:b\n","a:b雪","a:"+"b".repeat(255))) rejects(() -> StructureDefinitions.setVanillaSource(PATH,raw,key));rejects(() -> StructureDefinitions.setVanillaSource(PATH,raw,null));
        check(Arrays.equals(StructureDefinitions.setVanillaSource(PATH,raw,"minecraft:village"),raw),"escaped equivalent source token retained");
        check(text(StructureDefinitions.setVanillaSource(PATH,raw,"mod:custom")).equals(original.replace("minecraft:vill\\u0061ge","mod:custom")),"source-only span write");
        byte[] noSource=StructureDefinitions.resetVanillaSource(PATH,raw);check(root(noSource).token("vanillaSource")==null && root(noSource).token("edit").equals(root(raw).token("edit")),"source reset leaves rules exact");
        byte[] legacy=bytes("{\"vanillaSource\":\"LEGACY alias\"}");check(StructureDefinitions.vanillaSource(root(legacy)).equals("LEGACY alias") && StructureDefinitions.sourceAdvisory(root(legacy)).contains("preserved"),"legacy source readable and repairable");StructureDefinitions.setVanillaSource(PATH,legacy,"mod:repair");
        for (String token : List.of("null","true","1","[]","{}")) { byte[] invalid=bytes("{\"vanillaSource\":"+token+"}");rejects(() -> StructureDefinitions.setVanillaSource(PATH,invalid,"mod:repair"));rejects(() -> StructureDefinitions.resetVanillaSource(PATH,invalid));check(StructureDefinitions.sourceAdvisory(root(invalid)).contains("read-only"),"unsupported source remains read-only"); }
        for (String path : List.of("dimensions/a.json","biomes/a.json","jigsaw-pieces/a.json","structures/a.JSON","structures/../a.json")) { rejects(() -> StructureDefinitions.setVanillaSource(path,raw,"mod:custom"));rejects(() -> StructureDefinitions.resetVanillaSource(path,raw));rejects(() -> FloatingObjectReplacements.enable(path,empty,owner));rejects(() -> FloatingObjectReplacements.setChance(path,raw,owner,0,"0.5"));rejects(() -> target.enableEntries(path,created));rejects(() -> BlockDefinitions.createData(path,added,InlineBlockData.Context.find(owner,0,0).scope())); }
        // Indexed rule changes guard their own drafts. Source changes retain independent forms/history.
        var pack=PackWorkspace.newPack(Map.of(PATH,raw,"objects/raw.iob",new byte[]{0,-1,9},"future/malformed.json",bytes("not JSON"),"jigsaw-pieces/piece.json",bytes("{\"future\":true}")));var drafts=new InspectorDrafts();String chanceDraft=owner.rulePrefix(0)+"chance",sourceDraft="structureSource#vanillaSource",themeDraft=StructureThemes.prefix(0)+"weight";drafts.update(chanceDraft,"1e0","0.5");drafts.update(sourceDraft,"minecraft:village","mod:new");drafts.update(themeDraft,"1","2");
        check(!drafts.transitionUnder(owner.prefix(),() -> false,() -> pack.replace(PATH,moved)) && Arrays.equals(pack.read(PATH),raw) && drafts.count()==3,"rule move cancellation");
        try { drafts.transitionUnder(owner.prefix(),() -> true,() -> pack.replace(PATH,FloatingObjectReplacements.setChance(PATH,raw,owner,0,"NaN")));throw new AssertionError(); } catch (IllegalArgumentException expected) { check(drafts.count()==3 && pack.undoCount()==0,"failed edit retains drafts/history"); }
        drafts.transitionUnder(owner.prefix(),() -> true,() -> pack.replace(PATH,moved));check(drafts.count()==2 && drafts.contains(sourceDraft) && drafts.contains(themeDraft) && pack.undoCount()==1,"rule move clears indexed material drafts only");pack.undo();check(Arrays.equals(pack.read(PATH),raw),"exact undo");pack.redo();check(Arrays.equals(pack.read(PATH),moved),"exact redo");
        pack.replace(PATH,StructureDefinitions.setVanillaSource(PATH,pack.read(PATH),""));check(StructureDefinitions.sourceAdvisory(root(pack.read(PATH))).contains("REPLACE_SOURCE"),"advisory never prevents empty source storage");
        pack.save(temp.resolve("structure-materials"));pack.saveCopy(temp.resolve("structure-materials-copy"));pack.saveAs(temp.resolve("structure-materials-as"));pack.exportZip(temp.resolve("structure-materials.zip"));
        for (var reopened : List.of(PackWorkspace.open(temp.resolve("structure-materials")),PackWorkspace.open(temp.resolve("structure-materials-copy")),PackWorkspace.open(temp.resolve("structure-materials-as")),PackWorkspace.importZip(temp.resolve("structure-materials.zip")))) for (String path : pack.paths()) check(Arrays.equals(pack.read(path),reopened.read(path)),"exact persistence "+path);
        check(FieldCoverage.fields("structureDefinition").stream().filter(f -> f.classification().equals("generation")).allMatch(FieldCoverage.Field::implemented),"all bounded direct structure generation fields authored");
        System.out.println("PASS: structure-wide replacement/default/palette/block-data routing, ordered raw-copy/move rules and limits, exact namespaced source aliases with REPLACE_SOURCE advisories, malformed/owner isolation, scoped drafts, Undo/Redo and byte-exact Save/Copy/As/ZIP without native matching or suppression evaluation");
    }
}
