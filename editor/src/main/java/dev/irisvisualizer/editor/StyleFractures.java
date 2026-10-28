package dev.irisvisualizer.editor;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/** Optional recursive style distortion; never constructs or samples Iris noise. */
public final class StyleFractures {
    public static final List<String> EDITABLE = List.of("fracture");
    public static final int MAXIMUM_DEPTH=8;
    private static final Properties DATA=new Properties();
    static {
        try (var input=StyleFractures.class.getResourceAsStream("/style-fracture-fields.properties")) {
            if (input==null) throw new IOException("Missing pinned style-fracture definitions");DATA.load(input);
            if (!"null".equals(DATA.getProperty("fracture.default")) || !"FLAT".equals(DATA.getProperty("class.style")) || !"positiveFiniteReciprocal".equals(DATA.getProperty("multiplier.policy"))) throw new IOException("Unsupported style-fracture policy");
        } catch (IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
    public static String help() { return DATA.getProperty("fracture.description",""); }
    public static String multiplierDefault() { return DATA.getProperty("multiplier.default"); }
    public static String multiplierConstraints() { return "Finite number, minimum "+DATA.getProperty("multiplier.minimum")+", with a finite reciprocal. This child's multiplier distorts its parent's coordinate field."; }
    private static void authorize(String path,NoiseStyleFields.Target child) {
        if (child==null || !child.optionalFracture() || !child.resourceBase().equals(ResourceTypes.base(path))) throw new IllegalArgumentException("Select an existing style's fracture child");
    }
    public static byte[] enable(String path,byte[] original,NoiseStyleFields.Target child) { authorize(path,child);return NoiseStyleFields.enable(path,original,child,"style"); }
    public static byte[] disable(String path,byte[] original,NoiseStyleFields.Target child) {
        authorize(path,child);return NoiseStyleFields.editContainer(original,child,container -> container.nullObject(child.member()),false);
    }
    public static byte[] enableMultiplier(String path,byte[] original,NoiseStyleFields.Target child) {
        authorize(path,child);return NoiseStyleFields.editContainer(original,child,container -> container.objectEdit(child.member(),style -> style.enable("multiplier",multiplierDefault()),false),false);
    }
    public static byte[] setMultiplier(String path,byte[] original,NoiseStyleFields.Target child,String input) {
        authorize(path,child);double value;
        try {
            var exact=new BigDecimal(input);value=exact.doubleValue();
            if (exact.compareTo(new BigDecimal(DATA.getProperty("multiplier.minimum")))<0 || !Double.isFinite(value) || value<=0 || !Double.isFinite(1D/value)) throw new IllegalArgumentException("Style-fracture multiplier violates native bounds");
        } catch (NumberFormatException | NullPointerException invalid) { throw new IllegalArgumentException("Enter a finite positive fracture multiplier",invalid); }
        double next=value;return NoiseStyleFields.editContainer(original,child,container -> container.objectEdit(child.member(),style -> style.number("multiplier",next),false),false);
    }
    static boolean supported(LosslessJson style) {
        String token=style.token("fracture");if (token==null || token.equals("null")) return true;
        try { new LosslessJson(token.getBytes(StandardCharsets.UTF_8));return true; } catch (IllegalArgumentException invalid) { return false; }
    }
    static List<String> readOnlyPaths(LosslessJson style) { return readOnlyPaths(style,0,false); }
    static List<String> readOnlyPaths(LosslessJson style,int depth,boolean rootMultiplier) {
        var paths=new ArrayList<String>();readOnly(style,"",depth,rootMultiplier,paths);return List.copyOf(paths);
    }
    private static void readOnly(LosslessJson style,String prefix,int depth,boolean multiplier,List<String> paths) {
        for (String field : style.fields()) {
            if (field.equals("fracture")) {
                String token=style.token(field);if (token.equals("null")) continue;
                if (depth>=MAXIMUM_DEPTH) { paths.add(prefix+field);continue; }
                try { readOnly(new LosslessJson(token.getBytes(StandardCharsets.UTF_8)),prefix+"fracture.",depth+1,true,paths); }
                catch (IllegalArgumentException invalid) { paths.add(prefix+field); }
            } else if (field.equals("multiplier") && multiplier) {
                if (!LosslessJson.numericToken(style.token(field))) paths.add(prefix+field);
            } else if (NoiseStyleFields.readOnly(style,field)) paths.add(prefix+field);
        }
    }
}
