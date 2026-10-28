package dev.irisvisualizer.editor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class BiomeLayerTests {
    private static final String PATH="biomes/nested/layered.json";
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static String text(byte[] value) { return new String(value,StandardCharsets.UTF_8); }
    private static void check(boolean value) { if (!value) throw new AssertionError("Biome layer contract failed"); }
    private static void rejects(Runnable action) { try { action.run(); } catch (IllegalArgumentException expected) { return; }throw new AssertionError("Expected rejected biome layer operation"); }
    private static byte[] document(String list,String value) { return bytes("{\r\n \""+list+"\":"+value+",\"futureRoot\":9007199254740993,\"escaped\":\"雪\\u0061\"\r\n}\r\n"); }
    private static List<LosslessJson> layers(byte[] value,String list) { return BiomeLayers.entries(new LosslessJson(value),list); }
    private static LosslessJson layer(byte[] value,String list) { return layers(value,list).get(0); }
    private static LosslessJson block(byte[] value,String list,int index) { return DimensionPalettes.entries(layer(value,list)).get(index); }
    static void run(Path temp) throws Exception {
        check(BiomeLayers.defaultBlock().equals("GRASS_BLOCK") && BiomeLayers.defaultHeight("minHeight").equals("1") && BiomeLayers.defaultHeight("maxHeight").equals("1") && BiomeLayers.styleDefault().equals("STATIC") && BiomeLayers.zoomDefault().equals("5"));
        for (String list : BiomeLayers.LIST_FIELDS) {
            byte[] enabled=BiomeLayers.enable(PATH,bytes("{}"),list);
            check(layers(enabled,list).size()==(list.equals("layers") ? 1 : 0));
            if (list.equals("layers")) check(layer(enabled,list).fields().isEmpty());
            rejects(() -> BiomeLayers.enable(PATH,enabled,list));rejects(() -> BiomeLayers.change(PATH,bytes("{}"),list,RiverProfiles.Action.ADD,-1));
            byte[] added=BiomeLayers.change(PATH,document(list,"[]"),list,RiverProfiles.Action.ADD,-1);
            check(layers(added,list).size()==1 && layer(added,list).fields().isEmpty());
            check(layers(BiomeLayers.change(PATH,added,list,RiverProfiles.Action.REMOVE,0),list).isEmpty());
            var target=GeneralBlockPalettes.Target.layer(list,0);check(target.defaultBlock().equals("GRASS_BLOCK"));
            byte[] initialized=target.enableEntries(PATH,added);check(block(initialized,list,0).stringToken("block").equals("GRASS_BLOCK") && block(initialized,list,0).fields().equals(Set.of("block")));
            rejects(() -> target.enableEntries(PATH,initialized));
            byte[] thickness=BiomeLayers.setThickness(PATH,added,list,0,"0","2032");check(layer(thickness,list).token("minHeight").equals("0") && layer(thickness,list).token("maxHeight").equals("2032"));
            BiomeLayers.setThickness(PATH,added,list,0,"2.032e3","2032");
            for (String[] bad : new String[][]{{"-1","1"},{"0","0"},{"2033","2033"},{"2","1"},{"1.000000000000000001","2"},{"NaN","2"},{"1","Infinity"},{"2147483648","2147483648"}}) rejects(() -> BiomeLayers.setThickness(PATH,added,list,0,bad[0],bad[1]));
            byte[] zoomed=target.enableZoom(PATH,added);check(layer(zoomed,list).token("zoom").equals("5") && layer(zoomed,list).token("palette")==null);
            rejects(() -> target.enableZoom(PATH,zoomed));rejects(() -> target.setZoom(PATH,added,"10"));target.setZoom(PATH,zoomed,"0.0001");
            for (String bad : List.of("0","0.00009","NaN","Infinity","1e999")) rejects(() -> target.setZoom(PATH,zoomed,bad));
            String first="{\"minHeight\":1,\"maxHeight\":3,\"slopeCondition\":{\"minimumSlope\":0,\"maximumSlope\":10,\"futureSlope\":true},\"palette\":[ {\"block\":\"custom:stored\",\"weight\":1,\"data\":{\"future\":\"雪\\u0061\"},\"backup\":null,\"large\":9007199254740993}, {\"block\":\"water\",\"weight\":1000} ],\"zoom\":5e0,\"style\":{\"style\":\"UNKNOWN\",\"zoom\":1e0,\"cellularFrequency\":0,\"cellularZoom\":1,\"expression\":\"kept\",\"fracture\":{}},\"futureLayer\":true}";
            byte[] original=document(list,"[ "+first+",\r\n {\"minHeight\":2} ]"),copy=original.clone();
            check(text(BiomeLayers.setThickness(PATH,original,list,0,"2","4")).equals(text(original).replace("\"minHeight\":1","\"minHeight\":2").replace("\"maxHeight\":3","\"maxHeight\":4")));
            byte[] duplicate=BiomeLayers.change(PATH,original,list,RiverProfiles.Action.DUPLICATE,0);
            check(Arrays.equals(layers(duplicate,list).get(0).sourceBytes(),layers(duplicate,list).get(1).sourceBytes()));
            byte[] moved=BiomeLayers.change(PATH,original,list,RiverProfiles.Action.DOWN,0);check(Arrays.equals(layer(original,list).sourceBytes(),layers(moved,list).get(1).sourceBytes()));
            BiomeLayers.change(PATH,moved,list,RiverProfiles.Action.UP,1);check(layers(BiomeLayers.change(PATH,original,list,RiverProfiles.Action.REMOVE,0),list).size()==1);
            rejects(() -> BiomeLayers.change(PATH,original,list,RiverProfiles.Action.UP,0));rejects(() -> BiomeLayers.change(PATH,original,list,RiverProfiles.Action.DOWN,1));
            check(text(target.setBlock(PATH,original,0,"stone")).equals(text(original).replace("\"block\":\"custom:stored\"","\"block\":\"stone\"")));
            check(text(target.setWeight(PATH,original,0,"1e3")).equals(text(original).replace("\"weight\":1,","\"weight\":1000,")));
            for (String bad : List.of("0","1001","1.000000000000000001","NaN")) rejects(() -> target.setWeight(PATH,original,0,bad));
            check(block(target.enableWeight(PATH,initialized,0),list,0).token("weight").equals("1"));
            byte[] blockCopy=target.change(PATH,original,RiverProfiles.Action.DUPLICATE,0,null);check(Arrays.equals(block(blockCopy,list,0).sourceBytes(),block(blockCopy,list,1).sourceBytes()));
            byte[] blockMoved=target.change(PATH,original,RiverProfiles.Action.DOWN,0,null);check(Arrays.equals(block(original,list,0).sourceBytes(),block(blockMoved,list,1).sourceBytes()));
            target.change(PATH,blockMoved,RiverProfiles.Action.UP,1,null);target.change(PATH,original,RiverProfiles.Action.REMOVE,0,null);
            check(block(target.change(PATH,original,RiverProfiles.Action.ADD,-1,"air"),list,2).fields().equals(Set.of("block")));
            rejects(() -> target.change(PATH,initialized,RiverProfiles.Action.REMOVE,0,null));
            check(block(target.change(PATH,document(list,"[{\"palette\":[]}]"),RiverProfiles.Action.ADD,-1,"air"),list,0).stringToken("block").equals("air"));
            for (String bad : List.of(""," ","\t")) rejects(() -> target.setBlock(PATH,original,0,bad));
            var styleTarget=target.styleTarget();check(NoiseStyleFields.contextDefault(styleTarget).equals("STATIC"));
            check(layer(NoiseStyleFields.enable(PATH,added,styleTarget,"style"),list).stringPath("style.style").equals("STATIC"));
            check(layer(NoiseStyleFields.enable(PATH,document(list,"[{\"style\":{}}]"),styleTarget,"style"),list).stringPath("style.style").equals("FLAT"));
            for (String choice : NoiseStyleFields.choices()) check(text(NoiseStyleFields.editStyle(PATH,original,styleTarget,choice)).equals(text(original).replace("\"UNKNOWN\"","\""+choice+"\"")));
            byte[] zeroCell=NoiseStyleFields.editText(PATH,original,styleTarget,"cellularZoom","0");rejects(() -> NoiseStyleFields.editText(PATH,zeroCell,styleTarget,"cellularFrequency","1"));
            byte[] active=NoiseStyleFields.editText(PATH,original,styleTarget,"cellularFrequency","1");rejects(() -> NoiseStyleFields.editText(PATH,active,styleTarget,"cellularZoom","0"));
            if (list.equals("layers")) {
                byte[] slope=BiomeLayers.createSlope(PATH,added,list,0);check(BiomeLayers.slope(layer(slope,list)).fields().isEmpty());
                rejects(() -> BiomeLayers.createSlope(PATH,slope,list,0));rejects(() -> BiomeLayers.setSlope(PATH,added,list,0,"0","10"));
                check(text(BiomeLayers.setSlope(PATH,original,list,0,"1","9")).equals(text(original).replace("\"minimumSlope\":0","\"minimumSlope\":1").replace("\"maximumSlope\":10","\"maximumSlope\":9")));
                BiomeLayers.setSlope(PATH,slope,list,0,"0","1024");BiomeLayers.setSlope(PATH,slope,list,0,"0.25","1.5");
                for (String[] bad : new String[][]{{"-1","10"},{"0","1025"},{"2","1"},{"NaN","10"},{"0","Infinity"}}) rejects(() -> BiomeLayers.setSlope(PATH,slope,list,0,bad[0],bad[1]));
            } else { rejects(() -> BiomeLayers.createSlope(PATH,added,list,0));rejects(() -> BiomeLayers.setSlope(PATH,original,list,0,"1","9")); }
            var readOnly=BiomeLayers.readOnlyPaths(new LosslessJson(original),list);
            check(readOnly.containsAll(List.of(list+"[0].futureLayer",list+"[0].palette[0].large")));
            check(readOnly.contains(list.equals("layers") ? list+"[0].slopeCondition.futureSlope" : list+"[0].slopeCondition"));
            for (String bad : List.of("null","{}","true","1","\"snippet/biome-palette/x\"","[null]","[{},1]","[\"snippet/biome-palette/x\"]")) {
                byte[] invalid=document(list,bad);rejects(() -> BiomeLayers.enable(PATH,invalid,list));rejects(() -> BiomeLayers.change(PATH,invalid,list,RiverProfiles.Action.ADD,-1));
                check(BiomeLayers.readOnlyPaths(new LosslessJson(invalid),list).contains(list));
            }
            for (String bad : List.of("null","[]","true","\"1\"")) {
                byte[] invalid=document(list,"[{\"minHeight\":"+bad+",\"palette\":"+bad+",\"style\":"+bad+",\"slopeCondition\":"+bad+"}]");
                rejects(() -> BiomeLayers.setThickness(PATH,invalid,list,0,"1","2"));rejects(() -> target.enableEntries(PATH,invalid));
                if (bad.equals("[]")) check(block(target.change(PATH,invalid,RiverProfiles.Action.ADD,-1,"stone"),list,0).stringToken("block").equals("stone"));
                else rejects(() -> target.change(PATH,invalid,RiverProfiles.Action.ADD,-1,"stone"));
                rejects(() -> NoiseStyleFields.enable(PATH,invalid,styleTarget,"style"));
                if (list.equals("layers")) { rejects(() -> BiomeLayers.createSlope(PATH,invalid,list,0));rejects(() -> BiomeLayers.setSlope(PATH,invalid,list,0,"1","2")); }
                check(layer(target.enableZoom(PATH,invalid),list).token("palette").equals(bad));
            }
            for (String path : List.of("regions/a.json","dimensions/a.json","generators/a.json","a.json")) {
                rejects(() -> BiomeLayers.enable(path,bytes("{}"),list));rejects(() -> BiomeLayers.setThickness(path,original,list,0,"1","2"));rejects(() -> target.setBlock(path,original,0,"stone"));rejects(() -> NoiseStyleFields.enable(path,added,styleTarget,"style"));
            }
            rejects(() -> new NoiseStyleFields.Target("dimensions",styleTarget.objectField(),null));rejects(() -> new NoiseStyleFields.Target("biomes",styleTarget.objectField(),null,0));
            var drafts=new InspectorDrafts();String blockKey=target.listPrefix()+"0.block",noiseKey=target.noisePrefix()+"zoom",thicknessKey=BiomeLayers.layerPrefix(list,0)+"minHeight",otherKey=BiomeLayers.layerPrefix(list.equals("layers") ? "seaLayers" : "layers",0)+"maxHeight";
            for (String key : List.of(blockKey,noiseKey,thicknessKey,otherKey)) drafts.update(key,"1","2");
            check(!drafts.transitionUnder(BiomeLayers.listPrefix(list),() -> false,() -> { throw new AssertionError(); }) && drafts.count()==4);
            try { drafts.transitionUnder(BiomeLayers.listPrefix(list),() -> true,() -> { throw new IOException("failed write"); });throw new AssertionError(); } catch (IOException expected) { check(drafts.count()==4); }
            check(drafts.transitionUnder(target.listPrefix(),() -> true,() -> {}) && drafts.count()==3 && drafts.contains(noiseKey));
            check(drafts.transitionUnder(BiomeLayers.listPrefix(list),() -> true,() -> {}) && drafts.count()==1 && drafts.contains(otherKey));
            check(Arrays.equals(original,copy));var workspace=PackWorkspace.newPack(Map.of(PATH,original,"biomes/defaults.json",enabled,"biomes/added.json",added));
            workspace.save(temp.resolve("biome-"+list));workspace.exportZip(temp.resolve("biome-"+list+".zip"));var reopened=PackWorkspace.open(temp.resolve("biome-"+list));var imported=PackWorkspace.importZip(temp.resolve("biome-"+list+".zip"));
            for (String path : workspace.paths()) check(Arrays.equals(workspace.read(path),reopened.read(path)) && Arrays.equals(workspace.read(path),imported.read(path)));
        }
        byte[] independent=bytes("{\"layers\":[],\"seaLayers\":[],\"caveCeilingLayers\":[{},{}]}");
        check(layers(BiomeLayers.change(PATH,independent,"caveCeilingLayers",RiverProfiles.Action.ADD,-1),"caveCeilingLayers").size()==3);
        rejects(() -> BiomeLayers.enable(PATH,bytes("{}"),"slab"));rejects(() -> NoiseStyleFields.Target.biomeLayer("wall",0));rejects(() -> NoiseStyleFields.Target.biomeLayer("layers",-1));
        rejects(() -> new GeneralBlockPalettes.Target("rockPalette","layers",0));
        check(BiomeLayers.styleTarget("layers.01.style")==null && BiomeLayers.styleTarget("seaLayers.2147483648.style")==null);
        check(FieldCoverage.fields("biomePaletteLayer").size()==6 && FieldCoverage.fields("biomePaletteLayer").stream().allMatch(FieldCoverage.Field::implemented));
        check(FieldCoverage.fields("slopeClip").size()==2 && FieldCoverage.fields("slopeClip").stream().allMatch(FieldCoverage.Field::implemented));
        System.out.println("PASS: independent biome layer lists/defaults, lossless structure, paired thickness and surface slope gates, shared block weights/noise, typed owner/draft isolation and Save/ZIP contracts");
    }
}
