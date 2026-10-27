package dev.irisvisualizer.editor;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;

/** Ordered native deposit remaps; material aliases and effective world generation are not resolved. */
public final class DepositVariants {
    public static final List<String> LIST_FIELDS = List.of("depositVariants");
    public static final List<String> EDITABLE = List.of("minHeight", "maxHeight", "remap");
    private static final Properties DATA=new Properties();
    static {
        try (var input=DepositVariants.class.getResourceAsStream("/deposit-variant-fields.properties")) {
            if (input==null) throw new IOException("Missing pinned deposit variant definitions");DATA.load(input);
            if (!"inclusiveWorldY".equals(DATA.getProperty("height.policy")) || !"biomeRegionDimensionFirstRemap".equals(DATA.getProperty("dispatch.policy")) || !"materialIdentity".equals(DATA.getProperty("source.policy"))) throw new IOException("Unsupported deposit variant policy");
        } catch (IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
    static void verifyHostDefinitions() { if (!"nullInheritEmptyForbid".equals(DATA.getProperty("hosts.policy")) || !"oreDepositExteriorSurface".equals(DATA.getProperty("hosts.scope"))) throw new IllegalStateException("Unsupported biome ore-host policy"); }
    public record Context(String resourceBase,int index) {
        public Context { if (resourceBase==null || !OreGenerators.CONTEXTS.contains(resourceBase) || index<0) throw new IllegalArgumentException("Select a deposit variant"); }
        public String prefix() { return listPrefix(resourceBase)+index+"."; }
        public String mapPrefix() { return prefix()+"remap#."; }
    }
    private static void authorize(String path,String base) { if (base==null || !OreGenerators.CONTEXTS.contains(base) || !base.equals(ResourceTypes.base(path))) throw new IllegalArgumentException("Variant owner does not match this resource"); }
    private static void authorize(String path,Context context) { authorize(path,context.resourceBase()); }
    public static String listPrefix(String base) { if (base==null || !OreGenerators.CONTEXTS.contains(base)) throw new IllegalArgumentException("Unsupported variant owner");return "depositVariants#"+base+"."; }
    public static String help(String field) { if (field==null || !EDITABLE.contains(field)) throw new IllegalArgumentException("Unsupported variant field");return DATA.getProperty(field+".description",""); }
    public static String defaultHeight(String field) { if (!List.of("minHeight","maxHeight").contains(field)) throw new IllegalArgumentException("Select a variant height");return DATA.getProperty(field+".default"); }
    static List<LosslessJson> entries(LosslessJson root) { return root.token("depositVariants")==null ? null : root.objectArray("depositVariants"); }
    private static byte[] edit(byte[] original,Context context,java.util.function.Function<LosslessJson,byte[]> action) { return new LosslessJson(original).objectArrayEdit("depositVariants",context.index(),action); }
    static LosslessJson remap(LosslessJson variant) { String token=variant.token("remap");return token==null ? null : new LosslessJson(token.getBytes(StandardCharsets.UTF_8)); }
    private static void source(String value) { if (value==null || !value.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) throw new IllegalArgumentException("Enter a namespaced source block id without properties; aliases and registry existence are unchecked"); }
    private static void target(String value) { if (value==null || value.isBlank()) throw new IllegalArgumentException("Enter a nonblank target; registry and state resolution are unchecked"); }
    public static byte[] enable(String path,byte[] original,String base) { authorize(path,base);return new LosslessJson(original).enable("depositVariants","[]"); }
    public static byte[] change(String path,byte[] original,String base,RiverProfiles.Action action,int index,String source,String target) {
        authorize(path,base);if (action==null) throw new IllegalArgumentException("Choose a variant action");var root=new LosslessJson(original);
        if (action==RiverProfiles.Action.ADD) {
            source(source);target(target);byte[] map=new LosslessJson("{}".getBytes(StandardCharsets.UTF_8)).stringMapAdd(source,target);
            byte[] variant=new LosslessJson("{}".getBytes(StandardCharsets.UTF_8)).objectEdit("remap",ignored -> map,true);
            return root.objectArrayInsert("depositVariants",root.objectArray("depositVariants").size(),variant);
        }
        return BiomeLayers.changeLayers(root,"depositVariants",action,index);
    }
    private static int height(String field,String input) {
        int value;try { value=new BigDecimal(input).intValueExact(); } catch (NumberFormatException | ArithmeticException | NullPointerException invalid) { throw new IllegalArgumentException("Enter an exact signed 32-bit height",invalid); }
        if (value<Integer.parseInt(DATA.getProperty(field+".minimum")) || value>Integer.parseInt(DATA.getProperty(field+".maximum"))) throw new IllegalArgumentException("Variant heights must be within pinned bounds -2048 to 8192");return value;
    }
    private static byte[] bound(LosslessJson variant,String field,int value) { return variant.token(field)==null ? variant.enable(field,Integer.toString(value)) : variant.number(field,value); }
    public static byte[] setBand(String path,byte[] original,Context context,String minimum,String maximum) {
        authorize(path,context);int min=height("minHeight",minimum),max=height("maxHeight",maximum);if (min>max) throw new IllegalArgumentException("Minimum world Y must be at most maximum world Y");
        return edit(original,context,v -> bound(new LosslessJson(bound(v,"minHeight",min)),"maxHeight",max));
    }
    public static byte[] enableMap(String path,byte[] original,Context context) {
        authorize(path,context);return edit(original,context,v -> { if (v.token("remap")!=null) throw new IllegalArgumentException("Map already exists or has an unsupported type");return v.enableObject("remap"); });
    }
    public static byte[] addMapping(String path,byte[] original,Context context,String source,String target) { authorize(path,context);source(source);target(target);return edit(original,context,v -> v.objectEdit("remap",m -> m.stringMapAdd(source,target),false)); }
    public static byte[] setMapping(String path,byte[] original,Context context,String oldSource,String nextSource,String target) {
        authorize(path,context);if (oldSource==null || nextSource==null) throw new IllegalArgumentException("Select a source key");if (!oldSource.equals(nextSource)) source(nextSource);target(target);
        return edit(original,context,v -> v.objectEdit("remap",m -> {
            m.stringProperties();String previous=m.stringToken(oldSource);if (previous==null) throw new IllegalArgumentException("Source key is absent");
            byte[] renamed=m.stringMapRename(oldSource,nextSource);if (previous.equals(target)) return renamed;
            return new LosslessJson(renamed).stringValue(nextSource,target);
        },false));
    }
    public static byte[] removeMapping(String path,byte[] original,Context context,String source) { authorize(path,context);return edit(original,context,v -> v.objectEdit("remap",m -> m.stringMapRemove(source),false)); }
}
