package dev.irisvisualizer.editor;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.function.Function;

/** Explicit density and scale settings for root biome and floating-entry placements. */
public final class FloatingPlacementSettings {
    public static final List<String> OBJECT_FIELDS = List.of("densityStyle", "scale");
    public static final List<String> SCALE_FIELDS = List.of("size", "variations", "minimumScale", "maximumScale", "interpolation");
    public static final List<String> RANGE_FIELDS = List.of("min", "max", "style");
    private static final Properties DATA=new Properties();
    static {
        try (var input=FloatingPlacementSettings.class.getResourceAsStream("/floating-placement-settings.properties")) {
            if (input==null) throw new IOException("Missing pinned placement settings");DATA.load(input);
            if (!"dimensionFallback".equals(DATA.getProperty("scale.nullPolicy")) || !"fixedDensityFallback".equals(DATA.getProperty("densityStyle.nullPolicy"))) throw new IOException("Unsupported placement fallback policy");
        } catch (IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
    public record Context(int entry,String list,int placement) {
        public Context { if (!FloatingObjectPlacements.validContext(entry,list,placement)) throw new IllegalArgumentException("Select a biome object placement"); }
        public NoiseStyleFields.Target styleTarget() { return NoiseStyleFields.Target.placementDensity(entry,list,placement); }
        public String prefix(String field) { field(field);return FloatingObjectPlacements.placementPrefix(entry,list,placement)+field+"."; }
    }
    static void field(String value) { if (value==null || !OBJECT_FIELDS.contains(value)) throw new IllegalArgumentException("Unsupported placement setting"); }
    private static void authorize(String path) { if (!BiomeFields.biome(path)) throw new IllegalArgumentException("Floating settings are biome-only"); }
    public static String help(String object) { field(object);return DATA.getProperty(object+".description",""); }
    public static String scaleHelp(String member) { scaleField(member);return DATA.getProperty("scale."+member+".description",""); }
    public static String scaleDefault(String member) { scaleField(member);return DATA.getProperty("scale."+member+".default"); }
    public static String rangeDefault(String member) { if (!List.of("min","max").contains(member)) throw new IllegalArgumentException("Unsupported density bound");return DATA.getProperty("densityStyle."+member+".default"); }
    public static String styleDefault() { return DATA.getProperty("densityStyle.style.default"); }
    public static List<String> interpolations() { return List.of(DATA.getProperty("scale.interpolation.choices").split(",")); }
    public static String interpolationHelp(String choice) { if (!interpolations().contains(choice)) throw new IllegalArgumentException("Unknown interpolation");return DATA.getProperty("scale.interpolation."+choice+".description",""); }
    private static void scaleField(String value) { if (value==null || !SCALE_FIELDS.contains(value)) throw new IllegalArgumentException("Unsupported scale field"); }
    public static String scaleConstraints(String member) { scaleField(member);return (member.equals("variations") ? "Exact integer" : "Finite number")+", minimum "+DATA.getProperty("scale."+member+".minimum")+", maximum "+DATA.getProperty("scale."+member+".maximum"); }
    static LosslessJson object(LosslessJson placement,String field) { field(field);String token=placement.token(field);return token==null || token.equals("null") ? null : new LosslessJson(token.getBytes(StandardCharsets.UTF_8)); }
    static byte[] editPlacement(byte[] original,Context context,Function<LosslessJson,byte[]> action) { return FloatingObjectPlacements.editPlacement(original,context.entry(),context.list(),context.placement(),action); }
    static byte[] editObject(byte[] original,Context context,String field,Function<LosslessJson,byte[]> action) { field(field);return editPlacement(original,context,p -> p.objectEdit(field,action,false)); }
    public static byte[] enable(String path,byte[] original,Context context,String field) {
        authorize(path);field(field);return editPlacement(original,context,p -> { String token=p.token(field);if (token!=null && !token.equals("null")) throw new IllegalArgumentException("Setting already exists or has an unsupported type");return p.enableObject(field); });
    }
    public static byte[] disable(String path,byte[] original,Context context,String field) { authorize(path);field(field);return editPlacement(original,context,p -> p.nullObject(field)); }
    private static byte[] number(LosslessJson object,String member,double value) { return object.token(member)==null ? object.enable(member,BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()) : object.number(member,value); }
    private static double finite(String input) { try { double value=Double.parseDouble(input);if (!Double.isFinite(value)) throw new IllegalArgumentException("Enter finite numbers");return value; } catch (NumberFormatException | NullPointerException invalid) { throw new IllegalArgumentException("Enter finite numbers",invalid); } }
    private static double scaleNumber(String member,String input) {
        scaleField(member);double value;
        try { value=member.equals("variations") ? new BigDecimal(input).intValueExact() : finite(input); }
        catch (NumberFormatException | ArithmeticException | NullPointerException invalid) { throw new IllegalArgumentException("Enter an exact scale variation integer",invalid); }
        if (!Double.isFinite(value) || value<Double.parseDouble(DATA.getProperty("scale."+member+".minimum")) || value>Double.parseDouble(DATA.getProperty("scale."+member+".maximum"))) throw new IllegalArgumentException("Scale violates pinned bounds");return value;
    }
    public static byte[] setDensityBounds(String path,byte[] original,Context context,String minimum,String maximum) {
        authorize(path);double min=finite(minimum),max=finite(maximum);
        // A deliberately bounded editor subset: native styled ranges themselves have no annotations.
        if (min<0 || max>Integer.MAX_VALUE || min>max) throw new IllegalArgumentException("Use ordered density bounds from 0 to 2147483647; Iris rounds the sampled value");
        return editObject(original,context,"densityStyle",range -> number(new LosslessJson(number(range,"min",min)),"max",max));
    }
    public static byte[] setScaleBounds(String path,byte[] original,Context context,String minimum,String maximum) {
        authorize(path);double min=scaleNumber("minimumScale",minimum),max=scaleNumber("maximumScale",maximum);
        if (min>max) throw new IllegalArgumentException("Minimum scale must be at most maximum scale");
        return editObject(original,context,"scale",scale -> number(new LosslessJson(number(scale,"minimumScale",min)),"maximumScale",max));
    }
    public static byte[] enableScaleScalar(String path,byte[] original,Context context,String member) {
        authorize(path);if (!List.of("size","variations").contains(member)) throw new IllegalArgumentException("Use paired scale bounds or the interpolation picker");
        return editObject(original,context,"scale",scale -> scale.enable(member,scaleDefault(member)));
    }
    public static byte[] setScaleScalar(String path,byte[] original,Context context,String member,String input) {
        authorize(path);if (!List.of("size","variations").contains(member)) throw new IllegalArgumentException("Use paired scale bounds or the interpolation picker");double value=scaleNumber(member,input);
        return editObject(original,context,"scale",scale -> scale.number(member,value));
    }
    public static byte[] setInterpolation(String path,byte[] original,Context context,String value) {
        authorize(path);if (value==null || !interpolations().contains(value)) throw new IllegalArgumentException("Choose a pinned scale interpolation");
        return editObject(original,context,"scale",scale -> scale.token("interpolation")==null ? scale.enableStringPath("interpolation",value) : scale.stringValue("interpolation",value));
    }
    static Context styleContext(String path) {
        var parsed=FloatingObjectPlacements.parsePath(path);
        return parsed!=null && parsed.suffix().equals("densityStyle.style") ? new Context(parsed.entry(),parsed.list(),parsed.placement()) : null;
    }
    static List<String> readOnlyPaths(LosslessJson placement) {
        var paths=new ArrayList<String>();
        for (String field : OBJECT_FIELDS) try {
            var object=object(placement,field);if (object==null) continue;
            for (String member : object.fields()) if (!(field.equals("scale") ? SCALE_FIELDS : RANGE_FIELDS).contains(member)) paths.add(field+"."+member);
            if (field.equals("densityStyle")) {
                try { var style=NoiseStyleFields.object(object);if (style!=null) for (String member : style.fields()) if (!NoiseStyleFields.EDITABLE.contains(member)) paths.add("densityStyle.style."+member); }
                catch (IllegalArgumentException unsupported) { paths.add("densityStyle.style"); }
            }
        } catch (IllegalArgumentException unsupported) { paths.add(field); }
        return List.copyOf(paths);
    }
}
