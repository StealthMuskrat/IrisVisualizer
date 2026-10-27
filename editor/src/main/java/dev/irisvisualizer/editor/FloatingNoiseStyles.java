package dev.irisvisualizer.editor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/** Floating-entry noise contexts; only nullable wall warp/carving styles can be disabled. */
public final class FloatingNoiseStyles {
    public static final List<String> EDITABLE = List.of("footprintStyle", "edgeTaperVariationStyle", "pickerStyle", "altitudeStyle", "topShapeStyle", "bottomStyle", "wallWarpStyle", "carveStyle");
    public static final List<String> OPTIONAL = List.of("wallWarpStyle", "carveStyle");
    record Context(int index,String field) {}
    private static final Properties DATA=new Properties();
    static {
        try (var input=FloatingNoiseStyles.class.getResourceAsStream("/floating-noise-fields.properties")) {
            if (input==null) throw new IOException("Missing pinned floating noise definitions");DATA.load(input);
            if (!String.join(",",EDITABLE).equals(DATA.getProperty("fields")) || !String.join(",",OPTIONAL).equals(DATA.getProperty("optional")) || !"null".equals(DATA.getProperty("optional.disable")) || !"existingInlineOnly".equals(DATA.getProperty("entry.policy"))) throw new IOException("Unsupported floating noise policy");
        } catch (IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
    static void field(String value) { if (value==null || !EDITABLE.contains(value)) throw new IllegalArgumentException("Unsupported floating style"); }
    public static boolean optional(String value) { field(value);return OPTIONAL.contains(value); }
    public static String classStyle() { return DATA.getProperty("classStyle"); }
    public static String creationStyle(String value) { field(value);return DATA.getProperty(value+".enableStyle"); }
    public static String zoomOverride(String value) { field(value);return DATA.getProperty(value+".zoomOverride"); }
    public static String description(String value) { field(value);return DATA.getProperty(value+".description",""); }
    public static String prefix(int index,String value) { field(value);return FloatingBiomes.entryPrefix(index)+"noise#"+value+"."; }
    static Context context(String path) {
        if (path==null) return null;var match=java.util.regex.Pattern.compile("floatingChildBiomes\\.(0|[1-9][0-9]*)\\.([A-Za-z]+)").matcher(path);
        if (!match.matches() || !EDITABLE.contains(match.group(2))) return null;
        try { return new Context(Integer.parseInt(match.group(1)),match.group(2)); } catch (NumberFormatException unsupported) { return null; }
    }
    public static byte[] disable(String path,byte[] original,int index,String value) {
        field(value);if (!BiomeFields.biome(path) || !optional(value)) throw new IllegalArgumentException("Only biome wall-warp/carving styles can be disabled");
        return FloatingBiomes.editEntry(original,index,e -> e.nullObject(value));
    }
    static List<String> readOnlyPaths(LosslessJson entry,int index) {
        var paths=new ArrayList<String>();
        for (String value : EDITABLE) {
            String token=entry.token(value);if (token==null || token.equals("null") && optional(value)) continue;
            String prefix="floatingChildBiomes["+index+"]."+value;
            try { var style=new LosslessJson(token.getBytes(StandardCharsets.UTF_8));for (String field : style.fields()) if (NoiseStyleFields.readOnly(style,field)) paths.add(prefix+"."+field); }
            catch (IllegalArgumentException unsupported) { paths.add(prefix); }
        }
        return List.copyOf(paths);
    }
}
