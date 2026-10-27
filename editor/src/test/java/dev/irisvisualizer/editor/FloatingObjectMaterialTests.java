package dev.irisvisualizer.editor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class FloatingObjectMaterialTests {
    private static final String PATH="biomes/nested/materials.json";
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static String text(byte[] value) { return new String(value,StandardCharsets.UTF_8); }
    private static void check(boolean condition) { if (!condition) throw new AssertionError("Floating object material contract failed"); }
    private static void rejects(Runnable action) { try { action.run(); } catch (IllegalArgumentException expected) { return; }throw new AssertionError("Expected rejected object material operation"); }
    private static byte[] document(String list,String placement) { return bytes("{\r\n \"floatingChildBiomes\":[{\""+list+"\":["+placement+",{}],\"inheritObjects\":false},{}],\"future\":9007199254740993,\"escaped\":\"雪\\u0061\"\r\n}\r\n"); }
    private static LosslessJson placement(byte[] value,String list) { return FloatingObjectPlacements.placements(FloatingBiomes.entries(new LosslessJson(value)).getFirst(),list).getFirst(); }
    private static List<LosslessJson> rules(byte[] value,String list) { return FloatingObjectReplacements.rules(placement(value,list)); }
    private static LosslessJson rule(byte[] value,String list) { return rules(value,list).getFirst(); }
    private static LosslessJson palette(byte[] value,FloatingObjectPalettes.Context context) { return FloatingObjectPalettes.object(placement(value,context.list()),context); }
    static void run(Path temp) throws Exception {
        for (String list : FloatingObjectPlacements.LIST_FIELDS) {
            var context=new FloatingObjectReplacements.Context(0,list,0);byte[] absent=document(list,"{\"stiltSettings\":{},\"mode\":\"STILT\",\"futurePlacement\":9007199254740993}");
            check(rules(absent,list)==null);byte[] enabled=FloatingObjectReplacements.enable(PATH,absent,context);check(rules(enabled,list).isEmpty());
            rejects(() -> FloatingObjectReplacements.enable(PATH,enabled,context));
            byte[] added=FloatingObjectReplacements.change(PATH,enabled,context,RiverProfiles.Action.ADD,-1,"minecraft:oak_log[axis=y]");check(rule(added,list).fields().equals(Set.of("find")) && FloatingObjectReplacements.find(rule(added,list)).getFirst().stringToken("block").equals("minecraft:oak_log[axis=y]"));
            check(rules(FloatingObjectReplacements.change(PATH,added,context,RiverProfiles.Action.REMOVE,0,null),list).isEmpty());
            rejects(() -> FloatingObjectReplacements.change(PATH,enabled,context,RiverProfiles.Action.ADD,-1," "));
            byte[] exact=FloatingObjectReplacements.enableExact(PATH,added,context,0);check(rule(exact,list).token("exact").equals("false"));check(rule(FloatingObjectReplacements.setExact(PATH,exact,context,0,true),list).token("exact").equals("true"));
            rejects(() -> FloatingObjectReplacements.setExact(PATH,added,context,0,true));rejects(() -> FloatingObjectReplacements.enableExact(PATH,exact,context,0));
            byte[] chance=FloatingObjectReplacements.enableChance(PATH,added,context,0);check(rule(chance,list).token("chance").equals("1"));
            for (String valid : List.of("0","0.25","1")) check(rule(FloatingObjectReplacements.setChance(PATH,chance,context,0,valid),list).token("chance").equals(valid));
            for (String bad : List.of("-0.01","1.01","NaN","Infinity")) rejects(() -> FloatingObjectReplacements.setChance(PATH,chance,context,0,bad));
            rejects(() -> FloatingObjectReplacements.setChance(PATH,added,context,0,"0.5"));rejects(() -> FloatingObjectReplacements.enableChance(PATH,chance,context,0));
            byte[] emptyRule=document(list,"{\"stiltSettings\":{},\"edit\":[{}]}");byte[] finds=FloatingObjectReplacements.enableFind(PATH,emptyRule,context,0);check(FloatingObjectReplacements.find(rule(finds,list)).isEmpty());
            rejects(() -> FloatingObjectReplacements.enableFind(PATH,finds,context,0));
            byte[] one=FloatingObjectReplacements.changeFind(PATH,finds,context,0,RiverProfiles.Action.ADD,-1,"STONE");rejects(() -> FloatingObjectReplacements.changeFind(PATH,one,context,0,RiverProfiles.Action.REMOVE,0,null));
            byte[] two=FloatingObjectReplacements.changeFind(PATH,one,context,0,RiverProfiles.Action.DUPLICATE,0,null);check(FloatingObjectReplacements.find(rule(two,list)).size()==2);
            check(FloatingObjectReplacements.find(rule(FloatingObjectReplacements.changeFind(PATH,two,context,0,RiverProfiles.Action.REMOVE,1,null),list)).size()==1);
            rejects(() -> FloatingObjectReplacements.setFindBlock(PATH,one,context,0,1,"stone"));rejects(() -> FloatingObjectReplacements.setFindBlock(PATH,one,context,0,0,""));
            for (String bad : List.of("null","1","\"snippet/rules\"","[null]","[{},1]","[\"snippet/rule\"]")) {
                byte[] invalid=document(list,"{\"edit\":"+bad+"}");rejects(() -> rules(invalid,list));rejects(() -> FloatingObjectReplacements.enable(PATH,invalid,context));rejects(() -> FloatingObjectReplacements.change(PATH,invalid,context,RiverProfiles.Action.ADD,-1,"stone"));
            }
            for (String bad : List.of("null","1","\"true\"")) rejects(() -> FloatingObjectReplacements.setExact(PATH,document(list,"{\"edit\":[{\"exact\":"+bad+"}]}"),context,0,true));
            for (String bad : List.of("null","true","\"1\"")) rejects(() -> FloatingObjectReplacements.setChance(PATH,document(list,"{\"edit\":[{\"chance\":"+bad+"}]}"),context,0,"0.5"));
            for (String bad : List.of("null","{}","[null]","[\"snippet/find\"]","[{},1]")) {
                byte[] invalid=document(list,"{\"edit\":[{\"find\":"+bad+"}]}" );rejects(() -> FloatingObjectReplacements.find(rule(invalid,list)));rejects(() -> FloatingObjectReplacements.enableFind(PATH,invalid,context,0));rejects(() -> FloatingObjectReplacements.changeFind(PATH,invalid,context,0,RiverProfiles.Action.ADD,-1,"stone"));
            }
            for (int ruleIndex : List.of(-1,0)) {
                var owner=new FloatingObjectPalettes.Context(0,list,0,ruleIndex);var target=owner.target();byte[] base=added;
                check(target.objectPalette() && !target.layer() && !target.slabWall() && !target.floatingBottom() && target.minimumEntries()==1 && target.defaultBlock().equals("STONE"));
                check(target.styleTarget().objectPaletteContext().equals(owner) && target.styleTarget().member().equals("style") && target.styleTarget().draftPrefix().equals(target.noisePrefix()+"style."));
                check(palette(base,owner)==null);byte[] created=FloatingObjectPalettes.create(PATH,base,owner);check(palette(created,owner).fields().isEmpty());rejects(() -> FloatingObjectPalettes.create(PATH,created,owner));
                if (owner.stilt()) { byte[] restored=FloatingObjectPalettes.restoreStilt(PATH,created,owner);check(placement(restored,list).tokenPath("stiltSettings.palette").equals("null"));check(palette(FloatingObjectPalettes.create(PATH,restored,owner),owner).fields().isEmpty()); }
                else rejects(() -> FloatingObjectPalettes.restoreStilt(PATH,created,owner));
                byte[] entries=target.enableEntries(PATH,created);check(palette(entries,owner).objectArray("palette").getFirst().stringToken("block").equals("STONE"));rejects(() -> target.enableEntries(PATH,entries));
                byte[] weight=target.enableWeight(PATH,entries,0);check(palette(weight,owner).objectArray("palette").getFirst().token("weight").equals("1"));
                for (String valid : List.of("1","1000","1e3")) target.setWeight(PATH,weight,0,valid);
                for (String bad : List.of("0","1001","1.000000000000000001","2147483648","NaN")) rejects(() -> target.setWeight(PATH,weight,0,bad));
                rejects(() -> target.change(PATH,entries,RiverProfiles.Action.REMOVE,0,null));
                byte[] duplicate=target.change(PATH,weight,RiverProfiles.Action.DUPLICATE,0,null);check(palette(duplicate,owner).objectArray("palette").size()==2);target.change(PATH,duplicate,RiverProfiles.Action.REMOVE,1,null);
                byte[] zoom=target.enableZoom(PATH,created);check(palette(zoom,owner).token("zoom").equals("5"));target.setZoom(PATH,zoom,"0.0001");
                for (String bad : List.of("0","0.00009","NaN","Infinity")) rejects(() -> target.setZoom(PATH,zoom,bad));
                byte[] styled=NoiseStyleFields.enable(PATH,created,target.styleTarget(),"style");check(palette(styled,owner).stringPath("style.style").equals("STATIC"));
                for (String style : NoiseStyleFields.choices()) check(palette(NoiseStyleFields.editStyle(PATH,styled,target.styleTarget(),style),owner).stringPath("style.style").equals(style));
                for (String member : NoiseStyleFields.EDITABLE) if (!member.equals("style")) { byte[] scalar=NoiseStyleFields.enable(PATH,styled,target.styleTarget(),member);NoiseStyleFields.editText(PATH,scalar,target.styleTarget(),member,member.equals("cellularFrequency") ? "0" : "1");rejects(() -> NoiseStyleFields.editText(PATH,scalar,target.styleTarget(),member,"NaN")); }
                for (String bad : List.of("null","[]","true","\"snippet/palette\"")) {
                    byte[] invalid=owner.stilt() ? document(list,"{\"stiltSettings\":{\"palette\":"+bad+"}}") : document(list,"{\"edit\":[{\"replace\":"+bad+"}]}");
                    if (owner.stilt() && bad.equals("null")) check(palette(invalid,owner)==null);
                    else { rejects(() -> palette(invalid,owner));rejects(() -> FloatingObjectPalettes.create(PATH,invalid,owner)); }
                    rejects(() -> target.enableEntries(PATH,invalid));rejects(() -> NoiseStyleFields.enable(PATH,invalid,target.styleTarget(),"style"));
                }
                for (String path : List.of("dimensions/a.json","regions/a.json","generators/a.json","biomes/a.txt","a.json")) { rejects(() -> FloatingObjectPalettes.create(path,base,owner));rejects(() -> target.enableEntries(path,created));rejects(() -> NoiseStyleFields.enable(path,created,target.styleTarget(),"style"));rejects(() -> FloatingObjectReplacements.enable(path,base,context));rejects(() -> FloatingObjectReplacements.setFindBlock(path,base,context,0,0,"stone")); }
            }
            String rawRule="{\"find\":[{\"block\":\"legacy\\u0061\",\"weight\":999,\"data\":{\"axis\":\"y\"}},{\"block\":\"STONE\"}],\"replace\":{\"palette\":[{\"block\":\"STONE\",\"weight\":1e0,\"futureBlock\":9007199254740993}],\"zoom\":5e0,\"style\":{\"style\":\"UNKNOWN\",\"expression\":\"x + z\"},\"futurePalette\":true},\"exact\":false,\"chance\":1e0,\"futureRule\":\"雪\\u0061\"}";
            byte[] original=document(list,"{\"stiltSettings\":{\"palette\":{\"palette\":[{\"block\":\"STONE\"}],\"style\":{}},\"organicJitter\":3},\"edit\":[ "+rawRule+",\r\n {} ],\"force\":true,\"futurePlacement\":9007199254740993}"),copy=original.clone();
            check(text(FloatingObjectReplacements.setFindBlock(PATH,original,context,0,0,"minecraft:oak_log")).equals(text(original).replace("\"legacy\\u0061\"","\"minecraft:oak_log\"")));
            check(text(FloatingObjectReplacements.setChance(PATH,original,context,0,"0.25")).equals(text(original).replace("\"chance\":1e0","\"chance\":0.25")));
            byte[] duplicateRule=FloatingObjectReplacements.change(PATH,original,context,RiverProfiles.Action.DUPLICATE,0,null);check(rules(duplicateRule,list).size()==3 && Arrays.equals(rules(duplicateRule,list).get(0).sourceBytes(),rules(duplicateRule,list).get(1).sourceBytes()) && Arrays.equals(rules(duplicateRule,list).get(0).sourceBytes(),rule(original,list).sourceBytes()));
            byte[] movedRule=FloatingObjectReplacements.change(PATH,original,context,RiverProfiles.Action.DOWN,0,null);check(Arrays.equals(original,FloatingObjectReplacements.change(PATH,movedRule,context,RiverProfiles.Action.UP,1,null)));
            byte[] movedFind=FloatingObjectReplacements.changeFind(PATH,original,context,0,RiverProfiles.Action.DOWN,0,null);check(Arrays.equals(original,FloatingObjectReplacements.changeFind(PATH,movedFind,context,0,RiverProfiles.Action.UP,1,null)));
            var stiltContext=new FloatingObjectPalettes.Context(0,list,0,-1);check(palette(NoiseStyleFields.enable(PATH,original,stiltContext.target().styleTarget(),"style"),stiltContext).stringPath("style.style").equals("FLAT"));
            byte[] rawFindDuplicate=FloatingObjectReplacements.changeFind(PATH,original,context,0,RiverProfiles.Action.DUPLICATE,0,null);check(FloatingObjectReplacements.find(rule(rawFindDuplicate,list)).get(0).token("weight").equals("999") && Arrays.equals(FloatingObjectReplacements.find(rule(rawFindDuplicate,list)).get(0).sourceBytes(),FloatingObjectReplacements.find(rule(rawFindDuplicate,list)).get(1).sourceBytes()));
            var replacementContext=new FloatingObjectPalettes.Context(0,list,0,0);check(FloatingObjectPalettes.readOnlyPaths(placement(original,list),replacementContext).containsAll(List.of("futurePalette","style.expression")));
            check(text(replacementContext.target().setWeight(PATH,original,0,"2")).equals(text(original).replace("\"weight\":1e0","\"weight\":2")));
            for (String parent : List.of("{}","{\"stiltSettings\":null}","{\"stiltSettings\":\"snippet/settings\"}")) { byte[] invalid=document(list,parent);rejects(() -> FloatingObjectPalettes.create(PATH,invalid,stiltContext));rejects(() -> stiltContext.target().enableEntries(PATH,invalid));rejects(() -> NoiseStyleFields.enable(PATH,invalid,stiltContext.target().styleTarget(),"style")); }
            rejects(() -> new GeneralBlockPalettes.Target("rockPalette",null,-1,null,-1,stiltContext));
            var drafts=new InspectorDrafts();String find=context.findPrefix(0)+"0.block",chanceKey=context.rulePrefix(0)+"chance",block=replacementContext.target().listPrefix()+"0.block",style=replacementContext.target().styleTarget().draftPrefix()+"zoom",stilt=stiltContext.prefix()+"noise.zoom",outside=FloatingObjectPlacements.placementPrefix(0,list,0)+"density",other=new FloatingObjectReplacements.Context(1,list,0).rulePrefix(0)+"chance";
            for (String key : List.of(find,chanceKey,block,style,stilt,outside,other)) drafts.update(key,"1","2");
            check(!drafts.transitionUnder(context.prefix(),() -> false,() -> { throw new AssertionError(); }) && drafts.count()==7);
            try { drafts.transitionUnder(context.prefix(),() -> true,() -> { throw new IOException("failed write"); });throw new AssertionError(); } catch (IOException expected) { check(drafts.count()==7); }
            check(drafts.transitionUnder(context.findPrefix(0),() -> true,() -> {}) && drafts.count()==6 && drafts.contains(block) && drafts.contains(chanceKey));
            check(drafts.transitionUnder(replacementContext.target().listPrefix(),() -> true,() -> {}) && drafts.count()==5 && drafts.contains(style));
            check(drafts.transitionUnder(context.prefix(),() -> true,() -> {}) && drafts.count()==3 && drafts.contains(stilt) && drafts.contains(outside));
            check(drafts.transitionUnder(FloatingObjectPlacements.listPrefix(0,list),() -> true,() -> {}) && drafts.count()==1 && drafts.contains(other));
            check(Arrays.equals(original,copy));var saved=PackWorkspace.newPack(Map.of(PATH,original,"objects/opaque.iob",new byte[]{0,127,-1,42}));
            saved.replace(PATH,FloatingObjectReplacements.setChance(PATH,saved.read(PATH),context,0,"0.5"));saved.replace(PATH,FloatingObjectReplacements.setExact(PATH,saved.read(PATH),context,0,true));saved.replace(PATH,FloatingObjectReplacements.setFindBlock(PATH,saved.read(PATH),context,0,0,"minecraft:oak_log[axis=y]"));
            saved.replace(PATH,replacementContext.target().setBlock(PATH,saved.read(PATH),0,"minecraft:birch_log[axis=y]"));saved.replace(PATH,replacementContext.target().setWeight(PATH,saved.read(PATH),0,"1000"));saved.replace(PATH,stiltContext.target().setBlock(PATH,saved.read(PATH),0,"minecraft:stone_bricks"));
            saved.save(temp.resolve("object-materials-"+list));saved.exportZip(temp.resolve("object-materials-"+list+".zip"));var reopened=PackWorkspace.open(temp.resolve("object-materials-"+list));var imported=PackWorkspace.importZip(temp.resolve("object-materials-"+list+".zip"));
            for (String path : saved.paths()) check(Arrays.equals(saved.read(path),reopened.read(path)) && Arrays.equals(saved.read(path),imported.read(path)));
            check(FloatingObjectReplacements.find(rule(saved.read(PATH),list)).getFirst().token("weight").equals("999"));
        }
        for (String invalid : List.of("floatingChildBiomes.0.objects.0.stiltSettings.palette.style","floatingChildBiomes.0.extraObjects.0.edit.-1.replace.style","floatingChildBiomes.0.extraObjects.0.edit.2147483648.replace.style")) rejects(() -> new NoiseStyleFields.Target("biomes",invalid,null));
        rejects(() -> new FloatingObjectPalettes.Context(0,"objects",0,-1));rejects(() -> new FloatingObjectPalettes.Context(0,"extraObjects",0,-2));
        check(FieldCoverage.fields("objectReplace").size()==4 && FieldCoverage.fields("objectReplace").stream().allMatch(FieldCoverage.Field::implemented));
        System.out.println("PASS: floating stilt/replacement palettes, weighted blocks/zoom/all styles, ordered rules and required unweighted find lists, chance/state flags, null/default/type/context guards, raw preservation, scoped drafts and Save/ZIP contracts");
    }
}
