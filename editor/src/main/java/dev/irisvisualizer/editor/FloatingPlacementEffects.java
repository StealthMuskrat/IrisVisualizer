package dev.irisvisualizer.editor;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Properties;

/** Bounded excavation/water settings; native block placement is never executed here. */
public final class FloatingPlacementEffects {
    public static final List<String> EDITABLE = List.of("bore", "boreExtendMinY", "boreExtendMaxY", "smartBore", "meld", "waterloggable", "underwater", "onwater", "fromBottom");
    public static final List<String> FLAGS = List.of("bore", "smartBore", "meld", "waterloggable", "underwater", "onwater", "fromBottom");
    public static final List<String> SCALARS = List.of("boreExtendMinY", "boreExtendMaxY");
    public static final List<String> ALL_CONTEXT_FIELDS = List.of("bore", "boreExtendMinY", "boreExtendMaxY", "smartBore", "meld", "waterloggable");
    private static final Properties DATA=new Properties();
    static {
        try (var input=FloatingPlacementEffects.class.getResourceAsStream("/floating-placement-effects.properties")) {
            if (input==null) throw new IOException("Missing pinned placement effects");DATA.load(input);
            if (!String.join(",",EDITABLE).equals(DATA.getProperty("fields")) || !String.join(",",ALL_CONTEXT_FIELDS).equals(DATA.getProperty("allContextFields")) || !"implicitFalse".equals(DATA.getProperty("fromBottom.policy"))) throw new IOException("Unsupported placement effects policy");
        } catch (IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
    public record Context(int entry,String list,int placement) {
        public Context { if (!FloatingObjectPlacements.validContext(entry,list,placement)) throw new IllegalArgumentException("Select a biome object placement"); }
        public String prefix() { return FloatingObjectPlacements.placementPrefix(entry,list,placement)+"effects."; }
    }
    private static void field(String value,List<String> fields) { if (value==null || !fields.contains(value)) throw new IllegalArgumentException("Unsupported placement effect"); }
    static boolean editable(String list,String field) { return field!=null && EDITABLE.contains(field) && (ALL_CONTEXT_FIELDS.contains(field) || FloatingObjectPlacements.configured(list)); }
    private static void authorize(String path,Context context,String field) { field(field,EDITABLE);if (!BiomeFields.biome(path) || !editable(context.list(),field)) throw new IllegalArgumentException("This placement effect stays read-only in anchored collections"); }
    public static String help(String field) { field(field,EDITABLE);return DATA.getProperty(field+".description",""); }
    public static String contextHelp(String field) { field(field,EDITABLE);return DATA.getProperty(field+".contextHelp",""); }
    public static String contextHelp(String list,String field) {
        if ("objects".equals(list) && "onwater".equals(field)) return "Bypasses eligible native underwater and surface-support guards. Height queries still depend on mode and underwater; cave anchors keep their own selection rules. This flag alone does not guarantee a fluid-level placement.";
        return contextHelp(field)+("objects".equals(list) && "fromBottom".equals(field) ? " Native floor alignment takes precedence." : "");
    }
    public static String defaultToken(String field) { field(field,EDITABLE);return DATA.getProperty(field+".default"); }
    public static String constraints(String field) { field(field,SCALARS);return "Exact integer, minimum "+DATA.getProperty(field+".minimum")+", maximum "+DATA.getProperty(field+".maximum"); }
    public static byte[] enableFlag(String path,byte[] original,Context context,String field) { authorize(path,context,field);field(field,FLAGS);return FloatingObjectPlacements.editPlacement(original,context.entry(),context.list(),context.placement(),p -> p.enable(field,defaultToken(field))); }
    public static byte[] setFlag(String path,byte[] original,Context context,String field,boolean value) { authorize(path,context,field);field(field,FLAGS);return FloatingObjectPlacements.editPlacement(original,context.entry(),context.list(),context.placement(),p -> p.bool(field,value)); }
    public static byte[] enableScalar(String path,byte[] original,Context context,String field) { authorize(path,context,field);field(field,SCALARS);return FloatingObjectPlacements.editPlacement(original,context.entry(),context.list(),context.placement(),p -> p.enable(field,defaultToken(field))); }
    public static byte[] setScalar(String path,byte[] original,Context context,String field,String input) {
        authorize(path,context,field);field(field,SCALARS);int value;
        try { value=new BigDecimal(input).intValueExact(); } catch (NumberFormatException | ArithmeticException | NullPointerException invalid) { throw new IllegalArgumentException("Enter an exact signed 32-bit excavation extension",invalid); }
        if (value<Integer.parseInt(DATA.getProperty(field+".minimum")) || value>Integer.parseInt(DATA.getProperty(field+".maximum"))) throw new IllegalArgumentException("Excavation extension violates pinned bounds");
        return FloatingObjectPlacements.editPlacement(original,context.entry(),context.list(),context.placement(),p -> p.number(field,value));
    }
}
