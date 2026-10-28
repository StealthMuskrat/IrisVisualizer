package dev.irisvisualizer.editor;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/** Ordered dimension terrain layers; file-key checks only, with no terrain sampling. */
public final class DimensionStacks {
    public static final List<String> OBJECT_FIELDS = List.of("dimensionStack");
    public static final List<String> ROOT_FIELDS = List.of("upperDimension");
    public static final List<String> EDITABLE = List.of("dimensions", "spacer", "blend");
    public static final List<String> BLEND_FIELDS = List.of("amplitude", "style");
    public static final int MAXIMUM_LAYERS=64;
    private static final Properties DATA=new Properties();
    static {
        try (var input=DimensionStacks.class.getResourceAsStream("/dimension-stack-fields.properties")) {
            if (input==null) throw new IOException("Missing pinned dimension-stack definitions");DATA.load(input);
            if (!"2".equals(DATA.getProperty("dimensions.minimum")) || !"hostExactlyOnceAtEnd".equals(DATA.getProperty("layers.policy")) || !"none".equals(DATA.getProperty("upperDimension.default"))) throw new IOException("Unsupported stack policy");
        } catch (IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
    private static void authorize(String path) { if (!DimensionFields.dimension(path)) throw new IllegalArgumentException("Dimension stacking is dimension-only"); }
    public static String host(String path) { authorize(path);return path.substring("dimensions/".length(),path.length()-5); }
    public static String prefix() { return "dimensionStack#"; }
    public static String blendPrefix() { return prefix()+"blend."; }
    public static String help(String field) { return DATA.getProperty(field+".description",""); }
    public static String defaultToken(String field) { if (field==null || !List.of("spacer","amplitude").contains(field)) throw new IllegalArgumentException("Unsupported stack scalar");return DATA.getProperty(field+".default"); }
    public static String styleDefault() { return DATA.getProperty("style.default"); }
    public static List<String> choices(PackWorkspace workspace) { return ResourceTypes.referenceKeys(workspace,"dimensions"); }
    static LosslessJson object(LosslessJson document) { String token=document.token("dimensionStack");return token==null ? null : new LosslessJson(token.getBytes(StandardCharsets.UTF_8)); }
    static LosslessJson blend(LosslessJson stack) { String token=stack.token("blend");return token==null ? null : new LosslessJson(token.getBytes(StandardCharsets.UTF_8)); }
    static List<String> layers(LosslessJson stack) { return stack.token("dimensions")==null ? null : stack.stringArrayPath("dimensions"); }
    public static String draftToken(List<String> layers) { return new LosslessJson(new LosslessJson("{}".getBytes(StandardCharsets.UTF_8)).replaceStringArray("dimensions",layers)).token("dimensions"); }
    static List<String> draftLayers(String token) { return new LosslessJson(("{\"dimensions\":"+token+"}").getBytes(StandardCharsets.UTF_8)).stringArrayPath("dimensions"); }
    static boolean upperActive(LosslessJson document) {
        String token=document.token("upperDimension");if (token==null || token.equals("null")) return false;
        String key=document.stringToken("upperDimension");return !key.isEmpty() && !key.equalsIgnoreCase("none");
    }
    private static void noUpper(LosslessJson document) { if (upperActive(document)) throw new IllegalArgumentException("Dimension stacking cannot be used with active upperDimension; clear upper terrain first"); }
    private static void validateLayers(String path,List<String> keys,PackWorkspace workspace) {
        if (keys==null || keys.size()<2 || keys.size()>MAXIMUM_LAYERS) throw new IllegalArgumentException("Use 2..64 layers (64 is an editor limit)");
        String host=host(path);var choices=choices(workspace);
        for (String key : keys) if (key==null || !choices.contains(key)) throw new IllegalArgumentException("Choose exact dimension keys in this pack; aliases and target contents are not validated");
        if (!keys.getLast().equals(host) || keys.stream().filter(host::equals).count()!=1) throw new IllegalArgumentException("The host dimension must occur exactly once, as the final bottom layer");
    }
    public static byte[] create(String path,byte[] original,String top,PackWorkspace workspace) {
        authorize(path);var document=new LosslessJson(original);if (document.token("dimensionStack")!=null) throw new IllegalArgumentException("Stack already exists or has an unsupported type");
        noUpper(document);var keys=java.util.Arrays.asList(top,host(path));validateLayers(path,keys,workspace);
        return document.objectEdit("dimensionStack",stack -> stack.replaceStringArray("dimensions",keys),true);
    }
    public static byte[] disable(String path,byte[] original) {
        authorize(path);var document=new LosslessJson(original);if (document.token("dimensionStack")==null) return original.clone();object(document);return document.removeField("dimensionStack");
    }
    public static byte[] replaceLayers(String path,byte[] original,List<String> keys,PackWorkspace workspace) {
        authorize(path);var document=new LosslessJson(original);noUpper(document);validateLayers(path,keys,workspace);
        return document.objectEdit("dimensionStack",stack -> stack.replaceStringArray("dimensions",keys),false);
    }
    public static byte[] setUpper(String path,byte[] original,String key,PackWorkspace workspace) {
        authorize(path);var document=new LosslessJson(original);
        if (key!=null) {
            if (key.equalsIgnoreCase("none") || !choices(workspace).contains(key)) throw new IllegalArgumentException("Choose an exact non-reserved dimension key");
            if (document.token("dimensionStack")!=null) throw new IllegalArgumentException("Remove the dimensionStack member before enabling upper terrain");
        } else if (document.token("upperDimension")==null) return original.clone();
        return document.nullableString("upperDimension",key==null ? "none" : key);
    }
    private static int exact(String value) {
        try { int parsed=new BigDecimal(value).intValueExact();if (parsed<0 || parsed>256) throw new IllegalArgumentException("Use an exact integer 0..256");return parsed; }
        catch (NumberFormatException | ArithmeticException | NullPointerException invalid) { throw new IllegalArgumentException("Use an exact integer 0..256",invalid); }
    }
    static boolean numericSupported(LosslessJson object,String field) {
        String token=object.token(field);if (token==null) return true;if (!LosslessJson.numericToken(token)) return false;
        try { new BigDecimal(token).intValueExact();return true; } catch (NumberFormatException | ArithmeticException invalid) { return false; }
    }
    static byte[] editBlend(byte[] original,java.util.function.Function<LosslessJson,byte[]> operation) { return new LosslessJson(original).objectEdit("dimensionStack",stack -> stack.objectEdit("blend",operation,false),false); }
    public static byte[] enableScalar(String path,byte[] original,String field) {
        authorize(path);String value=defaultToken(field);
        return field.equals("spacer") ? new LosslessJson(original).objectEdit("dimensionStack",stack -> stack.enable(field,value),false) : editBlend(original,blend -> blend.enable(field,value));
    }
    public static byte[] setScalar(String path,byte[] original,String field,String value) {
        authorize(path);defaultToken(field);int number=exact(value);
        java.util.function.Function<LosslessJson,byte[]> change=object -> { if (object.token(field)==null || !numericSupported(object,field)) throw new IllegalArgumentException("Enable a supported exact integer first");return object.integer(field,number); };
        return field.equals("spacer") ? new LosslessJson(original).objectEdit("dimensionStack",change,false) : editBlend(original,change);
    }
    public static byte[] enableBlend(String path,byte[] original) {
        authorize(path);return new LosslessJson(original).objectEdit("dimensionStack",stack -> { if (stack.token("blend")!=null) throw new IllegalArgumentException("Blend already exists or has an unsupported type");return stack.enableObject("blend"); },false);
    }
    public static byte[] disableBlend(String path,byte[] original) {
        authorize(path);return new LosslessJson(original).objectEdit("dimensionStack",stack -> { if (stack.token("blend")==null) return stack.sourceBytes();blend(stack);return stack.removeField("blend"); },false);
    }
    public static NoiseStyleFields.Target styleTarget() { return new NoiseStyleFields.Target("dimensions","dimensionStack.blend.style",null); }
    static List<String> readOnlyPaths(LosslessJson document) {
        var paths=new ArrayList<String>();
        try { upperActive(document); } catch (IllegalArgumentException invalid) { paths.add("upperDimension"); }
        try {
            var stack=object(document);if (stack==null) return List.copyOf(paths);
            for (String field : stack.fields()) {
                if (field.equals("dimensions")) { try { layers(stack); } catch (IllegalArgumentException invalid) { paths.add("dimensionStack.dimensions"); } }
                else if (field.equals("spacer")) { if (!numericSupported(stack,field)) paths.add("dimensionStack.spacer"); }
                else if (field.equals("blend")) {
                    try { var blend=blend(stack);for (String member : blend.fields()) {
                        if (member.equals("amplitude")) { if (!numericSupported(blend,member)) paths.add("dimensionStack.blend.amplitude"); }
                        else if (member.equals("style")) { try { var style=NoiseStyleFields.object(blend);for (String path : StyleFractures.readOnlyPaths(style)) paths.add("dimensionStack.blend.style."+path); } catch (IllegalArgumentException invalid) { paths.add("dimensionStack.blend.style"); } }
                        else paths.add("dimensionStack.blend."+member);
                    } } catch (IllegalArgumentException invalid) { paths.add("dimensionStack.blend"); }
                } else paths.add("dimensionStack."+field);
            }
        } catch (IllegalArgumentException invalid) { paths.add("dimensionStack"); }
        return List.copyOf(paths);
    }
    static List<String> findings(String path,LosslessJson document,PackWorkspace workspace) {
        var findings=new ArrayList<String>();
        try { if (document.token("dimensionStack")!=null) { noUpper(document);var stack=object(document);var keys=layers(stack);validateLayers(path,keys,workspace); } }
        catch (IllegalArgumentException invalid) { findings.add(invalid.getMessage()); }
        return List.copyOf(findings);
    }
}
