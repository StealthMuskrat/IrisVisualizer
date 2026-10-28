package dev.irisvisualizer.editor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/** Virtual height queries and current native force aliases; no native noise is executed. */
public final class FloatingPlacementOverrides {
    public static final List<String> EDITABLE = List.of("heightmap", "forcePlace");
    public static final List<String> FORCE_KEYS = List.of("forcePlace", "force");
    private static final Properties DATA=new Properties();
    static {
        try (var input=FloatingPlacementOverrides.class.getResourceAsStream("/floating-placement-overrides.properties")) {
            if (input==null) throw new IOException("Missing pinned placement overrides");DATA.load(input);
            if (!String.join(",",FORCE_KEYS).equals(DATA.getProperty("force.keys")) || !"singleStoredBoolean".equals(DATA.getProperty("force.policy")) || !"nullUnderlyingQueries".equals(DATA.getProperty("heightmap.policy"))) throw new IOException("Unsupported placement override policy");
        } catch (IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
    public record Context(int entry,String list,int placement) {
        public Context { if (!FloatingObjectPlacements.validContext(entry,list,placement)) throw new IllegalArgumentException("Select a biome object placement"); }
        public String heightmapPrefix() { return FloatingObjectPlacements.placementPrefix(entry,list,placement)+"heightmap."; }
        public NoiseFields.Target noiseTarget() { return NoiseFields.Target.heightmap(this); }
    }
    private static void authorize(String path,Context context) { FloatingObjectPlacements.authorize(path,context.entry(),context.list()); }
    private static void forceContext(String path,Context context) { authorize(path,context);if (!FloatingObjectPlacements.configured(context.list())) throw new IllegalArgumentException("Anchored force placement stays read-only"); }
    public static String heightmapHelp() { return DATA.getProperty("heightmap.description",""); }
    static LosslessJson heightmap(LosslessJson placement) { String token=placement.token("heightmap");return token==null || token.equals("null") ? null : new LosslessJson(token.getBytes(StandardCharsets.UTF_8)); }
    static byte[] editHeightmap(byte[] original,Context context,java.util.function.Function<LosslessJson,byte[]> operation) { return FloatingObjectPlacements.editPlacement(original,context.entry(),context.list(),context.placement(),p -> p.objectEdit("heightmap",operation,false)); }
    public static byte[] enableHeightmap(String path,byte[] original,Context context) { authorize(path,context);return FloatingObjectPlacements.editPlacement(original,context.entry(),context.list(),context.placement(),p -> p.enableObject("heightmap")); }
    public static byte[] restoreHeightmap(String path,byte[] original,Context context) { authorize(path,context);return FloatingObjectPlacements.editPlacement(original,context.entry(),context.list(),context.placement(),p -> p.nullObject("heightmap")); }
    static String forceKey(LosslessJson placement) {
        boolean canonical=placement.token(FORCE_KEYS.getFirst())!=null,alias=placement.token(FORCE_KEYS.getLast())!=null;
        if (canonical && alias) throw new IllegalArgumentException("Both native force keys are present; toggle is read-only");
        return canonical ? FORCE_KEYS.getFirst() : alias ? FORCE_KEYS.getLast() : null;
    }
    public static byte[] enableForce(String path,byte[] original,Context context) {
        forceContext(path,context);return FloatingObjectPlacements.editPlacement(original,context.entry(),context.list(),context.placement(),p -> { if (forceKey(p)!=null) throw new IllegalArgumentException("A native force key is already stored");return p.enable(FORCE_KEYS.getFirst(),DATA.getProperty("force.default")); });
    }
    public static byte[] setForce(String path,byte[] original,Context context,boolean value) {
        forceContext(path,context);return FloatingObjectPlacements.editPlacement(original,context.entry(),context.list(),context.placement(),p -> { String key=forceKey(p);if (key==null) throw new IllegalArgumentException("Enable the absent force flag first");return p.bool(key,value); });
    }
    static boolean handled(LosslessJson placement,String list,String field) {
        if (field.equals("heightmap")) return true;
        if (!FORCE_KEYS.contains(field) || !FloatingObjectPlacements.configured(list)) return false;
        try { String key=forceKey(placement);return field.equals(key) && List.of("true","false").contains(placement.token(key)); } catch (IllegalArgumentException unsupported) { return false; }
    }
    static List<String> readOnlyHeightmapPaths(LosslessJson placement) {
        try { var noise=heightmap(placement);return noise==null ? List.of() : NoiseFractures.readOnlyPaths(noise).stream().map(path -> "heightmap."+path).toList(); }
        catch (IllegalArgumentException unsupported) { return List.of("heightmap"); }
    }
}
