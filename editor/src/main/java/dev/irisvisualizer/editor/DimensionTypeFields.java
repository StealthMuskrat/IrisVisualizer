package dev.irisvisualizer.editor;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/** Authoring of generated dimension types; registry and live-world compatibility are not resolved. */
public final class DimensionTypeFields {
    public static final List<String> OBJECT_FIELDS = List.of("dimensionOptions");
    public static final List<String> ROOT_FIELDS = List.of("environment", "fullbright");
    public static final List<String> EDITABLE = List.of("coordinateScale", "ambientLight", "fixedTime", "cloudHeight", "monsterSpawnBlockLightLimit", "ultrawarm", "natural", "piglinSafe", "respawnAnchorWorks", "bedWorks", "raids", "skylight", "ceiling");
    public static final List<String> NUMBERS = List.of("coordinateScale", "ambientLight", "fixedTime", "cloudHeight", "monsterSpawnBlockLightLimit");
    public static final List<String> STATES = List.of("ultrawarm", "natural", "piglinSafe", "respawnAnchorWorks", "bedWorks", "raids", "skylight", "ceiling");
    private static final Properties DATA=new Properties();
    static {
        try (var input=DimensionTypeFields.class.getResourceAsStream("/dimension-type-fields.properties")) {
            if (input==null) throw new IOException("Missing pinned dimension-type definitions");DATA.load(input);
            if (!"DEFAULT,TRUE,FALSE".equals(DATA.getProperty("states")) || !"-1".equals(DATA.getProperty("inherit.number")) || !"timeCycle".equals(DATA.getProperty("fixedTime.null")) || !"cloudsDisabled".equals(DATA.getProperty("cloudHeight.null"))) throw new IOException("Unsupported dimension-type policy");
        } catch (IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
    private static void authorize(String path) { if (!DimensionFields.dimension(path)) throw new IllegalArgumentException("Dimension-type settings are dimension-only"); }
    private static void number(String field) { if (field==null || !NUMBERS.contains(field)) throw new IllegalArgumentException("Unsupported dimension-type number"); }
    public static boolean nullable(String field) { number(field);return field.equals("fixedTime") || field.equals("cloudHeight"); }
    public static String prefix() { return "dimensionOptions#"; }
    public static String rootPrefix() { return "dimensionTypeRoot#"; }
    public static String help(String field) { return DATA.getProperty(field+".description",""); }
    public static List<String> stateChoices() { return List.of(DATA.getProperty("states").split(",")); }
    public static List<String> environments() { return List.of(DATA.getProperty("environments").split(",")); }
    public static String defaultToken(String field) { if (field==null || !EDITABLE.contains(field) && !ROOT_FIELDS.contains(field)) throw new IllegalArgumentException("Unsupported dimension-type field");return DATA.getProperty(field+".default"); }
    public static String constraints(String field) {
        number(field);return "Use -1 to inherit the base dimension; otherwise "+(field.equals("fixedTime") || field.equals("cloudHeight") || field.equals("monsterSpawnBlockLightLimit") ? "exact integers" : "finite numbers")+" from "+DATA.getProperty(field+".minimum")+" to "+DATA.getProperty(field+".maximum")+".";
    }
    static LosslessJson object(LosslessJson document) { String token=document.token("dimensionOptions");return token==null ? null : new LosslessJson(token.getBytes(StandardCharsets.UTF_8)); }
    static boolean supported(LosslessJson options,String field) {
        if (!EDITABLE.contains(field)) return false;String token=options.token(field);if (token==null) return true;
        if (STATES.contains(field)) { try { options.stringToken(field);return true; } catch (IllegalArgumentException invalid) { return false; } }
        if (token.equals("null")) return nullable(field);
        if (!LosslessJson.numericToken(token)) return false;
        try { if (field.equals("fixedTime")) new BigDecimal(token).longValueExact();else if (field.equals("cloudHeight") || field.equals("monsterSpawnBlockLightLimit")) new BigDecimal(token).intValueExact(); }
        catch (NumberFormatException | ArithmeticException invalid) { return false; }
        return true;
    }
    private static BigDecimal parse(String field,String input) {
        number(field);
        try {
            var value=new BigDecimal(input);
            if (field.equals("fixedTime")) value.longValueExact();else if (field.equals("cloudHeight") || field.equals("monsterSpawnBlockLightLimit")) value.intValueExact();
            if (value.compareTo(BigDecimal.valueOf(-1))!=0 && (value.compareTo(new BigDecimal(DATA.getProperty(field+".minimum")))<0 || value.compareTo(new BigDecimal(DATA.getProperty(field+".maximum")))>0)) throw new IllegalArgumentException("Dimension-type value violates native bounds");
            if (!Double.isFinite(value.doubleValue())) throw new IllegalArgumentException("Finite dimension-type number required");
            return value;
        } catch (NumberFormatException | ArithmeticException | NullPointerException invalid) { throw new IllegalArgumentException("Enter a dimension-type value within its exact native bounds",invalid); }
    }
    public static byte[] enable(String path,byte[] original) {
        authorize(path);var document=new LosslessJson(original);
        if (document.token("dimensionOptions")!=null) throw new IllegalArgumentException("Dimension options already exist or have an unsupported type");
        return document.enableObject("dimensionOptions");
    }
    public static byte[] enableField(String path,byte[] original,String field) {
        authorize(path);if (field==null || !EDITABLE.contains(field)) throw new IllegalArgumentException("Unsupported dimension option");
        return new LosslessJson(original).objectEdit("dimensionOptions",o -> STATES.contains(field) ? o.enableStringPath(field,defaultToken(field)) : o.enable(field,defaultToken(field)),false);
    }
    public static byte[] setState(String path,byte[] original,String field,String state) {
        authorize(path);if (field==null || state==null || !STATES.contains(field) || !stateChoices().contains(state)) throw new IllegalArgumentException("Select a pinned dimension-type state");
        return new LosslessJson(original).objectEdit("dimensionOptions",o -> o.stringValue(field,state),false);
    }
    public static byte[] setNumber(String path,byte[] original,String field,String input) {
        authorize(path);var value=parse(field,input);
        return new LosslessJson(original).objectEdit("dimensionOptions",o -> {
            if (o.token(field)==null || !supported(o,field)) throw new IllegalArgumentException("Enable this dimension option first; unsupported values remain read-only");
            if (nullable(field)) return o.nullableInteger(field,value.longValueExact());
            return field.equals("monsterSpawnBlockLightLimit") ? o.integer(field,value.longValueExact()) : o.number(field,value.doubleValue());
        },false);
    }
    public static byte[] setNull(String path,byte[] original,String field) {
        authorize(path);if (!nullable(field)) throw new IllegalArgumentException("Only time and cloud height can be null");
        return new LosslessJson(original).objectEdit("dimensionOptions",o -> {
            if (o.token(field)==null || !supported(o,field)) throw new IllegalArgumentException("Enable a supported nullable option first");return o.nullableInteger(field,null);
        },false);
    }
    public static byte[] enableRoot(String path,byte[] original,String field) {
        authorize(path);var document=new LosslessJson(original);
        if (field==null || !ROOT_FIELDS.contains(field)) throw new IllegalArgumentException("Unsupported dimension-type root field");
        return field.equals("environment") ? document.enableStringPath(field,defaultToken(field)) : document.enable(field,defaultToken(field));
    }
    public static byte[] setEnvironment(String path,byte[] original,String environment) {
        authorize(path);if (environment==null || !environments().contains(environment)) throw new IllegalArgumentException("Select a pinned environment");return new LosslessJson(original).stringValue("environment",environment);
    }
    public static byte[] setFullbright(String path,byte[] original,boolean value) { authorize(path);return new LosslessJson(original).bool("fullbright",value); }
    static List<String> readOnlyPaths(LosslessJson document) {
        var paths=new ArrayList<String>();
        for (String field : ROOT_FIELDS) {
            String token=document.token(field);if (token==null) continue;
            if (field.equals("fullbright")) { if (!token.equals("true") && !token.equals("false")) paths.add(field); }
            else { try { document.stringToken(field); } catch (IllegalArgumentException invalid) { paths.add(field); } }
        }
        try { var options=object(document);if (options!=null) for (String field : options.fields()) if (!supported(options,field)) paths.add("dimensionOptions."+field); }
        catch (IllegalArgumentException invalid) { paths.add("dimensionOptions"); }
        return List.copyOf(paths);
    }
}
