package dev.irisvisualizer.editor;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.function.Function;

/** Primitive surface-river authoring; inherited policies and physical generation are not evaluated. */
public final class SurfaceRiverFields {
    public static final List<String> OBJECT_FIELDS = List.of("surface");
    public static final List<String> ROOT_FIELDS = List.of("enabled", "sources", "channel", "banks", "flow", "mouths");
    public static final List<String> SECTIONS = List.of("sources", "channel", "banks", "flow", "mouths");
    public static final List<String> ROOT_SCALARS = List.of("enabled");
    public static final List<String> SOURCE_FIELDS = List.of("density", "minimumElevation", "minimumPerTile", "minimumSpacing");
    public static final List<String> CHANNEL_FIELDS = List.of("sink", "maximumIncision", "roughness", "roughnessWavelength", "springWidthRatio", "springLength", "smoothingRadius", "outlineMinimumRatio", "outlineMaximumRatio", "springExtraDepth");
    public static final List<String> BANK_FIELDS = List.of("shoreWidth", "blendSlope", "minimumBlendWidth", "maximumBlendWidth", "exposeCutStrata", "shoreRise", "blendBaseWidth");
    public static final List<String> FLOW_FIELDS = List.of("cascadeRun", "waterfallMinimumDrop", "waterfallThalwegFraction", "plungeBasinMinimumDrop", "plungeBasinLengthRatio", "plungeBasinDepth");
    public static final List<String> MOUTH_FIELDS = List.of("flareRatio", "maximumOceanApron", "inletLength", "inletDepth", "maximumIncision", "inletCourseFraction", "inletRampSlope");
    private static final Properties DATA=new Properties();
    static {
        try (var input=SurfaceRiverFields.class.getResourceAsStream("/surface-river-fields.properties")) {
            if (input==null) throw new IOException("Missing pinned surface-river definitions");
            DATA.load(input);
            for (String name : List.of("surface", "sources", "channel", "banks", "flow", "mouths"))
                if (!"{}".equals(DATA.getProperty(name+".default"))) throw new IOException("Unsupported surface-river object defaults");
            for (String section : List.of("", "sources", "channel", "banks", "flow", "mouths")) for (String field : fields(section))
                if (!List.of("boolean", "int", "double").contains(DATA.getProperty(prefix(section,field)+".type", ""))) throw new IOException("Missing surface-river primitive type");
        } catch (IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
    private static void authorize(String path) { if (!DimensionFields.dimension(path)) throw new IllegalArgumentException("Surface rivers are dimension-only"); }
    private static void authorize(String path,String section,String field) { authorize(path); if (!fields(section).contains(field)) throw new IllegalArgumentException("Surface-river field is read-only"); }
    public static List<String> fields(String section) {
        return switch(section) { case "" -> ROOT_SCALARS; case "sources" -> SOURCE_FIELDS; case "channel" -> CHANNEL_FIELDS; case "banks" -> BANK_FIELDS; case "flow" -> FLOW_FIELDS; case "mouths" -> MOUTH_FIELDS; default -> throw new IllegalArgumentException("Unsupported surface-river section"); };
    }
    private static String prefix(String section,String field) { return (section.isEmpty() ? "root" : section)+"."+field; }
    public static String description(String object) { return DATA.getProperty(object+".description",""); }
    public static String help(String section,String field) { return DATA.getProperty(prefix(section,field)+".description",""); }
    public static boolean booleanField(String section,String field) { return "boolean".equals(DATA.getProperty(prefix(section,field)+".type")); }
    public static boolean integer(String section,String field) { return "int".equals(DATA.getProperty(prefix(section,field)+".type")); }
    public static String defaultToken(String section,String field) {
        if (!fields(section).contains(field)) throw new IllegalArgumentException("Surface-river field is read-only");
        String token=DATA.getProperty(prefix(section,field)+".default");
        return booleanField(section,field) ? token : new BigDecimal(token).stripTrailingZeros().toPlainString();
    }
    public static String constraints(String section,String field) {
        String p=prefix(section,field);
        return (integer(section,field) ? "Signed 32-bit integer" : "Finite number")+", minimum "+DATA.getProperty(p+".minimum")+", maximum "+DATA.getProperty(p+".maximum");
    }
    public static String draftPrefix(String section) { fields(section); return "hydrology#rivers.surface."+(section.isEmpty() ? "" : section+"."); }
    private static byte[] createAbsent(LosslessJson object,String field) {
        if (object.token(field)!=null) throw new IllegalArgumentException("Surface-river object is already present or unsupported");
        return object.enableObject(field);
    }
    public static byte[] enableSurface(String path,byte[] original) { authorize(path); return HydrologyFields.editRivers(original,rivers -> createAbsent(rivers,"surface")); }
    private static byte[] editSurface(byte[] original,Function<LosslessJson,byte[]> action) { return HydrologyFields.editRivers(original,rivers -> rivers.objectEdit("surface",action,false)); }
    private static byte[] editSection(byte[] original,String section,Function<LosslessJson,byte[]> action) { return editSurface(original,surface -> section.isEmpty() ? action.apply(surface) : surface.objectEdit(section,action,false)); }
    public static byte[] enableSection(String path,byte[] original,String section) {
        authorize(path); if (!SECTIONS.contains(section)) throw new IllegalArgumentException("Surface-river section is read-only");
        return editSurface(original,surface -> createAbsent(surface,section));
    }
    public static byte[] enableScalar(String path,byte[] original,String section,String field) { authorize(path,section,field); return editSection(original,section,object -> object.enable(field,defaultToken(section,field))); }
    public static byte[] setBoolean(String path,byte[] original,String section,String field,boolean value) {
        authorize(path,section,field); if (!booleanField(section,field)) throw new IllegalArgumentException("This surface-river field is numeric");
        return editSection(original,section,object -> object.token(field)==null ? object.enable(field,Boolean.toString(value)) : object.bool(field,value));
    }
    public static byte[] setText(String path,byte[] original,String section,String field,String input) {
        authorize(path,section,field);
        if (booleanField(section,field) || input==null) throw new IllegalArgumentException("Enter a numeric surface-river value");
        double value;
        if (integer(section,field)) {
            try { value=new BigDecimal(input).intValueExact(); }
            catch (NumberFormatException | ArithmeticException invalid) { throw new IllegalArgumentException("Enter an exact signed 32-bit integer",invalid); }
        } else value=Double.parseDouble(input);
        String p=prefix(section,field);
        if (!Double.isFinite(value) || value<Double.parseDouble(DATA.getProperty(p+".minimum")) || value>Double.parseDouble(DATA.getProperty(p+".maximum"))) throw new IllegalArgumentException("Value violates pinned surface-river bounds");
        return editSection(original,section,object -> object.number(field,value));
    }
    private static LosslessJson child(LosslessJson object,String field) { String token=object.token(field); return token==null ? null : new LosslessJson(token.getBytes(StandardCharsets.UTF_8)); }
    static LosslessJson surface(LosslessJson rivers) { return child(rivers,"surface"); }
    static LosslessJson section(LosslessJson surface,String name) { fields(name); return name.isEmpty() ? surface : child(surface,name); }
    static List<String> readOnlyPaths(LosslessJson rivers) {
        var paths=new ArrayList<String>();
        try {
            var surface=surface(rivers); if (surface==null) return List.of();
            for (String field : surface.fields()) {
                if (ROOT_SCALARS.contains(field)) continue;
                if (!SECTIONS.contains(field)) { paths.add("surface."+field); continue; }
                try {
                    var object=section(surface,field);
                    for (String member : object.fields()) if (!fields(field).contains(member)) paths.add("surface."+field+"."+member);
                } catch (IllegalArgumentException mismatch) { paths.add("surface."+field); }
            }
        } catch (IllegalArgumentException mismatch) { paths.add("surface"); }
        return List.copyOf(paths);
    }
}
