package dev.irisvisualizer.editor;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.function.Function;

/** Dimension-owned bounded configuration editing; no drainage planning or generation is initialized. */
public final class HydrologyFields {
    public static final List<String> ROOT_FIELDS = List.of("hydrology");
    public static final List<String> HYDROLOGY_FIELDS = List.of("rivers");
    public static final List<String> RIVER_FIELDS = List.of("enabled", "routing", "geometry", "surface", "underground", "grottos", "profiles");
    public static final List<String> ROUTING_FIELDS = List.of("tileSize", "sampleSpacing", "maximumRouteLength", "minimumSurfaceCourseLength", "minimumUndergroundCourseLength", "maximumOutletsPerTile", "maximumCoastalOutletsPerTile", "oceanOutlets", "valleyPreference", "uphillPenalty", "slopePenalty", "confluenceAttraction", "lengthPreference", "tributaries");
    private static final Properties DEFINITIONS = new Properties();
    static {
        try (var input = HydrologyFields.class.getResourceAsStream("/hydrology-fields.properties")) {
            if (input==null) throw new IOException("Missing pinned hydrology definitions");
            DEFINITIONS.load(input);
            for (String field : List.of("hydrology","rivers","routing")) if (!"{}".equals(DEFINITIONS.getProperty(field+".default"))) throw new IOException("Unsupported hydrology object defaults");
            if (!"false".equals(DEFINITIONS.getProperty("rivers.enabled.default"))) throw new IOException("Unsupported river planning default");
            for (String field : ROUTING_FIELDS) if (!List.of("int","double","boolean").contains(DEFINITIONS.getProperty("routing."+field+".type", ""))) throw new IOException("Missing routing scalar type");
        } catch (IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
    private static void authorize(String path) {
        if (!DimensionFields.dimension(path)) throw new IllegalArgumentException("Physical hydrology editing is dimension-only");
    }
    private static void authorize(String path, String field) {
        authorize(path); if (!ROUTING_FIELDS.contains(field)) throw new IllegalArgumentException("Routing field is read-only");
    }
    public static String description(String object) { return DEFINITIONS.getProperty(object+".description", ""); }
    public static String enabledDescription() { return DEFINITIONS.getProperty("rivers.enabled.description", ""); }
    public static String routingHelp(String field) { return DEFINITIONS.getProperty("routing."+field+".description", ""); }
    public static boolean routingBoolean(String field) { return "boolean".equals(DEFINITIONS.getProperty("routing."+field+".type")); }
    public static String routingDefault(String field) {
        if (!ROUTING_FIELDS.contains(field)) throw new IllegalArgumentException("Routing field is read-only");
        String token=DEFINITIONS.getProperty("routing."+field+".default");
        return routingBoolean(field) ? token : new BigDecimal(token).stripTrailingZeros().toPlainString();
    }
    public static String constraints(String field) {
        String prefix="routing."+field, min=DEFINITIONS.getProperty(prefix+".minimum"), max=DEFINITIONS.getProperty(prefix+".maximum");
        return ("int".equals(DEFINITIONS.getProperty(prefix+".type")) ? "Signed 32-bit integer" : "Finite number")+(min==null ? "" : ", minimum "+min)+(max==null ? "" : ", maximum "+max);
    }
    public static String draftPrefix() { return "hydrology#rivers.routing."; }
    private static byte[] createAbsent(LosslessJson document, String field) {
        if (document.token(field)!=null) throw new IllegalArgumentException("Hydrology object is already present or unsupported");
        return document.enableObject(field);
    }
    public static byte[] enableHydrology(String path, byte[] original) { authorize(path); return createAbsent(new LosslessJson(original),"hydrology"); }
    public static byte[] enableRivers(String path, byte[] original) {
        authorize(path); return new LosslessJson(original).objectEdit("hydrology",hydro -> createAbsent(hydro,"rivers"),false);
    }
    public static byte[] enableRouting(String path, byte[] original) { authorize(path); return editRivers(original,rivers -> createAbsent(rivers,"routing")); }
    public static byte[] setEnabled(String path, byte[] original, boolean value) {
        authorize(path); return editRivers(original,rivers -> rivers.token("enabled")==null ? rivers.enable("enabled",Boolean.toString(value)) : rivers.bool("enabled",value));
    }
    static byte[] editRivers(byte[] original, Function<LosslessJson,byte[]> operation) {
        return new LosslessJson(original).objectEdit("hydrology",hydro -> hydro.objectEdit("rivers",operation,false),false);
    }
    static byte[] editRouting(byte[] original, Function<LosslessJson,byte[]> operation) { return editRivers(original,rivers -> rivers.objectEdit("routing",operation,false)); }
    public static byte[] enableScalar(String path, byte[] original, String field) {
        authorize(path,field); return editRouting(original,route -> route.enable(field,routingDefault(field)));
    }
    public static byte[] setRoutingBoolean(String path, byte[] original, String field, boolean value) {
        authorize(path,field);
        if (!routingBoolean(field)) throw new IllegalArgumentException("Numeric routing field requires a number");
        return editRouting(original,route -> route.token(field)==null ? route.enable(field,Boolean.toString(value)) : route.bool(field,value));
    }
    public static byte[] setRoutingText(String path, byte[] original, String field, String input) {
        authorize(path,field);
        if (routingBoolean(field) || input==null) throw new IllegalArgumentException("Enter a numeric routing value");
        double value;
        String prefix="routing."+field;
        if ("int".equals(DEFINITIONS.getProperty(prefix+".type"))) {
            try { value=new BigDecimal(input).intValueExact(); }
            catch (NumberFormatException | ArithmeticException invalid) { throw new IllegalArgumentException("Enter an exact signed 32-bit integer",invalid); }
        } else value=Double.parseDouble(input);
        String min=DEFINITIONS.getProperty(prefix+".minimum"), max=DEFINITIONS.getProperty(prefix+".maximum");
        if (!Double.isFinite(value) || min!=null && value<Double.parseDouble(min) || max!=null && value>Double.parseDouble(max)) throw new IllegalArgumentException("Value violates pinned routing constraints");
        return editRouting(original,route -> route.number(field,value));
    }
    private static LosslessJson child(LosslessJson parent, String field) {
        String token=parent.token(field); return token==null ? null : new LosslessJson(token.getBytes(StandardCharsets.UTF_8));
    }
    static LosslessJson hydrology(LosslessJson document) { return child(document,"hydrology"); }
    static LosslessJson rivers(LosslessJson hydrology) { return child(hydrology,"rivers"); }
    static LosslessJson routing(LosslessJson rivers) { return child(rivers,"routing"); }
    static List<String> readOnlyPaths(LosslessJson document) {
        var paths=new ArrayList<String>();
        try {
            var hydro=hydrology(document);
            if (hydro==null) return List.of();
            for (String field : hydro.fields()) {
                if (!field.equals("rivers")) { paths.add("hydrology."+field); continue; }
                try {
                    var rivers=rivers(hydro);
                    for (String riverField : rivers.fields()) {
                        if (riverField.equals("enabled")) continue;
                        if (riverField.equals("profiles")) {
                            for (String path : RiverProfiles.readOnlyPaths(rivers)) paths.add("hydrology.rivers."+path);
                            continue;
                        }
                        if (GrottoFields.OBJECT_FIELDS.contains(riverField)) {
                            for (String path : GrottoFields.readOnlyPaths(rivers)) paths.add("hydrology.rivers."+path);
                            continue;
                        }
                        if (UndergroundRiverFields.OBJECT_FIELDS.contains(riverField)) {
                            for (String path : UndergroundRiverFields.readOnlyPaths(rivers)) paths.add("hydrology.rivers."+path);
                            continue;
                        }
                        if (SurfaceRiverFields.OBJECT_FIELDS.contains(riverField)) {
                            for (String path : SurfaceRiverFields.readOnlyPaths(rivers)) paths.add("hydrology.rivers."+path);
                            continue;
                        }
                        if (RiverGeometryFields.OBJECT_FIELDS.contains(riverField)) {
                            for (String path : RiverGeometryFields.readOnlyPaths(rivers)) paths.add("hydrology.rivers."+path);
                            continue;
                        }
                        if (!riverField.equals("routing")) { paths.add("hydrology.rivers."+riverField); continue; }
                        try {
                            var route=routing(rivers);
                            for (String member : route.fields()) {
                                if (ROUTING_FIELDS.contains(member)) continue;
                                if (RoutingExtras.OBJECT_FIELDS.contains(member) || RoutingExtras.LIST_FIELDS.contains(member)) {
                                    for (String path : RoutingExtras.readOnlyPaths(route,member)) paths.add("hydrology.rivers.routing."+path);
                                } else paths.add("hydrology.rivers.routing."+member);
                            }
                        } catch (IllegalArgumentException mismatch) { paths.add("hydrology.rivers.routing"); }
                    }
                } catch (IllegalArgumentException mismatch) { paths.add("hydrology.rivers"); }
            }
        } catch (IllegalArgumentException mismatch) { paths.add("hydrology"); }
        return List.copyOf(paths);
    }
}
