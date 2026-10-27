package dev.irisvisualizer.editor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class StaticObjectMaterialTests {
    private static final String PATH="dimensions/static-materials.json";
    private static final StaticObjects.Context FIXED=new StaticObjects.Context(0);
    private static final FloatingObjectReplacements.Context RULES=FIXED.replacements();
    private static final FloatingObjectPalettes.Context PALETTE=RULES.palette(0);
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static String text(byte[] value) { return new String(value,StandardCharsets.UTF_8); }
    private static void check(boolean condition) { if (!condition) throw new AssertionError("Fixed object material contract failed"); }
    private static void rejects(Runnable action) { try { action.run(); } catch (IllegalArgumentException expected) { return; }throw new AssertionError("Expected rejected fixed object material operation"); }
    private static byte[] document(String entry) { return bytes("{\r\n \"staticObjects\":[ "+entry+", {\"object\":\"sibling\",\"edit\":[{\"futureSiblingRule\":1e2}]} ],\"allObjectScaleFactor\":2e0,\"futureRoot\":9007199254740993,\"escaped\":\"雪\\u0061\"\r\n}\r\n"); }
    private static LosslessJson entry(byte[] value) { return StaticObjects.entries(new LosslessJson(value)).getFirst(); }
    private static List<LosslessJson> rules(byte[] value) { return FloatingObjectReplacements.rules(entry(value)); }
    private static LosslessJson rule(byte[] value) { return rules(value).getFirst(); }
    private static LosslessJson palette(byte[] value) { return FloatingObjectPalettes.object(entry(value),PALETTE); }
    static void run(Path temp) throws Exception {
        var target=PALETTE.target();var style=target.styleTarget();
        check(RULES.resourceBase().equals("dimensions") && PALETTE.resourceBase().equals("dimensions") && !PALETTE.stilt());
        check(RULES.prefix().equals(FIXED.prefix()+"edit#") && PALETTE.stylePath().equals("staticObjects.0.edit.0.replace.style"));
        check(style.resourceBase().equals("dimensions") && style.objectPaletteContext().equals(PALETTE) && style.draftPrefix().equals(target.noisePrefix()+"style."));
        check(new NoiseStyleFields.Target("dimensions",PALETTE.stylePath(),null).equals(style));
        byte[] absent=document("{\"object\":\"tree\",\"position\":{\"x\":1,\"y\":64,\"z\":2},\"seed\":9007199254740993}");
        check(rules(absent)==null);byte[] enabled=FloatingObjectReplacements.enable(PATH,absent,RULES);check(rules(enabled).isEmpty());rejects(() -> FloatingObjectReplacements.enable(PATH,enabled,RULES));
        byte[] added=FloatingObjectReplacements.change(PATH,enabled,RULES,RiverProfiles.Action.ADD,-1,"minecraft:oak_log[axis=y]");
        check(rule(added).fields().equals(Set.of("find")) && FloatingObjectReplacements.find(rule(added)).getFirst().stringToken("block").equals("minecraft:oak_log[axis=y]"));
        check(rules(FloatingObjectReplacements.change(PATH,added,RULES,RiverProfiles.Action.REMOVE,0,null)).isEmpty());rejects(() -> FloatingObjectReplacements.change(PATH,enabled,RULES,RiverProfiles.Action.ADD,-1," "));
        byte[] exact=FloatingObjectReplacements.enableExact(PATH,added,RULES,0);check(rule(exact).token("exact").equals("false"));check(rule(FloatingObjectReplacements.setExact(PATH,exact,RULES,0,true)).token("exact").equals("true"));
        rejects(() -> FloatingObjectReplacements.setExact(PATH,added,RULES,0,true));rejects(() -> FloatingObjectReplacements.enableExact(PATH,exact,RULES,0));
        byte[] chance=FloatingObjectReplacements.enableChance(PATH,added,RULES,0);check(rule(chance).token("chance").equals("1"));
        for (String value : List.of("0","0.25","1")) check(rule(FloatingObjectReplacements.setChance(PATH,chance,RULES,0,value)).token("chance").equals(value));
        for (String bad : List.of("-0.01","1.01","NaN","Infinity")) rejects(() -> FloatingObjectReplacements.setChance(PATH,chance,RULES,0,bad));
        rejects(() -> FloatingObjectReplacements.setChance(PATH,added,RULES,0,"0.5"));
        byte[] emptyRule=document("{\"edit\":[{}]}");byte[] finds=FloatingObjectReplacements.enableFind(PATH,emptyRule,RULES,0);check(FloatingObjectReplacements.find(rule(finds)).isEmpty());
        byte[] found=FloatingObjectReplacements.changeFind(PATH,finds,RULES,0,RiverProfiles.Action.ADD,-1,"STONE");
        rejects(() -> FloatingObjectReplacements.changeFind(PATH,found,RULES,0,RiverProfiles.Action.REMOVE,0,null));
        check(FloatingObjectReplacements.find(rule(FloatingObjectReplacements.changeFind(PATH,found,RULES,0,RiverProfiles.Action.DUPLICATE,0,null))).size()==2);
        check(FloatingObjectReplacements.find(rule(FloatingObjectReplacements.setFindBlock(PATH,found,RULES,0,0,"minecraft:stone"))).getFirst().stringToken("block").equals("minecraft:stone"));
        rejects(() -> FloatingObjectReplacements.setFindBlock(PATH,found,RULES,0,1,"STONE"));
        check(palette(added)==null);byte[] created=FloatingObjectPalettes.create(PATH,added,PALETTE);check(palette(created).fields().isEmpty());
        rejects(() -> FloatingObjectPalettes.create(PATH,created,PALETTE));rejects(() -> FloatingObjectPalettes.restoreStilt(PATH,created,PALETTE));
        rejects(() -> target.enableEntries(PATH,added));rejects(() -> NoiseStyleFields.enable(PATH,added,style,"style"));
        byte[] entries=target.enableEntries(PATH,created);check(palette(entries).objectArray("palette").getFirst().stringToken("block").equals("STONE"));
        byte[] weight=target.enableWeight(PATH,entries,0);check(palette(weight).objectArray("palette").getFirst().token("weight").equals("1"));
        for (String valid : List.of("1","1000","1e3")) target.setWeight(PATH,weight,0,valid);
        for (String bad : List.of("0","1001","1.000000000000000001","2147483648","NaN")) rejects(() -> target.setWeight(PATH,weight,0,bad));
        rejects(() -> target.change(PATH,entries,RiverProfiles.Action.REMOVE,0,null));
        byte[] duplicate=target.change(PATH,weight,RiverProfiles.Action.DUPLICATE,0,null);check(palette(duplicate).objectArray("palette").size()==2);
        check(palette(target.change(PATH,duplicate,RiverProfiles.Action.REMOVE,1,null)).objectArray("palette").size()==1);
        byte[] extra=target.change(PATH,entries,RiverProfiles.Action.ADD,-1,"minecraft:dirt");byte[] moved=target.change(PATH,extra,RiverProfiles.Action.DOWN,0,null);check(Arrays.equals(extra,target.change(PATH,moved,RiverProfiles.Action.UP,1,null)));
        byte[] zoom=target.enableZoom(PATH,created);check(palette(zoom).token("zoom").equals("5"));target.setZoom(PATH,zoom,"0.0001");
        for (String bad : List.of("0","0.00009","NaN","Infinity")) rejects(() -> target.setZoom(PATH,zoom,bad));
        byte[] styled=NoiseStyleFields.enable(PATH,created,style,"style");check(palette(styled).stringPath("style.style").equals("STATIC"));
        for (String choice : NoiseStyleFields.choices()) check(palette(NoiseStyleFields.editStyle(PATH,styled,style,choice)).stringPath("style.style").equals(choice));
        for (String member : NoiseStyleFields.EDITABLE) if (!member.equals("style")) {
            byte[] scalar=NoiseStyleFields.enable(PATH,styled,style,member);NoiseStyleFields.editText(PATH,scalar,style,member,member.equals("cellularFrequency") ? "0" : "1");
            for (String bad : List.of("NaN","Infinity")) rejects(() -> NoiseStyleFields.editText(PATH,scalar,style,member,bad));
        }
        byte[] inline=document("{\"edit\":[{\"replace\":{\"style\":{}}}]}");check(palette(NoiseStyleFields.enable(PATH,inline,style,"style")).stringPath("style.style").equals("FLAT"));
        byte[] cellular=document("{\"edit\":[{\"replace\":{\"style\":{\"cellularFrequency\":0,\"cellularZoom\":0}}}]}");
        rejects(() -> NoiseStyleFields.editText(PATH,cellular,style,"cellularFrequency","1"));
        byte[] cellularReady=NoiseStyleFields.editText(PATH,cellular,style,"cellularZoom","1");byte[] active=NoiseStyleFields.editText(PATH,cellularReady,style,"cellularFrequency","1");rejects(() -> NoiseStyleFields.editText(PATH,active,style,"cellularZoom","0"));
        for (String bad : List.of("null","[]","true","\"snippet/palette\"")) {
            byte[] invalid=document("{\"edit\":[{\"replace\":"+bad+"}]}");rejects(() -> palette(invalid));rejects(() -> FloatingObjectPalettes.create(PATH,invalid,PALETTE));rejects(() -> target.enableEntries(PATH,invalid));rejects(() -> NoiseStyleFields.enable(PATH,invalid,style,"style"));
        }
        for (String bad : List.of("null","{}","[null]","[{},1]","[\"snippet/rule\"]")) {
            byte[] invalid=document("{\"edit\":"+bad+"}");rejects(() -> rules(invalid));rejects(() -> FloatingObjectReplacements.enable(PATH,invalid,RULES));rejects(() -> FloatingObjectReplacements.change(PATH,invalid,RULES,RiverProfiles.Action.ADD,-1,"STONE"));rejects(() -> FloatingObjectPalettes.create(PATH,invalid,PALETTE));
        }
        for (String bad : List.of("null","[]","[null]","[1]","[\"snippet/find\"]")) {
            byte[] invalid=document("{\"edit\":[{\"find\":"+bad+"}]}");rejects(() -> FloatingObjectReplacements.enableFind(PATH,invalid,RULES,0));rejects(() -> FloatingObjectReplacements.setFindBlock(PATH,invalid,RULES,0,0,"STONE"));
        }
        for (String bad : List.of("null","[]","true","\"1\"")) {
            byte[] invalid=document("{\"edit\":[{\"chance\":"+bad+"}]}");rejects(() -> FloatingObjectReplacements.setChance(PATH,invalid,RULES,0,"0.5"));
            if (!bad.equals("true")) rejects(() -> FloatingObjectReplacements.setExact(PATH,document("{\"edit\":[{\"exact\":"+bad+"}]}"),RULES,0,true));
            if (!bad.equals("\"1\"")) rejects(() -> FloatingObjectReplacements.setFindBlock(PATH,document("{\"edit\":[{\"find\":[{\"block\":"+bad+"}]}]}"),RULES,0,0,"STONE"));
        }
        for (String parent : List.of("{}","{\"staticObjects\":[]}","{\"staticObjects\":null}","{\"staticObjects\":[null]}")) {
            byte[] invalid=bytes(parent);rejects(() -> FloatingObjectReplacements.enable(PATH,invalid,RULES));rejects(() -> FloatingObjectPalettes.create(PATH,invalid,PALETTE));rejects(() -> NoiseStyleFields.enable(PATH,invalid,style,"style"));
        }
        String rawRule="{\"find\":[{\"block\":\"legacy\\u0061\",\"weight\":999,\"data\":{\"axis\":\"y\"}},{\"block\":\"STONE\"}],\"replace\":{\"palette\":[{\"block\":\"STONE\",\"weight\":1e0,\"futureBlock\":9007199254740993}],\"zoom\":5e0,\"style\":{\"style\":\"UNKNOWN\",\"expression\":\"x + z\"},\"futurePalette\":true},\"exact\":false,\"chance\":1e0,\"futureRule\":\"雪\\u0061\"}";
        byte[] original=document("{\"object\":\"legacy/key\",\"position\":{\"x\":1e2,\"y\":64,\"z\":-2},\"rotation\":{\"y\":22.5},\"scale\":0.5,\"seed\":9007199254740993,\"edit\":[ "+rawRule+",\r\n {} ],\"futureEntry\":9007199254740993}"),copy=original.clone();
        check(text(FloatingObjectReplacements.setFindBlock(PATH,original,RULES,0,0,"minecraft:oak_log")).equals(text(original).replace("\"legacy\\u0061\"","\"minecraft:oak_log\"")));
        check(text(FloatingObjectReplacements.setChance(PATH,original,RULES,0,"0.25")).equals(text(original).replace("\"chance\":1e0","\"chance\":0.25")));
        check(text(target.setWeight(PATH,original,0,"2")).equals(text(original).replace("\"weight\":1e0","\"weight\":2")));
        byte[] duplicateRule=FloatingObjectReplacements.change(PATH,original,RULES,RiverProfiles.Action.DUPLICATE,0,null);check(Arrays.equals(rules(duplicateRule).get(0).sourceBytes(),rules(duplicateRule).get(1).sourceBytes()));
        byte[] movedRule=FloatingObjectReplacements.change(PATH,original,RULES,RiverProfiles.Action.DOWN,0,null);check(Arrays.equals(original,FloatingObjectReplacements.change(PATH,movedRule,RULES,RiverProfiles.Action.UP,1,null)));
        byte[] movedFind=FloatingObjectReplacements.changeFind(PATH,original,RULES,0,RiverProfiles.Action.DOWN,0,null);check(Arrays.equals(original,FloatingObjectReplacements.changeFind(PATH,movedFind,RULES,0,RiverProfiles.Action.UP,1,null)));
        byte[] rawFind=FloatingObjectReplacements.changeFind(PATH,original,RULES,0,RiverProfiles.Action.DUPLICATE,0,null);check(FloatingObjectReplacements.find(rule(rawFind)).get(1).token("weight").equals("999"));
        check(FloatingObjectPalettes.readOnlyPaths(entry(original),PALETTE).containsAll(List.of("futurePalette")));
        for (String wrong : List.of("biomes/static.json","regions/static.json","generators/static.json","dimensions/nested/static.json","dimensions/static.txt","static.json")) rejectOwner(wrong,original);
        for (String invalid : List.of("staticObjects.00.edit.0.replace.style","staticObjects.-1.edit.0.replace.style","staticObjects.0.edit.2147483648.replace.style","staticObjects.2147483648.edit.0.replace.style","staticObjects.0.stiltSettings.palette.style","staticObjects.0.edit.0.replace.style.extra")) rejects(() -> new NoiseStyleFields.Target("dimensions",invalid,null));
        rejects(() -> new NoiseStyleFields.Target("biomes",PALETTE.stylePath(),null));rejects(() -> new NoiseStyleFields.Target("regions",PALETTE.stylePath(),null));
        rejects(() -> new FloatingObjectReplacements.Context(1,null,0,FIXED));rejects(() -> new FloatingObjectReplacements.Context(0,"objects",0,FIXED));rejects(() -> new FloatingObjectReplacements.Context(0,null,1,FIXED));rejects(() -> RULES.palette(-1));
        rejects(() -> new FloatingObjectPalettes.Context(0,null,0,-1,FIXED));rejects(() -> new FloatingObjectReplacements.Context(0,null,0));
        rejects(() -> FloatingObjectReplacements.enable(PATH,original,new StaticObjects.Context(2).replacements()));rejects(() -> FloatingObjectPalettes.create(PATH,original,RULES.palette(2)));
        var sibling=new StaticObjects.Context(1).replacements();byte[] siblingEdit=FloatingObjectReplacements.enableChance(PATH,original,sibling,0);check(Arrays.equals(entry(original).sourceBytes(),entry(siblingEdit).sourceBytes()) && StaticObjects.entries(new LosslessJson(siblingEdit)).get(1).objectArray("edit").getFirst().token("chance").equals("1"));
        var biomeOwner=new FloatingObjectReplacements.Context(FloatingObjectPlacements.BIOME_ROOT,"objects",0);rejects(() -> FloatingObjectReplacements.setChance(PATH,original,biomeOwner,0,"0.5"));rejects(() -> FloatingObjectPalettes.create(PATH,original,biomeOwner.palette(0)));
        var drafts=new InspectorDrafts();String find=RULES.findPrefix(0)+"0.block",chanceKey=RULES.rulePrefix(0)+"chance",block=target.listPrefix()+"0.block",styleKey=style.draftPrefix()+"zoom",seed=FIXED.prefix()+"seed",other=new StaticObjects.Context(1).replacements().rulePrefix(0)+"chance",biome=new FloatingObjectReplacements.Context(FloatingObjectPlacements.BIOME_ROOT,"objects",0).prefix()+"0.chance";
        for (String key : List.of(find,chanceKey,block,styleKey,seed,other,biome)) drafts.update(key,"1","2");
        check(!drafts.transitionUnder(RULES.prefix(),() -> false,() -> { throw new AssertionError(); }) && drafts.count()==7);
        try { drafts.transitionUnder(RULES.prefix(),() -> true,() -> { throw new IOException("failed write"); });throw new AssertionError(); } catch (IOException expected) { check(drafts.count()==7); }
        check(drafts.transitionUnder(RULES.findPrefix(0),() -> true,() -> {}) && drafts.count()==6 && drafts.contains(block));
        check(drafts.transitionUnder(target.listPrefix(),() -> true,() -> {}) && drafts.count()==5 && drafts.contains(styleKey));
        check(drafts.transitionUnder(RULES.prefix(),() -> true,() -> {}) && drafts.count()==3 && drafts.contains(seed) && drafts.contains(other) && drafts.contains(biome));
        byte[] asset={0,127,-1,42};var saved=PackWorkspace.newPack(Map.of(PATH,original,"biomes/unchanged.json",bytes("{\"objects\":[{\"edit\":[]}] }"),"objects/opaque.iob",asset));
        saved.replace(PATH,FloatingObjectReplacements.setChance(PATH,saved.read(PATH),RULES,0,"0.5"));saved.replace(PATH,FloatingObjectReplacements.setExact(PATH,saved.read(PATH),RULES,0,true));saved.replace(PATH,FloatingObjectReplacements.setFindBlock(PATH,saved.read(PATH),RULES,0,0,"minecraft:oak_log[axis=y]"));
        saved.replace(PATH,target.setBlock(PATH,saved.read(PATH),0,"minecraft:birch_log[axis=y]"));saved.replace(PATH,target.setWeight(PATH,saved.read(PATH),0,"1000"));
        for (String token : List.of("\"futureRoot\":9007199254740993","\"futureEntry\":9007199254740993","\"futureSiblingRule\":1e2","\"seed\":9007199254740993","\"position\":{\"x\":1e2,\"y\":64,\"z\":-2}","\"futureBlock\":9007199254740993","\"expression\":\"x + z\"","\"data\":{\"axis\":\"y\"}","\"weight\":999","\"escaped\":\"雪\\u0061\"")) check(text(saved.read(PATH)).contains(token));
        saved.save(temp.resolve("fixed-object-materials"));saved.exportZip(temp.resolve("fixed-object-materials.zip"));var reopened=PackWorkspace.open(temp.resolve("fixed-object-materials"));var imported=PackWorkspace.importZip(temp.resolve("fixed-object-materials.zip"));
        for (String path : saved.paths()) check(Arrays.equals(saved.read(path),reopened.read(path)) && Arrays.equals(saved.read(path),imported.read(path)));
        check(Arrays.equals(original,copy) && Arrays.equals(saved.read("objects/opaque.iob"),asset));check(FieldCoverage.fields("staticObject").stream().allMatch(FieldCoverage.Field::implemented));check(FieldCoverage.groups(PATH).contains("objectReplace"));
        System.out.println("PASS: fixed object replacement rules, unweighted find lists, weighted palettes/all styles and contextual defaults, scalar/type/owner guards, exact static owner routing, lossless raw rules/assets, independent drafts and Save/ZIP");
    }
    private static void rejectOwner(String path,byte[] original) {
        var target=PALETTE.target();var style=target.styleTarget();
        rejects(() -> FloatingObjectReplacements.enable(path,document("{}"),RULES));rejects(() -> FloatingObjectReplacements.change(path,original,RULES,RiverProfiles.Action.DUPLICATE,0,null));rejects(() -> FloatingObjectReplacements.enableExact(path,original,RULES,0));rejects(() -> FloatingObjectReplacements.setExact(path,original,RULES,0,true));rejects(() -> FloatingObjectReplacements.enableChance(path,original,RULES,0));rejects(() -> FloatingObjectReplacements.setChance(path,original,RULES,0,"0.5"));rejects(() -> FloatingObjectReplacements.enableFind(path,original,RULES,0));rejects(() -> FloatingObjectReplacements.setFindBlock(path,original,RULES,0,0,"STONE"));rejects(() -> FloatingObjectReplacements.changeFind(path,original,RULES,0,RiverProfiles.Action.ADD,-1,"STONE"));
        rejects(() -> FloatingObjectPalettes.create(path,document("{\"edit\":[{}]}"),PALETTE));rejects(() -> FloatingObjectPalettes.restoreStilt(path,original,PALETTE));rejects(() -> target.enableEntries(path,original));rejects(() -> target.setBlock(path,original,0,"STONE"));rejects(() -> target.enableWeight(path,original,0));rejects(() -> target.setWeight(path,original,0,"2"));rejects(() -> target.change(path,original,RiverProfiles.Action.DUPLICATE,0,null));rejects(() -> target.enableZoom(path,original));rejects(() -> target.setZoom(path,original,"1"));
        rejects(() -> NoiseStyleFields.enable(path,original,style,"style"));rejects(() -> NoiseStyleFields.editStyle(path,original,style,"STATIC"));rejects(() -> NoiseStyleFields.editText(path,original,style,"zoom","1"));
    }
}
