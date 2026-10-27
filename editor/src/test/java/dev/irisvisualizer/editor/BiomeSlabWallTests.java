package dev.irisvisualizer.editor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class BiomeSlabWallTests {
    private static final String PATH="biomes/nested/materials.json";
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static String text(byte[] value) { return new String(value,StandardCharsets.UTF_8); }
    private static void check(boolean value) { if (!value) throw new AssertionError("Biome slab/wall contract failed"); }
    private static void rejects(Runnable action) { try { action.run(); } catch (IllegalArgumentException expected) { return; }throw new AssertionError("Expected rejected slab/wall operation"); }
    private static byte[] document(String field,String token) { return bytes("{\r\n \""+field+"\":"+token+",\"futureRoot\":9007199254740993,\"escaped\":\"雪\\u0061\",\"postProcessingSlabs\":false\r\n}\r\n"); }
    private static LosslessJson object(byte[] value,String field) { return BiomeSlabWall.object(new LosslessJson(value),field); }
    private static List<LosslessJson> blocks(byte[] value,String field) { return DimensionPalettes.entries(object(value,field)); }
    static void run(Path temp) throws Exception {
        check(BiomeSlabWall.classBlock().equals("GRASS_BLOCK"));
        for (String field : BiomeSlabWall.OBJECT_FIELDS) {
            var target=GeneralBlockPalettes.Target.slabWall(field);check(target.slabWall() && !target.layer() && target.minimumEntries()==0 && target.defaultBlock().equals("GRASS_BLOCK"));
            byte[] absent=bytes("{\r\n \"future\":9007199254740993\r\n}\r\n");check(object(absent,field)==null);
            byte[] created=BiomeSlabWall.create(PATH,absent,field);check(blocks(created,field).isEmpty() && object(created,field).fields().equals(Set.of("palette")));
            check(new LosslessJson(created).token("future").equals("9007199254740993"));rejects(() -> BiomeSlabWall.create(PATH,created,field));
            rejects(() -> target.enableEntries(PATH,created));rejects(() -> target.change(PATH,absent,RiverProfiles.Action.ADD,-1,"stone"));rejects(() -> target.enableZoom(PATH,absent));
            byte[] added=target.change(PATH,created,RiverProfiles.Action.ADD,-1,"custom:material");check(blocks(added,field).getFirst().stringToken("block").equals("custom:material"));
            byte[] removed=target.change(PATH,added,RiverProfiles.Action.REMOVE,0,null);check(blocks(removed,field).isEmpty());
            check(blocks(target.change(PATH,removed,RiverProfiles.Action.ADD,-1,"air"),field).getFirst().stringToken("block").equals("air"));
            byte[] empty=document(field,"{}");check(blocks(empty,field)==null);
            byte[] initialized=target.enableEntries(PATH,empty);check(blocks(initialized,field).getFirst().stringToken("block").equals("GRASS_BLOCK"));
            check(object(initialized,field).fields().equals(Set.of("palette")));rejects(() -> target.enableEntries(PATH,initialized));
            byte[] weighted=target.enableWeight(PATH,initialized,0);check(blocks(weighted,field).getFirst().token("weight").equals("1"));
            rejects(() -> target.enableWeight(PATH,weighted,0));rejects(() -> target.setWeight(PATH,initialized,0,"2"));
            for (String value : List.of("1","64","65","1e3")) check(Integer.parseInt(blocks(target.setWeight(PATH,weighted,0,value),field).getFirst().token("weight"))==new java.math.BigDecimal(value).intValueExact());
            for (String bad : List.of("0","1001","1.000000000000000001","NaN","2147483648")) rejects(() -> target.setWeight(PATH,weighted,0,bad));
            String palette="{\"palette\":[ {\"block\":\"custom:stored\",\"weight\":1,\"data\":{\"future\":\"雪\\u0061\"},\"backup\":null,\"large\":9007199254740993}, {\"block\":\"stone\",\"weight\":1000} ],\"zoom\":5e0,\"style\":{\"style\":\"UNKNOWN\",\"zoom\":1e0,\"cellularFrequency\":0,\"cellularZoom\":1,\"expression\":\"kept\",\"fracture\":{}},\"minHeight\":7,\"maxHeight\":9,\"slopeCondition\":{\"minimumSlope\":0,\"maximumSlope\":10,\"futureSlope\":true},\"futureMaterial\":true}";
            byte[] original=document(field,palette),copy=original.clone();
            check(text(target.setBlock(PATH,original,0,"stone")).equals(text(original).replace("\"block\":\"custom:stored\"","\"block\":\"stone\"")));
            check(text(target.setWeight(PATH,original,0,"1000")).equals(text(original).replace("\"weight\":1,","\"weight\":1000,")));
            for (String bad : List.of(""," ","\t")) { rejects(() -> target.setBlock(PATH,original,0,bad));rejects(() -> target.change(PATH,original,RiverProfiles.Action.ADD,-1,bad)); }
            byte[] duplicate=target.change(PATH,original,RiverProfiles.Action.DUPLICATE,0,null);check(Arrays.equals(blocks(duplicate,field).get(0).sourceBytes(),blocks(duplicate,field).get(1).sourceBytes()));
            byte[] moved=target.change(PATH,original,RiverProfiles.Action.DOWN,0,null);check(Arrays.equals(blocks(original,field).getFirst().sourceBytes(),blocks(moved,field).get(1).sourceBytes()));
            check(Arrays.equals(original,target.change(PATH,moved,RiverProfiles.Action.UP,1,null)));
            check(blocks(target.change(PATH,original,RiverProfiles.Action.REMOVE,0,null),field).size()==1);
            rejects(() -> target.change(PATH,original,RiverProfiles.Action.UP,0,null));rejects(() -> target.change(PATH,original,RiverProfiles.Action.DOWN,1,null));
            byte[] zoomed=target.enableZoom(PATH,created);check(object(zoomed,field).token("zoom").equals("5") && blocks(zoomed,field).isEmpty());
            check(text(target.setZoom(PATH,original,"10")).equals(text(original).replace("\"zoom\":5e0","\"zoom\":10")));
            target.setZoom(PATH,zoomed,"0.0001");rejects(() -> target.setZoom(PATH,created,"10"));
            for (String bad : List.of("0","0.00009","NaN","Infinity","1e999")) rejects(() -> target.setZoom(PATH,zoomed,bad));
            var styleTarget=target.styleTarget();check(NoiseStyleFields.contextDefault(styleTarget).equals("STATIC") && styleTarget.member().equals("style"));
            byte[] style=NoiseStyleFields.enable(PATH,created,styleTarget,"style");check(object(style,field).stringPath("style.style").equals("STATIC") && blocks(style,field).isEmpty());
            check(object(NoiseStyleFields.enable(PATH,document(field,"{\"style\":{}}"),styleTarget,"style"),field).stringPath("style.style").equals("FLAT"));
            rejects(() -> NoiseStyleFields.enable(PATH,absent,styleTarget,"style"));
            for (String choice : NoiseStyleFields.choices()) check(text(NoiseStyleFields.editStyle(PATH,original,styleTarget,choice)).equals(text(original).replace("\"UNKNOWN\"","\""+choice+"\"")));
            byte[] inactive=NoiseStyleFields.editText(PATH,original,styleTarget,"cellularZoom","0");rejects(() -> NoiseStyleFields.editText(PATH,inactive,styleTarget,"cellularFrequency","1"));
            byte[] active=NoiseStyleFields.editText(PATH,original,styleTarget,"cellularFrequency","1");rejects(() -> NoiseStyleFields.editText(PATH,active,styleTarget,"cellularZoom","0"));
            if (field.equals("slab")) {
                byte[] slope=BiomeSlabWall.createSlope(PATH,created,field);check(BiomeLayers.slope(object(slope,field)).fields().isEmpty());
                rejects(() -> BiomeSlabWall.createSlope(PATH,slope,field));rejects(() -> BiomeSlabWall.setSlope(PATH,created,field,"0","10"));
                check(text(BiomeSlabWall.setSlope(PATH,original,field,"1","9")).equals(text(original).replace("\"minimumSlope\":0","\"minimumSlope\":1").replace("\"maximumSlope\":10","\"maximumSlope\":9")));
                BiomeSlabWall.setSlope(PATH,slope,field,"0","1024");BiomeSlabWall.setSlope(PATH,slope,field,"0.25","1.5");
                for (String[] bad : new String[][]{{"-1","10"},{"0","1025"},{"2","1"},{"NaN","10"},{"0","Infinity"}}) rejects(() -> BiomeSlabWall.setSlope(PATH,slope,field,bad[0],bad[1]));
            } else { rejects(() -> BiomeSlabWall.createSlope(PATH,created,field));rejects(() -> BiomeSlabWall.setSlope(PATH,original,field,"1","9")); }
            var readOnly=BiomeSlabWall.readOnlyPaths(new LosslessJson(original),field);check(readOnly.containsAll(List.of(field+".minHeight",field+".maxHeight",field+".futureMaterial",field+".palette[0].data")));
            check(readOnly.contains(field.equals("slab") ? field+".slopeCondition.futureSlope" : field+".slopeCondition"));
            for (String bad : List.of("null","[]","true","1","\"snippet/biome-palette/x\"")) {
                byte[] invalid=document(field,bad);rejects(() -> BiomeSlabWall.create(PATH,invalid,field));rejects(() -> target.enableEntries(PATH,invalid));rejects(() -> target.enableZoom(PATH,invalid));
                rejects(() -> target.change(PATH,invalid,RiverProfiles.Action.ADD,-1,"stone"));rejects(() -> NoiseStyleFields.enable(PATH,invalid,styleTarget,"style"));
                check(BiomeSlabWall.readOnlyPaths(new LosslessJson(invalid),field).contains(field));
            }
            for (String bad : List.of("null","[]","true","\"1\"")) {
                byte[] invalid=document(field,"{\"palette\":"+bad+",\"style\":"+bad+",\"slopeCondition\":"+bad+"}");
                rejects(() -> target.enableEntries(PATH,invalid));
                if (bad.equals("[]")) check(blocks(target.change(PATH,invalid,RiverProfiles.Action.ADD,-1,"stone"),field).size()==1);
                else rejects(() -> target.change(PATH,invalid,RiverProfiles.Action.ADD,-1,"stone"));
                rejects(() -> NoiseStyleFields.enable(PATH,invalid,styleTarget,"style"));
                if (field.equals("slab")) { rejects(() -> BiomeSlabWall.createSlope(PATH,invalid,field));rejects(() -> BiomeSlabWall.setSlope(PATH,invalid,field,"1","2")); }
                check(object(target.enableZoom(PATH,invalid),field).token("palette").equals(bad));
            }
            for (String bad : List.of("[null]","[{},1]","[\"snippet/block\"]")) rejects(() -> target.change(PATH,document(field,"{\"palette\":"+bad+"}"),RiverProfiles.Action.ADD,-1,"stone"));
            for (String path : List.of("regions/a.json","dimensions/a.json","generators/a.json","a.json","biomes/a.txt")) {
                rejects(() -> BiomeSlabWall.create(path,absent,field));rejects(() -> target.setBlock(path,original,0,"stone"));rejects(() -> target.enableZoom(path,created));rejects(() -> NoiseStyleFields.enable(path,created,styleTarget,"style"));
            }
            rejects(() -> new NoiseStyleFields.Target("dimensions",field+".style",null));rejects(() -> new NoiseStyleFields.Target("biomes",field+".style",null,0));
            var drafts=new InspectorDrafts();String blockKey=target.listPrefix()+"0.block",noiseKey=target.noisePrefix()+"zoom",slopeKey=BiomeSlabWall.prefix(field)+"slopeCondition.minimumSlope",otherKey=BiomeSlabWall.blockPrefix(field.equals("slab") ? "wall" : "slab")+"0.block",layerKey=BiomeLayers.blockPrefix("layers",0)+"0.block";
            for (String key : List.of(blockKey,noiseKey,slopeKey,otherKey,layerKey)) drafts.update(key,"1","2");
            check(!drafts.transitionUnder(target.listPrefix(),() -> false,() -> { throw new AssertionError(); }) && drafts.count()==5);
            try { drafts.transitionUnder(target.listPrefix(),() -> true,() -> { throw new IOException("failed write"); });throw new AssertionError(); } catch (IOException expected) { check(drafts.count()==5); }
            check(drafts.transitionUnder(target.listPrefix(),() -> true,() -> {}) && drafts.count()==4 && drafts.contains(noiseKey) && drafts.contains(slopeKey) && drafts.contains(otherKey) && drafts.contains(layerKey));
            check(Arrays.equals(original,copy));var workspace=PackWorkspace.newPack(Map.of(PATH,original,"biomes/defaults.json",created,"biomes/class-defaults.json",empty));
            workspace.replace(PATH,target.setBlock(PATH,workspace.read(PATH),0,"custom:changed"));workspace.replace(PATH,NoiseStyleFields.editStyle(PATH,workspace.read(PATH),styleTarget,"STATIC"));
            workspace.save(temp.resolve("slab-wall-"+field));workspace.exportZip(temp.resolve("slab-wall-"+field+".zip"));var reopened=PackWorkspace.open(temp.resolve("slab-wall-"+field));var imported=PackWorkspace.importZip(temp.resolve("slab-wall-"+field+".zip"));
            for (String path : workspace.paths()) check(Arrays.equals(workspace.read(path),reopened.read(path)) && Arrays.equals(workspace.read(path),imported.read(path)));
        }
        check(GeneralBlockPalettes.Target.dimension("rockPalette").minimumEntries()==1 && GeneralBlockPalettes.Target.layer("layers",0).minimumEntries()==1);
        rejects(() -> GeneralBlockPalettes.Target.slabWall("layers"));rejects(() -> NoiseStyleFields.Target.slabWall("rockPalette"));
        rejects(() -> new GeneralBlockPalettes.Target("rockPalette",null,-1,"slab"));rejects(() -> new GeneralBlockPalettes.Target(null,"layers",0,"wall"));
        check(!BiomeSlabWall.blockPrefix("slab").startsWith(BiomeSlabWall.noisePrefix("slab")));
        check(FieldCoverage.fields("biome").stream().filter(f -> BiomeSlabWall.OBJECT_FIELDS.contains(f.name())).allMatch(FieldCoverage.Field::implemented));
        System.out.println("PASS: biome slab/wall empty contextual versus GRASS class defaults, zero-entry palettes, weighted blocks/all noise choices, slab-only paired slope, read-only thickness/wall slope, owner/draft isolation and lossless Save/ZIP contracts");
    }
}
