package dev.irisvisualizer.editor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;

/** Source-backed cave settings only; hierarchy resolution and cave generation remain outside the editor. */
public final class CaveProfileFields {
    public static final List<String> CONTEXTS = List.of("dimensions","regions","biomes");
    public static final List<String> OBJECT_FIELDS = List.of("caveProfile");
    public static final List<String> EDITABLE = List.of("enabled","verticalEdgeFade","verticalEdgeFadeStrength","baseWeight","detailWeight","warpStrength","thresholdBias","sampleStep","adaptiveSampling","adaptiveSampleStep","adaptiveThresholdMargin","surfaceClearance","allowSurfaceBreak","surfaceBreakNoiseThreshold","surfaceBreakDepth","surfaceBreakThresholdBoost","objectMinDepthBelowSurface","anchorScanStep","anchorSearchAttempts","allowFluid","fluidMinDepthBelowSurface","fluidRequiresFloor","allowLava");
    private static final Properties DEFINITIONS = new Properties();
    static {
        try (var input = CaveProfileFields.class.getResourceAsStream("/cave-profile-fields.properties")) {
            if (input == null) throw new IOException("Missing pinned cave profile definitions");
            DEFINITIONS.load(input);
            if (!"{}".equals(DEFINITIONS.getProperty("profileDefault")) || !"false".equals(DEFINITIONS.getProperty("enabled.default")))
                throw new IOException("Unsupported cave profile defaults");
        } catch (IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
    private static String authorize(String path) {
        String base=ResourceTypes.base(path);
        if (base==null || !CONTEXTS.contains(base)) throw new IllegalArgumentException("Cave profile is read-only");
        return base;
    }
    private static void authorize(String path, String field) {
        authorize(path); if (!EDITABLE.contains(field)) throw new IllegalArgumentException("Cave field is read-only");
    }
    public static String description(String path) { return DEFINITIONS.getProperty(authorize(path)+".description",""); }
    public static String help(String field) { return DEFINITIONS.getProperty(field+".description",""); }
    public static boolean booleanField(String field) { return "boolean".equals(DEFINITIONS.getProperty(field+".type")); }
    public static String defaultToken(String field) {
        if (!EDITABLE.contains(field)) throw new IllegalArgumentException("Cave field is read-only");
        String token=DEFINITIONS.getProperty(field+".default");
        return booleanField(field) ? token : new java.math.BigDecimal(token).stripTrailingZeros().toPlainString();
    }
    public static String constraints(String field) {
        String min=DEFINITIONS.getProperty(field+".minimum"),max=DEFINITIONS.getProperty(field+".maximum");
        return ("int".equals(DEFINITIONS.getProperty(field+".type")) ? "Signed 32-bit integer" : "Finite number")
                +(min==null?"":", minimum "+min)+(max==null?"":", maximum "+max);
    }
    public static byte[] enableProfile(String path, byte[] original) {
        authorize(path); var document=new LosslessJson(original);
        if (document.token("caveProfile")!=null) throw new IllegalArgumentException("Cave profile is already present or has an unsupported type");
        return document.enableObject("caveProfile");
    }
    public static byte[] enable(String path, byte[] original, String field) {
        authorize(path,field);
        return new LosslessJson(original).objectEdit("caveProfile",object -> object.enable(field,defaultToken(field)),false);
    }
    public static byte[] editBoolean(String path, byte[] original, String field, boolean value) {
        authorize(path,field);
        if (!booleanField(field)) throw new IllegalArgumentException("Numeric cave field requires a number");
        return new LosslessJson(original).objectEdit("caveProfile",object -> object.token(field)==null
                ? object.enable(field,Boolean.toString(value)) : object.bool(field,value),false);
    }
    public static byte[] editText(String path, byte[] original, String field, String input) {
        authorize(path,field);
        if (booleanField(field)) throw new IllegalArgumentException("Boolean cave field requires its toggle");
        double value=Double.parseDouble(input);
        String min=DEFINITIONS.getProperty(field+".minimum"),max=DEFINITIONS.getProperty(field+".maximum");
        if (!Double.isFinite(value) || (min!=null && value<Double.parseDouble(min)) || (max!=null && value>Double.parseDouble(max))
                || ("int".equals(DEFINITIONS.getProperty(field+".type")) && (value!=Math.rint(value) || value<Integer.MIN_VALUE || value>Integer.MAX_VALUE)))
            throw new IllegalArgumentException("Value violates pinned cave field constraints");
        return new LosslessJson(original).objectEdit("caveProfile",object -> object.number(field,value),false);
    }
    static LosslessJson object(LosslessJson document) {
        String token=document.token("caveProfile");
        return token==null ? null : new LosslessJson(token.getBytes(StandardCharsets.UTF_8));
    }
}
