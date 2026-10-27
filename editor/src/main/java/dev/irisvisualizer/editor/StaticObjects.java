package dev.irisvisualizer.editor;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;
import java.util.function.Function;

/** Fixed dimension placements. This editor does not load or transform the referenced objects. */
public final class StaticObjects {
    public static final List<String> LIST_FIELDS = List.of("staticObjects");
    public static final List<String> ROOT_FIELDS = List.of("allObjectScaleFactor");
    public static final List<String> EDITABLE = List.of("object", "position", "rotation", "scale", "scaleInterpolation", "bore", "smartBore", "seed");
    public static final List<String> AXES = List.of("x", "y", "z");
    private static final Properties DATA=new Properties();
    static {
        try (var input=StaticObjects.class.getResourceAsStream("/static-object-fields.properties")) {
            if (input==null) throw new IOException("Missing pinned static object definitions");DATA.load(input);
            if (!"absoluteWorldYExclusiveMaximum".equals(DATA.getProperty("position.policy")) || !"laterWins".equals(DATA.getProperty("order.policy")) || !"dimensionFallback".equals(DATA.getProperty("scale.policy"))) throw new IOException("Unsupported static object policy");
        } catch (IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
    public record Context(int index) {
        public Context { if (index<0) throw new IllegalArgumentException("Select a static object"); }
        public String prefix() { return listPrefix()+index+"."; }
    }
    public static String listPrefix() { return "staticObjects#."; }
    public static String scalePrefix() { return "dimensionObjectScale#."; }
    private static void authorize(String path) { if (!DimensionFields.dimension(path)) throw new IllegalArgumentException("Static objects belong to dimensions"); }
    public static String help(String field) { if (!EDITABLE.contains(field) && !ROOT_FIELDS.contains(field)) throw new IllegalArgumentException("Unsupported object field");return DATA.getProperty(field+".description",""); }
    public static List<String> interpolations() { return List.of(DATA.getProperty("interpolation.choices").split(",")); }
    static List<LosslessJson> entries(LosslessJson root) { return root.token("staticObjects")==null ? null : root.objectArray("staticObjects"); }
    static LosslessJson object(LosslessJson entry,String field) {
        if (!List.of("position","rotation").contains(field)) throw new IllegalArgumentException("Select a fixed transform");
        String token=entry.token(field);return token==null ? null : new LosslessJson(token.getBytes(StandardCharsets.UTF_8));
    }
    private static byte[] edit(byte[] original,Context context,Function<LosslessJson,byte[]> action) { return new LosslessJson(original).objectArrayEdit("staticObjects",context.index(),action); }
    private static void reference(String key,PackWorkspace workspace) { if (key==null || !FloatingObjectPlacements.objectChoices(workspace).contains(key)) throw new IllegalArgumentException("Choose an exact .iob key present in this pack; content and registry compatibility are unchecked"); }
    private static int exact(String input) {
        try { return new BigDecimal(input).intValueExact(); } catch (NumberFormatException | ArithmeticException | NullPointerException invalid) { throw new IllegalArgumentException("Enter exact signed 32-bit position integers",invalid); }
    }
    private static double finite(String input) {
        try { double value=Double.parseDouble(input);if (!Double.isFinite(value)) throw new IllegalArgumentException("Enter finite numbers");return value; } catch (NumberFormatException | NullPointerException invalid) { throw new IllegalArgumentException("Enter finite numbers",invalid); }
    }
    private static double scale(String input) {
        double value=finite(input);if (value<Double.parseDouble(DATA.getProperty("scale.minimum")) || value>Double.parseDouble(DATA.getProperty("scale.maximum"))) throw new IllegalArgumentException("Scale must be from 0.01 to 50");return value;
    }
    private static int height(LosslessJson root,String field) {
        var range=DimensionHeights.object(root);String token=range==null ? null : range.token(field);
        return exact(token==null ? DimensionHeights.defaultBound(field,range==null) : token);
    }
    public static String heightLabel(LosslessJson root) { try { return height(root,"min")+" <= world Y < "+height(root,"max"); } catch (IllegalArgumentException unsupported) { return "unsupported dimension height; position edits are unavailable"; } }
    private static int[] position(LosslessJson root,String x,String y,String z) {
        int[] values={exact(x),exact(y),exact(z)};int horizontal=Integer.parseInt(DATA.getProperty("position.horizontalMaximum"));
        if (values[0]<-horizontal || values[0]>horizontal || values[2]<-horizontal || values[2]>horizontal) throw new IllegalArgumentException("World X/Z must be from -29999984 to 29999984");
        int min=height(root,"min"),max=height(root,"max");if (min>=max || values[1]<min || values[1]>=max) throw new IllegalArgumentException("Position world Y must fit the dimension's inclusive minimum and exclusive maximum");return values;
    }
    private static byte[] writeNumber(LosslessJson json,String field,double value) { return json.token(field)==null ? json.enable(field,BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()) : json.number(field,value); }
    private static byte[] triple(LosslessJson entry,String field,double[] values) {
        // Only an absent parent may be created. Stored nulls/snippets/wrong types remain read-only.
        return entry.objectEdit(field,json -> {
            byte[] next=json.sourceBytes();for (int index=0;index<AXES.size();index++) next=writeNumber(new LosslessJson(next),AXES.get(index),values[index]);return next;
        },entry.token(field)==null);
    }
    public static byte[] enable(String path,byte[] original) { authorize(path);return new LosslessJson(original).enable("staticObjects","[]"); }
    public static byte[] change(String path,byte[] original,RiverProfiles.Action action,int index,String key,String x,String y,String z,PackWorkspace workspace) {
        authorize(path);if (action==null) throw new IllegalArgumentException("Choose a static object action");var root=new LosslessJson(original);
        if (action==RiverProfiles.Action.ADD) {
            reference(key,workspace);int[] p=position(root,x,y,z);byte[] entry=new LosslessJson("{}".getBytes(StandardCharsets.UTF_8)).enableStringPath("object",key);
            entry=triple(new LosslessJson(entry),"position",new double[]{p[0],p[1],p[2]});return root.objectArrayInsert("staticObjects",root.objectArray("staticObjects").size(),entry);
        }
        return BiomeLayers.changeLayers(root,"staticObjects",action,index);
    }
    public static byte[] setReference(String path,byte[] original,Context context,String key,PackWorkspace workspace) {
        authorize(path);reference(key,workspace);return edit(original,context,e -> e.token("object")==null ? e.enableStringPath("object",key) : e.stringValue("object",key));
    }
    public static byte[] setPosition(String path,byte[] original,Context context,String x,String y,String z) {
        authorize(path);int[] p=position(new LosslessJson(original),x,y,z);return edit(original,context,e -> triple(e,"position",new double[]{p[0],p[1],p[2]}));
    }
    public static byte[] setRotation(String path,byte[] original,Context context,String x,String y,String z) {
        authorize(path);double[] values={finite(x),finite(y),finite(z)};
        for (double value : values) if (value<Double.parseDouble(DATA.getProperty("rotation.minimum")) || value>Double.parseDouble(DATA.getProperty("rotation.maximum"))) throw new IllegalArgumentException("Fixed rotations must be from -360 to 360 degrees");
        return edit(original,context,e -> triple(e,"rotation",values));
    }
    public static byte[] setScale(String path,byte[] original,Context context,String input) { authorize(path);Double value=input==null ? null : scale(input);return edit(original,context,e -> e.nullableNumber("scale",value)); }
    public static byte[] setDimensionScale(String path,byte[] original,String input) { authorize(path);return writeNumber(new LosslessJson(original),"allObjectScaleFactor",scale(input)); }
    public static byte[] setInterpolation(String path,byte[] original,Context context,String value) {
        authorize(path);if (value==null || !interpolations().contains(value)) throw new IllegalArgumentException("Choose a pinned interpolation mode");
        return edit(original,context,e -> e.token("scaleInterpolation")==null ? e.enableStringPath("scaleInterpolation",value) : e.stringValue("scaleInterpolation",value));
    }
    public static byte[] setBoolean(String path,byte[] original,Context context,String field,boolean value) {
        authorize(path);if (!List.of("bore","smartBore").contains(field)) throw new IllegalArgumentException("Select a bore flag");
        return edit(original,context,e -> e.token(field)==null ? e.enable(field,Boolean.toString(value)) : e.bool(field,value));
    }
    public static byte[] setSeed(String path,byte[] original,Context context,String input) {
        authorize(path);long value;try { value=new BigDecimal(input).longValueExact(); } catch (NumberFormatException | ArithmeticException | NullPointerException invalid) { throw new IllegalArgumentException("Enter an exact signed 64-bit seed",invalid); }
        return edit(original,context,e -> e.token("seed")==null ? e.enable("seed",Long.toString(value)) : e.integer("seed",value));
    }
}
