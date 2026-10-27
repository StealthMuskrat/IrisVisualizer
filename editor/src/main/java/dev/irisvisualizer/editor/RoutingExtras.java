package dev.irisvisualizer.editor;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;

/** Source-bounded regional settings and outlet choices; no basin planning or outlet generation runs. */
public final class RoutingExtras {
    public static final List<String> OBJECT_FIELDS = List.of("regional");
    public static final List<String> LIST_FIELDS = List.of("inlandOutlets");
    public static final List<String> REGIONAL_FIELDS = List.of("enabled", "sampleSpacing", "minimumLength", "maximumTrunks", "maximumCachedBasins", "maximumCachedStations", "coastalChannels", "coastalChannelChance", "maximumCoastalIncision");
    public enum Action { DUPLICATE, UP, DOWN }
    private static final Properties DEFINITIONS=new Properties();
    static {
        try (var input=RoutingExtras.class.getResourceAsStream("/routing-extras.properties")) {
            if (input==null) throw new IOException("Missing pinned routing extras");
            DEFINITIONS.load(input);
            if (!"{}".equals(DEFINITIONS.getProperty("regionalDefault")) || !"[]".equals(DEFINITIONS.getProperty("outletsDefault")) || !"positivePowerOfTwo".equals(DEFINITIONS.getProperty("sampleSpacing.rule"))) throw new IOException("Unsupported routing extras defaults/rule");
            var choices=outletChoices();
            if (choices.isEmpty() || choices.stream().anyMatch(value -> !value.matches("[A-Z][A-Z0-9_]*")) || choices.stream().distinct().count()!=choices.size()) throw new IOException("Invalid inland outlet choices");
            if (!options("sampleSpacing").contains(defaultToken("sampleSpacing"))) throw new IOException("Invalid regional spacing default");
        } catch (IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
    private static void authorize(String path) {
        if (!DimensionFields.dimension(path)) throw new IllegalArgumentException("Routing extras are dimension-only");
    }
    private static void authorize(String path, String field) {
        authorize(path); if (!REGIONAL_FIELDS.contains(field)) throw new IllegalArgumentException("Regional field is read-only");
    }
    public static String regionalDescription() { return DEFINITIONS.getProperty("regionalDescription", ""); }
    public static String outletsDescription() { return DEFINITIONS.getProperty("outletsDescription", ""); }
    public static String help(String field) { return DEFINITIONS.getProperty(field+".description", ""); }
    public static boolean booleanField(String field) { return "boolean".equals(DEFINITIONS.getProperty(field+".type")); }
    public static String defaultToken(String field) {
        if (!REGIONAL_FIELDS.contains(field)) throw new IllegalArgumentException("Regional field is read-only");
        String value=DEFINITIONS.getProperty(field+".default");
        return booleanField(field) ? value : new BigDecimal(value).stripTrailingZeros().toPlainString();
    }
    public static List<String> options(String field) {
        String value=DEFINITIONS.getProperty(field+".options"); return value==null ? List.of() : List.of(value.split(","));
    }
    static boolean optionMatches(String token, String option) {
        if (token==null) return false;
        try { return new BigDecimal(token).compareTo(new BigDecimal(option))==0; }
        catch (NumberFormatException mismatch) { return false; }
    }
    public static String constraints(String field) {
        String min=DEFINITIONS.getProperty(field+".minimum"), max=DEFINITIONS.getProperty(field+".maximum");
        return ("int".equals(DEFINITIONS.getProperty(field+".type")) ? "Signed 32-bit integer" : "Finite number")+(min==null ? "" : ", minimum "+min)+(max==null ? "" : ", maximum "+max)+(field.equals("sampleSpacing") ? "; power of two" : "");
    }
    public static String regionalPrefix() { return HydrologyFields.draftPrefix()+"regional."; }
    public static List<String> outletChoices() { return List.of(DEFINITIONS.getProperty("outletChoices", "").split(",")); }
    static LosslessJson regional(LosslessJson route) {
        String token=route.token("regional"); return token==null ? null : new LosslessJson(token.getBytes(StandardCharsets.UTF_8));
    }
    public static byte[] enableRegional(String path, byte[] original) {
        authorize(path);
        return HydrologyFields.editRouting(original,route -> {
            if (route.token("regional")!=null) throw new IllegalArgumentException("Regional settings are already present or unsupported");
            return route.enableObject("regional");
        });
    }
    public static byte[] enableScalar(String path, byte[] original, String field) {
        authorize(path,field);
        return HydrologyFields.editRouting(original,route -> route.objectEdit("regional",region -> region.enable(field,defaultToken(field)),false));
    }
    public static byte[] setBoolean(String path, byte[] original, String field, boolean value) {
        authorize(path,field);
        if (!booleanField(field)) throw new IllegalArgumentException("Numeric regional field requires a number");
        return HydrologyFields.editRouting(original,route -> route.objectEdit("regional",region -> region.token(field)==null ? region.enable(field,Boolean.toString(value)) : region.bool(field,value),false));
    }
    public static byte[] setText(String path, byte[] original, String field, String input) {
        authorize(path,field);
        if (booleanField(field) || input==null) throw new IllegalArgumentException("Enter a numeric regional value");
        double value;
        if ("int".equals(DEFINITIONS.getProperty(field+".type"))) {
            try { value=new BigDecimal(input).intValueExact(); }
            catch (NumberFormatException | ArithmeticException invalid) { throw new IllegalArgumentException("Enter an exact signed 32-bit integer",invalid); }
        } else value=Double.parseDouble(input);
        String min=DEFINITIONS.getProperty(field+".minimum"), max=DEFINITIONS.getProperty(field+".maximum");
        if (!Double.isFinite(value) || min!=null && value<Double.parseDouble(min) || max!=null && value>Double.parseDouble(max)) throw new IllegalArgumentException("Value violates pinned regional constraints");
        if (field.equals("sampleSpacing") && !options(field).contains(Integer.toString((int)value))) throw new IllegalArgumentException("Choose a source-described power-of-two spacing");
        return HydrologyFields.editRouting(original,route -> route.objectEdit("regional",region -> region.number(field,value),false));
    }
    public static byte[] enableOutlets(String path, byte[] original) {
        authorize(path); return HydrologyFields.editRouting(original,route -> route.enable("inlandOutlets","[]"));
    }
    public static byte[] editOutlet(String path, byte[] original, int index, String value) {
        authorize(path);
        if (value!=null && !outletChoices().contains(value)) throw new IllegalArgumentException("Choose a declared inland outlet kind");
        return HydrologyFields.editRouting(original,route -> route.stringArrayPath("inlandOutlets",index,value));
    }
    public static byte[] changeOutlet(String path, byte[] original, int index, Action action) {
        authorize(path);
        if (action==null) throw new IllegalArgumentException("Choose an outlet list action");
        return HydrologyFields.editRouting(original,route -> switch(action) {
            case DUPLICATE -> route.stringArrayDuplicate("inlandOutlets",index);
            case UP -> route.stringArrayMove("inlandOutlets",index,index-1);
            case DOWN -> route.stringArrayMove("inlandOutlets",index,index+1);
        });
    }
    static List<String> readOnlyPaths(LosslessJson route, String field) {
        if (route.token(field)==null) return List.of();
        try {
            if (field.equals("regional")) { var region=regional(route); return region==null ? List.of() : region.fields().stream().filter(member -> !REGIONAL_FIELDS.contains(member)).map(member -> field+"."+member).toList(); }
            if (field.equals("inlandOutlets")) { route.stringArrayPath(field); return List.of(); }
            throw new IllegalArgumentException("Unsupported routing extra");
        } catch (IllegalArgumentException mismatch) { return List.of(field); }
    }
}
