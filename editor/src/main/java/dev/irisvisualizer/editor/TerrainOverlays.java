package dev.irisvisualizer.editor;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;
import java.util.function.Function;

/** Ordered additive terrain overlay data; native noise and height calculations are not run. */
public final class TerrainOverlays {
    public static final List<String> LIST_FIELDS = List.of("overlayNoise");
    public static final List<String> EDITABLE = List.of("generator", "min", "max");
    public static final int MAXIMUM_ENTRIES=64;
    private static final Properties DATA=new Properties();
    static {
        try (var input=TerrainOverlays.class.getResourceAsStream("/dimension-terrain-fields.properties")) {
            if (input==null) throw new IOException("Missing pinned terrain controls");DATA.load(input);
            if (!"sumFittedOverlays".equals(DATA.getProperty("overlay.policy"))) throw new IOException("Unsupported overlay policy");
        } catch (IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
    private static void authorize(String path) { if (!"dimensions".equals(ResourceTypes.base(path))) throw new IllegalArgumentException("Terrain overlays are dimension-only"); }
    public static String prefix() { return "terrainOverlay#"; }
    public static String prefix(int index) { if (index<0) throw new IllegalArgumentException("Select an overlay");return prefix()+index+"."; }
    public static String help(String field) { if (!EDITABLE.contains(field)) throw new IllegalArgumentException("Unsupported overlay field");return DATA.getProperty("overlay."+field+".description"); }
    public static String styleDefault() { return DATA.getProperty("overlay.generator.default"); }
    static List<LosslessJson> entries(LosslessJson root) { return root.token("overlayNoise")==null ? null : root.objectArray("overlayNoise"); }
    static byte[] edit(byte[] original,int index,Function<LosslessJson,byte[]> operation) {
        var root=new LosslessJson(original);var entries=entries(root);if (entries==null || entries.size()>MAXIMUM_ENTRIES) throw new IllegalArgumentException("Select an inline overlay list within the editor limit");return root.objectArrayEdit("overlayNoise",index,operation);
    }
    public static byte[] change(String path,byte[] original,RiverProfiles.Action action,int index) {
        authorize(path);if (action==null) throw new IllegalArgumentException("Choose an overlay action");var root=new LosslessJson(original);var entries=entries(root);
        if (entries==null) { if (action!=RiverProfiles.Action.ADD) throw new IllegalArgumentException("No overlay list");root=new LosslessJson(root.enableArray("overlayNoise"));entries=entries(root); }
        if (entries.size()>MAXIMUM_ENTRIES || (action==RiverProfiles.Action.ADD || action==RiverProfiles.Action.DUPLICATE) && entries.size()>=MAXIMUM_ENTRIES) throw new IllegalArgumentException("Overlay lists have a 64-entry editor limit");
        if (action==RiverProfiles.Action.ADD) {
            byte[] seed=new LosslessJson("{}".getBytes(StandardCharsets.UTF_8)).enableStringPath("generator.style",styleDefault());
            for (String field : List.of("min","max")) seed=new LosslessJson(seed).enable(field,DATA.getProperty("overlay."+field+".default"));
            return root.objectArrayInsert("overlayNoise",entries.size(),seed);
        }
        if (index<0 || index>=entries.size()) throw new IllegalArgumentException("Select an existing overlay");
        if (action==RiverProfiles.Action.REMOVE && entries.size()==1) return root.removeField("overlayNoise");
        return BiomeLayers.changeLayers(root,"overlayNoise",action,index);
    }
    static String bound(LosslessJson entry,String field) {
        if (!List.of("min","max").contains(field)) throw new IllegalArgumentException("Unsupported overlay bound");
        String token=entry.token(field);if (token==null) return DATA.getProperty("overlay."+field+".default");
        try { if (!LosslessJson.numericToken(token)) throw new NumberFormatException();new BigDecimal(token).intValueExact();return token; }
        catch (NumberFormatException | ArithmeticException invalid) { throw new IllegalArgumentException("Stored overlay bounds must be exact signed 32-bit integers",invalid); }
    }
    private static int input(String field,String input) {
        try { int value=new BigDecimal(input).intValueExact();if (value<Integer.parseInt(DATA.getProperty("overlay."+field+".minimum")) || value>Integer.parseInt(DATA.getProperty("overlay."+field+".maximum"))) throw new IllegalArgumentException("Overlay bounds must be -2032..2032");return value; }
        catch (NumberFormatException | ArithmeticException | NullPointerException invalid) { throw new IllegalArgumentException("Enter exact signed 32-bit overlay bounds",invalid); }
    }
    private static byte[] write(LosslessJson entry,String field,int value) {
        String old=entry.token(field);if (old==null) return entry.enable(field,Integer.toString(value));
        return new BigDecimal(old).intValueExact()==value ? entry.sourceBytes() : entry.integer(field,value);
    }
    public static byte[] setBounds(String path,byte[] original,int index,String min,String max) {
        authorize(path);int low=input("min",min),high=input("max",max);
        return edit(original,index,e -> { bound(e,"min");bound(e,"max");return write(new LosslessJson(write(e,"min",low)),"max",high); });
    }
    public static int styleIndex(String path) { if (path==null || !path.matches("overlayNoise\\.(0|[1-9][0-9]*)\\.generator")) return -1;try { return Integer.parseInt(path.split("\\.")[1]); } catch (NumberFormatException invalid) { return -1; } }
    public static NoiseStyleFields.Target styleTarget(int index) { prefix(index);return new NoiseStyleFields.Target("dimensions","overlayNoise."+index+".generator",null); }
}
