package dev.irisvisualizer.editor;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;

/** Bounded dimension-height authoring; logical height and borrowed runtime types remain read-only. */
public final class DimensionHeights {
    public static final List<String> OBJECT_FIELDS = List.of("dimensionHeight");
    private static final Properties DATA=new Properties();
    static {
        try (var input=DimensionHeights.class.getResourceAsStream("/dimension-height-fields.properties")) {
            if (input==null) throw new IOException("Missing pinned dimension-height definitions");DATA.load(input);
            if (!"strictInside".equals(DATA.getProperty("envelope.rule"))) throw new IOException("Unsupported envelope rule");
        } catch (IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
    private static void authorize(String path) { if (!DimensionFields.dimension(path)) throw new IllegalArgumentException("Dimension heights are dimension-only"); }
    public static String description() { return DATA.getProperty("description",""); }
    public static String defaultBound(String field,boolean absent) { if (!List.of("min","max").contains(field)) throw new IllegalArgumentException("Unsupported height bound");return DATA.getProperty((absent ? "context." : "class.")+field); }
    public static String draftPrefix() { return "dimensionHeight#"; }
    static String nativeBound(String field) { return DATA.getProperty("native."+field); }
    static LosslessJson object(LosslessJson document) { String token=document.token("dimensionHeight");return token==null ? null : new LosslessJson(token.getBytes(StandardCharsets.UTF_8)); }
    private static int exact(String input) {
        try { return new BigDecimal(input).intValueExact(); } catch (NumberFormatException | ArithmeticException | NullPointerException invalid) { throw new IllegalArgumentException("Enter exact signed 32-bit height integers",invalid); }
    }
    private static int logicalHeight(LosslessJson document) {
        String token=document.token("logicalHeight");
        if (token==null || token.equals("null")) return Integer.parseInt(DATA.getProperty("logical.default"));
        if (!LosslessJson.numericToken(token)) throw new IllegalArgumentException("Stored logicalHeight has an unsupported type; it remains read-only");return exact(token);
    }
    public static String logicalLabel(LosslessJson document) { try { return Integer.toString(logicalHeight(document)); } catch (IllegalArgumentException unsupported) { return "unsupported stored value"; } }
    public static String constraints() { return "Exact integers; minimum Y "+DATA.getProperty("minimumY.minimum")+" to "+DATA.getProperty("minimumY.maximum")+"; span "+DATA.getProperty("span.minimum")+" to "+DATA.getProperty("span.maximum")+". Minimum Y and span must be multiples of "+DATA.getProperty("step")+". The read-only logicalHeight must fit within the span."; }
    private static void validate(LosslessJson document,int min,int max) {
        long span=(long)max-min;int step=Integer.parseInt(DATA.getProperty("step"));
        if (min<Integer.parseInt(DATA.getProperty("minimumY.minimum")) || min>Integer.parseInt(DATA.getProperty("minimumY.maximum")) || min%step!=0) throw new IllegalArgumentException("Dimension minimum Y violates native bounds/alignment");
        if (span<Integer.parseInt(DATA.getProperty("span.minimum")) || span>Integer.parseInt(DATA.getProperty("span.maximum")) || span%step!=0) throw new IllegalArgumentException("Dimension span violates native bounds/alignment");
        int logical=logicalHeight(document);if (logical<0 || logical>span) throw new IllegalArgumentException("Read-only logicalHeight must be nonnegative and no greater than the dimension span");
    }
    private static byte[] write(LosslessJson range,String field,int value) { return range.token(field)==null ? range.enable(field,Integer.toString(value)) : range.number(field,value); }
    public static byte[] enable(String path,byte[] original) { return create(path,original,defaultBound("min",true),defaultBound("max",true)); }
    public static byte[] create(String path,byte[] original,String minimum,String maximum) {
        authorize(path);var document=new LosslessJson(original);
        if (document.token("dimensionHeight")!=null) throw new IllegalArgumentException("Dimension height already exists or is unsupported");
        int min=exact(minimum),max=exact(maximum);validate(document,min,max);
        return document.objectEdit("dimensionHeight",r -> write(new LosslessJson(write(r,"min",min)),"max",max),true);
    }
    public static byte[] set(String path,byte[] original,String minimum,String maximum) {
        authorize(path);var document=new LosslessJson(original);int min=exact(minimum),max=exact(maximum);validate(document,min,max);
        return document.objectEdit("dimensionHeight",r -> write(new LosslessJson(write(r,"min",min)),"max",max),false);
    }
    static List<String> readOnlyPaths(LosslessJson document) {
        try { var range=object(document);return range==null ? List.of() : range.fields().stream().filter(field -> !List.of("min","max").contains(field)).map(field -> "dimensionHeight."+field).toList(); }
        catch (IllegalArgumentException unsupported) { return List.of("dimensionHeight"); }
    }
}
