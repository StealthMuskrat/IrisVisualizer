package dev.irisvisualizer.editor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

final class DepositVariantTests {
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static String text(byte[] value) { return new String(value,StandardCharsets.UTF_8); }
    private static void check(boolean condition) { if (!condition) throw new AssertionError("Deposit variant contract failed"); }
    private static void rejects(Runnable action) { try { action.run(); } catch (IllegalArgumentException expected) { return; }throw new AssertionError("Expected rejected variant operation"); }
    private static String path(String base) { return base.equals("dimensions") ? "dimensions/variant.json" : base+"/nested/variant.json"; }
    private static byte[] document(String variant) { return bytes("{\r\n \"depositVariants\":[ "+variant+", {\"futureSibling\":1e2} ],\"deposits\":[{\"futureDeposit\":true}],\"futureRoot\":9007199254740993,\"escaped\":\"雪\\u0061\"\r\n}\r\n"); }
    private static LosslessJson variant(byte[] value) { return new LosslessJson(value).objectArray("depositVariants").getFirst(); }
    private static LosslessJson map(byte[] value) { return DepositVariants.remap(variant(value)); }
    static void run(Path temp) throws Exception {
        literalMaps();
        for (String base : OreGenerators.CONTEXTS) {
            String path=path(base);var context=new DepositVariants.Context(base,0);byte[] blank=document("{}");
            check(FieldCoverage.groups(path).contains("depositVariant"));check(DepositVariants.defaultHeight("minHeight").equals("0") && DepositVariants.defaultHeight("maxHeight").equals("0"));
            check(DepositVariants.entries(new LosslessJson(bytes("{}")))==null);byte[] empty=DepositVariants.enable(path,bytes("{}"),base);check(new LosslessJson(empty).objectArray("depositVariants").isEmpty());
            byte[] added=DepositVariants.change(path,empty,base,RiverProfiles.Action.ADD,-1,"minecraft:iron_ore","minecraft:deepslate_iron_ore");check(variant(added).fields().equals(java.util.Set.of("remap")) && map(added).stringToken("minecraft:iron_ore").equals("minecraft:deepslate_iron_ore"));
            rejects(() -> DepositVariants.enable(path,empty,base));rejects(() -> DepositVariants.change(path,empty,base,RiverProfiles.Action.ADD,-1,"iron_ore","stone"));
            check(new LosslessJson(DepositVariants.change(path,added,base,RiverProfiles.Action.REMOVE,0,null,null)).objectArray("depositVariants").isEmpty());
            byte[] duplicate=DepositVariants.change(path,added,base,RiverProfiles.Action.DUPLICATE,0,null,null);check(new LosslessJson(duplicate).objectArray("depositVariants").size()==2);
            byte[] band=DepositVariants.setBand(path,blank,context,"-2048","8192");check(variant(band).token("minHeight").equals("-2048") && variant(band).token("maxHeight").equals("8192"));
            DepositVariants.setBand(path,blank,context,"0","0");
            for (String bad : List.of("-2049","8193","2147483648","1.000000000000000001","NaN","Infinity")) { rejects(() -> DepositVariants.setBand(path,blank,context,bad,"8192"));rejects(() -> DepositVariants.setBand(path,blank,context,"-2048",bad)); }
            rejects(() -> DepositVariants.setBand(path,blank,context,"2","1"));
            for (String field : List.of("minHeight","maxHeight")) for (String bad : List.of("null","[]","true","\"0\"")) rejects(() -> DepositVariants.setBand(path,document("{\""+field+"\":"+bad+"}"),context,"-64","64"));
            byte[] enabled=DepositVariants.enableMap(path,blank,context);check(map(enabled).fields().isEmpty());
            rejects(() -> DepositVariants.enableMap(path,enabled,context));rejects(() -> DepositVariants.addMapping(path,blank,context,"minecraft:iron_ore","stone"));
            byte[] one=DepositVariants.addMapping(path,enabled,context,"your.mod:ore/deep","your.mod:block[state=value]");check(map(one).stringToken("your.mod:ore/deep").equals("your.mod:block[state=value]") && map(one).token("your")==null);
            rejects(() -> DepositVariants.addMapping(path,one,context,"your.mod:ore/deep","stone"));check(map(DepositVariants.removeMapping(path,one,context,"your.mod:ore/deep")).fields().isEmpty());
            for (String bad : List.of("","iron_ore","Minecraft:iron_ore","minecraft:iron_ore[axis=x]","minecraft:iron ore")) rejects(() -> DepositVariants.addMapping(path,enabled,context,bad,"stone"));
            rejects(() -> DepositVariants.addMapping(path,enabled,context,"minecraft:iron_ore"," "));
            byte[] original=document("{\"minHeight\":-6.4e1,\"maxHeight\":0e0,\"remap\":{ \"minecraft:iron\\u005fore\" : \"legacy\\u0061\",\r\n \"your.mod:ore/deep\":\"unknown:target[state=x]\",\"OLD SOURCE\":\"future target\" },\"futureVariant\":9007199254740993}"),copy=original.clone();
            check(text(DepositVariants.setMapping(path,original,context,"minecraft:iron_ore","minecraft:iron_ore","minecraft:gold_ore")).equals(text(original).replace("\"legacy\\u0061\"","\"minecraft:gold_ore\"")));
            check(text(DepositVariants.setMapping(path,original,context,"minecraft:iron_ore","minecraft:gold_ore","legacya")).equals(text(original).replace("\"minecraft:iron\\u005fore\"","\"minecraft:gold_ore\"")));
            check(Arrays.equals(original,DepositVariants.setMapping(path,original,context,"minecraft:iron_ore","minecraft:iron_ore","legacya")));
            check(map(DepositVariants.setMapping(path,original,context,"OLD SOURCE","OLD SOURCE","minecraft:dirt")).stringToken("OLD SOURCE").equals("minecraft:dirt"));
            rejects(() -> DepositVariants.setMapping(path,original,context,"minecraft:iron_ore","your.mod:ore/deep","stone"));rejects(() -> DepositVariants.setMapping(path,original,context,"absent","minecraft:gold_ore","stone"));rejects(() -> DepositVariants.setMapping(path,original,context,"OLD SOURCE","NEW INVALID","stone"));
            byte[] moved=DepositVariants.change(path,original,base,RiverProfiles.Action.DOWN,0,null,null);check(Arrays.equals(original,DepositVariants.change(path,moved,base,RiverProfiles.Action.UP,1,null,null)));
            for (String bad : List.of("null","[]","true","1","\"snippet/map\"","{\"minecraft:ore\":null}","{\"good\":\"stone\",\"future\":{}}")) {
                byte[] invalid=document("{\"remap\":"+bad+"}");rejects(() -> { var remap=map(invalid);if (remap!=null) remap.stringProperties(); });
                rejects(() -> DepositVariants.enableMap(path,invalid,context));rejects(() -> DepositVariants.addMapping(path,invalid,context,"minecraft:iron_ore","stone"));rejects(() -> DepositVariants.removeMapping(path,invalid,context,"good"));
                check(variant(DepositVariants.setBand(path,invalid,context,"0","64")).token("remap").equals(bad));
            }
            for (String bad : List.of("null","{}","[null]","[{},1]","[\"snippet/variant\"]")) {
                byte[] invalid=bytes("{\"depositVariants\":"+bad+"}");rejects(() -> DepositVariants.entries(new LosslessJson(invalid)));rejects(() -> DepositVariants.enable(path,invalid,base));rejects(() -> DepositVariants.change(path,invalid,base,RiverProfiles.Action.ADD,-1,"minecraft:iron_ore","stone"));rejects(() -> DepositVariants.enableMap(path,invalid,context));
            }
            for (String wrong : List.of("generators/variant.json","dimensions/nested/variant.json",base+"/variant.txt","variant.json")) rejectOwner(wrong,base,context,original);
            for (String other : OreGenerators.CONTEXTS) if (!other.equals(base)) rejectOwner(path(other),base,context,original);
            byte[] edited=DepositVariants.setBand(path,original,context,"-128","64");edited=DepositVariants.setMapping(path,edited,context,"minecraft:iron_ore","minecraft:gold_ore","minecraft:deepslate_gold_ore");
            for (String raw : List.of("\"futureSibling\":1e2","\"futureRoot\":9007199254740993","\"futureVariant\":9007199254740993","\"OLD SOURCE\":\"future target\"","\"your.mod:ore/deep\":\"unknown:target[state=x]\"","\"deposits\":[{\"futureDeposit\":true}]","\"escaped\":\"雪\\u0061\"")) check(text(edited).contains(raw));
            check(Arrays.equals(original,copy));var drafts=new InspectorDrafts();String list=DepositVariants.listPrefix(base),height=context.prefix()+"minHeight",mapping=context.mapPrefix()+"0",other=DepositVariants.listPrefix(base.equals("biomes") ? "regions" : "biomes")+"0.minHeight",deposit=DepositGenerators.listPrefix(base)+"0.minHeight";
            for (String key : List.of(height,mapping,other,deposit)) drafts.update(key,"1","2");
            check(!drafts.transitionUnder(list,() -> false,() -> { throw new AssertionError(); }) && drafts.count()==4);
            try { drafts.transitionUnder(list,() -> true,() -> { throw new IOException("failed write"); });throw new AssertionError(); } catch (IOException expected) { check(drafts.count()==4); }
            check(drafts.transitionUnder(context.mapPrefix(),() -> true,() -> {}) && drafts.count()==3 && drafts.contains(height));
            check(drafts.transitionUnder(list,() -> true,() -> {}) && drafts.count()==2 && drafts.contains(other) && drafts.contains(deposit));
            var workspace=PackWorkspace.newPack(Map.of(path("dimensions"),original,path("regions"),original,path("biomes"),original,"objects/opaque.iob",new byte[]{0,127,-1,42}));workspace.replace(path,edited);
            for (String otherBase : OreGenerators.CONTEXTS) if (!otherBase.equals(base)) check(Arrays.equals(workspace.read(path(otherBase)),original));
            workspace.save(temp.resolve("variants-"+base));workspace.exportZip(temp.resolve("variants-"+base+".zip"));var reopened=PackWorkspace.open(temp.resolve("variants-"+base));var imported=PackWorkspace.importZip(temp.resolve("variants-"+base+".zip"));
            for (String resource : workspace.paths()) check(Arrays.equals(workspace.read(resource),reopened.read(resource)) && Arrays.equals(workspace.read(resource),imported.read(resource)));
        }
        check(FieldCoverage.fields("depositVariant").size()==3 && FieldCoverage.fields("depositVariant").stream().allMatch(FieldCoverage.Field::implemented));
        rejects(() -> new DepositVariants.Context("generators",0));rejects(() -> new DepositVariants.Context("dimensions",-1));
        System.out.println("PASS: literal lossless string-map keys/values, world-Y variant lists/bands/remaps across three scopes, key rename/collision and owner/type guards, raw preservation, scoped drafts and Save/ZIP contracts");
    }
    private static void literalMaps() {
        byte[] raw=bytes("{\r\n \"a\\u002eb/c:d\" : \"legacy\\u0061\",\r\n \"second\" : \"v2\", \"雪:key\" : \"v3\"\r\n}\r\n");var json=new LosslessJson(raw);
        check(json.stringProperties().getFirst().key().equals("a.b/c:d") && json.stringProperties().getFirst().value().equals("legacya"));
        check(Arrays.equals(raw,json.stringMapRename("a.b/c:d","a.b/c:d")));
        check(text(json.stringMapRename("a.b/c:d","other.key:🌱")).equals(text(raw).replace("\"a\\u002eb/c:d\"","\"other.key:🌱\"")));
        String literal="x.y/z:\"\\雪";byte[] added=json.stringMapAdd(literal,"target \"雪\"");check(new LosslessJson(added).stringToken(literal).equals("target \"雪\"") && new LosslessJson(added).token("x")==null && text(added).contains("\"legacy\\u0061\""));
        for (String key : List.of("a.b/c:d","second","雪:key")) {
            var removed=new LosslessJson(json.stringMapRemove(key));check(removed.fields().size()==2 && !removed.fields().contains(key));
            for (String other : json.fields()) if (!other.equals(key)) check(removed.token(other).equals(json.token(other)));
        }
        check(new LosslessJson(new LosslessJson(bytes("{ \"one\": \"value\" }" )).stringMapRemove("one")).fields().isEmpty());
        check(new LosslessJson(new LosslessJson(bytes("{}" )).stringMapAdd("","value")).stringToken("").equals("value"));
        rejects(() -> json.stringMapAdd("a.b/c:d","new"));rejects(() -> json.stringMapRename("second","雪:key"));rejects(() -> json.stringMapRename("absent","new"));rejects(() -> json.stringMapRemove("absent"));
        for (String malformed : List.of("{\"a\":null}","{\"a\":[]}","{\"a\":1}","{\"a\":true}","{\"a\":{}}")) {
            var bad=new LosslessJson(bytes(malformed));rejects(bad::stringProperties);rejects(() -> bad.stringMapAdd("b","value"));rejects(() -> bad.stringMapRename("a","b"));rejects(() -> bad.stringMapRemove("a"));
        }
        rejects(() -> new LosslessJson(bytes("{\"a\\u002eb\":\"x\",\"a.b\":\"y\"}")));
    }
    private static void rejectOwner(String path,String base,DepositVariants.Context context,byte[] source) {
        rejects(() -> DepositVariants.enable(path,bytes("{}"),base));rejects(() -> DepositVariants.change(path,source,base,RiverProfiles.Action.DUPLICATE,0,null,null));
        rejects(() -> DepositVariants.setBand(path,source,context,"0","64"));rejects(() -> DepositVariants.enableMap(path,document("{}"),context));rejects(() -> DepositVariants.addMapping(path,source,context,"minecraft:gold_ore","stone"));
        rejects(() -> DepositVariants.setMapping(path,source,context,"minecraft:iron_ore","minecraft:iron_ore","stone"));rejects(() -> DepositVariants.removeMapping(path,source,context,"minecraft:iron_ore"));
    }
}
