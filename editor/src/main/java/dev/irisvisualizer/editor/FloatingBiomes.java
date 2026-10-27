package dev.irisvisualizer.editor;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;
import java.util.function.Function;

/** Inline floating child settings; placement families have separate bounded inspectors. */
public final class FloatingBiomes {
    public static final List<String> LIST_FIELDS = List.of("floatingChildBiomes");
    public static final List<String> EDITABLE = List.of("biome", "rarity", "footprintThreshold", "edgeTaperWidth", "edgeTaperExponent", "edgeTaperVariationAmplitude", "minHeightAboveSurface", "maxHeightAboveSurface", "minAbsoluteY", "maxAbsoluteY", "topShapeMode", "maxTopHeight", "topShapeAmp", "bottomDepthMin", "bottomDepthMax", "bottomExponent", "bottomPaletteMode", "maxThickness", "wallWarpAmplitude", "carving", "carveThreshold", "localFluidHeight", "fluidBlock", "inheritDecorators", "inheritObjects", "objectShrinkFactor", "topObjectMode", "bottomObjectMode");
    public static final List<String> SCALARS = List.of("rarity", "footprintThreshold", "edgeTaperWidth", "edgeTaperExponent", "edgeTaperVariationAmplitude", "maxTopHeight", "topShapeAmp", "bottomExponent", "maxThickness", "wallWarpAmplitude", "carveThreshold", "objectShrinkFactor");
    public static final List<String> TOGGLES = List.of("inheritDecorators", "inheritObjects");
    public static final List<String> ENUMS = List.of("topShapeMode", "bottomPaletteMode", "topObjectMode", "bottomObjectMode");
    private static final List<String> HEIGHT = List.of("minHeightAboveSurface", "maxHeightAboveSurface"), DEPTH = List.of("bottomDepthMin", "bottomDepthMax"), OPTIONAL = List.of("minAbsoluteY", "maxAbsoluteY", "localFluidHeight");
    private static final Properties DATA=new Properties();
    static {
        try (var input=FloatingBiomes.class.getResourceAsStream("/floating-biome-fields.properties")) {
            if (input==null) throw new IOException("Missing pinned floating entry definitions");DATA.load(input);
            if (!"[]".equals(DATA.getProperty("list.default")) || !"absoluteWorldY".equals(DATA.getProperty("height.context")) || !"editorOrdered".equals(DATA.getProperty("pair.rule"))) throw new IOException("Unsupported floating entry policy");
            for (String value : ENUMS) if (!choices(value).contains(defaultToken(value))) throw new IOException("Invalid floating mode default");
        } catch (IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
    private static void field(String value,List<String> fields) { if (value==null || !fields.contains(value)) throw new IllegalArgumentException("Unsupported floating entry field"); }
    private static void authorize(String path) { if (!BiomeFields.biome(path)) throw new IllegalArgumentException("Floating entries are biome-only"); }
    public static String description() { return DATA.getProperty("list.description",""); }
    public static String help(String value) { field(value,EDITABLE);return DATA.getProperty(value+".description",""); }
    public static String defaultToken(String value) { field(value,EDITABLE);return DATA.getProperty(value+".default"); }
    public static String constraints(String value) { field(value,SCALARS);return (integer(value) ? "Exact integer" : "Finite number")+", minimum "+DATA.getProperty(value+".minimum")+", maximum "+DATA.getProperty(value+".maximum"); }
    public static String prefix() { return "floatingBiomes#"; }
    public static String entryPrefix(int index) { if (index<0) throw new IllegalArgumentException("Select an entry");return prefix()+index+"."; }
    static List<LosslessJson> entries(LosslessJson document) { return document.token("floatingChildBiomes")==null ? null : document.objectArray("floatingChildBiomes"); }
    static byte[] editEntry(byte[] original,int index,Function<LosslessJson,byte[]> action) { return new LosslessJson(original).objectArrayEdit("floatingChildBiomes",index,action); }
    public static byte[] enable(String path,byte[] original) { authorize(path);return new LosslessJson(original).enable("floatingChildBiomes","[]"); }
    public static byte[] change(String path,byte[] original,RiverProfiles.Action action,int index) {
        authorize(path);if (action==null) throw new IllegalArgumentException("Choose an entry action");var document=new LosslessJson(original);var entries=document.objectArray("floatingChildBiomes");
        if (action==RiverProfiles.Action.ADD) return document.objectArrayInsert("floatingChildBiomes",entries.size(),"{}".getBytes(StandardCharsets.UTF_8));
        if (index<0 || index>=entries.size()) throw new IllegalArgumentException("Select an existing entry");
        return switch(action) {
            case DUPLICATE -> document.objectArrayInsert("floatingChildBiomes",index+1,entries.get(index).sourceBytes());
            case REMOVE -> document.objectArrayRemove("floatingChildBiomes",index);
            case UP -> document.objectArrayMove("floatingChildBiomes",index,index-1);
            case DOWN -> document.objectArrayMove("floatingChildBiomes",index,index+1);
            default -> throw new IllegalArgumentException("Unsupported entry action");
        };
    }
    private static boolean integer(String value) { return List.of("int","Integer").contains(DATA.getProperty(value+".type")); }
    private static double number(String value,String input) {
        double parsed;try { parsed=integer(value) ? new BigDecimal(input).intValueExact() : Double.parseDouble(input); }
        catch (NumberFormatException | ArithmeticException | NullPointerException invalid) { throw new IllegalArgumentException("Enter "+(integer(value) ? "an exact signed 32-bit integer" : "a finite number"),invalid); }
        if (!Double.isFinite(parsed) || parsed<Double.parseDouble(DATA.getProperty(value+".minimum","-Infinity")) || parsed>Double.parseDouble(DATA.getProperty(value+".maximum","Infinity"))) throw new IllegalArgumentException("Value violates pinned floating entry bounds");return parsed;
    }
    private static byte[] writeNumber(LosslessJson entry,String value,double input) { return entry.token(value)==null ? entry.enable(value,BigDecimal.valueOf(input).stripTrailingZeros().toPlainString()) : entry.number(value,input); }
    public static byte[] enableScalar(String path,byte[] original,int index,String value) { authorize(path);field(value,SCALARS);return editEntry(original,index,e -> e.enable(value,defaultToken(value))); }
    public static byte[] setScalar(String path,byte[] original,int index,String value,String input) { authorize(path);field(value,SCALARS);double parsed=number(value,input);return editEntry(original,index,e -> e.number(value,parsed)); }
    public static byte[] setToggle(String path,byte[] original,int index,String value,boolean input) {
        authorize(path);field(value,TOGGLES);return editEntry(original,index,e -> e.token(value)==null ? e.enable(value,Boolean.toString(input)) : e.bool(value,input));
    }
    public static byte[] setPair(String path,byte[] original,int index,boolean height,String minimum,String maximum) {
        authorize(path);var fields=height ? HEIGHT : DEPTH;double min=number(fields.get(0),minimum),max=number(fields.get(1),maximum);if (min>max) throw new IllegalArgumentException("Minimum must not exceed maximum");
        return editEntry(original,index,e -> writeNumber(new LosslessJson(writeNumber(e,fields.get(0),min)),fields.get(1),max));
    }
    private static Double optional(String value,String input) { return input==null || input.isBlank() ? null : number(value,input); }
    public static byte[] setAbsoluteBounds(String path,byte[] original,int index,String minimum,String maximum) {
        authorize(path);Double min=optional("minAbsoluteY",minimum),max=optional("maxAbsoluteY",maximum);if (min!=null && max!=null && min>max) throw new IllegalArgumentException("Minimum Y must not exceed maximum Y");
        return editEntry(original,index,e -> new LosslessJson(e.nullableNumber("minAbsoluteY",min)).nullableNumber("maxAbsoluteY",max));
    }
    public static byte[] setFluidHeight(String path,byte[] original,int index,String input) { authorize(path);Double value=optional("localFluidHeight",input);return editEntry(original,index,e -> e.nullableNumber("localFluidHeight",value)); }
    public static List<String> choices(String value) { field(value,ENUMS);return List.of(DATA.getProperty(DATA.getProperty(value+".type")+".choices").split(",")); }
    public static String choiceHelp(String value,String choice) { if (!choices(value).contains(choice)) throw new IllegalArgumentException("Choose a pinned mode");return DATA.getProperty(DATA.getProperty(value+".type")+"."+choice+".description",""); }
    public static byte[] setMode(String path,byte[] original,int index,String value,String choice) {
        authorize(path);if (choice==null || !choices(value).contains(choice)) throw new IllegalArgumentException("Choose a pinned mode");return editEntry(original,index,e -> e.token(value)==null ? e.enableStringPath(value,choice) : e.stringValue(value,choice));
    }
    public static List<String> biomeChoices(PackWorkspace workspace) { return ResourceTypes.referenceKeys(workspace,"biomes"); }
    public static byte[] setBiome(String path,byte[] original,int index,String value,PackWorkspace workspace) {
        authorize(path);if (value==null || !value.isEmpty() && !biomeChoices(workspace).contains(value)) throw new IllegalArgumentException("Choose Self or an exact biome key in this pack");return editEntry(original,index,e -> e.nullableString("biome",value));
    }
    public static byte[] setLiteral(String path,byte[] original,int index,String value,String input) {
        authorize(path);field(value,List.of("carving","fluidBlock"));if (input==null || value.equals("fluidBlock") && input.isBlank()) throw new IllegalArgumentException("Enter a nonblank block name; carving may be empty");return value.equals("carving") ? editEntry(original,index,e -> e.nullableString(value,input)) : setString(original,index,value,input);
    }
    private static byte[] setString(byte[] original,int index,String value,String input) { return editEntry(original,index,e -> e.token(value)==null ? e.enableStringPath(value,input) : e.stringValue(value,input)); }
    static List<String> readOnlyFields(LosslessJson entry) { return entry.fields().stream().filter(value -> !EDITABLE.contains(value) && !FloatingNoiseStyles.EDITABLE.contains(value) && !FloatingBottomPalettes.EDITABLE.contains(value) && !FloatingObjectPlacements.LIST_FIELDS.contains(value)).toList(); }
}
