package dev.irisvisualizer.editor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.function.Function;

/** General dimension palettes, with source annotation weights rather than hydrology-specific limits. */
public final class DimensionPalettes {
    public static final List<String> OBJECT_FIELDS = List.of("rockPalette", "fluidPalette");
    private static final Properties DATA=new Properties();
    static {
        try (var input=DimensionPalettes.class.getResourceAsStream("/dimension-palette-fields.properties")) {
            if (input==null) throw new IOException("Missing pinned dimension palette definitions");DATA.load(input);
            if (!"1".equals(DATA.getProperty("palette.minimum"))) throw new IOException("Unsupported palette minimum");
        } catch (IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
    private static void field(String field) { if (field==null || !OBJECT_FIELDS.contains(field)) throw new IllegalArgumentException("Unsupported dimension palette"); }
    private static void authorize(String path,String field) { if (!DimensionFields.dimension(path)) throw new IllegalArgumentException("Root palettes are dimension-only");field(field); }
    public static String defaultBlock(String field) { field(field);return DATA.getProperty(field+".block"); }
    public static String classBlock() { return DATA.getProperty("palette.classBlock"); }
    public static String description(String field) { field(field);return DATA.getProperty(field+".description",""); }
    public static String weightDefault() { return DATA.getProperty("weight.default"); }
    static int minimumWeight() { return Integer.parseInt(DATA.getProperty("weight.minimum")); }
    static int minimumEntries() { return Integer.parseInt(DATA.getProperty("palette.minimum")); }
    public static int maximumWeight() { return Integer.parseInt(DATA.getProperty("weight.maximum")); }
    public static String listPrefix(String field) { field(field);return "dimensionPalette#"+field+".entries#"; }
    public static String noisePrefix(String field) { field(field);return "dimensionPalette#"+field+".noise."; }
    static LosslessJson object(LosslessJson document,String field) { field(field);String token=document.token(field);return token==null ? null : new LosslessJson(token.getBytes(StandardCharsets.UTF_8)); }
    static List<LosslessJson> entries(LosslessJson palette) { return palette.token("palette")==null ? null : palette.objectArray("palette"); }
    static byte[] editPalette(byte[] original,String field,Function<LosslessJson,byte[]> action) { field(field);return new LosslessJson(original).objectEdit(field,action,false); }
    public static byte[] create(String path,byte[] original,String field) {
        authorize(path,field);var document=new LosslessJson(original);
        if (document.token(field)!=null) throw new IllegalArgumentException("Palette already exists or has an unsupported type");
        return document.objectEdit(field,p -> GeneralBlockPalettes.seed(p,defaultBlock(field)),true);
    }
    public static byte[] enableEntries(String path,byte[] original,String field) { authorize(path,field);return editPalette(original,field,p -> GeneralBlockPalettes.seed(p,classBlock())); }
    public static byte[] setBlock(String path,byte[] original,String field,int index,String value) { authorize(path,field);return editPalette(original,field,p -> GeneralBlockPalettes.setBlock(p,index,value)); }
    public static byte[] enableWeight(String path,byte[] original,String field,int index) { authorize(path,field);return editPalette(original,field,p -> GeneralBlockPalettes.enableWeight(p,index)); }
    public static byte[] setWeight(String path,byte[] original,String field,int index,String input) { authorize(path,field);return editPalette(original,field,p -> GeneralBlockPalettes.setWeight(p,index,input)); }
    public static byte[] change(String path,byte[] original,String field,RiverProfiles.Action action,int index,String value) { authorize(path,field);return editPalette(original,field,p -> GeneralBlockPalettes.change(p,action,index,value)); }
    public static byte[] enableZoom(String path,byte[] original,String field) { authorize(path,field);return editPalette(original,field,p -> p.enable("zoom",RiverPaletteFields.zoomDefault())); }
    public static byte[] setZoom(String path,byte[] original,String field,String input) { authorize(path,field);double zoom=RiverPaletteFields.checkedZoom(input);return editPalette(original,field,p -> p.number("zoom",zoom)); }
    static List<String> readOnlyPaths(LosslessJson document,String field) {
        field(field);var paths=new ArrayList<String>();
        try {
            var palette=object(document,field);if (palette==null) return List.of();
            for (String item : palette.fields()) if (!RiverMaterialFields.PALETTE_FIELDS.contains(item)) paths.add(field+"."+item);
            try { var style=NoiseStyleFields.object(palette,NoiseStyleFields.Target.dimensionPalette(field));if (style!=null) for (String item : style.fields()) if (!NoiseStyleFields.EDITABLE.contains(item)) paths.add(field+".style."+item); }
            catch (IllegalArgumentException unsupported) { paths.add(field+".style"); }
            try { var entries=entries(palette);if (entries!=null) for (int index=0;index<entries.size();index++) for (String item : entries.get(index).fields()) if (!RiverMaterialFields.ENTRY_FIELDS.contains(item)) paths.add(field+".palette["+index+"]."+item); }
            catch (IllegalArgumentException unsupported) { paths.add(field+".palette"); }
        } catch (IllegalArgumentException unsupported) { paths.add(field); }
        return List.copyOf(paths);
    }
}
