package dev.irisvisualizer.editor;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.function.Function;

/** Independent underground source configuration; no river planning or cave connection is executed. */
public final class UndergroundRiverFields {
    public static final List<String> OBJECT_FIELDS = List.of("underground");
    public static final List<String> ROOT_FIELDS = List.of("enabled", "sources", "fluidLevel", "channelWidth", "depth", "headroom", "connectToExistingCaves", "mouthLevelingDistance", "tributaries", "minimumRockCover", "minimumFloorCover", "wideningSources");
    public static final List<String> ROOT_SCALARS = List.of("enabled", "connectToExistingCaves", "mouthLevelingDistance", "tributaries", "minimumRockCover", "minimumFloorCover", "wideningSources");
    public static final List<String> SOURCE_FIELDS = List.of("density", "minimumPerTile", "minimumSpacing");
    private static final Properties DATA=new Properties();
    static {
        try (var input=UndergroundRiverFields.class.getResourceAsStream("/underground-river-fields.properties")) {
            if (input==null) throw new IOException("Missing pinned underground-river definitions");
            DATA.load(input);
            if (!"{}".equals(DATA.getProperty("underground.default")) || !"{}".equals(DATA.getProperty("sources.default"))) throw new IOException("Unsupported underground parent defaults");
            for (boolean sources : List.of(false,true)) for (String field : fields(sources))
                if (!List.of("int","double","boolean").contains(DATA.getProperty(prefix(sources,field)+".type", ""))) throw new IOException("Missing underground primitive type");
        } catch (IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
    private static void authorize(String path) { if (!DimensionFields.dimension(path)) throw new IllegalArgumentException("Underground rivers are dimension-only"); }
    private static void authorize(String path,boolean sources,String field) { authorize(path); if (!fields(sources).contains(field)) throw new IllegalArgumentException("Underground-river field is read-only"); }
    public static List<String> fields(boolean sources) { return sources ? SOURCE_FIELDS : ROOT_SCALARS; }
    private static String prefix(boolean sources,String field) { return (sources ? "sources." : "root.")+field; }
    public static String description(String object) { return DATA.getProperty(object+".description",""); }
    public static String help(boolean sources,String field) { return DATA.getProperty(prefix(sources,field)+".description",""); }
    public static boolean booleanField(boolean sources,String field) { return "boolean".equals(DATA.getProperty(prefix(sources,field)+".type")); }
    public static boolean integer(boolean sources,String field) { return "int".equals(DATA.getProperty(prefix(sources,field)+".type")); }
    public static String defaultToken(boolean sources,String field) {
        if (!fields(sources).contains(field)) throw new IllegalArgumentException("Underground-river field is read-only");
        String token=DATA.getProperty(prefix(sources,field)+".default"); return booleanField(sources,field) ? token : new BigDecimal(token).stripTrailingZeros().toPlainString();
    }
    public static String constraints(boolean sources,String field) { String p=prefix(sources,field); return (integer(sources,field) ? "Signed 32-bit integer" : "Finite number")+", minimum "+DATA.getProperty(p+".minimum")+", maximum "+DATA.getProperty(p+".maximum"); }
    public static String draftPrefix(boolean sources) { return "hydrology#rivers.underground."+(sources ? "sources." : ""); }
    static byte[] editUnderground(byte[] original,Function<LosslessJson,byte[]> action) { return HydrologyFields.editRivers(original,rivers -> rivers.objectEdit("underground",action,false)); }
    private static byte[] edit(byte[] original,boolean sources,Function<LosslessJson,byte[]> action) { return editUnderground(original,underground -> sources ? underground.objectEdit("sources",action,false) : action.apply(underground)); }
    private static byte[] create(LosslessJson object,String field) {
        if (object.token(field)!=null) throw new IllegalArgumentException("Underground object is already present or unsupported");
        return object.enableObject(field);
    }
    public static byte[] enableUnderground(String path,byte[] original) { authorize(path); return HydrologyFields.editRivers(original,rivers -> create(rivers,"underground")); }
    public static byte[] enableSources(String path,byte[] original) { authorize(path); return editUnderground(original,underground -> create(underground,"sources")); }
    public static byte[] enableScalar(String path,byte[] original,boolean sources,String field) { authorize(path,sources,field); return edit(original,sources,object -> object.enable(field,defaultToken(sources,field))); }
    public static byte[] setBoolean(String path,byte[] original,boolean sources,String field,boolean value) {
        authorize(path,sources,field); if (!booleanField(sources,field)) throw new IllegalArgumentException("This underground field is numeric");
        return edit(original,sources,object -> object.token(field)==null ? object.enable(field,Boolean.toString(value)) : object.bool(field,value));
    }
    public static byte[] setText(String path,byte[] original,boolean sources,String field,String input) {
        authorize(path,sources,field); if (booleanField(sources,field) || input==null) throw new IllegalArgumentException("Enter a numeric underground-river value");
        double value;
        if (integer(sources,field)) {
            try { value=new BigDecimal(input).intValueExact(); }
            catch (NumberFormatException | ArithmeticException invalid) { throw new IllegalArgumentException("Enter an exact signed 32-bit integer",invalid); }
        } else value=Double.parseDouble(input);
        String p=prefix(sources,field);
        if (!Double.isFinite(value) || value<Double.parseDouble(DATA.getProperty(p+".minimum")) || value>Double.parseDouble(DATA.getProperty(p+".maximum"))) throw new IllegalArgumentException("Value violates pinned underground bounds");
        return edit(original,sources,object -> object.number(field,value));
    }
    private static LosslessJson child(LosslessJson object,String field) { String token=object.token(field); return token==null ? null : new LosslessJson(token.getBytes(StandardCharsets.UTF_8)); }
    static LosslessJson underground(LosslessJson rivers) { return child(rivers,"underground"); }
    static LosslessJson sources(LosslessJson underground) { return child(underground,"sources"); }
    static List<String> readOnlyPaths(LosslessJson rivers) {
        var paths=new ArrayList<String>();
        try {
            var underground=underground(rivers); if (underground==null) return List.of();
            for (String field : underground.fields()) {
                if (ROOT_SCALARS.contains(field)) continue;
                if (RiverChannelRanges.UNDERGROUND_FIELDS.contains(field)) {
                    for (String path : RiverChannelRanges.readOnlyPaths(underground,RiverChannelRanges.undergroundKey(field))) paths.add("underground."+path);
                } else if (field.equals("sources")) {
                    try {
                        var sources=sources(underground);
                        for (String member : sources.fields()) if (!SOURCE_FIELDS.contains(member)) paths.add("underground.sources."+member);
                    } catch (IllegalArgumentException mismatch) { paths.add("underground.sources"); }
                } else paths.add("underground."+field);
            }
        } catch (IllegalArgumentException mismatch) { paths.add("underground"); }
        return List.copyOf(paths);
    }
}
