package dev.irisvisualizer.editor;

import java.io.IOException;
import java.util.List;
import java.util.Properties;

/** Root biome/region objects share native anchor and alignment controls. */
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
    private static void authorize(String path,int owner) {
        if (!FloatingObjectPlacements.root(owner)) throw new IllegalArgumentException("Select a root object collection");
        FloatingObjectPlacements.authorize(path,owner,"objects");
    }
    public static byte[] setAnchor(String path,byte[] original,int placement,String choice) { return setAnchor(path,original,FloatingObjectPlacements.BIOME_ROOT,placement,choice); }
    public static byte[] setAnchor(String path,byte[] original,int owner,int placement,String choice) {
        authorize(path,owner);if (choice==null || !anchors().contains(choice)) throw new IllegalArgumentException("Select a native cave anchor");
        return FloatingObjectPlacements.editPlacement(original,owner,"objects",placement,p -> p.token("caveAnchorMode")==null ? p.enableStringPath("caveAnchorMode",choice) : p.stringValue("caveAnchorMode",choice));
    }
    public static byte[] enableBottom(String path,byte[] original,int placement) { return enableBottom(path,original,FloatingObjectPlacements.BIOME_ROOT,placement); }
    public static byte[] enableBottom(String path,byte[] original,int owner,int placement) {
        authorize(path,owner);
        return FloatingObjectPlacements.editPlacement(original,owner,"objects",placement,p -> p.enable("bottom",DATA.getProperty("bottom.default")));
    }
    public static byte[] setBottom(String path,byte[] original,int placement,boolean value) { return setBottom(path,original,FloatingObjectPlacements.BIOME_ROOT,placement,value); }
    public static byte[] setBottom(String path,byte[] original,int owner,int placement,boolean value) {
        authorize(path,owner);
        return FloatingObjectPlacements.editPlacement(original,owner,"objects",placement,p -> p.bool("bottom",value));
    }
}
