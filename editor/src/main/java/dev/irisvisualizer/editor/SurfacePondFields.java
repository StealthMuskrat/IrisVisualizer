package dev.irisvisualizer.editor;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;

/** Surface endpoint pond configuration; contextual creation retains constructor-specific radii. */
public final class SurfacePondFields {
    public static final List<String> OBJECT_FIELDS = List.of("ponds");
    public static final List<String> CONTEXTS = List.of("source", "terminal");
    public static final List<String> EDITABLE = List.of("enabled", "minimumRadius", "maximumRadius", "depth");
    private static final Properties DATA=new Properties();
    static {
        try (var input=SurfacePondFields.class.getResourceAsStream("/surface-pond-fields.properties")) {
            if (input==null) throw new IOException("Missing pinned surface-pond definitions");
            DATA.load(input);
            if (!"{}".equals(DATA.getProperty("ponds.default")) || !"true".equals(defaultToken("enabled"))) throw new IOException("Unsupported pond defaults");
            for (String context : CONTEXTS) {
                var seed=new LosslessJson(createToken(context).getBytes(StandardCharsets.UTF_8));
                var overrides=new HashSet<String>();
                for (String field : EDITABLE) {
                    String expected=contextDefault(context,field);
                    if (!expected.equals(defaultToken(field))) overrides.add(field);
                    if (!expected.equals(seed.token(field)==null ? defaultToken(field) : seed.token(field))) throw new IOException("Pond creation changes contextual defaults");
                }
                if (!seed.fields().equals(overrides)) throw new IOException("Unsupported pond creation fields");
            }
        } catch (IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
    private static void context(String value) { if (!CONTEXTS.contains(value)) throw new IllegalArgumentException("Unsupported pond endpoint"); }
    private static void field(String value) { if (!EDITABLE.contains(value)) throw new IllegalArgumentException("Pond field is read-only"); }
    private static void authorize(String path) { if (!DimensionFields.dimension(path)) throw new IllegalArgumentException("Surface ponds are dimension-only"); }
    public static String description(String object) { return DATA.getProperty(object+".description",""); }
    public static String help(String field) { field(field); return DATA.getProperty(field+".description",""); }
    public static String defaultToken(String field) {
        field(field); String token=DATA.getProperty(field+".default");
        return field.equals("enabled") ? token : new BigDecimal(token).stripTrailingZeros().toPlainString();
    }
    public static String contextDefault(String context,String field) {
        context(context); field(field); return DATA.getProperty(context+"."+field+".default");
    }
    private static String createToken(String context) { return DATA.getProperty(context+".createToken"); }
    public static String constraints(String field) { field(field); return "Signed 32-bit integer, minimum "+DATA.getProperty(field+".minimum")+", maximum "+DATA.getProperty(field+".maximum"); }
    public static String draftPrefix(String context) { context(context); return "hydrology#rivers.surface.ponds."+context+"."; }
    public static byte[] enableContainer(String path,byte[] original) {
        authorize(path);
        return SurfaceRiverFields.editSurface(original,surface -> {
            if (surface.token("ponds")!=null) throw new IllegalArgumentException("Pond settings are already present or unsupported");
            return surface.enableObject("ponds");
        });
    }
    public static byte[] enablePond(String path,byte[] original,String context) {
        authorize(path); context(context);
        return SurfaceRiverFields.editSurface(original,surface -> surface.objectEdit("ponds",ponds -> {
            if (ponds.token(context)!=null) throw new IllegalArgumentException("Pond endpoint is already present or unsupported");
            return ponds.objectEdit(context,pond -> {
                byte[] next=pond.sourceBytes();
                var seed=new LosslessJson(createToken(context).getBytes(StandardCharsets.UTF_8));
                for (String field : seed.fields()) next=new LosslessJson(next).enable(field,seed.token(field));
                return next;
            },true);
        },false));
    }
    private static byte[] edit(byte[] original,String context,java.util.function.Function<LosslessJson,byte[]> action) {
        return SurfaceRiverFields.editSurface(original,surface -> surface.objectEdit("ponds",ponds -> ponds.objectEdit(context,action,false),false));
    }
    public static byte[] enableScalar(String path,byte[] original,String context,String field) {
        authorize(path); context(context); field(field); return edit(original,context,pond -> pond.enable(field,defaultToken(field)));
    }
    public static byte[] setEnabled(String path,byte[] original,String context,boolean value) {
        authorize(path); context(context);
        return edit(original,context,pond -> pond.token("enabled")==null ? pond.enable("enabled",Boolean.toString(value)) : pond.bool("enabled",value));
    }
    public static byte[] setText(String path,byte[] original,String context,String field,String input) {
        authorize(path); context(context); field(field);
        if (field.equals("enabled") || input==null) throw new IllegalArgumentException("Enter a numeric pond value");
        int value;
        try { value=new BigDecimal(input).intValueExact(); }
        catch (NumberFormatException | ArithmeticException invalid) { throw new IllegalArgumentException("Enter an exact signed 32-bit integer",invalid); }
        if (value<Integer.parseInt(DATA.getProperty(field+".minimum")) || value>Integer.parseInt(DATA.getProperty(field+".maximum"))) throw new IllegalArgumentException("Value violates pinned pond bounds");
        return edit(original,context,pond -> pond.number(field,value));
    }
    private static LosslessJson child(LosslessJson object,String name) { String token=object.token(name); return token==null ? null : new LosslessJson(token.getBytes(StandardCharsets.UTF_8)); }
    static LosslessJson ponds(LosslessJson surface) { return child(surface,"ponds"); }
    static LosslessJson pond(LosslessJson ponds,String context) { context(context); return child(ponds,context); }
    static List<String> readOnlyPaths(LosslessJson surface) {
        var paths=new ArrayList<String>();
        try {
            var ponds=ponds(surface); if (ponds==null) return List.of();
            for (String context : ponds.fields()) {
                if (!CONTEXTS.contains(context)) { paths.add("ponds."+context); continue; }
                try {
                    var pond=pond(ponds,context);
                    for (String field : pond.fields()) if (!EDITABLE.contains(field)) paths.add("ponds."+context+"."+field);
                } catch (IllegalArgumentException mismatch) { paths.add("ponds."+context); }
            }
        } catch (IllegalArgumentException mismatch) { paths.add("ponds"); }
        return List.copyOf(paths);
    }
}
