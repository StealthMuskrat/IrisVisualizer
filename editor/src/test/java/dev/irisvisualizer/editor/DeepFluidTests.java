package dev.irisvisualizer.editor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class DeepFluidTests {
    private static final String PATH="dimensions/deep-fluids.json";
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static String text(byte[] value) { return new String(value,StandardCharsets.UTF_8); }
    private static void check(boolean value) { if (!value) throw new AssertionError("Deep-fluid contract failed"); }
    private static void rejects(Runnable action) { try { action.run(); } catch (IllegalArgumentException expected) { return; }throw new AssertionError("Expected rejected deep-fluid operation"); }
    private static byte[] document(String list) { return bytes("{\r\n \"hydrology\":{\"deepFluids\":"+list+",\"surfacePools\":[{\"id\":\"surface\"}],\"rivers\":{\"enabled\":false,\"profiles\":[{\"id\":\"river\"}]},\"futureHydro\":9007199254740993},\"escaped\":\"雪\\u0061\"\r\n}\r\n"); }
    private static LosslessJson hydro(byte[] value) { return HydrologyFields.hydrology(new LosslessJson(value)); }
    private static List<LosslessJson> systems(byte[] value) { return DeepFluids.entries(hydro(value)); }
    private static LosslessJson system(byte[] value) { return systems(value).get(0); }
    private static LosslessJson palette(byte[] value) { return RiverProfiles.palette(system(value)); }
    private static LosslessJson fluid(byte[] value,int index) { return RiverProfiles.fluids(palette(value)).get(index); }
    private static Map<String,String> geometry() { return new LinkedHashMap<>(Map.of("spacing","768","horizontalRadius","14","verticalRadius","6","channelWidth","3","depth","1","headroom","6")); }
    static void run(Path temp) throws Exception {
        byte[] parent=bytes("{\"hydrology\":{}}");byte[] empty=DeepFluids.enable(PATH,parent);
        check(systems(empty).isEmpty() && hydro(empty).fields().equals(Set.of("deepFluids")));rejects(() -> DeepFluids.enable(PATH,empty));
        rejects(() -> DeepFluids.change(PATH,empty,RiverProfiles.Action.ADD,-1,"deep"));
        byte[] added=DeepFluids.change(PATH,empty,RiverProfiles.Action.ADD,-1,"hot");
        check(system(added).fields().equals(Set.of("id","fluidPalette")) && fluid(added,0).stringToken("block").equals("lava") && hydro(added).token("rivers")==null);
        check(systems(DeepFluids.change(PATH,added,RiverProfiles.Action.REMOVE,0,null)).isEmpty());
        rejects(() -> DeepFluids.change(PATH,parent,RiverProfiles.Action.ADD,-1,"hot"));
        for (String bad : List.of("","Upper","-first","_first","contains space","a:b","a".repeat(65),"pool","deep","coastal-grotto","default")) rejects(() -> DeepFluids.change(PATH,empty,RiverProfiles.Action.ADD,-1,bad));
        byte[] bare=document("[{\"id\":\"hot\"}]");
        Map<String,String> defaults=new LinkedHashMap<>(geometry());defaults.put("density","0.125");defaults.put("containedPools","true");defaults.put("shortChannels","false");
        for (var item : defaults.entrySet()) {
            check(DeepFluids.defaultToken(item.getKey()).equals(item.getValue()) && !DeepFluids.help(item.getKey()).isBlank());
            byte[] one=DeepFluids.enableScalar(PATH,bare,0,item.getKey());check(system(one).token(item.getKey()).equals(item.getValue()));
            rejects(() -> DeepFluids.enableScalar(PATH,one,0,item.getKey()));
        }
        check(system(DeepFluids.setBoolean(PATH,bare,0,"containedPools",false)).token("containedPools").equals("false"));
        check(system(DeepFluids.setBoolean(PATH,bare,0,"shortChannels",true)).token("shortChannels").equals("true"));
        rejects(() -> DeepFluids.setBoolean(PATH,bare,0,"density",false));rejects(() -> DeepFluids.setScalar(PATH,bare,0,"containedPools","1"));
        byte[] explicit=DeepFluids.setGeometry(PATH,bare,0,geometry());
        for (String field : DeepFluids.GEOMETRY) check(system(explicit).token(field).equals(defaults.get(field)));
        check(system(explicit).token("density")==null && system(explicit).token("height")==null);
        var exact=geometry();exact.put("spacing","7.68e2");DeepFluids.setGeometry(PATH,bare,0,exact);
        var badSpacing=geometry();badSpacing.put("spacing","33");rejects(() -> DeepFluids.setGeometry(PATH,bare,0,badSpacing));
        var tightSpacing=geometry();tightSpacing.put("spacing","34");DeepFluids.setGeometry(PATH,bare,0,tightSpacing);
        var badClearance=geometry();badClearance.put("depth","7");rejects(() -> DeepFluids.setGeometry(PATH,bare,0,badClearance));
        var tightClearance=geometry();tightClearance.put("depth","6");DeepFluids.setGeometry(PATH,bare,0,tightClearance);
        var resized=geometry();resized.put("verticalRadius","2");resized.put("depth","2");resized.put("headroom","2");DeepFluids.setGeometry(PATH,bare,0,resized);
        rejects(() -> DeepFluids.setScalar(PATH,explicit,0,"spacing","33"));check(system(DeepFluids.setScalar(PATH,explicit,0,"horizontalRadius","128")).token("horizontalRadius").equals("128"));
        byte[] tightSystem=DeepFluids.setGeometry(PATH,bare,0,tightSpacing);rejects(() -> DeepFluids.setScalar(PATH,tightSystem,0,"horizontalRadius","15"));
        rejects(() -> DeepFluids.setScalar(PATH,explicit,0,"depth","7"));rejects(() -> DeepFluids.setScalar(PATH,explicit,0,"verticalRadius","2"));
        Map<String,int[]> bounds=Map.of("spacing",new int[]{16,8192},"horizontalRadius",new int[]{2,128},"verticalRadius",new int[]{2,64},"channelWidth",new int[]{1,32},"depth",new int[]{1,32},"headroom",new int[]{1,63});
        for (var item : bounds.entrySet()) for (String bad : List.of(Integer.toString(item.getValue()[0]-1),Integer.toString(item.getValue()[1]+1),defaults.get(item.getKey())+".000000000000000001","2147483648","NaN","")) {
            var values=geometry();values.put(item.getKey(),bad);rejects(() -> DeepFluids.setGeometry(PATH,bare,0,values));
        }
        DeepFluids.setGeometry(PATH,bare,0,Map.of("spacing","8192","horizontalRadius","128","verticalRadius","64","channelWidth","32","depth","32","headroom","63"));
        DeepFluids.setGeometry(PATH,bare,0,Map.of("spacing","16","horizontalRadius","2","verticalRadius","2","channelWidth","1","depth","1","headroom","1"));
        var incomplete=geometry();incomplete.remove("headroom");rejects(() -> DeepFluids.setGeometry(PATH,bare,0,incomplete));
        byte[] density=DeepFluids.enableScalar(PATH,bare,0,"density");for (String good : List.of("0","64","0.5")) DeepFluids.setScalar(PATH,density,0,"density",good);
        for (String bad : List.of("-1","65","NaN","Infinity","1e999")) rejects(() -> DeepFluids.setScalar(PATH,density,0,"density",bad));
        byte[] height=DeepFluids.createHeight(PATH,bare,0);var range=DeepFluids.height(system(height));
        check(range.token("min").equals("-192") && range.token("max").equals("32") && range.stringPath("style.style").equals("IRIS") && range.tokenPath("style.zoom").equals("1024"));
        rejects(() -> DeepFluids.createHeight(PATH,height,0));rejects(() -> DeepFluids.setHeight(PATH,bare,0,"0","1"));
        DeepFluids.setHeight(PATH,height,0,"-2048","2048");DeepFluids.setHeight(PATH,height,0,"-20.5","20.5");
        for (String bad : List.of("-2049","2049","NaN","Infinity","1e999","")) rejects(() -> DeepFluids.setHeight(PATH,height,0,bad,"2048"));
        rejects(() -> DeepFluids.setHeight(PATH,height,0,"33","32"));
        byte[] emptyHeight=document("[{\"height\":{}}]");check(DeepFluids.heightDefault("min",false).equals("16") && DeepFluids.heightDefault("max",false).equals("32"));
        var heightTarget=NoiseStyleFields.Target.deepHeight(0);var paletteTarget=NoiseStyleFields.Target.deepPalette(0);
        check(NoiseStyleFields.contextDefault(heightTarget).equals("STATIC") && NoiseStyleFields.contextDefault(paletteTarget).equals("STATIC"));
        byte[] heightStyle=NoiseStyleFields.enable(PATH,emptyHeight,heightTarget,"style");check(DeepFluids.height(system(heightStyle)).stringPath("style.style").equals("STATIC"));
        byte[] emptyHeightStyle=document("[{\"height\":{\"style\":{}}}]");check(DeepFluids.height(system(NoiseStyleFields.enable(PATH,emptyHeightStyle,heightTarget,"style"))).stringPath("style.style").equals("FLAT"));
        String entry="{\"id\":\"hot\",\"density\":0.125,\"spacing\":768,\"horizontalRadius\":14,\"verticalRadius\":6,\"channelWidth\":3,\"depth\":1,\"headroom\":6,\"height\":{\"min\":-192,\"max\":32,\"style\":{\"style\":\"UNKNOWN_HEIGHT\",\"zoom\":1024,\"cellularFrequency\":0,\"cellularZoom\":1,\"fracture\":{},\"expression\":\"kept\"},\"futureRange\":true},\"fluidPalette\":{\"palette\":[ {\"block\":\"custom:stored\",\"weight\":1,\"data\":{\"future\":true},\"large\":9007199254740993}, {\"block\":\"lava\",\"weight\":2} ],\"zoom\":5e0,\"style\":{\"style\":\"UNKNOWN_PALETTE\",\"zoom\":1e0,\"cellularFrequency\":0,\"cellularZoom\":1,\"imageMap\":{}},\"futurePalette\":true},\"futureSystem\":true}";
        byte[] original=document("["+entry+",{\"id\":\"cold\"}]"),copy=original.clone();
        for (String choice : NoiseStyleFields.choices()) {
            check(text(NoiseStyleFields.editStyle(PATH,original,heightTarget,choice)).equals(text(original).replace("\"UNKNOWN_HEIGHT\"","\""+choice+"\"")));
            check(text(NoiseStyleFields.editStyle(PATH,original,paletteTarget,choice)).equals(text(original).replace("\"UNKNOWN_PALETTE\"","\""+choice+"\"")));
        }
        var target=HydrologyFluidPalettes.Target.deep(0);check(target.defaultFluid().equals("lava") && target.styleTarget().equals(paletteTarget));
        check(text(target.setZoom(PATH,original,"10")).equals(text(original).replace("\"zoom\":5e0","\"zoom\":10")));
        check(text(target.setFluid(PATH,original,0,"Minecraft:WATER[level=0]")).equals(text(original).replace("\"block\":\"custom:stored\"","\"block\":\"Minecraft:WATER[level=0]\"")));
        for (String bad : List.of(""," ","stone","minecraft:dirt")) rejects(() -> target.setFluid(PATH,original,0,bad));
        check(text(target.setWeight(PATH,original,0,"6.4e1")).equals(text(original).replace("\"weight\":1,","\"weight\":64,")));
        for (String bad : List.of("0","65","1.000000000000000001","NaN")) rejects(() -> target.setWeight(PATH,original,0,bad));
        byte[] fluidCopy=target.change(PATH,original,RiverProfiles.Action.DUPLICATE,0,null);check(Arrays.equals(fluid(fluidCopy,0).sourceBytes(),fluid(fluidCopy,1).sourceBytes()));
        byte[] fluidMoved=target.change(PATH,original,RiverProfiles.Action.DOWN,0,null);check(Arrays.equals(fluid(original,0).sourceBytes(),fluid(fluidMoved,1).sourceBytes()));
        target.change(PATH,fluidMoved,RiverProfiles.Action.UP,1,null);target.change(PATH,original,RiverProfiles.Action.REMOVE,0,null);
        check(fluid(target.change(PATH,original,RiverProfiles.Action.ADD,-1,"water"),2).fields().equals(Set.of("block")));
        rejects(() -> target.change(PATH,added,RiverProfiles.Action.REMOVE,0,null));
        check(fluid(target.createPalette(PATH,bare),0).stringToken("block").equals("lava"));
        byte[] emptyPalette=document("[{\"fluidPalette\":{}}]");check(fluid(target.initialize(PATH,emptyPalette),0).stringToken("block").equals("lava"));
        check(palette(target.enableZoom(PATH,emptyPalette)).token("palette")==null);
        byte[] zeroCell=NoiseStyleFields.editText(PATH,original,heightTarget,"cellularZoom","0");rejects(() -> NoiseStyleFields.editText(PATH,zeroCell,heightTarget,"cellularFrequency","1"));
        byte[] active=NoiseStyleFields.editText(PATH,original,paletteTarget,"cellularFrequency","1");rejects(() -> NoiseStyleFields.editText(PATH,active,paletteTarget,"cellularZoom","0"));
        check(text(DeepFluids.setId(PATH,original,0,"renamed")).equals(text(original).replace("\"id\":\"hot\"","\"id\":\"renamed\"")));
        rejects(() -> DeepFluids.setId(PATH,original,0,"cold"));rejects(() -> DeepFluids.setId(PATH,original,0,"river"));
        byte[] duplicate=DeepFluids.change(PATH,original,RiverProfiles.Action.DUPLICATE,0,"copy");check(systems(duplicate).size()==3 && systems(duplicate).get(1).token("futureSystem").equals("true"));
        byte[] moved=DeepFluids.change(PATH,original,RiverProfiles.Action.DOWN,0,null);check(Arrays.equals(system(original).sourceBytes(),systems(moved).get(1).sourceBytes()));
        DeepFluids.change(PATH,moved,RiverProfiles.Action.UP,1,null);DeepFluids.change(PATH,original,RiverProfiles.Action.REMOVE,0,null);
        rejects(() -> DeepFluids.change(PATH,original,RiverProfiles.Action.UP,0,null));rejects(() -> DeepFluids.change(PATH,original,RiverProfiles.Action.DOWN,1,null));
        rejects(() -> DeepFluids.setId(PATH,document("[{}, {\"id\":\"other\"}]"),1,"deep_lava"));
        var readOnly=DimensionFields.readOnlyPaths(new LosslessJson(original));
        check(readOnly.containsAll(List.of("hydrology.deepFluids[0].futureSystem","hydrology.deepFluids[0].height.futureRange","hydrology.deepFluids[0].fluidPalette.futurePalette","hydrology.deepFluids[0].fluidPalette.palette[0].data")));
        for (String bad : List.of("null","{}","true","1","\"snippet\"","[null]","[{},1]")) {
            byte[] invalid=document(bad);rejects(() -> DeepFluids.enable(PATH,invalid));rejects(() -> DeepFluids.change(PATH,invalid,RiverProfiles.Action.ADD,-1,"valid"));check(DimensionFields.readOnlyPaths(new LosslessJson(invalid)).contains("hydrology.deepFluids"));
        }
        for (String bad : List.of("null","[]","true","\"snippet\"")) {
            byte[] invalid=document("[{\"id\":"+bad+",\"height\":"+bad+",\"fluidPalette\":"+bad+",\"density\":"+bad+",\"containedPools\":"+bad+"}]");
            if (!bad.startsWith("\"")) rejects(() -> DeepFluids.setId(PATH,invalid,0,"valid"));
            if (!bad.equals("true")) rejects(() -> DeepFluids.setBoolean(PATH,invalid,0,"containedPools",false));
            rejects(() -> DeepFluids.setScalar(PATH,invalid,0,"density","1"));rejects(() -> DeepFluids.createHeight(PATH,invalid,0));rejects(() -> DeepFluids.setHeight(PATH,invalid,0,"0","1"));
            rejects(() -> NoiseStyleFields.enable(PATH,invalid,heightTarget,"style"));rejects(() -> target.createPalette(PATH,invalid));rejects(() -> target.initialize(PATH,invalid));
            check(system(DeepFluids.setGeometry(PATH,invalid,0,geometry())).token("density").equals(bad));
        }
        byte[] wrongGeometry=document("[{\"spacing\":768,\"horizontalRadius\":\"14\"}]");rejects(() -> DeepFluids.setGeometry(PATH,wrongGeometry,0,geometry()));check(system(DeepFluids.enableScalar(PATH,wrongGeometry,0,"density")).token("horizontalRadius").equals("\"14\""));
        StringBuilder full=new StringBuilder("[");for (int i=0;i<64;i++) { if (i>0) full.append(',');full.append("{\"id\":\"f").append(i).append("\"}"); }
        byte[] fullList=document(full+"]");rejects(() -> DeepFluids.change(PATH,fullList,RiverProfiles.Action.ADD,-1,"extra"));check(systems(DeepFluids.change(PATH,fullList,RiverProfiles.Action.REMOVE,0,null)).size()==63);
        for (String missing : List.of("{}","{\"hydrology\":null}")) rejects(() -> DeepFluids.enable(PATH,bytes(missing)));
        for (String path : List.of("regions/a.json","biomes/a.json","generators/a.json","dimensions/nested/a.json","a.json")) {
            rejects(() -> DeepFluids.enable(path,parent));rejects(() -> DeepFluids.setGeometry(path,original,0,geometry()));rejects(() -> target.setFluid(path,original,0,"water"));rejects(() -> NoiseStyleFields.enable(path,height,heightTarget,"style"));
        }
        rejects(() -> NoiseStyleFields.Target.deepHeight(-1));rejects(() -> NoiseStyleFields.Target.deepPalette(-1));rejects(() -> new NoiseStyleFields.Target("biomes",heightTarget.objectField(),null));rejects(() -> new NoiseStyleFields.Target("dimensions",paletteTarget.objectField(),null,0));
        rejects(() -> new HydrologyFluidPalettes.Target(true,0,true));
        check(DeepFluids.styleIndex("hydrology.deepFluids.01.fluidPalette.style")==-1 && DeepFluids.heightStyleIndex("hydrology.deepFluids.2147483648.height.style")==-1);
        var drafts=new InspectorDrafts();String entryKey=target.fluidPrefix()+"0.block",noiseKey=target.noisePrefix()+"zoom",heightKey=DeepFluids.systemPrefix(0)+"height.min",geometryKey=DeepFluids.systemPrefix(0)+"spacing",otherKey=SurfacePools.poolPrefix(0)+"id";
        for (String key : List.of(entryKey,noiseKey,heightKey,geometryKey,otherKey)) drafts.update(key,"1","2");
        check(!drafts.transitionUnder(DeepFluids.draftPrefix(),() -> false,() -> { throw new AssertionError(); }) && drafts.count()==5);
        try { drafts.transitionUnder(DeepFluids.draftPrefix(),() -> true,() -> { throw new IOException("failed write"); });throw new AssertionError(); } catch (IOException expected) { check(drafts.count()==5); }
        check(drafts.transitionUnder(target.fluidPrefix(),() -> true,() -> {}) && drafts.count()==4 && drafts.contains(noiseKey));
        check(drafts.transitionUnder(DeepFluids.draftPrefix(),() -> true,() -> {}) && drafts.count()==1 && drafts.contains(otherKey));
        check(Arrays.equals(original,copy));var workspace=PackWorkspace.newPack(Map.of(PATH,original,"dimensions/empty.json",empty,"dimensions/defaults.json",added,"dimensions/height.json",height));
        workspace.save(temp.resolve("deep-fluids"));workspace.exportZip(temp.resolve("deep-fluids.zip"));var reopened=PackWorkspace.open(temp.resolve("deep-fluids"));var imported=PackWorkspace.importZip(temp.resolve("deep-fluids.zip"));
        for (String path : workspace.paths()) check(Arrays.equals(workspace.read(path),reopened.read(path)) && Arrays.equals(workspace.read(path),imported.read(path)));
        check(FieldCoverage.fields("deepFluid").size()==12 && FieldCoverage.fields("deepFluid").stream().allMatch(FieldCoverage.Field::implemented));check(FieldCoverage.fields("hydrology").stream().allMatch(FieldCoverage.Field::implemented));
        System.out.println("PASS: deep-fluid list/ID/scalar contracts, native spacing and clearance with atomic geometry, contextual/class heights, shared fluids/noise, typed parent/draft isolation and lossless Save/ZIP contracts");
    }
}
