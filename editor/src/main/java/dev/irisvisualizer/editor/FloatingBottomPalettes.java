package dev.irisvisualizer.editor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.function.Function;

/** Inline CUSTOM underside layers; editing never changes the entry's selected material mode. */
public final class FloatingBottomPalettes {
    public static final List<String> EDITABLE = List.of("bottomPalette");
    record Context(int entry,int layer) {}
    private static final Properties DATA=new Properties();
    static {
        try (var input=FloatingBottomPalettes.class.getResourceAsStream("/floating-bottom-fields.properties")) {
            if (input==null) throw new IOException("Missing pinned floating underside definitions");DATA.load(input);
            if (!"[]".equals(DATA.getProperty("list.default")) || !"noGate".equals(DATA.getProperty("nullSlope")) || !"CUSTOM".equals(DATA.getProperty("mode")) || !BiomeLayers.defaultBlock().equals(DATA.getProperty("block.default")) || !BiomeLayers.styleDefault().equals(DATA.getProperty("style.default"))) throw new IOException("Unsupported floating underside policy");
        } catch (IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
    private static void authorize(String path) { if (!BiomeFields.biome(path)) throw new IllegalArgumentException("Floating underside palettes are biome-only"); }
    public static String description() { return DATA.getProperty("list.description",""); }
    public static String listPrefix(int entry) { return FloatingBiomes.entryPrefix(entry)+"bottomPalette#"; }
    public static String layerPrefix(int entry,int layer) { if (layer<0) throw new IllegalArgumentException("Select a layer");return listPrefix(entry)+layer+"."; }
    public static String blockPrefix(int entry,int layer) { return layerPrefix(entry,layer)+"blocks#"; }
    public static String noisePrefix(int entry,int layer) { return layerPrefix(entry,layer)+"noise."; }
    static Context context(String path) {
        if (path==null) return null;var match=java.util.regex.Pattern.compile("floatingChildBiomes\\.(0|[1-9][0-9]*)\\.bottomPalette\\.(0|[1-9][0-9]*)\\.style").matcher(path);
        if (!match.matches()) return null;try { return new Context(Integer.parseInt(match.group(1)),Integer.parseInt(match.group(2))); } catch (NumberFormatException unsupported) { return null; }
    }
    static List<LosslessJson> layers(LosslessJson entry) { String token=entry.token("bottomPalette");return token==null || token.equals("null") ? null : entry.objectArray("bottomPalette"); }
    static byte[] editLayer(byte[] original,int entry,int layer,Function<LosslessJson,byte[]> action) { return FloatingBiomes.editEntry(original,entry,e -> e.objectArrayEdit("bottomPalette",layer,action)); }
    public static byte[] enable(String path,byte[] original,int entry) { authorize(path);return FloatingBiomes.editEntry(original,entry,e -> e.enableArray("bottomPalette")); }
    public static byte[] change(String path,byte[] original,int entry,RiverProfiles.Action action,int layer) { authorize(path);return FloatingBiomes.editEntry(original,entry,e -> BiomeLayers.changeLayers(e,"bottomPalette",action,layer)); }
    public static byte[] setThickness(String path,byte[] original,int entry,int layer,String minimum,String maximum) { authorize(path);return editLayer(original,entry,layer,e -> BiomeLayers.thicknessBounds(e,minimum,maximum)); }
    public static byte[] createSlope(String path,byte[] original,int entry,int layer) { authorize(path);return editLayer(original,entry,layer,e -> { String token=e.token("slopeCondition");if (token!=null && !token.equals("null")) throw new IllegalArgumentException("Slope condition already exists or is unsupported");return e.enableObject("slopeCondition"); }); }
    public static byte[] setSlope(String path,byte[] original,int entry,int layer,String minimum,String maximum) { authorize(path);return editLayer(original,entry,layer,e -> BiomeLayers.slopeBounds(e,minimum,maximum)); }
    public static byte[] enableBlocks(String path,byte[] original,int entry,int layer) { authorize(path);return editLayer(original,entry,layer,e -> GeneralBlockPalettes.seed(e,BiomeLayers.defaultBlock())); }
    public static byte[] setBlock(String path,byte[] original,int entry,int layer,int block,String value) { authorize(path);return editLayer(original,entry,layer,e -> GeneralBlockPalettes.setBlock(e,block,value)); }
    public static byte[] enableWeight(String path,byte[] original,int entry,int layer,int block) { authorize(path);return editLayer(original,entry,layer,e -> GeneralBlockPalettes.enableWeight(e,block)); }
    public static byte[] setWeight(String path,byte[] original,int entry,int layer,int block,String value) { authorize(path);return editLayer(original,entry,layer,e -> GeneralBlockPalettes.setWeight(e,block,value)); }
    public static byte[] changeBlock(String path,byte[] original,int entry,int layer,RiverProfiles.Action action,int block,String value) { authorize(path);return editLayer(original,entry,layer,e -> GeneralBlockPalettes.change(e,action,block,value)); }
    public static byte[] enableZoom(String path,byte[] original,int entry,int layer) { authorize(path);return editLayer(original,entry,layer,e -> e.enable("zoom",BiomeLayers.zoomDefault())); }
    public static byte[] setZoom(String path,byte[] original,int entry,int layer,String value) { authorize(path);double checked=BiomeLayers.checkedZoom(value);return editLayer(original,entry,layer,e -> e.number("zoom",checked)); }
    static List<String> readOnlyPaths(LosslessJson entry,int index) {
        var paths=new ArrayList<String>();String prefix="floatingChildBiomes["+index+"].bottomPalette";
        try {
            var layers=layers(entry);if (layers==null) return List.of();
            for (int i=0;i<layers.size();i++) {
                var layer=layers.get(i);String layerPath=prefix+"["+i+"]";
                for (String field : layer.fields()) if (!BiomeLayers.EDITABLE.contains(field)) paths.add(layerPath+"."+field);
                try { var style=NoiseStyleFields.object(layer);if (style!=null) for (String field : style.fields()) if (!NoiseStyleFields.EDITABLE.contains(field)) paths.add(layerPath+".style."+field); }
                catch (IllegalArgumentException unsupported) { paths.add(layerPath+".style"); }
                try { var blocks=DimensionPalettes.entries(layer);if (blocks!=null) for (int j=0;j<blocks.size();j++) for (String field : blocks.get(j).fields()) if (!RiverMaterialFields.ENTRY_FIELDS.contains(field)) paths.add(layerPath+".palette["+j+"]."+field); }
                catch (IllegalArgumentException unsupported) { paths.add(layerPath+".palette"); }
                if (layer.token("slopeCondition")!=null && !layer.token("slopeCondition").equals("null")) {
                    try { for (String field : BiomeLayers.slope(layer).fields()) if (!BiomeLayers.SLOPE_FIELDS.contains(field)) paths.add(layerPath+".slopeCondition."+field); }
                    catch (IllegalArgumentException unsupported) { paths.add(layerPath+".slopeCondition"); }
                }
            }
        } catch (IllegalArgumentException unsupported) { paths.add(prefix); }
        return List.copyOf(paths);
    }
}
