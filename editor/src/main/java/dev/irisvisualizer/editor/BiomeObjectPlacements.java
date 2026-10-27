package dev.irisvisualizer.editor;

import java.io.IOException;
import java.util.List;
import java.util.Properties;

/** Root biome objects use the same native placement type, with their own dispatch and cave anchors. */
public final class BiomeObjectPlacements {
    public static final List<String> LIST_FIELDS = List.of("objects");
    public static final List<String> EDITABLE = List.of("caveAnchorMode", "bottom");
    private static final Properties DATA=new Properties();
    static {
        try (var input=BiomeObjectPlacements.class.getResourceAsStream("/biome-object-fields.properties")) {
            if (input==null) throw new IOException("Missing pinned biome object definitions");DATA.load(input);
            if (!"beforeDensityLoop".equals(DATA.getProperty("chance.policy")) || !"absentOnly".equals(DATA.getProperty("objects.enablePolicy")) || !anchors().contains(defaultAnchor())) throw new IOException("Unsupported biome object policy");
        } catch (IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
    public static String description() { return DATA.getProperty("objects.description"); }
    public static String defaultAnchor() { return DATA.getProperty("caveAnchorMode.default"); }
    public static List<String> anchors() { return List.of(DATA.getProperty("caveAnchorMode.choices").split(",")); }
    public static String anchorHelp(String choice) { if (!anchors().contains(choice)) throw new IllegalArgumentException("Select a native cave anchor");return DATA.getProperty("caveAnchorMode."+choice+".description",""); }
    public static String bottomHelp() { return "Bottom alignment affects native placement at an explicit non-negative Y, except raw structure pieces and floor-aligned calls. Surface placements pass Y=-1, so this flag does not change their alignment. It differs from fromBottom and is not compatible with X/Z rotation."; }
    public static byte[] setAnchor(String path,byte[] original,int placement,String choice) {
        if (!BiomeFields.biome(path) || choice==null || !anchors().contains(choice)) throw new IllegalArgumentException("Select a native biome cave anchor");
        return FloatingObjectPlacements.editPlacement(original,-1,"objects",placement,p -> p.token("caveAnchorMode")==null ? p.enableStringPath("caveAnchorMode",choice) : p.stringValue("caveAnchorMode",choice));
    }
    public static byte[] enableBottom(String path,byte[] original,int placement) {
        if (!BiomeFields.biome(path)) throw new IllegalArgumentException("Biome object placements are biome-only");
        return FloatingObjectPlacements.editPlacement(original,-1,"objects",placement,p -> p.enable("bottom",DATA.getProperty("bottom.default")));
    }
    public static byte[] setBottom(String path,byte[] original,int placement,boolean value) {
        if (!BiomeFields.biome(path)) throw new IllegalArgumentException("Biome object placements are biome-only");
        return FloatingObjectPlacements.editPlacement(original,-1,"objects",placement,p -> p.bool("bottom",value));
    }
}
