package dev.irisvisualizer.editor;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.function.Function;

/** Reference and cache authoring for enabled inline styles; no source loading or evaluation. */
public final class NoiseStyleSources {
    public static final List<String> EDITABLE = List.of("expression", "imageMap", "cacheSize");
    public static final List<String> REFERENCES = List.of("expression", "imageMap");
    private static final Properties DATA=new Properties();
    static {
        try (var input=NoiseStyleSources.class.getResourceAsStream("/style-source-fields.properties")) {
            if (input==null) throw new IOException("Missing pinned style source definitions");DATA.load(input);
            if (!"expressionThenImageThenBase".equals(DATA.getProperty("precedence.policy")) || !"baseOnUnloadedExpression".equals(DATA.getProperty("expression.fallback")) || !"positiveOverrideOtherwiseCallerFallback".equals(DATA.getProperty("cache.policy"))) throw new IOException("Unsupported style source policy");
        } catch (IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
    private static void reference(String field) { if (field==null || !REFERENCES.contains(field)) throw new IllegalArgumentException("Select a style resource reference"); }
    private static void authorize(String path,NoiseStyleFields.Target target) { if (!target.resourceBase().equals(ResourceTypes.base(path))) throw new IllegalArgumentException("Style owner does not match this resource"); }
    public static String help(String field) { if (field==null || !EDITABLE.contains(field)) throw new IllegalArgumentException("Unsupported style source field");return DATA.getProperty(field+".description",""); }
    public static String family(String field) { reference(field);return DATA.getProperty(field+".family"); }
    public static String cacheDefault() { return DATA.getProperty("cacheSize.default"); }
    static boolean supported(LosslessJson style,String field) {
        if (field==null || !EDITABLE.contains(field)) return false;
        String token=style.token(field);if (token==null) return true;
        try {
            if (REFERENCES.contains(field)) { if (!token.equals("null")) style.stringToken(field); }
            else new BigDecimal(token).intValueExact();
            return true;
        } catch (IllegalArgumentException | ArithmeticException invalid) { return false; }
    }
    public static List<String> choices(PackWorkspace workspace,String field) {
        String prefix=family(field)+"/";return workspace.paths().stream().filter(path -> path.startsWith(prefix) && path.endsWith(".json"))
                .map(path -> path.substring(prefix.length(),path.length()-5)).filter(ResourceTypes::referenceKey).sorted().toList();
    }
    public static final class CatalogCache {
        private PackWorkspace workspace;
        private long revision=-1;
        private final Map<String,List<String>> choices=new HashMap<>();
        public List<String> choices(PackWorkspace current,String field) {
            Objects.requireNonNull(current);reference(field);if (workspace!=current || revision!=current.resourceRevision()) { workspace=current;revision=current.resourceRevision();choices.clear(); }
            return choices.computeIfAbsent(field,ignored -> NoiseStyleSources.choices(current,field));
        }
    }
    private static byte[] edit(byte[] original,NoiseStyleFields.Target target,Function<LosslessJson,byte[]> action) {
        return NoiseStyleFields.editContainer(original,target,container -> container.objectEdit(target.member(),action,false),false);
    }
    public static byte[] setReference(String path,byte[] original,NoiseStyleFields.Target target,String field,String value,PackWorkspace workspace) {
        authorize(path,target);reference(field);if (value!=null && !choices(workspace,field).contains(value)) throw new IllegalArgumentException("Choose an exact in-pack "+family(field)+" JSON key; target content and loader aliases are unchecked");
        return edit(original,target,style -> style.nullableString(field,value));
    }
    public static byte[] enableCache(String path,byte[] original,NoiseStyleFields.Target target) { authorize(path,target);return edit(original,target,style -> style.enable("cacheSize",cacheDefault())); }
    public static byte[] setCache(String path,byte[] original,NoiseStyleFields.Target target,String input) {
        authorize(path,target);int value;
        try { value=new BigDecimal(input).intValueExact(); } catch (NumberFormatException | ArithmeticException | NullPointerException invalid) { throw new IllegalArgumentException("Enter an exact signed 32-bit cache size",invalid); }
        if (value<Integer.parseInt(DATA.getProperty("cacheSize.minimum")) || value>Integer.parseInt(DATA.getProperty("cacheSize.maximum"))) throw new IllegalArgumentException("Cache size must be from 0 to 8192");
        return edit(original,target,style -> { if (!supported(style,"cacheSize")) throw new IllegalArgumentException("Stored cache size has an unsupported type or integer width");return style.integer("cacheSize",value); });
    }
}
