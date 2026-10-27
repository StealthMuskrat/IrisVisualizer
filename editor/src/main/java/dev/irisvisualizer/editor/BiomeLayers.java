package dev.irisvisualizer.editor;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.function.Function;

/** Inline biome material layers; snippet resolution and generation remain outside this inspector. */
public final class BiomeLayers {
    public static final List<String> LIST_FIELDS = List.of("layers", "seaLayers", "caveCeilingLayers");
    public static final List<String> EDITABLE = List.of("style", "minHeight", "maxHeight", "slopeCondition", "zoom", "palette");
    public static final List<String> SLOPE_FIELDS = List.of("minimumSlope", "maximumSlope");
    record Layer(String field,int index) {}
    private static final Properties DATA=new Properties();
    static {
        try (var input=BiomeLayers.class.getResourceAsStream("/biome-layer-fields.properties")) {
            if (input==null) throw new IOException("Missing pinned biome layer definitions");DATA.load(input);
            if (!"0".equals(DATA.getProperty("list.minimum")) || !"independentCeilingGenerators".equals(DATA.getProperty("ceiling.rule"))) throw new IOException("Unsupported layer list/generator policy");
        } catch (IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
    static void field(String field) { if (field==null || !LIST_FIELDS.contains(field)) throw new IllegalArgumentException("Unsupported biome layer list"); }
    private static void authorize(String path,String field) { field(field);if (!BiomeFields.biome(path)) throw new IllegalArgumentException("Material layers are biome-only"); }
    public static String description(String list) { field(list);return DATA.getProperty(list+".description",""); }
    public static String defaultBlock() { return DATA.getProperty("palette.defaultBlock"); }
    public static String styleDefault() { return DATA.getProperty("style.default"); }
    public static String styleDescription() { return DATA.getProperty("style.description",""); }
    public static String defaultHeight(String field) { if (!List.of("minHeight","maxHeight").contains(field)) throw new IllegalArgumentException("Unsupported thickness bound");return DATA.getProperty(field+".default"); }
    public static String slopeDefault(String field) { if (!SLOPE_FIELDS.contains(field)) throw new IllegalArgumentException("Unsupported slope bound");return DATA.getProperty(field+".default"); }
    public static String zoomDefault() { return DATA.getProperty("zoom.default"); }
    public static String zoomConstraints() { return "Finite layer zoom, minimum "+DATA.getProperty("zoom.minimum")+". Layer zoom affects sampling and is separate from generator style zoom."; }
    public static String listPrefix(String list) { field(list);return "biomeLayers#"+list+"."; }
    public static String layerPrefix(String list,int index) { if (index<0) throw new IllegalArgumentException("Select a layer");return listPrefix(list)+index+"."; }
    public static String blockPrefix(String list,int index) { return layerPrefix(list,index)+"blocks#"; }
    public static String noisePrefix(String list,int index) { return layerPrefix(list,index)+"noise."; }
    static Layer styleTarget(String path) {
        if (path==null) return null;var match=java.util.regex.Pattern.compile("(layers|seaLayers|caveCeilingLayers)\\.(0|[1-9][0-9]*)\\.style").matcher(path);
        if (!match.matches()) return null;try { return new Layer(match.group(1),Integer.parseInt(match.group(2))); } catch (NumberFormatException unsupported) { return null; }
    }
    static List<LosslessJson> entries(LosslessJson document,String list) { field(list);return document.token(list)==null ? null : document.objectArray(list); }
    static byte[] editLayer(byte[] original,String list,int index,Function<LosslessJson,byte[]> action) { field(list);return new LosslessJson(original).objectArrayEdit(list,index,action); }
    public static byte[] enable(String path,byte[] original,String list) {
        authorize(path,list);var document=new LosslessJson(original);if (document.token(list)!=null) throw new IllegalArgumentException("Layer list already exists or is unsupported");
        byte[] next=document.enable(list,"[]");for (int index=0;index<Integer.parseInt(DATA.getProperty(list+".defaultCount"));index++) next=new LosslessJson(next).objectArrayInsert(list,index,"{}".getBytes(StandardCharsets.UTF_8));return next;
    }
    public static byte[] change(String path,byte[] original,String list,RiverProfiles.Action action,int index) {
        authorize(path,list);return changeLayers(new LosslessJson(original),list,action,index);
    }
    static byte[] changeLayers(LosslessJson document,String list,RiverProfiles.Action action,int index) {
        if (action==null) throw new IllegalArgumentException("Choose a layer action");var layers=document.objectArray(list);
        if (action==RiverProfiles.Action.ADD) return document.objectArrayInsert(list,layers.size(),"{}".getBytes(StandardCharsets.UTF_8));
        if (index<0 || index>=layers.size()) throw new IllegalArgumentException("Select an existing layer");
        return switch(action) {
            case DUPLICATE -> document.objectArrayInsert(list,index+1,layers.get(index).sourceBytes());
            case REMOVE -> document.objectArrayRemove(list,index);
            case UP -> { if (index==0) throw new IllegalArgumentException("Already first");yield document.objectArrayMove(list,index,index-1); }
            case DOWN -> { if (index==layers.size()-1) throw new IllegalArgumentException("Already last");yield document.objectArrayMove(list,index,index+1); }
            default -> throw new IllegalArgumentException("Unsupported layer action");
        };
    }
    private static int thickness(String field,String input) {
        defaultHeight(field);int value;try { value=new BigDecimal(input).intValueExact(); } catch (NumberFormatException | ArithmeticException | NullPointerException invalid) { throw new IllegalArgumentException("Enter exact integer thickness bounds",invalid); }
        if (value<Integer.parseInt(DATA.getProperty(field+".minimum")) || value>Integer.parseInt(DATA.getProperty(field+".maximum"))) throw new IllegalArgumentException("Thickness violates pinned "+field+" bounds");return value;
    }
    private static byte[] integer(LosslessJson object,String field,int value) { return object.token(field)==null ? object.enable(field,Integer.toString(value)) : object.number(field,value); }
    private static byte[] number(LosslessJson object,String field,double value) { return object.token(field)==null ? object.enable(field,BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()) : object.number(field,value); }
    public static byte[] setThickness(String path,byte[] original,String list,int index,String minimum,String maximum) {
        authorize(path,list);return editLayer(original,list,index,layer -> thicknessBounds(layer,minimum,maximum));
    }
    static byte[] thicknessBounds(LosslessJson layer,String minimum,String maximum) {
        int min=thickness("minHeight",minimum),max=thickness("maxHeight",maximum);if (min>max) throw new IllegalArgumentException("Minimum thickness must not exceed maximum thickness");
        return integer(new LosslessJson(integer(layer,"minHeight",min)),"maxHeight",max);
    }
    static LosslessJson slope(LosslessJson layer) { String token=layer.token("slopeCondition");return token==null ? null : new LosslessJson(token.getBytes(StandardCharsets.UTF_8)); }
    private static void surface(String list) { field(list);if (!list.equals("layers")) throw new IllegalArgumentException("Slope gates are authored for surface layers only"); }
    public static byte[] createSlope(String path,byte[] original,String list,int index) {
        authorize(path,list);surface(list);return editLayer(original,list,index,layer -> { if (layer.token("slopeCondition")!=null) throw new IllegalArgumentException("Slope condition already exists or is unsupported");return layer.enableObject("slopeCondition"); });
    }
    private static double slopeValue(String field,String input) {
        slopeDefault(field);double value;try { value=Double.parseDouble(input); } catch (NumberFormatException | NullPointerException invalid) { throw new IllegalArgumentException("Enter finite slope bounds",invalid); }
        if (!Double.isFinite(value) || value<Double.parseDouble(DATA.getProperty(field+".minimum")) || value>Double.parseDouble(DATA.getProperty(field+".maximum"))) throw new IllegalArgumentException("Slope violates source bounds");return value;
    }
    public static byte[] setSlope(String path,byte[] original,String list,int index,String minimum,String maximum) {
        authorize(path,list);surface(list);return editLayer(original,list,index,layer -> slopeBounds(layer,minimum,maximum));
    }
    static byte[] slopeBounds(LosslessJson layer,String minimum,String maximum) {
        double min=slopeValue("minimumSlope",minimum),max=slopeValue("maximumSlope",maximum);if (min>max) throw new IllegalArgumentException("Minimum slope must not exceed maximum slope");
        return layer.objectEdit("slopeCondition",s -> number(new LosslessJson(number(s,"minimumSlope",min)),"maximumSlope",max),false);
    }
    public static byte[] enableZoom(String path,byte[] original,String list,int index) { authorize(path,list);return editLayer(original,list,index,layer -> layer.enable("zoom",zoomDefault())); }
    public static byte[] setZoom(String path,byte[] original,String list,int index,String input) {
        authorize(path,list);double value=checkedZoom(input);return editLayer(original,list,index,layer -> layer.number("zoom",value));
    }
    static double checkedZoom(String input) {
        double value;try { value=Double.parseDouble(input); } catch (NumberFormatException | NullPointerException invalid) { throw new IllegalArgumentException("Enter a finite layer zoom",invalid); }
        if (!Double.isFinite(value) || value<Double.parseDouble(DATA.getProperty("zoom.minimum"))) throw new IllegalArgumentException("Layer zoom violates its source minimum");return value;
    }
    public static byte[] enableBlocks(String path,byte[] original,String list,int index) { authorize(path,list);return editLayer(original,list,index,layer -> GeneralBlockPalettes.seed(layer,defaultBlock())); }
    public static byte[] setBlock(String path,byte[] original,String list,int layer,int index,String value) { authorize(path,list);return editLayer(original,list,layer,p -> GeneralBlockPalettes.setBlock(p,index,value)); }
    public static byte[] enableWeight(String path,byte[] original,String list,int layer,int index) { authorize(path,list);return editLayer(original,list,layer,p -> GeneralBlockPalettes.enableWeight(p,index)); }
    public static byte[] setWeight(String path,byte[] original,String list,int layer,int index,String input) { authorize(path,list);return editLayer(original,list,layer,p -> GeneralBlockPalettes.setWeight(p,index,input)); }
    public static byte[] changeBlock(String path,byte[] original,String list,int layer,RiverProfiles.Action action,int index,String value) { authorize(path,list);return editLayer(original,list,layer,p -> GeneralBlockPalettes.change(p,action,index,value)); }
    static List<String> readOnlyPaths(LosslessJson document,String list) {
        field(list);var paths=new ArrayList<String>();
        try {
            var layers=entries(document,list);if (layers==null) return List.of();
            for (int index=0;index<layers.size();index++) {
                var layer=layers.get(index);String prefix=list+"["+index+"]";
                for (String item : layer.fields()) if (!EDITABLE.contains(item)) paths.add(prefix+"."+item);
                try { var style=NoiseStyleFields.object(layer,NoiseStyleFields.Target.biomeLayer(list,index));if (style!=null) for (String item : style.fields()) if (!NoiseStyleFields.EDITABLE.contains(item)) paths.add(prefix+".style."+item); }
                catch (IllegalArgumentException unsupported) { paths.add(prefix+".style"); }
                try { var blocks=DimensionPalettes.entries(layer);if (blocks!=null) for (int i=0;i<blocks.size();i++) for (String item : blocks.get(i).fields()) if (!RiverMaterialFields.ENTRY_FIELDS.contains(item)) paths.add(prefix+".palette["+i+"]."+item); }
                catch (IllegalArgumentException unsupported) { paths.add(prefix+".palette"); }
                if (layer.token("slopeCondition")!=null) {
                    if (!list.equals("layers")) paths.add(prefix+".slopeCondition");
                    else try { var slope=slope(layer);for (String item : slope.fields()) if (!SLOPE_FIELDS.contains(item)) paths.add(prefix+".slopeCondition."+item); }
                    catch (IllegalArgumentException unsupported) { paths.add(prefix+".slopeCondition"); }
                }
            }
        } catch (IllegalArgumentException unsupported) { paths.add(list); }
        return List.copyOf(paths);
    }
}
