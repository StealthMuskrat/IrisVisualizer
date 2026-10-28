package dev.irisvisualizer.editor;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.function.Function;

/** Bounded bank authoring only; density fields and native validation are not executed. */
public final class RiverBankFields {
    public static final List<String> OBJECT_FIELDS = List.of("banks3D");
    public static final List<String> EDITABLE = List.of("enabled", "seed", "amplitude", "horizontalScale", "verticalScale", "maximumOverhang");
    public static final List<String> STYLE_FIELDS = List.of("densityStyle");
    private static final Properties DATA=new Properties();
    static {
        try (var input=RiverBankFields.class.getResourceAsStream("/river-bank-fields.properties")) {
            if (input==null) throw new IOException("Missing pinned bank definitions");
            DATA.load(input);
            if (!"{}".equals(DATA.getProperty("banks3D.default")) || !"true".equals(DATA.getProperty("enabled.default"))
                    || !"nativeBankRanges".equals(DATA.getProperty("style.runtimeRule"))) throw new IOException("Unsupported pinned bank defaults/rules");
        } catch (IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
    private static void authorize(String path) { if (!DimensionFields.dimension(path)) throw new IllegalArgumentException("River bank geometry is dimension-only"); }
    private static void authorize(String path,String field) { authorize(path); if (!EDITABLE.contains(field)) throw new IllegalArgumentException("Bank field is read-only"); }
    public static String description(String field) { return DATA.getProperty(field+".description",""); }
    public static String styleDefault() { return DATA.getProperty("densityStyle.default"); }
    static String styleBound(String field,String kind) { return DATA.getProperty("style."+field+"."+kind); }
    public static String draftPrefix() { return "hydrology#rivers.geometry.banks3D."; }
    public static boolean booleanField(String field) { return "boolean".equals(DATA.getProperty(field+".type")); }
    public static String defaultToken(String field) {
        if (!EDITABLE.contains(field)) throw new IllegalArgumentException("Bank field is read-only");
        String token=DATA.getProperty(field+".default");
        return booleanField(field) ? token : new BigDecimal(token).stripTrailingZeros().toPlainString();
    }
    public static String constraints(String field) {
        if (field.equals("seed")) return "Signed 64-bit integer (exact)";
        return (field.equals("maximumOverhang") ? "Signed 32-bit integer" : "Finite number")+", minimum "+DATA.getProperty(field+".minimum")+", maximum "+DATA.getProperty(field+".maximum");
    }
    static byte[] editObject(byte[] original,Function<LosslessJson,byte[]> operation) {
        return HydrologyFields.editRivers(original,rivers -> rivers.objectEdit("geometry",geometry -> geometry.objectEdit("banks3D",operation,false),false));
    }
    public static byte[] enableObject(String path,byte[] original) {
        authorize(path);
        return HydrologyFields.editRivers(original,rivers -> rivers.objectEdit("geometry",geometry -> {
            if (geometry.token("banks3D")!=null) throw new IllegalArgumentException("Bank geometry is already present or unsupported");
            return geometry.enableObject("banks3D");
        },false));
    }
    public static byte[] enable(String path,byte[] original,String field) {
        authorize(path,field); return editObject(original,bank -> bank.enable(field,defaultToken(field)));
    }
    public static byte[] setEnabled(String path,byte[] original,boolean value) {
        authorize(path);
        return editObject(original,bank -> bank.token("enabled")==null ? bank.enable("enabled",Boolean.toString(value)) : bank.bool("enabled",value));
    }
    public static byte[] setText(String path,byte[] original,String field,String input) {
        authorize(path,field);
        if (booleanField(field) || input==null) throw new IllegalArgumentException("Enter a numeric bank value");
        if (field.equals("seed")) {
            long value;
            try { value=new BigDecimal(input).longValueExact(); }
            catch (NumberFormatException | ArithmeticException invalid) { throw new IllegalArgumentException("Enter an exact signed 64-bit integer",invalid); }
            return editObject(original,bank -> bank.integer(field,value));
        }
        double value;
        if (field.equals("maximumOverhang")) {
            try { value=new BigDecimal(input).intValueExact(); }
            catch (NumberFormatException | ArithmeticException invalid) { throw new IllegalArgumentException("Enter an exact signed 32-bit integer",invalid); }
        } else value=Double.parseDouble(input);
        if (!Double.isFinite(value) || value<Double.parseDouble(DATA.getProperty(field+".minimum")) || value>Double.parseDouble(DATA.getProperty(field+".maximum")))
            throw new IllegalArgumentException("Value violates pinned native bank bounds");
        return editObject(original,bank -> bank.number(field,value));
    }
    static LosslessJson object(LosslessJson geometry) {
        String token=geometry.token("banks3D"); return token==null ? null : new LosslessJson(token.getBytes(StandardCharsets.UTF_8));
    }
    static List<String> readOnlyPaths(LosslessJson geometry) {
        var paths=new ArrayList<String>();
        try {
            var bank=object(geometry); if (bank==null) return List.of();
            for (String field : bank.fields()) {
                if (EDITABLE.contains(field)) continue;
                if (!STYLE_FIELDS.contains(field)) { paths.add("banks3D."+field); continue; }
                try {
                    var style=NoiseStyleFields.object(bank,NoiseStyleFields.Target.bank());
                    for (String member : StyleFractures.readOnlyPaths(style)) paths.add("banks3D.densityStyle."+member);
                } catch (IllegalArgumentException mismatch) { paths.add("banks3D.densityStyle"); }
            }
        } catch (IllegalArgumentException mismatch) { paths.add("banks3D"); }
        return List.copyOf(paths);
    }
}
