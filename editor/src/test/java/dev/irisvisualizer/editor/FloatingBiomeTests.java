package dev.irisvisualizer.editor;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class FloatingBiomeTests {
    private static final String PATH="biomes/nested/floating.json";
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static String text(byte[] value) { return new String(value,StandardCharsets.UTF_8); }
    private static void check(boolean condition) { if (!condition) throw new AssertionError("Floating biome contract failed"); }
    private static void rejects(Runnable action) { try { action.run(); } catch (IllegalArgumentException expected) { return; }throw new AssertionError("Expected rejected floating biome operation"); }
    private static byte[] document(String token) { return bytes("{\r\n \"floatingChildBiomes\":"+token+",\"futureRoot\":9007199254740993,\"escaped\":\"雪\\u0061\",\"mergeFloatingChildBiomes\":false\r\n}\r\n"); }
    private static List<LosslessJson> entries(byte[] value) { return FloatingBiomes.entries(new LosslessJson(value)); }
    private static LosslessJson entry(byte[] value) { return entries(value).getFirst(); }
    static void run(Path temp) throws Exception {
        byte[] absent=bytes("{\r\n \"future\":9007199254740993\r\n}\r\n"),enabled=FloatingBiomes.enable(PATH,absent);
        check(entries(absent)==null && entries(enabled).isEmpty());rejects(() -> FloatingBiomes.enable(PATH,enabled));
        rejects(() -> FloatingBiomes.change(PATH,absent,RiverProfiles.Action.ADD,-1));
        byte[] added=FloatingBiomes.change(PATH,enabled,RiverProfiles.Action.ADD,-1);check(entry(added).fields().isEmpty());
        check(entries(FloatingBiomes.change(PATH,added,RiverProfiles.Action.REMOVE,0)).isEmpty());
        check(FloatingBiomes.defaultToken("edgeTaperWidth").equals("10") && FloatingBiomes.defaultToken("minHeightAboveSurface").equals("160") && FloatingBiomes.defaultToken("maxHeightAboveSurface").equals("210"));
        check(FloatingBiomes.help("footprintThreshold").contains("≈"));
        Map<String,String[]> bounds=Map.ofEntries(Map.entry("rarity",new String[]{"1","512","0","513"}),Map.entry("footprintThreshold",new String[]{"0","1","-0.1","1.1"}),Map.entry("edgeTaperWidth",new String[]{"2","32","1","33"}),Map.entry("edgeTaperExponent",new String[]{"0.25","4","0.24","4.1"}),Map.entry("edgeTaperVariationAmplitude",new String[]{"0","8","-1","9"}),Map.entry("maxTopHeight",new String[]{"0","512","-1","513"}),Map.entry("topShapeAmp",new String[]{"0","1","-1","2"}),Map.entry("bottomExponent",new String[]{"0.1","8","0","9"}),Map.entry("maxThickness",new String[]{"1","512","0","513"}),Map.entry("wallWarpAmplitude",new String[]{"0","64","-1","65"}),Map.entry("carveThreshold",new String[]{"0","1","-1","2"}),Map.entry("objectShrinkFactor",new String[]{"0.01","1","0","2"}));
        for (String field : FloatingBiomes.SCALARS) {
            byte[] materialized=FloatingBiomes.enableScalar(PATH,added,0,field);check(entry(materialized).token(field).equals(FloatingBiomes.defaultToken(field)));
            rejects(() -> FloatingBiomes.enableScalar(PATH,materialized,0,field));rejects(() -> FloatingBiomes.setScalar(PATH,added,0,field,"1"));
            var limit=bounds.get(field);for (int i=0;i<2;i++) check(new BigDecimal(entry(FloatingBiomes.setScalar(PATH,materialized,0,field,limit[i])).token(field)).compareTo(new BigDecimal(limit[i]))==0);
            for (String bad : List.of(limit[2],limit[3],"NaN","Infinity","1e999")) rejects(() -> FloatingBiomes.setScalar(PATH,materialized,0,field,bad));
            if (List.of("rarity","edgeTaperWidth","maxTopHeight","maxThickness").contains(field)) rejects(() -> FloatingBiomes.setScalar(PATH,materialized,0,field,"1.000000000000000001"));
            for (String bad : List.of("null","true","[]","\"1\"")) rejects(() -> FloatingBiomes.setScalar(PATH,document("[{\""+field+"\":"+bad+"}]"),0,field,"1"));
        }
        for (boolean height : List.of(true,false)) {
            String min=height ? "minHeightAboveSurface" : "bottomDepthMin",max=height ? "maxHeightAboveSurface" : "bottomDepthMax",ceiling=height ? "2032" : "512";
            byte[] pair=FloatingBiomes.setPair(PATH,added,0,height,"0",ceiling);check(entry(pair).token(min).equals("0") && entry(pair).token(max).equals(ceiling));
            FloatingBiomes.setPair(PATH,added,0,height,"2e1","20");
            for (String[] bad : new String[][]{{"2","1"},{"-1","1"},{"0",height ? "2033" : "513"},{"1.000000000000000001","2"},{"0","NaN"},{"0","2147483648"}}) rejects(() -> FloatingBiomes.setPair(PATH,added,0,height,bad[0],bad[1]));
            for (String bad : List.of("null","[]","\"2\"")) {
                byte[] invalid=document("[{\""+max+"\":"+bad+"}]"),copy=invalid.clone();rejects(() -> FloatingBiomes.setPair(PATH,invalid,0,height,"0","1"));check(Arrays.equals(copy,invalid));
                check(entry(FloatingBiomes.enableScalar(PATH,invalid,0,"rarity")).token(max).equals(bad));
            }
        }
        check(Arrays.equals(added,FloatingBiomes.setAbsoluteBounds(PATH,added,0,"","")));check(Arrays.equals(added,FloatingBiomes.setFluidHeight(PATH,added,0,"")));
        byte[] optional=FloatingBiomes.setAbsoluteBounds(PATH,added,0,"-2147483648","2147483647");check(entry(optional).token("minAbsoluteY").equals("-2147483648") && entry(optional).token("maxAbsoluteY").equals("2147483647"));
        byte[] reset=FloatingBiomes.setAbsoluteBounds(PATH,optional,0,"","");check(entry(reset).token("minAbsoluteY").equals("null") && entry(reset).token("maxAbsoluteY").equals("null"));
        check(entry(FloatingBiomes.setAbsoluteBounds(PATH,added,0,"","-64")).token("minAbsoluteY")==null);
        for (String bad : List.of("2147483648","-2147483649","1.000000000000000001","NaN","Infinity")) {
            rejects(() -> FloatingBiomes.setAbsoluteBounds(PATH,added,0,bad,""));rejects(() -> FloatingBiomes.setFluidHeight(PATH,added,0,bad));
        }
        rejects(() -> FloatingBiomes.setAbsoluteBounds(PATH,added,0,"2","1"));
        byte[] fluid=FloatingBiomes.setFluidHeight(PATH,added,0,"-1");check(entry(fluid).token("localFluidHeight").equals("-1"));check(entry(FloatingBiomes.setFluidHeight(PATH,fluid,0,"")).token("localFluidHeight").equals("null"));
        for (String field : List.of("minAbsoluteY","maxAbsoluteY","localFluidHeight")) for (String bad : List.of("true","[]","\"1\"")) {
            byte[] invalid=document("[{\""+field+"\":"+bad+"}]");
            if (field.equals("localFluidHeight")) rejects(() -> FloatingBiomes.setFluidHeight(PATH,invalid,0,"1"));else rejects(() -> FloatingBiomes.setAbsoluteBounds(PATH,invalid,0,"0","1"));
        }
        for (String field : FloatingBiomes.TOGGLES) {
            check(entry(FloatingBiomes.setToggle(PATH,added,0,field,false)).token(field).equals("false"));
            for (String bad : List.of("null","[]","0","\"true\"")) rejects(() -> FloatingBiomes.setToggle(PATH,document("[{\""+field+"\":"+bad+"}]"),0,field,true));
        }
        for (String field : FloatingBiomes.ENUMS) {
            byte[] original=document("[{\""+field+"\":\"FUTURE\",\"noise\":{\"future\":9007199254740993}}]");
            for (String choice : FloatingBiomes.choices(field)) {
                check(!FloatingBiomes.choiceHelp(field,choice).isBlank());check(text(FloatingBiomes.setMode(PATH,original,0,field,choice)).equals(text(original).replace("\"FUTURE\"","\""+choice+"\"")));
                check(entry(FloatingBiomes.setMode(PATH,added,0,field,choice)).stringToken(field).equals(choice));
            }
            rejects(() -> FloatingBiomes.setMode(PATH,original,0,field,"FUTURE"));
            for (String bad : List.of("null","1","[]")) rejects(() -> FloatingBiomes.setMode(PATH,document("[{\""+field+"\":"+bad+"}]"),0,field,FloatingBiomes.choices(field).getFirst()));
        }
        var references=PackWorkspace.newPack(Map.of(PATH,added,"biomes/nested/target.json",bytes("{}"),"biomes/unrelated.json",bytes("{}"),"regions/a.json",bytes("{}")));
        check(FloatingBiomes.biomeChoices(references).contains("nested/target"));check(entry(FloatingBiomes.setBiome(PATH,added,0,"nested/target",references)).stringToken("biome").equals("nested/target"));
        check(entry(FloatingBiomes.setBiome(PATH,added,0,"",references)).stringToken("biome").isEmpty());
        check(entry(FloatingBiomes.setBiome(PATH,document("[{\"biome\":null}]"),0,"",references)).stringToken("biome").isEmpty());
        for (String bad : List.of("missing","nested/target.json","biomes/nested/target","regions/a")) rejects(() -> FloatingBiomes.setBiome(PATH,added,0,bad,references));
        for (String value : List.of("","dimension-entry","carving/mushroom","custom:unresolved"," ")) check(entry(FloatingBiomes.setLiteral(PATH,added,0,"carving",value)).stringToken("carving").equals(value));
        check(entry(FloatingBiomes.setLiteral(PATH,document("[{\"carving\":null}]"),0,"carving","")).stringToken("carving").isEmpty());
        rejects(() -> FloatingBiomes.setLiteral(PATH,added,0,"fluidBlock"," "));check(entry(FloatingBiomes.setLiteral(PATH,added,0,"fluidBlock","custom:unknown")).stringToken("fluidBlock").equals("custom:unknown"));
        String first="{\"biome\":\"missing/stored\",\"rarity\":1,\"footprintStyle\":\"snippet/kept\",\"bottomPalette\":[{\"future\":9007199254740993}],\"extraObjects\":[{\"id\":\"unknown\"}],\"future\":{\"escaped\":\"雪\\u0061\"}}";
        byte[] original=document("[ "+first+",\r\n {\"rarity\":2} ]"),copy=original.clone();
        check(text(FloatingBiomes.setScalar(PATH,original,0,"rarity","3")).equals(text(original).replace("\"rarity\":1","\"rarity\":3")));
        byte[] duplicate=FloatingBiomes.change(PATH,original,RiverProfiles.Action.DUPLICATE,0);check(Arrays.equals(entries(duplicate).get(0).sourceBytes(),entries(duplicate).get(1).sourceBytes()));
        byte[] moved=FloatingBiomes.change(PATH,original,RiverProfiles.Action.DOWN,0);check(Arrays.equals(entries(original).getFirst().sourceBytes(),entries(moved).get(1).sourceBytes()));
        check(Arrays.equals(original,FloatingBiomes.change(PATH,moved,RiverProfiles.Action.UP,1)));check(entries(FloatingBiomes.change(PATH,original,RiverProfiles.Action.REMOVE,0)).size()==1);
        rejects(() -> FloatingBiomes.change(PATH,original,RiverProfiles.Action.UP,0));rejects(() -> FloatingBiomes.change(PATH,original,RiverProfiles.Action.DOWN,1));rejects(() -> FloatingBiomes.change(PATH,original,null,0));
        check(FloatingBiomes.readOnlyFields(entry(original)).containsAll(List.of("bottomPalette","extraObjects","future")));
        for (String bad : List.of("null","{}","true","1","\"snippet/floating\"","[null]","[{},1]","[\"snippet/floating\"]")) {
            byte[] invalid=document(bad);rejects(() -> FloatingBiomes.entries(new LosslessJson(invalid)));rejects(() -> FloatingBiomes.enable(PATH,invalid));rejects(() -> FloatingBiomes.change(PATH,invalid,RiverProfiles.Action.ADD,-1));
        }
        for (String path : List.of("dimensions/a.json","regions/a.json","generators/a.json","a.json","biomes/a.txt")) {
            rejects(() -> FloatingBiomes.enable(path,absent));rejects(() -> FloatingBiomes.change(path,original,RiverProfiles.Action.ADD,-1));rejects(() -> FloatingBiomes.setScalar(path,original,0,"rarity","1"));
            rejects(() -> FloatingBiomes.setPair(path,original,0,true,"1","2"));rejects(() -> FloatingBiomes.setAbsoluteBounds(path,original,0,"",""));rejects(() -> FloatingBiomes.setFluidHeight(path,original,0,""));
            rejects(() -> FloatingBiomes.setMode(path,original,0,"topShapeMode","BIOME"));rejects(() -> FloatingBiomes.setBiome(path,original,0,"",references));rejects(() -> FloatingBiomes.setLiteral(path,original,0,"carving",""));
        }
        rejects(() -> FloatingBiomes.enableScalar(PATH,added,0,"altitudeStyle"));rejects(() -> FloatingBiomes.setToggle(PATH,added,0,"mergeFloatingChildBiomes",true));
        var drafts=new InspectorDrafts();String minKey=FloatingBiomes.entryPrefix(0)+"minHeightAboveSurface",maxKey=FloatingBiomes.entryPrefix(0)+"maxHeightAboveSurface",fluidKey=FloatingBiomes.entryPrefix(1)+"localFluidHeight",otherKey=BiomeSlabWall.noisePrefix("wall")+"zoom";
        for (String key : List.of(minKey,maxKey,fluidKey,otherKey)) drafts.update(key,"1","2");
        check(!drafts.transitionUnder(FloatingBiomes.prefix(),() -> false,() -> { throw new AssertionError(); }) && drafts.count()==4);
        try { drafts.transitionUnder(FloatingBiomes.prefix(),() -> true,() -> { throw new IOException("failed write"); });throw new AssertionError(); } catch (IOException expected) { check(drafts.count()==4); }
        drafts.applied(minKey);drafts.applied(maxKey);check(drafts.count()==2 && drafts.contains(fluidKey));
        check(drafts.transitionUnder(FloatingBiomes.prefix(),() -> true,() -> {}) && drafts.count()==1 && drafts.contains(otherKey));
        check(Arrays.equals(original,copy));var workspace=PackWorkspace.newPack(Map.of(PATH,original,"biomes/defaults.json",added,"biomes/disabled.json",enabled));
        workspace.replace(PATH,FloatingBiomes.setPair(PATH,workspace.read(PATH),0,true,"170","220"));workspace.replace(PATH,FloatingBiomes.setAbsoluteBounds(PATH,workspace.read(PATH),0,"-64","300"));
        workspace.replace(PATH,FloatingBiomes.setFluidHeight(PATH,workspace.read(PATH),0,"3"));workspace.replace(PATH,FloatingBiomes.setLiteral(PATH,workspace.read(PATH),0,"carving","dimension-entry"));
        workspace.save(temp.resolve("floating-biomes"));workspace.exportZip(temp.resolve("floating-biomes.zip"));var reopened=PackWorkspace.open(temp.resolve("floating-biomes"));var imported=PackWorkspace.importZip(temp.resolve("floating-biomes.zip"));
        for (String path : workspace.paths()) check(Arrays.equals(workspace.read(path),reopened.read(path)) && Arrays.equals(workspace.read(path),imported.read(path)));
        check(FieldCoverage.fields("floatingBiome").size()==42 && FieldCoverage.fields("floatingBiome").stream().filter(FieldCoverage.Field::implemented).count()==36);
        check(FieldCoverage.groups(PATH).contains("floatingBiome"));
        System.out.println("PASS: floating entry defaults/list/raw preservation, all numeric bounds, atomic altitude/depth and optional limits, exact biome refs, literal carving/fluid, every mode/toggle, typed owner/draft guards and Save/ZIP contracts");
    }
}
