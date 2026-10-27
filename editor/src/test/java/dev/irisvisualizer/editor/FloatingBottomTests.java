package dev.irisvisualizer.editor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class FloatingBottomTests {
    private static final String PATH="biomes/nested/underside.json";
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static String text(byte[] value) { return new String(value,StandardCharsets.UTF_8); }
    private static void check(boolean condition) { if (!condition) throw new AssertionError("Floating underside contract failed"); }
    private static void rejects(Runnable action) { try { action.run(); } catch (IllegalArgumentException expected) { return; }throw new AssertionError("Expected rejected floating underside operation"); }
    private static byte[] document(String token) { return bytes("{\r\n \"floatingChildBiomes\":[ {\"bottomPalette\":"+token+",\"bottomPaletteMode\":\"DEPTH\",\"carving\":\"kept\",\"footprintStyle\":{\"style\":\"UNKNOWN\"}}, {\"bottomPalette\":[{}],\"futureEntry\":9007199254740993} ],\"futureRoot\":9007199254740993,\"escaped\":\"雪\\u0061\"\r\n}\r\n"); }
    private static LosslessJson entry(byte[] value,int index) { return FloatingBiomes.entries(new LosslessJson(value)).get(index); }
    private static List<LosslessJson> layers(byte[] value) { return FloatingBottomPalettes.layers(entry(value,0)); }
    private static LosslessJson layer(byte[] value) { return layers(value).getFirst(); }
    private static List<LosslessJson> blocks(byte[] value) { return DimensionPalettes.entries(layer(value)); }
    static void run(Path temp) throws Exception {
        byte[] absent=bytes("{\"floatingChildBiomes\":[{},{}],\"future\":9007199254740993}");
        check(layers(absent)==null && layers(document("null"))==null);
        byte[] enabled=FloatingBottomPalettes.enable(PATH,absent,0);check(layers(enabled).isEmpty() && entry(enabled,0).fields().equals(Set.of("bottomPalette")));
        check(layers(FloatingBottomPalettes.enable(PATH,document("null"),0)).isEmpty());rejects(() -> FloatingBottomPalettes.enable(PATH,enabled,0));
        rejects(() -> FloatingBottomPalettes.change(PATH,absent,0,RiverProfiles.Action.ADD,-1));rejects(() -> FloatingBottomPalettes.change(PATH,document("null"),0,RiverProfiles.Action.ADD,-1));
        byte[] added=FloatingBottomPalettes.change(PATH,document("[]"),0,RiverProfiles.Action.ADD,-1);check(layer(added).fields().isEmpty() && entry(added,0).stringToken("bottomPaletteMode").equals("DEPTH"));
        check(layers(FloatingBottomPalettes.change(PATH,added,0,RiverProfiles.Action.REMOVE,0)).isEmpty());
        var target=GeneralBlockPalettes.Target.floatingBottom(0,0);check(target.floatingBottom() && !target.layer() && !target.slabWall() && target.minimumEntries()==1 && target.defaultBlock().equals("GRASS_BLOCK"));
        byte[] initialized=target.enableEntries(PATH,added);check(blocks(initialized).getFirst().stringToken("block").equals("GRASS_BLOCK") && blocks(initialized).getFirst().fields().equals(Set.of("block")));
        rejects(() -> target.enableEntries(PATH,initialized));rejects(() -> target.change(PATH,initialized,RiverProfiles.Action.REMOVE,0,null));
        byte[] emptyBlocks=document("[{\"palette\":[]}]");check(blocks(target.change(PATH,emptyBlocks,RiverProfiles.Action.ADD,-1,"air")).getFirst().stringToken("block").equals("air"));
        byte[] weighted=target.enableWeight(PATH,initialized,0);check(blocks(weighted).getFirst().token("weight").equals("1"));
        for (String weight : List.of("1","64","65","1000")) check(blocks(target.setWeight(PATH,weighted,0,weight)).getFirst().token("weight").equals(weight));
        for (String bad : List.of("0","1001","1.000000000000000001","NaN")) rejects(() -> target.setWeight(PATH,weighted,0,bad));
        byte[] thickness=FloatingBottomPalettes.setThickness(PATH,added,0,0,"0","2032");check(layer(thickness).token("minHeight").equals("0") && layer(thickness).token("maxHeight").equals("2032"));
        FloatingBottomPalettes.setThickness(PATH,added,0,0,"2e1","20");
        for (String[] bad : new String[][]{{"-1","1"},{"0","0"},{"2","1"},{"0","2033"},{"1.000000000000000001","2"},{"0","NaN"}}) rejects(() -> FloatingBottomPalettes.setThickness(PATH,added,0,0,bad[0],bad[1]));
        byte[] slope=FloatingBottomPalettes.createSlope(PATH,added,0,0);check(BiomeLayers.slope(layer(slope)).fields().isEmpty());
        check(BiomeLayers.slope(layer(FloatingBottomPalettes.createSlope(PATH,document("[{\"slopeCondition\":null}]"),0,0))).fields().isEmpty());
        rejects(() -> FloatingBottomPalettes.createSlope(PATH,slope,0,0));rejects(() -> FloatingBottomPalettes.setSlope(PATH,added,0,0,"0","10"));
        rejects(() -> FloatingBottomPalettes.setSlope(PATH,document("[{\"slopeCondition\":null}]"),0,0,"0","10"));
        FloatingBottomPalettes.setSlope(PATH,slope,0,0,"0","1024");FloatingBottomPalettes.setSlope(PATH,slope,0,0,"0.25","1.5");
        for (String[] bad : new String[][]{{"-1","10"},{"0","1025"},{"2","1"},{"NaN","10"}}) rejects(() -> FloatingBottomPalettes.setSlope(PATH,slope,0,0,bad[0],bad[1]));
        byte[] zoomed=target.enableZoom(PATH,added);check(layer(zoomed).token("zoom").equals("5") && layer(zoomed).token("palette")==null);target.setZoom(PATH,zoomed,"0.0001");
        for (String bad : List.of("0","0.00009","NaN","Infinity")) rejects(() -> target.setZoom(PATH,zoomed,bad));
        var styleTarget=target.styleTarget();check(styleTarget.floatingBottom() && !styleTarget.floating() && !styleTarget.optionalFloating() && styleTarget.member().equals("style") && NoiseStyleFields.contextDefault(styleTarget).equals("STATIC"));
        check(layer(NoiseStyleFields.enable(PATH,added,styleTarget,"style")).stringPath("style.style").equals("STATIC"));
        check(layer(NoiseStyleFields.enable(PATH,document("[{\"style\":{}}]"),styleTarget,"style")).stringPath("style.style").equals("FLAT"));
        String first="{\"minHeight\":1,\"maxHeight\":3,\"slopeCondition\":{\"minimumSlope\":0,\"maximumSlope\":10,\"futureSlope\":true},\"palette\":[ {\"block\":\"custom:stored\",\"weight\":1,\"data\":{\"future\":\"雪\\u0061\"},\"large\":9007199254740993}, {\"block\":\"stone\",\"weight\":1000} ],\"zoom\":5e0,\"style\":{\"style\":\"UNKNOWN\",\"zoom\":1e0,\"cellularFrequency\":0,\"cellularZoom\":1,\"expression\":\"kept\"},\"futureLayer\":true}";
        byte[] original=document("[ "+first+",\r\n {} ]"),copy=original.clone();
        check(text(FloatingBottomPalettes.setThickness(PATH,original,0,0,"2","4")).equals(text(original).replace("\"minHeight\":1","\"minHeight\":2").replace("\"maxHeight\":3","\"maxHeight\":4")));
        check(text(FloatingBottomPalettes.setSlope(PATH,original,0,0,"1","9")).equals(text(original).replace("\"minimumSlope\":0","\"minimumSlope\":1").replace("\"maximumSlope\":10","\"maximumSlope\":9")));
        check(text(target.setBlock(PATH,original,0,"air")).equals(text(original).replace("\"block\":\"custom:stored\"","\"block\":\"air\"")));
        check(text(target.setWeight(PATH,original,0,"1e3")).equals(text(original).replace("\"weight\":1,","\"weight\":1000,")));
        check(text(target.setZoom(PATH,original,"10")).equals(text(original).replace("\"zoom\":5e0","\"zoom\":10")));
        for (String choice : NoiseStyleFields.choices()) check(text(NoiseStyleFields.editStyle(PATH,original,styleTarget,choice)).equals(text(original).replace("\"style\":\"UNKNOWN\",\"zoom\":1e0","\"style\":\""+choice+"\",\"zoom\":1e0")));
        byte[] inactive=NoiseStyleFields.editText(PATH,original,styleTarget,"cellularZoom","0");rejects(() -> NoiseStyleFields.editText(PATH,inactive,styleTarget,"cellularFrequency","1"));
        byte[] active=NoiseStyleFields.editText(PATH,original,styleTarget,"cellularFrequency","1");rejects(() -> NoiseStyleFields.editText(PATH,active,styleTarget,"cellularZoom","0"));
        byte[] duplicated=FloatingBottomPalettes.change(PATH,original,0,RiverProfiles.Action.DUPLICATE,0);check(Arrays.equals(layers(duplicated).get(0).sourceBytes(),layers(duplicated).get(1).sourceBytes()));
        byte[] moved=FloatingBottomPalettes.change(PATH,original,0,RiverProfiles.Action.DOWN,0);check(Arrays.equals(layers(original).getFirst().sourceBytes(),layers(moved).get(1).sourceBytes()));
        check(Arrays.equals(original,FloatingBottomPalettes.change(PATH,moved,0,RiverProfiles.Action.UP,1)));check(layers(FloatingBottomPalettes.change(PATH,original,0,RiverProfiles.Action.REMOVE,0)).size()==1);
        rejects(() -> FloatingBottomPalettes.change(PATH,original,0,RiverProfiles.Action.UP,0));rejects(() -> FloatingBottomPalettes.change(PATH,original,0,RiverProfiles.Action.DOWN,1));
        byte[] copiedBlock=target.change(PATH,original,RiverProfiles.Action.DUPLICATE,0,null);check(Arrays.equals(blocks(copiedBlock).get(0).sourceBytes(),blocks(copiedBlock).get(1).sourceBytes()));
        byte[] movedBlock=target.change(PATH,original,RiverProfiles.Action.DOWN,0,null);check(Arrays.equals(original,target.change(PATH,movedBlock,RiverProfiles.Action.UP,1,null)));
        check(blocks(target.change(PATH,original,RiverProfiles.Action.REMOVE,0,null)).size()==1);
        var paths=FloatingBottomPalettes.readOnlyPaths(entry(original,0),0);check(paths.containsAll(List.of("floatingChildBiomes[0].bottomPalette[0].futureLayer","floatingChildBiomes[0].bottomPalette[0].palette[0].data","floatingChildBiomes[0].bottomPalette[0].slopeCondition.futureSlope")));
        check(FloatingBottomPalettes.readOnlyPaths(entry(document("[{\"slopeCondition\":null}]"),0),0).isEmpty());
        for (String bad : List.of("{}","true","1","\"snippet/palette\"","[null]","[{},1]","[\"snippet/biome-palette\"]")) {
            byte[] invalid=document(bad);rejects(() -> FloatingBottomPalettes.layers(entry(invalid,0)));rejects(() -> FloatingBottomPalettes.enable(PATH,invalid,0));rejects(() -> FloatingBottomPalettes.change(PATH,invalid,0,RiverProfiles.Action.ADD,-1));
            check(FloatingBottomPalettes.readOnlyPaths(entry(invalid,0),0).contains("floatingChildBiomes[0].bottomPalette"));
        }
        for (String bad : List.of("null","[]","true","\"1\"")) {
            byte[] invalid=document("[{\"maxHeight\":"+bad+",\"palette\":"+bad+",\"style\":"+bad+",\"slopeCondition\":"+bad+"}]");
            rejects(() -> FloatingBottomPalettes.setThickness(PATH,invalid,0,0,"1","2"));rejects(() -> target.enableEntries(PATH,invalid));rejects(() -> NoiseStyleFields.enable(PATH,invalid,styleTarget,"style"));
            if (bad.equals("[]")) check(blocks(target.change(PATH,invalid,RiverProfiles.Action.ADD,-1,"air")).size()==1);else rejects(() -> target.change(PATH,invalid,RiverProfiles.Action.ADD,-1,"air"));
            if (!bad.equals("null")) rejects(() -> FloatingBottomPalettes.createSlope(PATH,invalid,0,0));
            check(layer(target.enableZoom(PATH,invalid)).token("palette").equals(bad));
        }
        for (String root : List.of("{}","{\"floatingChildBiomes\":null}","{\"floatingChildBiomes\":[]}","{\"floatingChildBiomes\":[null]}")) {
            byte[] invalid=bytes(root);rejects(() -> FloatingBottomPalettes.enable(PATH,invalid,0));rejects(() -> NoiseStyleFields.enable(PATH,invalid,styleTarget,"style"));
        }
        for (String path : List.of("dimensions/a.json","regions/a.json","generators/a.json","a.json","biomes/a.txt")) {
            rejects(() -> FloatingBottomPalettes.enable(path,absent,0));rejects(() -> FloatingBottomPalettes.change(path,original,0,RiverProfiles.Action.ADD,-1));rejects(() -> FloatingBottomPalettes.setThickness(path,original,0,0,"1","2"));
            rejects(() -> FloatingBottomPalettes.setSlope(path,original,0,0,"0","10"));rejects(() -> target.setBlock(path,original,0,"air"));rejects(() -> NoiseStyleFields.enable(path,added,styleTarget,"style"));
        }
        rejects(() -> GeneralBlockPalettes.Target.floatingBottom(-1,0));rejects(() -> GeneralBlockPalettes.Target.floatingBottom(0,-1));rejects(() -> NoiseStyleFields.Target.floatingBottom(-1,0));
        rejects(() -> new GeneralBlockPalettes.Target("rockPalette",null,0,null,0));rejects(() -> new GeneralBlockPalettes.Target(null,"layers",0,null,0));
        for (String path : List.of("floatingChildBiomes.01.bottomPalette.0.style","floatingChildBiomes.0.bottomPalette.01.style","floatingChildBiomes.2147483648.bottomPalette.0.style","floatingChildBiomes.0.bottomPalette.2147483648.style")) rejects(() -> new NoiseStyleFields.Target("biomes",path,null));
        rejects(() -> new NoiseStyleFields.Target("dimensions",styleTarget.objectField(),null));rejects(() -> new NoiseStyleFields.Target("biomes",styleTarget.objectField(),null,0));
        var drafts=new InspectorDrafts();String blockKey=target.listPrefix()+"0.block",thicknessKey=FloatingBottomPalettes.layerPrefix(0,0)+"minHeight",noiseKey=target.noisePrefix()+"zoom",directNoise=NoiseStyleFields.Target.floating(0,"footprintStyle").draftPrefix()+"zoom",otherEntry=FloatingBottomPalettes.blockPrefix(1,0)+"0.block",geometry=FloatingBiomes.entryPrefix(0)+"minHeightAboveSurface",otherLayer=FloatingBottomPalettes.layerPrefix(0,1)+"maxHeight";
        for (String key : List.of(blockKey,thicknessKey,noiseKey,directNoise,otherEntry,geometry,otherLayer)) drafts.update(key,"1","2");
        check(!drafts.transitionUnder(FloatingBottomPalettes.listPrefix(0),() -> false,() -> { throw new AssertionError(); }) && drafts.count()==7);
        try { drafts.transitionUnder(FloatingBottomPalettes.listPrefix(0),() -> true,() -> { throw new IOException("failed write"); });throw new AssertionError(); } catch (IOException expected) { check(drafts.count()==7); }
        check(drafts.transitionUnder(target.listPrefix(),() -> true,() -> {}) && drafts.count()==6 && drafts.contains(thicknessKey) && drafts.contains(noiseKey));
        check(drafts.transitionUnder(FloatingBottomPalettes.listPrefix(0),() -> true,() -> {}) && drafts.count()==3 && drafts.contains(directNoise) && drafts.contains(otherEntry) && drafts.contains(geometry));
        check(Arrays.equals(original,copy));check(Arrays.equals(entry(original,1).sourceBytes(),entry(target.setBlock(PATH,original,0,"air"),1).sourceBytes()));
        var workspace=PackWorkspace.newPack(Map.of(PATH,original,"biomes/defaults.json",added,"biomes/null.json",document("null")));
        workspace.replace(PATH,FloatingBottomPalettes.setThickness(PATH,workspace.read(PATH),0,0,"2","4"));workspace.replace(PATH,target.setBlock(PATH,workspace.read(PATH),0,"custom:changed"));
        workspace.replace(PATH,FloatingBottomPalettes.setSlope(PATH,workspace.read(PATH),0,0,"1","9"));workspace.replace(PATH,NoiseStyleFields.editStyle(PATH,workspace.read(PATH),styleTarget,"STATIC"));
        workspace.save(temp.resolve("floating-bottom"));workspace.exportZip(temp.resolve("floating-bottom.zip"));var reopened=PackWorkspace.open(temp.resolve("floating-bottom"));var imported=PackWorkspace.importZip(temp.resolve("floating-bottom.zip"));
        for (String path : workspace.paths()) check(Arrays.equals(workspace.read(path),reopened.read(path)) && Arrays.equals(workspace.read(path),imported.read(path)));
        check(new LosslessJson(bytes("{\"optional\":null}")).enableArray("optional").length>0);rejects(() -> new LosslessJson(bytes("{\"optional\":[]}")).enableArray("optional"));
        check(FieldCoverage.fields("floatingBiome").stream().filter(f -> f.name().equals("bottomPalette")).allMatch(FieldCoverage.Field::implemented));
        System.out.println("PASS: floating underside lists/defaults and mode preservation, nested thickness/slope/weighted-block/noise controls, null fallback states, typed/index/owner guards, scoped drafts and lossless Save/ZIP contracts");
    }
}
