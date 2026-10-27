package dev.irisvisualizer.editor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

final class OreGeneratorTests {
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static String text(byte[] value) { return new String(value,StandardCharsets.UTF_8); }
    private static void check(boolean condition) { if (!condition) throw new AssertionError("Ore generator contract failed"); }
    private static void rejects(Runnable action) { try { action.run(); } catch (IllegalArgumentException expected) { return; }throw new AssertionError("Expected rejected ore operation"); }
    private static byte[] document(String ore) { return bytes("{\r\n \"ores\":[ "+ore+", {\"futureSibling\":1e2} ],\"objects\":[{\"futureObject\":true}],\"futureRoot\":9007199254740993,\"escaped\":\"雪\\u0061\"\r\n}\r\n"); }
    private static LosslessJson ore(byte[] value) { return new LosslessJson(value).objectArray("ores").getFirst(); }
    private static LosslessJson palette(byte[] value) { return OreGenerators.object(ore(value),"palette"); }
    private static String resourcePath(String base) { return base.equals("dimensions") ? "dimensions/ore.json" : base+"/nested/ore.json"; }
    static void run(Path temp) throws Exception {
        check(OreGenerators.rangeDefault("min",false).equals("30") && OreGenerators.rangeDefault("max",false).equals("80"));
        check(OreGenerators.rangeDefault("min",true).equals("16") && OreGenerators.rangeDefault("max",true).equals("32"));
        for (String base : OreGenerators.CONTEXTS) {
            String path=resourcePath(base);check(FieldCoverage.groups(path).containsAll(List.of("oreGenerator","materialPalette","blockData","noiseStyle","range")));var context=new OreGenerators.Context(base,0);var target=context.paletteTarget();
            check(target.ore() && !target.layer() && !target.objectPalette() && target.minimumEntries()==0 && target.defaultBlock().equals("STONE"));
            check(OreGenerators.entries(new LosslessJson(bytes("{}")))==null);byte[] empty=OreGenerators.enable(path,bytes("{}"),base);check(new LosslessJson(empty).objectArray("ores").isEmpty());
            byte[] added=OreGenerators.change(path,empty,base,RiverProfiles.Action.ADD,-1,"minecraft:iron_ore");
            check(ore(added).fields().equals(java.util.Set.of("palette")) && palette(added).objectArray("palette").getFirst().stringToken("block").equals("minecraft:iron_ore"));
            rejects(() -> OreGenerators.change(path,empty,base,RiverProfiles.Action.ADD,-1," "));rejects(() -> OreGenerators.enable(path,empty,base));
            byte[] duplicate=OreGenerators.change(path,added,base,RiverProfiles.Action.DUPLICATE,0,null);check(new LosslessJson(duplicate).objectArray("ores").size()==2);
            check(new LosslessJson(OreGenerators.change(path,added,base,RiverProfiles.Action.REMOVE,0,null)).objectArray("ores").isEmpty());
            byte[] original=document("{\"threshold\":1e-1,\"generateSurface\":false,\"range\":{\"min\":3e1,\"max\":8e1,\"futureRange\":true},\"palette\":{\"zoom\":5e0,\"palette\":[{\"block\":\"legacy\\u0061\",\"weight\":1e0,\"data\":{\"future\":true}},{\"block\":\"unknown:stored\",\"futureBlock\":9007199254740993}],\"style\":{\"style\":\"UNKNOWN\",\"expression\":\"x+z\"},\"futurePalette\":true},\"chanceStyle\":{\"style\":\"UNKNOWN\",\"imageMap\":\"future/map\"},\"futureOre\":\"雪\\u0061\"}");
            check(text(OreGenerators.setThreshold(path,original,context,"0.25")).equals(text(original).replace("\"threshold\":1e-1","\"threshold\":0.25")));
            check(text(OreGenerators.setSurface(path,original,context,true)).equals(text(original).replace("\"generateSurface\":false","\"generateSurface\":true")));
            for (String bad : List.of("-0.1","1.1","NaN","Infinity")) rejects(() -> OreGenerators.setThreshold(path,original,context,bad));
            for (String field : List.of("threshold","generateSurface")) {
                byte[] initialized=OreGenerators.enableScalar(path,document("{}"),context,field);check(ore(initialized).token(field).equals(OreGenerators.defaultToken(field)));
                rejects(() -> OreGenerators.enableScalar(path,initialized,context,field));
                for (String bad : List.of("null","[]","\"value\"")) {
                    byte[] invalid=document("{\""+field+"\":"+bad+"}");
                    if (field.equals("threshold")) rejects(() -> OreGenerators.setThreshold(path,invalid,context,"0.5"));else rejects(() -> OreGenerators.setSurface(path,invalid,context,true));
                }
            }
            rejects(() -> OreGenerators.setThreshold(path,document("{}"),context,"0.5"));rejects(() -> OreGenerators.setSurface(path,document("{}"),context,true));
            byte[] band=OreGenerators.createRange(path,document("{}"),context);check(OreGenerators.object(ore(band),"range").token("min").equals("30") && OreGenerators.object(ore(band),"range").token("max").equals("80"));
            byte[] ranged=OreGenerators.setRange(path,document("{\"range\":{}}"),context,"-8192","8192");check(OreGenerators.object(ore(ranged),"range").token("min").equals("-8192"));
            OreGenerators.setRange(path,band,context,"30.5","80.5");OreGenerators.setRange(path,band,context,"32","32");
            rejects(() -> OreGenerators.setRange(path,band,context,"81","80"));rejects(() -> OreGenerators.setRange(path,band,context,"-8193","80"));rejects(() -> OreGenerators.setRange(path,band,context,"30","8193"));rejects(() -> OreGenerators.setRange(path,band,context,"NaN","80"));
            rejects(() -> OreGenerators.createRange(path,band,context));rejects(() -> OreGenerators.setRange(path,document("{}"),context,"30","80"));
            byte[] created=OreGenerators.createPalette(path,document("{}"),context);check(palette(created).objectArray("palette").isEmpty());
            byte[] classPalette=target.enableEntries(path,document("{\"palette\":{}}"));check(palette(classPalette).objectArray("palette").getFirst().stringToken("block").equals("STONE"));
            byte[] one=target.change(path,created,RiverProfiles.Action.ADD,-1,"minecraft:iron_ore");check(palette(target.change(path,one,RiverProfiles.Action.REMOVE,0,null)).objectArray("palette").isEmpty());
            byte[] weighted=target.enableWeight(path,one,0);check(palette(weighted).objectArray("palette").getFirst().token("weight").equals(DimensionPalettes.weightDefault()));
            target.setWeight(path,weighted,0,"1");target.setWeight(path,weighted,0,"1000");
            for (String bad : List.of("0","1001","1.000000000000000001","2147483648","NaN")) rejects(() -> target.setWeight(path,weighted,0,bad));
            byte[] zoomed=target.enableZoom(path,created);target.setZoom(path,zoomed,"0.0001");rejects(() -> target.setZoom(path,zoomed,"0.00009"));
            check(text(target.setBlock(path,original,0,"minecraft:dirt")).equals(text(original).replace("\"legacy\\u0061\"","\"minecraft:dirt\"")));
            byte[] moved=OreGenerators.change(path,original,base,RiverProfiles.Action.DOWN,0,null);check(Arrays.equals(original,OreGenerators.change(path,moved,base,RiverProfiles.Action.UP,1,null)));
            byte[] movedPalette=target.change(path,original,RiverProfiles.Action.DOWN,0,null);check(Arrays.equals(original,target.change(path,movedPalette,RiverProfiles.Action.UP,1,null)));
            for (String field : List.of("chanceStyle","palette")) {
                var style=context.styleTarget(field);byte[] container=field.equals("palette") ? created : document("{}");
                check(style.resourceBase().equals(base) && style.ore() && style.oreContext().owner().equals(context) && style.draftPrefix().startsWith(context.prefix(field)));
                byte[] styled=NoiseStyleFields.enable(path,container,style,"style");
                var owner=field.equals("palette") ? palette(styled) : ore(styled);check(owner.stringPath(style.member()+".style").equals("STATIC"));
                byte[] inline=field.equals("palette") ? document("{\"palette\":{\"style\":{}}}") : document("{\"chanceStyle\":{}}");
                byte[] explicit=NoiseStyleFields.enable(path,inline,style,"style");check((field.equals("palette") ? palette(explicit) : ore(explicit)).stringPath(style.member()+".style").equals("FLAT"));
                for (String choice : NoiseStyleFields.choices()) NoiseStyleFields.editStyle(path,styled,style,choice);
                for (String member : NoiseStyleFields.EDITABLE) if (!member.equals("style")) {
                    byte[] enabled=NoiseStyleFields.enable(path,styled,style,member);NoiseStyleFields.editText(path,enabled,style,member,member.equals("cellularFrequency") ? "0" : "1");rejects(() -> NoiseStyleFields.editText(path,enabled,style,member,"NaN"));
                }
                for (String wrongBase : OreGenerators.CONTEXTS) if (!wrongBase.equals(base)) rejects(() -> NoiseStyleFields.enable(wrongBase+"/ore.json",container,style,"style"));
            }
            for (String invalid : List.of("null","[]","true","1","\"snippet/value\"")) for (String field : List.of("palette","range")) {
                byte[] source=document("{\""+field+"\":"+invalid+"}");rejects(() -> OreGenerators.object(ore(source),field));
                if (field.equals("palette")) { rejects(() -> OreGenerators.createPalette(path,source,context));rejects(() -> target.enableZoom(path,source));rejects(() -> NoiseStyleFields.enable(path,source,context.styleTarget("palette"),"style")); }
                else { rejects(() -> OreGenerators.createRange(path,source,context));rejects(() -> OreGenerators.setRange(path,source,context,"30","80")); }
            }
            for (String invalid : List.of("null","{}","[null]","[{},1]","[\"snippet/ore\"]")) {
                byte[] source=bytes("{\"ores\":"+invalid+"}");rejects(() -> OreGenerators.entries(new LosslessJson(source)));rejects(() -> OreGenerators.enable(path,source,base));rejects(() -> OreGenerators.change(path,source,base,RiverProfiles.Action.ADD,-1,"stone"));
            }
            for (String wrong : List.of("generators/ore.json","dimensions/nested/ore.json",base+"/ore.txt","ore.json")) rejectOwner(wrong,base,context,original);
            for (String wrong : OreGenerators.CONTEXTS) if (!wrong.equals(base)) rejectOwner(wrong+"/ore.json",base,context,original);
            for (String bad : List.of("ores.-1.chanceStyle","ores.01.chanceStyle","ores.2147483648.palette.style","ores.0.palette","ores.0.range.style")) check(OreGenerators.styleContext(base,bad)==null);
            var drafts=new InspectorDrafts();String list=OreGenerators.listPrefix(base),threshold=context.prefix()+"threshold",material=target.listPrefix()+"0.block",noise=context.styleTarget("chanceStyle").draftPrefix()+"zoom",other=OreGenerators.listPrefix(base.equals("biomes") ? "regions" : "biomes")+"0.threshold";
            for (String key : List.of(threshold,material,noise,other)) drafts.update(key,"1","2");
            check(!drafts.transitionUnder(list,() -> false,() -> { throw new AssertionError(); }) && drafts.count()==4);
            try { drafts.transitionUnder(list,() -> true,() -> { throw new IOException("failed write"); });throw new AssertionError(); } catch (IOException expected) { check(drafts.count()==4); }
            check(drafts.transitionUnder(target.listPrefix(),() -> true,() -> {}) && drafts.count()==3 && drafts.contains(threshold) && drafts.contains(noise));
            check(drafts.transitionUnder(list,() -> true,() -> {}) && drafts.count()==1 && drafts.contains(other));
            byte[] edited=OreGenerators.setThreshold(path,original,context,"0.25");edited=OreGenerators.setRange(path,edited,context,"-64","160");edited=target.setWeight(path,edited,0,"1000");edited=NoiseStyleFields.editStyle(path,edited,context.styleTarget("chanceStyle"),"SIMPLEX");
            for (String raw : List.of("\"futureSibling\":1e2","\"futureRoot\":9007199254740993","\"futureOre\":\"雪\\u0061\"","\"futureRange\":true","\"futurePalette\":true","\"futureBlock\":9007199254740993","\"imageMap\":\"future/map\"","\"expression\":\"x+z\"","\"data\":{\"future\":true}","\"objects\":[{\"futureObject\":true}]")) check(text(edited).contains(raw));
            var workspace=PackWorkspace.newPack(Map.of("dimensions/ore.json",original,"regions/nested/ore.json",original,"biomes/nested/ore.json",original,"objects/opaque.iob",new byte[]{0,127,-1,42}));workspace.replace(path,edited);
            for (String otherBase : OreGenerators.CONTEXTS) if (!otherBase.equals(base)) check(Arrays.equals(workspace.read(resourcePath(otherBase)),original));
            workspace.save(temp.resolve("ores-"+base));workspace.exportZip(temp.resolve("ores-"+base+".zip"));var reopened=PackWorkspace.open(temp.resolve("ores-"+base));var imported=PackWorkspace.importZip(temp.resolve("ores-"+base+".zip"));
            for (String resource : workspace.paths()) check(Arrays.equals(workspace.read(resource),reopened.read(resource)) && Arrays.equals(workspace.read(resource),imported.read(resource)));
        }
        rejects(() -> new OreGenerators.Context("generators",0));rejects(() -> new OreGenerators.Context("dimensions",-1));
        rejects(() -> new GeneralBlockPalettes.Target("rockPalette",null,-1,null,-1,null,new OreGenerators.Context("dimensions",0)));
        check(FieldCoverage.fields("oreGenerator").size()==5 && FieldCoverage.fields("oreGenerator").stream().allMatch(FieldCoverage.Field::implemented));
        System.out.println("PASS: dimension/region/biome ore lists, contextual/class palette/range/style defaults, threshold/flag/atomic ranges, weighted materials/all styles, typed owner guards, scoped drafts, raw/other-resource preservation and Save/ZIP contracts");
    }
    private static void rejectOwner(String path,String base,OreGenerators.Context context,byte[] original) {
        rejects(() -> OreGenerators.enable(path,bytes("{}"),base));rejects(() -> OreGenerators.change(path,original,base,RiverProfiles.Action.DUPLICATE,0,null));
        rejects(() -> OreGenerators.enableScalar(path,document("{}"),context,"threshold"));rejects(() -> OreGenerators.setThreshold(path,original,context,"0.5"));rejects(() -> OreGenerators.setSurface(path,original,context,true));
        rejects(() -> OreGenerators.createRange(path,document("{}"),context));rejects(() -> OreGenerators.setRange(path,original,context,"30","80"));rejects(() -> OreGenerators.createPalette(path,document("{}"),context));
        rejects(() -> context.paletteTarget().setBlock(path,original,0,"stone"));rejects(() -> context.paletteTarget().setWeight(path,original,0,"2"));rejects(() -> context.paletteTarget().enableZoom(path,original));
        rejects(() -> NoiseStyleFields.editStyle(path,original,context.styleTarget("chanceStyle"),"STATIC"));rejects(() -> NoiseStyleFields.editStyle(path,original,context.styleTarget("palette"),"STATIC"));
    }
}
