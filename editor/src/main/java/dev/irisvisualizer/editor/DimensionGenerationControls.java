package dev.irisvisualizer.editor;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/** Authored generation and server Studio settings; never creates a generator or preview. */
public final class DimensionGenerationControls {
    public static final List<String> ROOT_FIELDS = List.of("mode", "studioMode", "useMantle", "preventLeafDecay", "hideOresForHiddenOre", "debugChunkCrossSections", "explodeBiomePalettes", "debugSmartBore", "explodeBiomePaletteSize", "debugCrossSectionsMod");
    public static final List<String> EDITABLE = List.of("type");
    public static final List<String> BOOLEANS = List.of("useMantle", "preventLeafDecay", "hideOresForHiddenOre", "debugChunkCrossSections", "explodeBiomePalettes", "debugSmartBore");
    public static final List<String> NUMBERS = List.of("explodeBiomePaletteSize", "debugCrossSectionsMod");
    private static final Properties DATA=new Properties();
    static {
        try (var input=DimensionGenerationControls.class.getResourceAsStream("/dimension-generation-fields.properties")) {
            if (input==null) throw new IOException("Missing pinned generation controls");DATA.load(input);
            if (!"inlineOnly".equals(DATA.getProperty("mode.policy")) || !"serverOnly".equals(DATA.getProperty("studio.policy"))) throw new IOException("Unsupported generation control policy");
        } catch (IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
    private static void authorize(String path) { if (!DimensionFields.dimension(path)) throw new IllegalArgumentException("Generation controls are dimension-only"); }
    private static void field(String field) { if (field==null || !ROOT_FIELDS.contains(field) && !EDITABLE.contains(field)) throw new IllegalArgumentException("Unsupported generation control"); }
    public static String prefix() { return "dimensionGeneration#"; }
    public static String help(String field) { field(field);return DATA.getProperty(field+".description"); }
    public static String defaultToken(String field) { field(field);return DATA.getProperty(field+".default"); }
    public static List<String> modes() { return List.of(DATA.getProperty("modes").split(",")); }
    public static List<String> studioModes() { return List.of(DATA.getProperty("studioModes").split(",")); }
    public static String choiceHelp(boolean studio,String choice) { return DATA.getProperty((studio ? "studio." : "mode.")+choice+".description",""); }
    public static String constraints(String field) { if (!NUMBERS.contains(field)) throw new IllegalArgumentException("Unsupported generation integer");return "Exact integer "+DATA.getProperty(field+".minimum")+".."+DATA.getProperty(field+".maximum")+"."; }
    static LosslessJson mode(LosslessJson root) { String token=root.token("mode");return token==null ? null : new LosslessJson(token.getBytes(StandardCharsets.UTF_8)); }
    public static String modeType(LosslessJson root) { var object=mode(root);String value=object==null ? null : object.stringToken("type");return value==null ? defaultToken("type") : value; }
    public static String studioMode(LosslessJson root) { String value=root.stringToken("studioMode");return value==null ? defaultToken("studioMode") : value; }
    public static boolean booleanValue(LosslessJson root,String field) {
        if (!BOOLEANS.contains(field)) throw new IllegalArgumentException("Unsupported generation toggle");String token=root.token(field);
        if (token==null) token=defaultToken(field);if (!token.equals("true") && !token.equals("false")) throw new IllegalArgumentException("Stored generation toggle is not boolean");return token.equals("true");
    }
    public static String numberValue(LosslessJson root,String field) {
        if (!NUMBERS.contains(field)) throw new IllegalArgumentException("Unsupported generation integer");String token=root.token(field);if (token==null) return defaultToken(field);
        try { if (!LosslessJson.numericToken(token)) throw new NumberFormatException();new BigDecimal(token).intValueExact();return token; }
        catch (NumberFormatException | ArithmeticException invalid) { throw new IllegalArgumentException("Stored generation number must be an exact signed 32-bit integer",invalid); }
    }
    public static byte[] enableMode(String path,byte[] original) {
        authorize(path);var root=new LosslessJson(original);if (root.token("mode")!=null) throw new IllegalArgumentException("Only an absent dimension mode can be enabled; nulls/snippets remain read-only");return root.enableObject("mode");
    }
    public static byte[] setMode(String path,byte[] original,String value) {
        authorize(path);if (value==null || !modes().contains(value)) throw new IllegalArgumentException("Choose a pinned generation mode");var root=new LosslessJson(original);modeType(root);if (mode(root)==null) throw new IllegalArgumentException("Enable the inline mode first");
        return root.objectEdit("mode",o -> putString(o,"type",value),false);
    }
    public static byte[] resetModeType(String path,byte[] original) {
        authorize(path);var root=new LosslessJson(original);modeType(root);if (mode(root)==null) return original;return root.objectEdit("mode",o -> o.token("type")==null ? o.sourceBytes() : o.removeField("type"),false);
    }
    private static byte[] putString(LosslessJson object,String field,String value) { String old=object.stringToken(field);return value.equals(old) ? object.sourceBytes() : old==null ? object.enableStringPath(field,value) : object.stringValue(field,value); }
    public static byte[] setStudioMode(String path,byte[] original,String value) {
        authorize(path);if (value==null || !studioModes().contains(value)) throw new IllegalArgumentException("Choose a pinned server Studio mode");var root=new LosslessJson(original);studioMode(root);return putString(root,"studioMode",value);
    }
    public static byte[] setBoolean(String path,byte[] original,String field,boolean value) {
        authorize(path);var root=new LosslessJson(original);booleanValue(root,field);return root.token(field)==null ? root.enable(field,Boolean.toString(value)) : root.bool(field,value);
    }
    public static byte[] enableNumber(String path,byte[] original,String field) {
        authorize(path);if (!NUMBERS.contains(field)) throw new IllegalArgumentException("Unsupported generation integer");var root=new LosslessJson(original);if (root.token(field)!=null) throw new IllegalArgumentException("Generation number is already present");return root.enable(field,defaultToken(field));
    }
    public static byte[] setNumber(String path,byte[] original,String field,String input) {
        authorize(path);if (!NUMBERS.contains(field)) throw new IllegalArgumentException("Unsupported generation integer");int value;
        try { value=new BigDecimal(input).intValueExact(); }
        catch (NumberFormatException | ArithmeticException | NullPointerException invalid) { throw new IllegalArgumentException("Enter an exact signed 32-bit generation integer",invalid); }
        if (value<Integer.parseInt(DATA.getProperty(field+".minimum")) || value>Integer.parseInt(DATA.getProperty(field+".maximum"))) throw new IllegalArgumentException(constraints(field));
        var root=new LosslessJson(original);numberValue(root,field);if (root.token(field)==null) throw new IllegalArgumentException("Enable the generation number first");return new BigDecimal(root.token(field)).intValueExact()==value ? original : root.integer(field,value);
    }
    public static byte[] resetRoot(String path,byte[] original,String field) {
        authorize(path);var root=new LosslessJson(original);
        if (BOOLEANS.contains(field)) booleanValue(root,field);else if (NUMBERS.contains(field)) numberValue(root,field);else if ("studioMode".equals(field)) studioMode(root);else throw new IllegalArgumentException("Only supported leaf controls can be reset");
        return root.token(field)==null ? original : root.removeField(field);
    }
    static List<String> readOnlyPaths(LosslessJson root) {
        var paths=new ArrayList<String>();
        for (String field : ROOT_FIELDS) {
            if (root.token(field)==null) continue;
            try {
                if (field.equals("mode")) { var mode=mode(root);for (String child : mode.fields()) if (!EDITABLE.contains(child)) paths.add("mode."+child);modeType(root); }
                else if (field.equals("studioMode")) studioMode(root);else if (BOOLEANS.contains(field)) booleanValue(root,field);else numberValue(root,field);
            } catch (IllegalArgumentException invalid) { paths.add(field.equals("mode") && root.token(field).stripLeading().startsWith("{") ? "mode.type" : field); }
        }
        return List.copyOf(paths);
    }
}
