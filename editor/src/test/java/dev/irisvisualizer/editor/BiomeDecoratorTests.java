package dev.irisvisualizer.editor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class BiomeDecoratorTests {
    private static final String PATH="biomes/nested/decorators.json";
    private static final BiomeDecorators.Context CONTEXT=new BiomeDecorators.Context(0);
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static String text(byte[] value) { return new String(value,StandardCharsets.UTF_8); }
    private static void check(boolean condition) { if (!condition) throw new AssertionError("Biome decorator contract failed"); }
    private static void rejects(Runnable action) { try { action.run(); } catch (IllegalArgumentException expected) { return; }throw new AssertionError("Expected rejected decorator operation"); }
    private static byte[] document(String entry) { return bytes("{\r\n \"decorators\":[ "+entry+", {\"futureSibling\":1e2} ],\"objects\":[{\"futureObject\":true}],\"futureRoot\":9007199254740993,\"escaped\":\"雪\\u0061\"\r\n}\r\n"); }
    private static LosslessJson decorator(byte[] value) { return BiomeDecorators.entries(new LosslessJson(value)).getFirst(); }
    private static List<LosslessJson> blocks(byte[] value,String field) { return BiomeDecorators.blocks(decorator(value),field); }
    static void run(Path temp) throws Exception {
        byte[] blank=document("{}");check(BiomeDecorators.entries(new LosslessJson(bytes("{}")))==null);byte[] empty=BiomeDecorators.enable(PATH,bytes("{}"));check(BiomeDecorators.entries(new LosslessJson(empty)).isEmpty());rejects(() -> BiomeDecorators.enable(PATH,empty));
        byte[] added=BiomeDecorators.change(PATH,empty,RiverProfiles.Action.ADD,-1,"minecraft:short_grass");check(decorator(added).fields().equals(Set.of("palette")) && blocks(added,"palette").getFirst().stringToken("block").equals("minecraft:short_grass"));
        check(BiomeDecorators.entries(new LosslessJson(BiomeDecorators.change(PATH,added,RiverProfiles.Action.REMOVE,0,null))).isEmpty());rejects(() -> BiomeDecorators.change(PATH,empty,RiverProfiles.Action.ADD,-1," "));
        for (String flag : BiomeDecorators.FLAGS) {
            byte[] enabled=BiomeDecorators.enableScalar(PATH,blank,CONTEXT,flag);check(decorator(enabled).token(flag).equals("false"));check(decorator(BiomeDecorators.setFlag(PATH,enabled,CONTEXT,flag,true)).token(flag).equals("true"));rejects(() -> BiomeDecorators.setFlag(PATH,blank,CONTEXT,flag,true));
        }
        for (String field : BiomeDecorators.SCALARS) {
            byte[] enabled=BiomeDecorators.enableScalar(PATH,blank,CONTEXT,field);check(decorator(enabled).token(field).equals(BiomeDecorators.defaultToken(field)));
            for (String valid : field.equals("chance") ? List.of("0","1") : field.equals("topThreshold") ? List.of("0.01","1") : List.of("1","2032","1e3")) BiomeDecorators.setScalar(PATH,enabled,CONTEXT,field,valid);
            for (String invalid : field.equals("absoluteMaxStack") ? List.of("0","2033","1.000000000000000001","2147483648","NaN","Infinity") : List.of("-0.01","1.01","NaN","Infinity")) rejects(() -> BiomeDecorators.setScalar(PATH,enabled,CONTEXT,field,invalid));
            rejects(() -> BiomeDecorators.setScalar(PATH,blank,CONTEXT,field,"1"));
        }
        rejects(() -> BiomeDecorators.setScalar(PATH,BiomeDecorators.enableScalar(PATH,blank,CONTEXT,"topThreshold"),CONTEXT,"topThreshold","0"));
        check(decorator(BiomeDecorators.setStack(PATH,blank,CONTEXT,"1","2032")).token("stackMax").equals("2032"));
        for (String invalid : List.of("0","2033","1.000000000000000001","2147483648","NaN")) { rejects(() -> BiomeDecorators.setStack(PATH,blank,CONTEXT,invalid,"2032"));rejects(() -> BiomeDecorators.setStack(PATH,blank,CONTEXT,"1",invalid)); }
        rejects(() -> BiomeDecorators.setStack(PATH,blank,CONTEXT,"2","1"));check(BiomeDecorators.parts().equals(List.of("NONE","SHORE_LINE","SEA_SURFACE","SEA_FLOOR","CEILING")));
        for (String part : BiomeDecorators.parts()) check(decorator(BiomeDecorators.setPart(PATH,blank,CONTEXT,part)).stringToken("partOf").equals(part));rejects(() -> BiomeDecorators.setPart(PATH,blank,CONTEXT,"UNKNOWN"));
        for (String field : BiomeDecorators.BLOCK_LISTS) {
            check(blocks(blank,field)==null);byte[] enabled=BiomeDecorators.enableBlocks(PATH,blank,CONTEXT,field);check(blocks(enabled,field).size()==(field.equals("palette") ? 1 : 0));
            if (field.equals("palette")) check(blocks(enabled,field).getFirst().stringToken("block").equals("grass"));
            rejects(() -> BiomeDecorators.enableBlocks(PATH,enabled,CONTEXT,field));
            byte[] one=BiomeDecorators.changeBlock(PATH,enabled,CONTEXT,field,RiverProfiles.Action.ADD,-1,"minecraft:stone");int last=blocks(one,field).size()-1;
            check(blocks(BiomeDecorators.setBlock(PATH,one,CONTEXT,field,last,"minecraft:dirt"),field).get(last).stringToken("block").equals("minecraft:dirt"));rejects(() -> BiomeDecorators.setBlock(PATH,one,CONTEXT,field,last+1,"minecraft:dirt"));
            byte[] doubled=BiomeDecorators.changeBlock(PATH,one,CONTEXT,field,RiverProfiles.Action.DUPLICATE,last,null);check(blocks(doubled,field).size()==blocks(one,field).size()+1);
            byte[] moved=BiomeDecorators.changeBlock(PATH,doubled,CONTEXT,field,RiverProfiles.Action.UP,last+1,null);check(Arrays.equals(doubled,BiomeDecorators.changeBlock(PATH,moved,CONTEXT,field,RiverProfiles.Action.DOWN,last,null)));
            if (BiomeDecorators.weighted(field)) {
                byte[] weighted=BiomeDecorators.enableWeight(PATH,one,CONTEXT,field,last);check(blocks(weighted,field).get(last).token("weight").equals("1"));
                for (String valid : List.of("1","1000","1e3")) BiomeDecorators.setWeight(PATH,weighted,CONTEXT,field,last,valid);
                for (String bad : List.of("0","1001","1.000000000000000001","NaN")) rejects(() -> BiomeDecorators.setWeight(PATH,weighted,CONTEXT,field,last,bad));
                rejects(() -> BiomeDecorators.restoreFilter(PATH,one,CONTEXT,field));
            } else {
                rejects(() -> BiomeDecorators.enableWeight(PATH,one,CONTEXT,field,last));rejects(() -> BiomeDecorators.setWeight(PATH,one,CONTEXT,field,last,"2"));
                check(Arrays.equals(blank,BiomeDecorators.restoreFilter(PATH,blank,CONTEXT,field)));byte[] inherited=BiomeDecorators.restoreFilter(PATH,one,CONTEXT,field);check(decorator(inherited).token(field).equals("null") && blocks(inherited,field)==null);
                check(blocks(BiomeDecorators.enableBlocks(PATH,inherited,CONTEXT,field),field).isEmpty());
            }
            if (field.equals("palette")) rejects(() -> BiomeDecorators.changeBlock(PATH,enabled,CONTEXT,field,RiverProfiles.Action.REMOVE,0,null));
            else check(blocks(BiomeDecorators.changeBlock(PATH,one,CONTEXT,field,RiverProfiles.Action.REMOVE,0,null),field).isEmpty());
            for (String bad : List.of("null","{}","[null]","[{},1]","[\"snippet/block\"]")) {
                byte[] invalid=document("{\""+field+"\":"+bad+"}");if (BiomeDecorators.filter(field) && bad.equals("null")) { check(blocks(invalid,field)==null);continue; }
                rejects(() -> blocks(invalid,field));rejects(() -> BiomeDecorators.enableBlocks(PATH,invalid,CONTEXT,field));rejects(() -> BiomeDecorators.changeBlock(PATH,invalid,CONTEXT,field,RiverProfiles.Action.ADD,-1,"STONE"));if (BiomeDecorators.filter(field)) rejects(() -> BiomeDecorators.restoreFilter(PATH,invalid,CONTEXT,field));
            }
        }
        byte[] force=BiomeDecorators.setForceBlock(PATH,blank,CONTEXT,"minecraft:dirt");check(BiomeDecorators.forceBlock(decorator(force)).stringToken("block").equals("minecraft:dirt") && decorator(force).token("forcePlace")==null);
        byte[] forceNull=BiomeDecorators.restoreForceBlock(PATH,force,CONTEXT);check(decorator(forceNull).token("forceBlock").equals("null"));check(BiomeDecorators.forceBlock(decorator(BiomeDecorators.setForceBlock(PATH,forceNull,CONTEXT,"minecraft:stone"))).stringToken("block").equals("minecraft:stone"));
        check(Arrays.equals(blank,BiomeDecorators.restoreForceBlock(PATH,blank,CONTEXT)));rejects(() -> BiomeDecorators.setForceBlock(PATH,blank,CONTEXT," "));
        byte[] slope=BiomeDecorators.enableSlope(PATH,blank,CONTEXT);check(BiomeLayers.slope(decorator(slope)).fields().isEmpty());BiomeDecorators.setSlope(PATH,slope,CONTEXT,"0","1024");rejects(() -> BiomeDecorators.setSlope(PATH,blank,CONTEXT,"0","10"));
        for (String bad : List.of("-1","1025","NaN","Infinity")) rejects(() -> BiomeDecorators.setSlope(PATH,slope,CONTEXT,"0",bad));rejects(() -> BiomeDecorators.setSlope(PATH,slope,CONTEXT,"2","1"));
        for (String field : BiomeDecorators.STYLES) {
            var target=CONTEXT.styleTarget(field);check(target.decorator() && target.decoratorContext().owner().equals(CONTEXT) && target.member().equals(field) && NoiseStyleFields.contextDefault(target).equals("STATIC"));
            byte[] enabled=NoiseStyleFields.enable(PATH,blank,target,"style");check(decorator(enabled).stringPath(field+".style").equals("STATIC"));
            for (String choice : NoiseStyleFields.choices()) check(decorator(NoiseStyleFields.editStyle(PATH,enabled,target,choice)).stringPath(field+".style").equals(choice));
            for (String member : NoiseStyleFields.EDITABLE) if (!member.equals("style")) { byte[] scalar=NoiseStyleFields.enable(PATH,enabled,target,member);NoiseStyleFields.editText(PATH,scalar,target,member,member.equals("cellularFrequency") ? "0" : "1");rejects(() -> NoiseStyleFields.editText(PATH,scalar,target,member,"NaN")); }
            check(decorator(NoiseStyleFields.enable(PATH,document("{\""+field+"\":{}}"),target,"style")).stringPath(field+".style").equals("FLAT"));
            for (String bad : List.of("null","[]","true","\"snippet/style\"")) rejects(() -> NoiseStyleFields.enable(PATH,document("{\""+field+"\":"+bad+"}"),target,"style"));
            for (String wrong : List.of("dimensions/decorators.json","regions/decorators.json","generators/decorators.json")) rejects(() -> NoiseStyleFields.enable(wrong,blank,target,"style"));
        }
        for (String bad : List.of("null","[]","true","\"0\"")) {
            rejects(() -> BiomeDecorators.setScalar(PATH,document("{\"chance\":"+bad+"}"),CONTEXT,"chance","0.5"));rejects(() -> BiomeDecorators.setStack(PATH,document("{\"stackMax\":"+bad+"}"),CONTEXT,"1","2"));if (!bad.equals("\"0\"")) rejects(() -> BiomeDecorators.setPart(PATH,document("{\"partOf\":"+bad+"}"),CONTEXT,"NONE"));
            if (!bad.equals("true")) rejects(() -> BiomeDecorators.setFlag(PATH,document("{\"forcePlace\":"+bad+"}"),CONTEXT,"forcePlace",true));
            if (!bad.equals("null")) { rejects(() -> BiomeDecorators.setForceBlock(PATH,document("{\"forceBlock\":"+bad+"}"),CONTEXT,"STONE"));rejects(() -> BiomeDecorators.restoreForceBlock(PATH,document("{\"forceBlock\":"+bad+"}"),CONTEXT)); }
            rejects(() -> BiomeDecorators.setSlope(PATH,document("{\"slopeCondition\":"+bad+"}"),CONTEXT,"0","10"));
        }
        for (String bad : List.of("null","{}","[null]","[{},1]","[\"snippet/decorator\"]")) { byte[] invalid=bytes("{\"decorators\":"+bad+"}");rejects(() -> BiomeDecorators.entries(new LosslessJson(invalid)));rejects(() -> BiomeDecorators.enable(PATH,invalid));rejects(() -> BiomeDecorators.change(PATH,invalid,RiverProfiles.Action.ADD,-1,"STONE"));rejects(() -> BiomeDecorators.enableScalar(PATH,invalid,CONTEXT,"chance")); }
        byte[] original=document("{\"palette\":[{\"block\":\"legacy\\u0061\",\"weight\":1e0,\"data\":{\"axis\":\"y\"},\"futureBlock\":9007199254740993}],\"topPalette\":[{\"block\":\"STONE\"}],\"whitelist\":[{\"block\":\"DIRT\",\"weight\":999}],\"forceBlock\":{\"block\":\"STONE\",\"backup\":{\"future\":true}},\"slopeCondition\":{\"futureSlope\":true},\"chance\":1e-1,\"variance\":{\"style\":\"UNKNOWN\",\"expression\":\"x+z\"},\"futureDecorator\":9007199254740993}"),copy=original.clone();
        check(text(BiomeDecorators.setBlock(PATH,original,CONTEXT,"palette",0,"minecraft:short_grass")).equals(text(original).replace("\"legacy\\u0061\"","\"minecraft:short_grass\"")));
        check(text(BiomeDecorators.setWeight(PATH,original,CONTEXT,"palette",0,"2")).equals(text(original).replace("\"weight\":1e0","\"weight\":2")));
        byte[] duplicate=BiomeDecorators.change(PATH,original,RiverProfiles.Action.DUPLICATE,0,null);check(Arrays.equals(BiomeDecorators.entries(new LosslessJson(duplicate)).get(0).sourceBytes(),BiomeDecorators.entries(new LosslessJson(duplicate)).get(1).sourceBytes()));
        byte[] moved=BiomeDecorators.change(PATH,original,RiverProfiles.Action.DOWN,0,null);check(Arrays.equals(original,BiomeDecorators.change(PATH,moved,RiverProfiles.Action.UP,1,null)));
        for (String wrong : List.of("dimensions/decorators.json","regions/decorators.json","generators/decorators.json","biomes/decorators.txt","decorators.json")) rejectOwner(wrong,original);
        for (String invalid : List.of("decorators.00.style","decorators.-1.style","decorators.2147483648.style","decorators.0.palette.style","decorators.0.style.extra")) rejects(() -> new NoiseStyleFields.Target("biomes",invalid,null));rejects(() -> new NoiseStyleFields.Target("dimensions","decorators.0.style",null));rejects(() -> new BiomeDecorators.Context(-1));
        var drafts=new InspectorDrafts();String chance=CONTEXT.prefix()+"chance",block=CONTEXT.blocksPrefix("palette")+"0.block",style=CONTEXT.styleTarget("variance").draftPrefix()+"zoom",filter=CONTEXT.blocksPrefix("whitelist")+"0.block",other=new BiomeDecorators.Context(1).prefix()+"chance",objects="biomeObjects#.0.chance";
        for (String key : List.of(chance,block,style,filter,other,objects)) drafts.update(key,"1","2");check(!drafts.transitionUnder(CONTEXT.prefix(),() -> false,() -> { throw new AssertionError(); }) && drafts.count()==6);
        try { drafts.transitionUnder(CONTEXT.prefix(),() -> true,() -> { throw new IOException("failed write"); });throw new AssertionError(); } catch (IOException expected) { check(drafts.count()==6); }
        check(drafts.transitionUnder(CONTEXT.blocksPrefix("palette"),() -> true,() -> {}) && drafts.count()==5 && drafts.contains(style));check(drafts.transitionUnder(CONTEXT.blocksPrefix("whitelist"),() -> true,() -> {}) && drafts.count()==4 && drafts.contains(chance));check(drafts.transitionUnder(CONTEXT.prefix(),() -> true,() -> {}) && drafts.count()==2 && drafts.contains(other) && drafts.contains(objects));
        byte[] edited=BiomeDecorators.setBlock(PATH,original,CONTEXT,"palette",0,"minecraft:short_grass");edited=BiomeDecorators.setScalar(PATH,edited,CONTEXT,"chance","0.25");edited=BiomeDecorators.setStack(PATH,edited,CONTEXT,"2","4");edited=BiomeDecorators.setSlope(PATH,edited,CONTEXT,"0","2");
        for (String token : List.of("\"futureRoot\":9007199254740993","\"futureDecorator\":9007199254740993","\"futureSibling\":1e2","\"futureBlock\":9007199254740993","\"weight\":999","\"futureSlope\":true","\"expression\":\"x+z\"","\"backup\":{\"future\":true}","\"objects\":[{\"futureObject\":true}]","\"escaped\":\"雪\\u0061\"")) check(text(edited).contains(token));check(Arrays.equals(original,copy));
        var pack=PackWorkspace.newPack(Map.of(PATH,edited,"biomes/other.json",original,"objects/opaque.iob",new byte[]{0,127,-1,42}));pack.save(temp.resolve("biome-decorators"));pack.exportZip(temp.resolve("biome-decorators.zip"));var reopened=PackWorkspace.open(temp.resolve("biome-decorators"));var imported=PackWorkspace.importZip(temp.resolve("biome-decorators.zip"));for (String path : pack.paths()) check(Arrays.equals(pack.read(path),reopened.read(path)) && Arrays.equals(pack.read(path),imported.read(path)));
        check(FieldCoverage.fields("decorator").size()==17 && FieldCoverage.fields("decorator").stream().allMatch(FieldCoverage.Field::implemented));check(FieldCoverage.groups(PATH).contains("decorator"));
        System.out.println("PASS: all 17 decorator fields, list lifecycle, weighted palettes/unweighted nullable filters, atomic stacks/slopes and force overrides, three style contexts/all choices, owner/type/bound guards, independent drafts, raw preservation and Save/ZIP");
    }
    private static void rejectOwner(String path,byte[] original) {
        rejects(() -> BiomeDecorators.enable(path,bytes("{}")));rejects(() -> BiomeDecorators.change(path,original,RiverProfiles.Action.DUPLICATE,0,null));rejects(() -> BiomeDecorators.enableScalar(path,original,CONTEXT,"chance"));rejects(() -> BiomeDecorators.setScalar(path,original,CONTEXT,"chance","0.5"));rejects(() -> BiomeDecorators.setFlag(path,original,CONTEXT,"forcePlace",true));rejects(() -> BiomeDecorators.setStack(path,original,CONTEXT,"1","2"));rejects(() -> BiomeDecorators.setPart(path,original,CONTEXT,"NONE"));
        for (String field : BiomeDecorators.BLOCK_LISTS) { rejects(() -> BiomeDecorators.enableBlocks(path,original,CONTEXT,field));rejects(() -> BiomeDecorators.setBlock(path,original,CONTEXT,field,0,"STONE"));rejects(() -> BiomeDecorators.changeBlock(path,original,CONTEXT,field,RiverProfiles.Action.ADD,-1,"STONE"));if (BiomeDecorators.filter(field)) rejects(() -> BiomeDecorators.restoreFilter(path,original,CONTEXT,field));else { rejects(() -> BiomeDecorators.enableWeight(path,original,CONTEXT,field,0));rejects(() -> BiomeDecorators.setWeight(path,original,CONTEXT,field,0,"1")); } }
        rejects(() -> BiomeDecorators.setForceBlock(path,original,CONTEXT,"STONE"));rejects(() -> BiomeDecorators.restoreForceBlock(path,original,CONTEXT));rejects(() -> BiomeDecorators.enableSlope(path,original,CONTEXT));rejects(() -> BiomeDecorators.setSlope(path,original,CONTEXT,"0","10"));
    }
}
