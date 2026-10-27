package dev.irisvisualizer.editor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

final class DepositGeneratorTests {
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static String text(byte[] value) { return new String(value,StandardCharsets.UTF_8); }
    private static void check(boolean condition) { if (!condition) throw new AssertionError("Deposit generator contract failed"); }
    private static void rejects(Runnable action) { try { action.run(); } catch (IllegalArgumentException expected) { return; }throw new AssertionError("Expected rejected deposit operation"); }
    private static String path(String base) { return base.equals("dimensions") ? "dimensions/deposit.json" : base+"/nested/deposit.json"; }
    private static byte[] document(String entry) { return bytes("{\r\n \"deposits\":[ "+entry+",{\"futureSibling\":1e2} ],\"ores\":[{\"futureOre\":1e-1}],\"futureRoot\":9007199254740993,\"escaped\":\"雪\\u0061\"\r\n}\r\n"); }
    private static LosslessJson entry(byte[] value) { return new LosslessJson(value).objectArray("deposits").getFirst(); }
    static void run(Path temp) throws Exception {
        Map<String,String> minima=Map.of("surfaceClearance","0","spawnChance","0","perClumpSpawnChance","0","discardChanceOnAirExposure","0","varience","1");
        Map<String,String> maxima=Map.of("surfaceClearance","256","spawnChance","1","perClumpSpawnChance","1","discardChanceOnAirExposure","1","varience","64");
        for (String base : OreGenerators.CONTEXTS) {
            String path=path(base);var context=new DepositGenerators.Context(base,0);byte[] blank=document("{}");
            var workspace=PackWorkspace.newPack(Map.of(path("dimensions"),blank,path("regions"),blank,path("biomes"),blank,"biomes/catalog/forest.json",bytes("{}"),"objects/opaque.iob",new byte[]{0,127,-1,42}));
            check(FieldCoverage.groups(path).contains("depositGenerator") && !DepositGenerators.listPrefix(base).equals(OreGenerators.listPrefix(base)));
            check(DepositGenerators.entries(new LosslessJson(bytes("{}")))==null);byte[] empty=DepositGenerators.enable(path,bytes("{}"),base);check(new LosslessJson(empty).objectArray("deposits").isEmpty());
            byte[] added=DepositGenerators.change(path,empty,base,RiverProfiles.Action.ADD,-1,"minecraft:iron_ore");check(entry(added).fields().equals(java.util.Set.of("palette")) && DepositGenerators.palette(entry(added)).getFirst().fields().equals(java.util.Set.of("block")));
            rejects(() -> DepositGenerators.change(path,empty,base,RiverProfiles.Action.ADD,-1," "));rejects(() -> DepositGenerators.enable(path,empty,base));
            byte[] copied=DepositGenerators.change(path,added,base,RiverProfiles.Action.DUPLICATE,0,null);check(new LosslessJson(copied).objectArray("deposits").size()==2);
            check(new LosslessJson(DepositGenerators.change(path,added,base,RiverProfiles.Action.REMOVE,0,null)).objectArray("deposits").isEmpty());
            for (String scalar : DepositGenerators.SCALARS) {
                byte[] enabled=DepositGenerators.enableScalar(path,blank,context,scalar);check(entry(enabled).token(scalar).equals(DepositGenerators.defaultToken(scalar)));
                DepositGenerators.setScalar(path,enabled,context,scalar,minima.get(scalar));DepositGenerators.setScalar(path,enabled,context,scalar,maxima.get(scalar));
                rejects(() -> DepositGenerators.enableScalar(path,enabled,context,scalar));rejects(() -> DepositGenerators.setScalar(path,blank,context,scalar,"1"));
                for (String bad : List.of("-1","8193","NaN","Infinity")) rejects(() -> DepositGenerators.setScalar(path,enabled,context,scalar,bad));
                if (DepositGenerators.integer(scalar)) for (String bad : List.of("1.000000000000000001","2147483648")) rejects(() -> DepositGenerators.setScalar(path,enabled,context,scalar,bad));
                for (String bad : List.of("null","[]","\"1\"","true")) rejects(() -> DepositGenerators.setScalar(path,document("{\""+scalar+"\":"+bad+"}"),context,scalar,"1"));
            }
            for (String pair : DepositGenerators.PAIRS) {
                String low=DepositGenerators.pairField(pair,true),high=DepositGenerators.pairField(pair,false);
                byte[] enabled=DepositGenerators.setPair(path,blank,context,pair,DepositGenerators.defaultToken(low),DepositGenerators.defaultToken(high));
                check(entry(enabled).token(low).equals(DepositGenerators.defaultToken(low)) && entry(enabled).token(high).equals(DepositGenerators.defaultToken(high)));
                String min=pair.equals("Height") ? "-8192" : "0",max=pair.equals("PerChunk") ? "2048" : "8192";
                DepositGenerators.setPair(path,enabled,context,pair,min,max);DepositGenerators.setPair(path,enabled,context,pair,min,min);
                rejects(() -> DepositGenerators.setPair(path,enabled,context,pair,"2","1"));rejects(() -> DepositGenerators.setPair(path,enabled,context,pair,"-8193",max));rejects(() -> DepositGenerators.setPair(path,enabled,context,pair,min,"8193"));
                for (String bad : List.of("NaN","Infinity","1.000000000000000001","2147483648")) rejects(() -> DepositGenerators.setPair(path,enabled,context,pair,bad,max));
                for (String field : List.of(low,high)) for (String bad : List.of("null","[]","true","\"1\"")) rejects(() -> DepositGenerators.setPair(path,document("{\""+field+"\":"+bad+"}"),context,pair,min,max));
                rejects(() -> DepositGenerators.enableScalar(path,blank,context,low));
            }
            for (String field : DepositGenerators.ENUMS) {
                check(DepositGenerators.choices(field).contains(DepositGenerators.defaultToken(field)));
                for (String choice : DepositGenerators.choices(field)) { check(!DepositGenerators.choiceHelp(field,choice).isBlank());check(entry(DepositGenerators.setChoice(path,blank,context,field,choice)).stringToken(field).equals(choice)); }
                rejects(() -> DepositGenerators.setChoice(path,blank,context,field,"UNKNOWN"));
                for (String bad : List.of("null","[]","1","false")) rejects(() -> DepositGenerators.setChoice(path,document("{\""+field+"\":"+bad+"}"),context,field,DepositGenerators.defaultToken(field)));
                byte[] unknown=document("{\""+field+"\":\"FUTURE\",\"future\":true}");check(text(DepositGenerators.enableBedrock(path,unknown,context)).contains("\""+field+"\":\"FUTURE\""));
            }
            byte[] bedrock=DepositGenerators.enableBedrock(path,blank,context);check(entry(bedrock).token("replaceBedrock").equals("false"));check(entry(DepositGenerators.setBedrock(path,bedrock,context,true)).token("replaceBedrock").equals("true"));
            rejects(() -> DepositGenerators.setBedrock(path,blank,context,true));rejects(() -> DepositGenerators.enableBedrock(path,bedrock,context));
            for (String bad : List.of("null","[]","1","\"false\"")) rejects(() -> DepositGenerators.setBedrock(path,document("{\"replaceBedrock\":"+bad+"}"),context,true));
            byte[] palette=DepositGenerators.enablePalette(path,blank,context);check(DepositGenerators.palette(entry(palette)).isEmpty());
            byte[] one=DepositGenerators.changePalette(path,palette,context,RiverProfiles.Action.ADD,-1,"minecraft:gold_ore");check(DepositGenerators.palette(entry(one)).getFirst().fields().equals(java.util.Set.of("block")));
            rejects(() -> DepositGenerators.changePalette(path,one,context,RiverProfiles.Action.REMOVE,0,null));rejects(() -> DepositGenerators.setBlock(path,one,context,0," "));
            byte[] rawPalette=document("{\"palette\":[{\"block\":\"legacyBlock\\u0061\",\"weight\":999,\"data\":{\"future\":true}},{\"block\":\"unknown:stored\",\"futureBlock\":9007199254740993}]}");
            check(text(DepositGenerators.setBlock(path,rawPalette,context,0,"minecraft:stone")).equals(text(rawPalette).replace("\"legacyBlock\\u0061\"","\"minecraft:stone\"")));
            byte[] moved=DepositGenerators.changePalette(path,rawPalette,context,RiverProfiles.Action.DOWN,0,null);check(Arrays.equals(rawPalette,DepositGenerators.changePalette(path,moved,context,RiverProfiles.Action.UP,1,null)));
            for (String invalid : List.of("null","{}","[null]","[{},1]","[\"snippet/block\"]")) {
                byte[] source=document("{\"palette\":"+invalid+"}");rejects(() -> DepositGenerators.palette(entry(source)));rejects(() -> DepositGenerators.enablePalette(path,source,context));rejects(() -> DepositGenerators.changePalette(path,source,context,RiverProfiles.Action.ADD,-1,"stone"));
            }
            for (String field : DepositGenerators.FILTERS) {
                byte[] initialized=DepositGenerators.enableFilter(path,blank,context,field);check(DepositGenerators.filter(entry(initialized),field).isEmpty());
                String value=DepositGenerators.biomeFilter(field) ? "catalog/forest" : "minecraft:stone";
                byte[] populated=DepositGenerators.changeFilter(path,initialized,context,field,RiverProfiles.Action.ADD,-1,value,workspace);check(DepositGenerators.filter(entry(populated),field).equals(List.of(value)));
                DepositGenerators.setFilter(path,populated,context,field,0,DepositGenerators.biomeFilter(field) ? "minecraft:plains" : "other:custom",workspace);
                check(DepositGenerators.filter(entry(DepositGenerators.changeFilter(path,populated,context,field,RiverProfiles.Action.REMOVE,0,null,null)),field).isEmpty());
                byte[] unknown=document("{\""+field+"\":[ \"legacyFilter\\u0061\",\"Mixed:Old Key\" ],\"future\":true}");
                check(text(DepositGenerators.setFilter(path,unknown,context,field,0,value,workspace)).equals(text(unknown).replace("\"legacyFilter\\u0061\"","\""+value+"\"")));
                byte[] duplicate=DepositGenerators.changeFilter(path,unknown,context,field,RiverProfiles.Action.DUPLICATE,0,null,null);check(DepositGenerators.filter(entry(duplicate),field).size()==3 && text(duplicate).contains("legacyFilter\\u0061"));
                byte[] down=DepositGenerators.changeFilter(path,unknown,context,field,RiverProfiles.Action.DOWN,0,null,null);check(Arrays.equals(unknown,DepositGenerators.changeFilter(path,down,context,field,RiverProfiles.Action.UP,1,null,null)));
                for (String bad : List.of(""," ","Minecraft:stone","minecraft:stone[axis=x]")) rejects(() -> DepositGenerators.changeFilter(path,initialized,context,field,RiverProfiles.Action.ADD,-1,bad,workspace));
                if (DepositGenerators.biomeFilter(field)) rejects(() -> DepositGenerators.changeFilter(path,initialized,context,field,RiverProfiles.Action.ADD,-1,"missing/biome",workspace));
                rejects(() -> DepositGenerators.setFilter(path,populated,context,field,1,value,workspace));rejects(() -> DepositGenerators.enableFilter(path,populated,context,field));
                for (String bad : List.of("null","{}","[null]","[\"stone\",1]","\"snippet\"")) { byte[] invalid=document("{\""+field+"\":"+bad+"}");rejects(() -> DepositGenerators.filter(entry(invalid),field));rejects(() -> DepositGenerators.enableFilter(path,invalid,context,field));rejects(() -> DepositGenerators.changeFilter(path,invalid,context,field,RiverProfiles.Action.ADD,-1,value,workspace)); }
            }
            for (String bad : List.of("null","{}","[null]","[{},1]","[\"snippet/deposit\"]")) {
                byte[] invalid=bytes("{\"deposits\":"+bad+"}");rejects(() -> DepositGenerators.entries(new LosslessJson(invalid)));rejects(() -> DepositGenerators.enable(path,invalid,base));rejects(() -> DepositGenerators.change(path,invalid,base,RiverProfiles.Action.ADD,-1,"stone"));rejects(() -> DepositGenerators.enableBedrock(path,invalid,context));
            }
            for (String wrong : List.of("generators/deposit.json","dimensions/nested/deposit.json",base+"/deposit.txt","deposit.json")) rejectOwner(wrong,base,context,added,workspace);
            for (String otherBase : OreGenerators.CONTEXTS) if (!otherBase.equals(base)) rejectOwner(path(otherBase),base,context,added,workspace);
            byte[] original=document("{\"spawnChance\":1e0,\"varience\":3,\"minHeight\":1,\"maxHeight\":75,\"shape\":\"UNKNOWN\",\"palette\":[{\"block\":\"legacyBlock\\u0061\",\"weight\":999,\"data\":{\"future\":true}}],\"includedBiomes\":[\"Mixed:Old Key\"],\"futureDeposit\":\"雪\\u0061\"}"),copy=original.clone();
            check(text(DepositGenerators.setScalar(path,original,context,"spawnChance","0.25")).equals(text(original).replace("\"spawnChance\":1e0","\"spawnChance\":0.25")));
            byte[] down=DepositGenerators.change(path,original,base,RiverProfiles.Action.DOWN,0,null);check(Arrays.equals(original,DepositGenerators.change(path,down,base,RiverProfiles.Action.UP,1,null)));
            byte[] edited=DepositGenerators.setPair(path,original,context,"Height","-64","160");edited=DepositGenerators.setScalar(path,edited,context,"spawnChance","0.25");edited=DepositGenerators.setBlock(path,edited,context,0,"minecraft:iron_ore");
            for (String raw : List.of("\"futureSibling\":1e2","\"futureRoot\":9007199254740993","\"futureDeposit\":\"雪\\u0061\"","\"shape\":\"UNKNOWN\"","\"weight\":999","\"data\":{\"future\":true}","\"includedBiomes\":[\"Mixed:Old Key\"]","\"ores\":[{\"futureOre\":1e-1}]")) check(text(edited).contains(raw));
            check(Arrays.equals(original,copy));var drafts=new InspectorDrafts();String list=DepositGenerators.listPrefix(base),height=context.prefix()+"minHeight",block=context.palettePrefix()+"0.block",filter=context.filterPrefix("includedBiomes")+"0",other=DepositGenerators.listPrefix(base.equals("biomes") ? "regions" : "biomes")+"0.spawnChance",ore=OreGenerators.listPrefix(base)+"0.threshold";
            for (String key : List.of(height,block,filter,other,ore)) drafts.update(key,"1","2");
            check(!drafts.transitionUnder(list,() -> false,() -> { throw new AssertionError(); }) && drafts.count()==5);
            try { drafts.transitionUnder(list,() -> true,() -> { throw new IOException("failed write"); });throw new AssertionError(); } catch (IOException expected) { check(drafts.count()==5); }
            check(drafts.transitionUnder(context.palettePrefix(),() -> true,() -> {}) && drafts.count()==4 && drafts.contains(height) && drafts.contains(filter));
            check(drafts.transitionUnder(list,() -> true,() -> {}) && drafts.count()==2 && drafts.contains(other) && drafts.contains(ore));
            workspace.replace(path,edited);for (String otherBase : OreGenerators.CONTEXTS) if (!otherBase.equals(base)) check(Arrays.equals(workspace.read(path(otherBase)),blank));
            workspace.save(temp.resolve("deposits-"+base));workspace.exportZip(temp.resolve("deposits-"+base+".zip"));var reopened=PackWorkspace.open(temp.resolve("deposits-"+base));var imported=PackWorkspace.importZip(temp.resolve("deposits-"+base+".zip"));
            for (String resource : workspace.paths()) check(Arrays.equals(workspace.read(resource),reopened.read(resource)) && Arrays.equals(workspace.read(resource),imported.read(resource)));
        }
        rejects(() -> new DepositGenerators.Context("generators",0));rejects(() -> new DepositGenerators.Context("dimensions",-1));
        check(FieldCoverage.fields("depositGenerator").size()==21 && FieldCoverage.fields("depositGenerator").stream().allMatch(FieldCoverage.Field::implemented));
        System.out.println("PASS: all 21 deposit fields across dimension/region/biome lists, source defaults/exact bounds/enums, atomic pairs, unweighted palette and host/biome filters, owner/type guards, scoped drafts, raw/other-resource preservation and Save/ZIP contracts");
    }
    private static void rejectOwner(String path,String base,DepositGenerators.Context context,byte[] source,PackWorkspace workspace) {
        rejects(() -> DepositGenerators.enable(path,bytes("{}"),base));rejects(() -> DepositGenerators.change(path,source,base,RiverProfiles.Action.DUPLICATE,0,null));
        rejects(() -> DepositGenerators.setPair(path,source,context,"Height","1","75"));rejects(() -> DepositGenerators.enableScalar(path,source,context,"spawnChance"));rejects(() -> DepositGenerators.setScalar(path,source,context,"spawnChance","0.5"));
        rejects(() -> DepositGenerators.setChoice(path,source,context,"shape","IRIS"));rejects(() -> DepositGenerators.enableBedrock(path,source,context));rejects(() -> DepositGenerators.setBedrock(path,source,context,true));
        rejects(() -> DepositGenerators.enablePalette(path,source,context));rejects(() -> DepositGenerators.setBlock(path,source,context,0,"stone"));rejects(() -> DepositGenerators.changePalette(path,source,context,RiverProfiles.Action.DUPLICATE,0,null));
        rejects(() -> DepositGenerators.enableFilter(path,source,context,"includedBiomes"));rejects(() -> DepositGenerators.changeFilter(path,source,context,"includedBiomes",RiverProfiles.Action.ADD,-1,"catalog/forest",workspace));rejects(() -> DepositGenerators.setFilter(path,document("{\"includedBiomes\":[\"catalog/forest\"]}"),context,"includedBiomes",0,"minecraft:plains",workspace));
    }
}
