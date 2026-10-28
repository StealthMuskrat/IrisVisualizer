package dev.irisvisualizer.editor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.function.Function;

/** Biome slab/wall material palettes, including the source's empty contextual default. */
public final class BiomeSlabWall {
    public static final List<String> OBJECT_FIELDS = List.of("slab", "wall");
    public static final List<String> PALETTE_FIELDS = List.of("style", "zoom", "palette");
    private static final Properties DATA=new Properties();
    static {
        try (var input=BiomeSlabWall.class.getResourceAsStream("/biome-slab-wall-fields.properties")) {
            if (input==null) throw new IOException("Missing pinned slab/wall definitions");DATA.load(input);
            if (!"[]".equals(DATA.getProperty("palette.contextDefault")) || !"0".equals(DATA.getProperty("palette.minimum")) || !"slabOnly".equals(DATA.getProperty("slope.context")) || !"readOnly".equals(DATA.getProperty("thickness.context"))) throw new IOException("Unsupported slab/wall policy");
        } catch (IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
    static void field(String field) { if (field==null || !OBJECT_FIELDS.contains(field)) throw new IllegalArgumentException("Unsupported slab/wall field"); }
    private static void authorize(String path,String field) { field(field);if (!BiomeFields.biome(path)) throw new IllegalArgumentException("Slab/wall palettes are biome-only"); }
    public static String description(String field) { field(field);return DATA.getProperty(field+".description",""); }
    public static String classBlock() { return DATA.getProperty("palette.classBlock"); }
    public static String prefix(String field) { field(field);return "biomeSlabWall#"+field+"."; }
    public static String blockPrefix(String field) { return prefix(field)+"entries#"; }
    public static String noisePrefix(String field) { return prefix(field)+"noise."; }
    static LosslessJson object(LosslessJson document,String field) { field(field);String token=document.token(field);return token==null ? null : new LosslessJson(token.getBytes(StandardCharsets.UTF_8)); }
    static byte[] editObject(byte[] original,String field,Function<LosslessJson,byte[]> action) { field(field);return new LosslessJson(original).objectEdit(field,action,false); }
    public static byte[] create(String path,byte[] original,String field) {
        authorize(path,field);var document=new LosslessJson(original);if (document.token(field)!=null) throw new IllegalArgumentException("Material already exists or is unsupported");
        return document.objectEdit(field,p -> p.enable("palette","[]"),true);
    }
    public static byte[] enableEntries(String path,byte[] original,String field) { authorize(path,field);return editObject(original,field,p -> GeneralBlockPalettes.seed(p,classBlock())); }
    public static byte[] setBlock(String path,byte[] original,String field,int index,String value) { authorize(path,field);return editObject(original,field,p -> GeneralBlockPalettes.setBlock(p,index,value)); }
    public static byte[] enableWeight(String path,byte[] original,String field,int index) { authorize(path,field);return editObject(original,field,p -> GeneralBlockPalettes.enableWeight(p,index)); }
    public static byte[] setWeight(String path,byte[] original,String field,int index,String value) { authorize(path,field);return editObject(original,field,p -> GeneralBlockPalettes.setWeight(p,index,value)); }
    public static byte[] change(String path,byte[] original,String field,RiverProfiles.Action action,int index,String value) { authorize(path,field);return editObject(original,field,p -> GeneralBlockPalettes.change(p,action,index,value,0)); }
    public static byte[] enableZoom(String path,byte[] original,String field) { authorize(path,field);return editObject(original,field,p -> p.enable("zoom",BiomeLayers.zoomDefault())); }
    public static byte[] setZoom(String path,byte[] original,String field,String input) { authorize(path,field);double value=BiomeLayers.checkedZoom(input);return editObject(original,field,p -> p.number("zoom",value)); }
    private static void slab(String field) { field(field);if (!field.equals("slab")) throw new IllegalArgumentException("Wall slope stays read-only"); }
    public static byte[] createSlope(String path,byte[] original,String field) {
        authorize(path,field);slab(field);return editObject(original,field,p -> { if (p.token("slopeCondition")!=null) throw new IllegalArgumentException("Slope condition already exists or is unsupported");return p.enableObject("slopeCondition"); });
    }
    public static byte[] setSlope(String path,byte[] original,String field,String minimum,String maximum) { authorize(path,field);slab(field);return editObject(original,field,p -> BiomeLayers.slopeBounds(p,minimum,maximum)); }
    static List<String> readOnlyPaths(LosslessJson document,String field) {
        field(field);var paths=new ArrayList<String>();
        try {
            var palette=object(document,field);if (palette==null) return List.of();
            for (String item : palette.fields()) if (!PALETTE_FIELDS.contains(item) && !(field.equals("slab") && item.equals("slopeCondition"))) paths.add(field+"."+item);
            try { var style=NoiseStyleFields.object(palette);if (style!=null) for (String item : StyleFractures.readOnlyPaths(style)) paths.add(field+".style."+item); }
            catch (IllegalArgumentException unsupported) { paths.add(field+".style"); }
            try { PaletteBlockData.report(paths,DimensionPalettes.entries(palette),field+".palette"); }
            catch (IllegalArgumentException unsupported) { paths.add(field+".palette"); }
            if (field.equals("slab") && palette.token("slopeCondition")!=null) {
                try { var slope=BiomeLayers.slope(palette);for (String item : slope.fields()) if (!BiomeLayers.SLOPE_FIELDS.contains(item)) paths.add(field+".slopeCondition."+item); }
                catch (IllegalArgumentException unsupported) { paths.add(field+".slopeCondition"); }
            }
        } catch (IllegalArgumentException unsupported) { paths.add(field); }
        return List.copyOf(paths);
    }
}
