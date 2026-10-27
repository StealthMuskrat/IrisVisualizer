package dev.irisvisualizer.editor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class DimensionPaletteTests {
    private static final String PATH="dimensions/root-palettes.json";
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static String text(byte[] value) { return new String(value,StandardCharsets.UTF_8); }
    private static void check(boolean value) { if (!value) throw new AssertionError("Dimension palette/fallback contract failed"); }
    private static void rejects(Runnable action) { try { action.run(); } catch (IllegalArgumentException expected) { return; }throw new AssertionError("Expected rejected dimension palette operation"); }
    private static LosslessJson palette(byte[] value,String field) { return DimensionPalettes.object(new LosslessJson(value),field); }
    private static LosslessJson entry(byte[] value,String field,int index) { return DimensionPalettes.entries(palette(value,field)).get(index); }
    private static byte[] document(String field,String value) { return bytes("{\r\n \""+field+"\":"+value+",\"futureRoot\":9007199254740993,\"escaped\":\"雪\\u0061\"\r\n}\r\n"); }
    static void run(Path temp) throws Exception {
        check(DimensionPalettes.maximumWeight()==1000 && DimensionPalettes.weightDefault().equals("1") && DimensionPalettes.classBlock().equals("STONE"));
        for (String field : DimensionPalettes.OBJECT_FIELDS) {
            String contextual=field.equals("rockPalette") ? "stone" : "water";
            byte[] created=DimensionPalettes.create(PATH,bytes("{}"),field);
            check(entry(created,field,0).stringToken("block").equals(contextual) && entry(created,field,0).fields().equals(Set.of("block")));
            check(palette(created,field).fields().equals(Set.of("palette")));
            rejects(() -> DimensionPalettes.create(PATH,created,field));
            byte[] bare=document(field,"{\"futurePalette\":true}");
            byte[] initialized=DimensionPalettes.enableEntries(PATH,bare,field);
            check(entry(initialized,field,0).stringToken("block").equals("STONE") && palette(initialized,field).token("futurePalette").equals("true"));
            rejects(() -> DimensionPalettes.enableEntries(PATH,initialized,field));
            byte[] original=document(field,"{\"palette\":[ {\"block\":\"custom:existing\",\"weight\":1,\"data\":{\"future\":\"雪\\u0061\"},\"tileData\":{},\"debug\":true,\"backup\":{\"block\":\"unresolved\"},\"large\":9007199254740993}, {\"block\":\"stone\",\"weight\":1000} ],\"style\":{\"style\":\"UNKNOWN_STYLE\",\"zoom\":1e0,\"cellularFrequency\":0,\"cellularZoom\":1,\"expression\":\"kept\",\"fracture\":{}},\"zoom\":5e0,\"futurePalette\":true}"),copy=original.clone();
            for (String block : List.of("stone","water","lava","custom:unverified[state=value]"," Minecraft:Stone ")) {
                check(entry(DimensionPalettes.setBlock(PATH,original,field,0,block),field,0).stringToken("block").equals(block));
            }
            for (String invalid : List.of(""," ","\t")) { rejects(() -> DimensionPalettes.setBlock(PATH,original,field,0,invalid));rejects(() -> DimensionPalettes.change(PATH,original,field,RiverProfiles.Action.ADD,-1,invalid)); }
            check(text(DimensionPalettes.setBlock(PATH,original,field,0,"minecraft:deepslate")).equals(text(original).replace("\"block\":\"custom:existing\"","\"block\":\"minecraft:deepslate\"")));
            for (String value : List.of("1","64","65","1e3")) DimensionPalettes.setWeight(PATH,original,field,0,value);
            check(text(DimensionPalettes.setWeight(PATH,original,field,0,"1e3")).equals(text(original).replace("\"weight\":1,","\"weight\":1000,")));
            for (String invalid : List.of("0","1001","1.000000000000000001","2147483648","NaN","Infinity","")) rejects(() -> DimensionPalettes.setWeight(PATH,original,field,0,invalid));
            check(entry(DimensionPalettes.enableWeight(PATH,created,field,0),field,0).token("weight").equals("1"));rejects(() -> DimensionPalettes.setWeight(PATH,created,field,0,"2"));
            byte[] copied=DimensionPalettes.change(PATH,original,field,RiverProfiles.Action.DUPLICATE,0,null);
            check(Arrays.equals(entry(copied,field,0).sourceBytes(),entry(copied,field,1).sourceBytes()));
            byte[] moved=DimensionPalettes.change(PATH,original,field,RiverProfiles.Action.DOWN,0,null);check(Arrays.equals(entry(original,field,0).sourceBytes(),entry(moved,field,1).sourceBytes()));
            DimensionPalettes.change(PATH,moved,field,RiverProfiles.Action.UP,1,null);
            check(DimensionPalettes.entries(palette(DimensionPalettes.change(PATH,original,field,RiverProfiles.Action.REMOVE,0,null),field)).size()==1);
            rejects(() -> DimensionPalettes.change(PATH,created,field,RiverProfiles.Action.REMOVE,0,null));rejects(() -> DimensionPalettes.change(PATH,original,field,RiverProfiles.Action.UP,0,null));rejects(() -> DimensionPalettes.change(PATH,original,field,RiverProfiles.Action.DOWN,1,null));
            check(entry(DimensionPalettes.change(PATH,document(field,"{\"palette\":[]}"),field,RiverProfiles.Action.ADD,-1,"air"),field,0).stringToken("block").equals("air"));
            check(entry(DimensionPalettes.change(PATH,original,field,RiverProfiles.Action.ADD,-1,"air"),field,2).fields().equals(Set.of("block")));
            byte[] missingBlock=document(field,"{\"palette\":[{}]}");check(entry(DimensionPalettes.setBlock(PATH,missingBlock,field,0,"stone"),field,0).fields().equals(Set.of("block")));
            var styleTarget=NoiseStyleFields.Target.dimensionPalette(field);check(NoiseStyleFields.contextDefault(styleTarget).equals("STATIC"));
            check(text(DimensionPalettes.setZoom(PATH,original,field,"10")).equals(text(original).replace("\"zoom\":5e0","\"zoom\":10")));
            check(palette(DimensionPalettes.enableZoom(PATH,bare,field),field).token("palette")==null);
            for (String invalid : List.of("0","0.00009","NaN","Infinity","1e999")) rejects(() -> DimensionPalettes.setZoom(PATH,original,field,invalid));
            for (String choice : NoiseStyleFields.choices()) check(text(NoiseStyleFields.editStyle(PATH,original,styleTarget,choice)).equals(text(original).replace("\"UNKNOWN_STYLE\"","\""+choice+"\"")));
            check(palette(NoiseStyleFields.enable(PATH,bare,styleTarget,"style"),field).stringPath("style.style").equals("STATIC"));
            check(palette(NoiseStyleFields.enable(PATH,document(field,"{\"style\":{}}"),styleTarget,"style"),field).stringPath("style.style").equals("FLAT"));
            byte[] zero=NoiseStyleFields.editText(PATH,original,styleTarget,"cellularZoom","0");rejects(() -> NoiseStyleFields.editText(PATH,zero,styleTarget,"cellularFrequency","1"));
            byte[] active=NoiseStyleFields.editText(PATH,original,styleTarget,"cellularFrequency","1");rejects(() -> NoiseStyleFields.editText(PATH,active,styleTarget,"cellularZoom","0"));
            check(DimensionFields.readOnlyPaths(new LosslessJson(original)).containsAll(List.of(field+".futurePalette",field+".palette[0].data",field+".palette[0].backup")));
            for (String wrong : List.of("null","[]","true","1","\"snippet/palette/example\"")) {
                byte[] invalid=document(field,wrong);rejects(() -> DimensionPalettes.create(PATH,invalid,field));rejects(() -> DimensionPalettes.enableEntries(PATH,invalid,field));rejects(() -> NoiseStyleFields.enable(PATH,invalid,styleTarget,"style"));
                check(DimensionFields.readOnlyPaths(new LosslessJson(invalid)).contains(field));
                byte[] invalidList=document(field,"{\"palette\":"+wrong+",\"zoom\":5}");
                if (!wrong.equals("[]")) rejects(() -> DimensionPalettes.change(PATH,invalidList,field,RiverProfiles.Action.ADD,-1,"stone"));
                rejects(() -> DimensionPalettes.enableEntries(PATH,invalidList,field));check(palette(DimensionPalettes.setZoom(PATH,invalidList,field,"10"),field).token("palette").equals(wrong));
                byte[] invalidEntry=document(field,"{\"palette\":[{\"block\":"+wrong+",\"weight\":"+wrong+"}]}");
                if (!wrong.startsWith("\"")) rejects(() -> DimensionPalettes.setBlock(PATH,invalidEntry,field,0,"stone"));
                if (!wrong.equals("1")) rejects(() -> DimensionPalettes.setWeight(PATH,invalidEntry,field,0,"2"));
            }
            byte[] mixed=document(field,"{\"palette\":[{},1]}");rejects(() -> DimensionPalettes.change(PATH,mixed,field,RiverProfiles.Action.ADD,-1,"stone"));
            rejects(() -> DimensionPalettes.enableZoom(PATH,bytes("{}"),field));rejects(() -> NoiseStyleFields.enable(PATH,bytes("{}"),styleTarget,"style"));
            for (String path : List.of("regions/a.json","biomes/a.json","generators/a.json","dimensions/nested/a.json","a.json")) {
                rejects(() -> DimensionPalettes.create(path,bytes("{}"),field));rejects(() -> DimensionPalettes.setBlock(path,original,field,0,"stone"));rejects(() -> NoiseStyleFields.editStyle(path,original,styleTarget,"STATIC"));
            }
            rejects(() -> new NoiseStyleFields.Target("biomes",styleTarget.objectField(),null));rejects(() -> new NoiseStyleFields.Target("dimensions",styleTarget.objectField(),null,0));
            var drafts=new InspectorDrafts();String listKey=DimensionPalettes.listPrefix(field)+"0.block",noiseKey=DimensionPalettes.noisePrefix(field)+"zoom",otherKey=DimensionPalettes.listPrefix(field.equals("rockPalette") ? "fluidPalette" : "rockPalette")+"0.weight",hydroKey=DeepFluids.systemPrefix(0)+"spacing";
            for (String key : List.of(listKey,noiseKey,otherKey,hydroKey)) drafts.update(key,"1","2");
            check(!drafts.transitionUnder(DimensionPalettes.listPrefix(field),() -> false,() -> { throw new AssertionError(); }) && drafts.count()==4);
            try { drafts.transitionUnder(DimensionPalettes.listPrefix(field),() -> true,() -> { throw new IOException("failed write"); });throw new AssertionError(); } catch (IOException expected) { check(drafts.count()==4); }
            check(drafts.transitionUnder(DimensionPalettes.listPrefix(field),() -> true,() -> {}) && drafts.count()==3 && drafts.contains(noiseKey) && drafts.contains(otherKey));
            check(Arrays.equals(original,copy));var workspace=PackWorkspace.newPack(Map.of(PATH,original,"dimensions/defaults.json",created,"dimensions/class-defaults.json",initialized));
            workspace.save(temp.resolve("dimension-"+field));workspace.exportZip(temp.resolve("dimension-"+field+".zip"));var reopened=PackWorkspace.open(temp.resolve("dimension-"+field));var imported=PackWorkspace.importZip(temp.resolve("dimension-"+field+".zip"));
            for (String path : workspace.paths()) check(Arrays.equals(workspace.read(path),reopened.read(path)) && Arrays.equals(workspace.read(path),imported.read(path)));
        }
        rejects(() -> DimensionPalettes.create(PATH,bytes("{}"),"unknown"));rejects(() -> NoiseStyleFields.Target.dimensionPalette("fluidPalette.style"));
        check(SurfaceLayerFallbacks.dimensionDefault().equals("ROCK") && SurfaceLayerFallbacks.choices().equals(List.of("ROCK","TOP_LAYER")));
        for (String owner : List.of(PATH,"biomes/a.json","biomes/nested/a.json")) {
            byte[] source=bytes("{\r\n \"surfaceLayerFallback\":\"FUTURE\",\"future\":9007199254740993\r\n}\r\n");
            for (String choice : SurfaceLayerFallbacks.choices()) {
                check(text(SurfaceLayerFallbacks.set(owner,source,choice)).equals(text(source).replace("\"FUTURE\"","\""+choice+"\"")));
                check(new LosslessJson(SurfaceLayerFallbacks.set(owner,bytes("{}"),choice)).stringToken("surfaceLayerFallback").equals(choice));check(!SurfaceLayerFallbacks.choiceHelp(choice).isBlank());
            }
            rejects(() -> SurfaceLayerFallbacks.set(owner,source,"FUTURE"));
            for (String wrong : List.of("true","1","[]","{}")) rejects(() -> SurfaceLayerFallbacks.set(owner,bytes("{\"surfaceLayerFallback\":"+wrong+"}"),"ROCK"));
            if (BiomeFields.biome(owner)) {
                check(Arrays.equals(SurfaceLayerFallbacks.set(owner,bytes("{}"),null),bytes("{}")));
                check(new LosslessJson(SurfaceLayerFallbacks.set(owner,source,null)).token("surfaceLayerFallback").equals("null"));
                check(new LosslessJson(SurfaceLayerFallbacks.set(owner,bytes("{\"surfaceLayerFallback\":null}"),"TOP_LAYER")).stringToken("surfaceLayerFallback").equals("TOP_LAYER"));
            } else { rejects(() -> SurfaceLayerFallbacks.set(owner,source,null));rejects(() -> SurfaceLayerFallbacks.set(owner,bytes("{\"surfaceLayerFallback\":null}"),"ROCK")); }
        }
        for (String wrong : List.of("regions/a.json","generators/a.json","dimensions/nested/a.json","a.json")) rejects(() -> SurfaceLayerFallbacks.set(wrong,bytes("{}"),"ROCK"));
        var fallbackWorkspace=PackWorkspace.newPack(Map.of(PATH,SurfaceLayerFallbacks.set(PATH,bytes("{}"),"TOP_LAYER"),"biomes/a.json",SurfaceLayerFallbacks.set("biomes/a.json",bytes("{\"surfaceLayerFallback\":\"ROCK\"}"),null)));
        fallbackWorkspace.save(temp.resolve("surface-fallbacks"));fallbackWorkspace.exportZip(temp.resolve("surface-fallbacks.zip"));var imported=PackWorkspace.importZip(temp.resolve("surface-fallbacks.zip"));
        for (String path : fallbackWorkspace.paths()) check(Arrays.equals(fallbackWorkspace.read(path),imported.read(path)));
        check(FieldCoverage.groups(PATH).containsAll(List.of("materialPalette","blockData","riverProfile","standingSurfacePool","deepFluid","noiseStyle")));
        System.out.println("PASS: dimension palette contextual/class defaults, annotation weights 1-1000, all list/noise operations, nullable surface fallback choices, type/owner/draft guards and lossless Save/ZIP contracts");
    }
}
