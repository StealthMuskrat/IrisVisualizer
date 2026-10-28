package dev.irisvisualizer.editor;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Typed image-map definition authoring only. No PNG decoding, map compilation or sampling occurs. */
public final class ImageMapDefinitions {
    public static final List<String> EDITABLE = List.of("source", "type", "blocksPerPixel", "origin", "sourceOrigin", "rotation", "mirrorX", "mirrorZ", "sampling", "outOfBounds", "fallbackValue", "fallbackTarget", "alpha", "minimumHeight", "maximumHeight", "verticalOffset", "clamp", "inverted", "curveExponent", "smoothingRadius", "threshold", "falloff", "colorTolerance", "unknownColor", "colors");
    public static final List<String> ENUMS = List.of("type", "rotation", "sampling", "outOfBounds", "alpha", "unknownColor");
    public static final List<String> NUMBERS = List.of("blocksPerPixel", "fallbackValue", "minimumHeight", "maximumHeight", "verticalOffset", "curveExponent", "smoothingRadius", "threshold", "falloff", "colorTolerance");
    public static final List<String> TOGGLES = List.of("mirrorX", "mirrorZ", "clamp", "inverted");
    public static final List<String> ORIGINS = List.of("origin", "sourceOrigin");
    public static final List<String> AXES = List.of("x", "z");
    public static final int MAXIMUM_COLORS=64;
    private static final Properties DATA=new Properties();
    static {
        try (var input=ImageMapDefinitions.class.getResourceAsStream("/image-map-definition-fields.properties")) {
            if (input==null) throw new IOException("Missing pinned image-map definition fields");DATA.load(input);
            if (!"pngOnly".equals(DATA.getProperty("source.policy")) || !"orderedFiniteHeights".equals(DATA.getProperty("height.policy")) || !"exactRawRgbUnique".equals(DATA.getProperty("legend.policy"))) throw new IOException("Unsupported image-map definition policy");
        } catch (IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
    public static boolean supports(String path) { return "image-maps".equals(ResourceTypes.base(path)); }
    private static void authorize(String path) { if (!supports(path)) throw new IllegalArgumentException("Definition controls require an image-maps JSON file"); }
    private static void field(String field) { if (field==null || !EDITABLE.contains(field)) throw new IllegalArgumentException("Unsupported image-map field"); }
    public static String prefix() { return "imageMapDefinition#"; }
    public static String help(String field) { field(field);return DATA.getProperty(field+".description",""); }
    public static String defaultToken(String field) { field(field);return DATA.getProperty(field+".default"); }
    public static List<String> choices(String field) { if (!ENUMS.contains(field)) throw new IllegalArgumentException("Unsupported image-map choice");return List.of(DATA.getProperty(field+".choices").split(",")); }
    static String text(LosslessJson root,String field) { if (!List.of("source","fallbackTarget").contains(field)) throw new IllegalArgumentException("Unsupported image-map text field");return root.token(field)==null ? "" : root.stringToken(field); }
    static String choice(LosslessJson root,String field) { choices(field);return root.token(field)==null ? defaultToken(field) : root.stringToken(field); }
    private static byte[] put(LosslessJson root,String field,String value) { String old=root.stringToken(field);return old==null ? root.enableStringPath(field,value) : old.equals(value) ? root.sourceBytes() : root.stringValue(field,value); }
    public static List<String> sources(PackWorkspace workspace) {
        return workspace.paths().stream().filter(path -> path.startsWith("images/") && path.endsWith(".png") && "images".equals(ResourceTypes.fileFamily(path)))
                .map(path -> path.substring("images/".length(),path.length()-4)).filter(ResourceTypes::referenceKey).sorted().toList();
    }
    public static final class SourceCache {
        private PackWorkspace workspace;private long revision=-1;private List<String> keys;
        public List<String> choices(PackWorkspace current) { Objects.requireNonNull(current);if (current!=workspace || current.resourceRevision()!=revision) { keys=sources(current);workspace=current;revision=current.resourceRevision(); }return keys; }
    }
    private static void source(PackWorkspace workspace,String key) { if (key==null || !sources(workspace).contains(key)) throw new IllegalArgumentException("Choose an exact in-pack lowercase .png file key; bytes, dimensions, format and map compatibility are unchecked"); }
    public static byte[] template(PackWorkspace workspace,String source) { source(workspace,source);return new LosslessJson("{}".getBytes(StandardCharsets.UTF_8)).enableStringPath("source",source); }
    public static byte[] setSource(String path,byte[] original,String key,PackWorkspace workspace) { authorize(path);source(workspace,key);return put(new LosslessJson(original),"source",key); }
    public static byte[] setFallbackTarget(String path,byte[] original,String value) { authorize(path);if (value==null) throw new IllegalArgumentException("Use a string target; empty restores an unset target");return put(new LosslessJson(original),"fallbackTarget",value); }
    public static byte[] setChoice(String path,byte[] original,String field,String value) { authorize(path);if (value==null || !choices(field).contains(value)) throw new IllegalArgumentException("Choose a pinned image-map enum value");return put(new LosslessJson(original),field,value); }
    public static byte[] setToggle(String path,byte[] original,String field,boolean value) { authorize(path);if (!TOGGLES.contains(field)) throw new IllegalArgumentException("Unsupported image-map toggle");var root=new LosslessJson(original);return root.token(field)==null ? root.enable(field,Boolean.toString(value)) : root.bool(field,value); }
    public static String constraints(String field) {
        if (!NUMBERS.contains(field)) throw new IllegalArgumentException("Unsupported numeric field");String low=DATA.getProperty(field+".minimum"),high=DATA.getProperty(field+".maximum");
        return (field.equals("smoothingRadius") ? "Exact signed 32-bit integer" : "Finite number")+(low==null ? "" : "; minimum "+low)+(high==null ? "" : "; maximum "+high);
    }
    private static double number(String field,String input) {
        if (!NUMBERS.contains(field)) throw new IllegalArgumentException("Unsupported numeric field");double value;
        try { var parsed=new BigDecimal(input);value=field.equals("smoothingRadius") ? parsed.intValueExact() : parsed.doubleValue(); }
        catch (NumberFormatException | ArithmeticException | NullPointerException invalid) { throw new IllegalArgumentException("Enter a finite number or exact smoothing integer",invalid); }
        String low=DATA.getProperty(field+".minimum"),high=DATA.getProperty(field+".maximum");
        if (!Double.isFinite(value) || low!=null && value<Double.parseDouble(low) || high!=null && value>Double.parseDouble(high)) throw new IllegalArgumentException(constraints(field));return value;
    }
    static boolean numericSupported(LosslessJson root,String field) {
        if (!NUMBERS.contains(field)) return false;String token=root.token(field);if (token==null) return true;
        try { if (!LosslessJson.numericToken(token)) return false;if (field.equals("smoothingRadius")) new BigDecimal(token).intValueExact();return Double.isFinite(new BigDecimal(token).doubleValue()); } catch (NumberFormatException | ArithmeticException invalid) { return false; }
    }
    private static byte[] writeNumber(LosslessJson root,String field,double value) {
        String token=root.token(field);if (token==null) return root.enable(field,BigDecimal.valueOf(value).stripTrailingZeros().toPlainString());
        if (Double.compare(new BigDecimal(token).doubleValue(),value)==0) return root.sourceBytes();return field.equals("smoothingRadius") ? root.integer(field,(int)value) : root.number(field,value);
    }
    public static byte[] enableScalar(String path,byte[] original,String field) { authorize(path);if (List.of("minimumHeight","maximumHeight").contains(field)) throw new IllegalArgumentException("Apply the height pair together");number(field,defaultToken(field));return new LosslessJson(original).enable(field,defaultToken(field)); }
    public static byte[] setScalar(String path,byte[] original,String field,String input) {
        authorize(path);if (List.of("minimumHeight","maximumHeight").contains(field)) throw new IllegalArgumentException("Apply the height pair together");double value=number(field,input);var root=new LosslessJson(original);
        if (root.token(field)==null || !numericSupported(root,field)) throw new IllegalArgumentException("Enable a supported scalar first");return writeNumber(root,field,value);
    }
    static String height(LosslessJson root,String field) { if (!List.of("minimumHeight","maximumHeight").contains(field) || !numericSupported(root,field)) throw new IllegalArgumentException("Unsupported finite height bound");return root.token(field)==null ? defaultToken(field) : root.token(field); }
    public static byte[] setHeights(String path,byte[] original,String min,String max) {
        authorize(path);double low=number("minimumHeight",min),high=number("maximumHeight",max);if (low>high) throw new IllegalArgumentException("Maximum height must be at least minimum height");var root=new LosslessJson(original);height(root,"minimumHeight");height(root,"maximumHeight");return writeNumber(new LosslessJson(writeNumber(root,"minimumHeight",low)),"maximumHeight",high);
    }
    static LosslessJson origin(LosslessJson root,String field) { if (!ORIGINS.contains(field)) throw new IllegalArgumentException("Unsupported origin");return root.token(field)==null ? null : new LosslessJson(root.token(field).getBytes(StandardCharsets.UTF_8)); }
    static String coordinate(LosslessJson root,String field,String axis) {
        if (!AXES.contains(axis)) throw new IllegalArgumentException("Unsupported origin axis");var origin=origin(root,field);String token=origin==null ? null : origin.token(axis);if (token==null) return DATA.getProperty("originClass."+axis+".default");
        try { if (!LosslessJson.numericToken(token) || !Double.isFinite(new BigDecimal(token).doubleValue())) throw new NumberFormatException();return token; } catch (NumberFormatException invalid) { throw new IllegalArgumentException("Stored coordinates must be finite numbers",invalid); }
    }
    public static byte[] setOrigin(String path,byte[] original,String field,String x,String z) {
        authorize(path);double first=number("verticalOffset",x),second=number("verticalOffset",z);var root=new LosslessJson(original);coordinate(root,field,"x");coordinate(root,field,"z");
        return root.objectEdit(field,origin -> { byte[] next=writeNumber(origin,"x",first);return writeNumber(new LosslessJson(next),"z",second); },root.token(field)==null);
    }
    public static byte[] removeOrigin(String path,byte[] original,String field) { authorize(path);var root=new LosslessJson(original);origin(root,field);return root.token(field)==null ? original.clone() : root.removeField(field); }
    static List<LosslessJson.StringProperty> colors(LosslessJson root) { return root.token("colors")==null ? null : new LosslessJson(root.token("colors").getBytes(StandardCharsets.UTF_8)).stringProperties(); }
    private static void color(String key) { if (key==null || !key.matches("#[0-9a-fA-F]{6}")) throw new IllegalArgumentException("Use exact #RRGGBB color syntax"); }
    private static void target(String value) { if (value==null || value.isBlank()) throw new IllegalArgumentException("Use a nonblank legend target; resource/registry meaning depends on the binding and is unchecked"); }
    private static void uniqueColor(LosslessJson map,String value,String except) {
        for (var row : map.stringProperties()) if (!row.key().equals(except) && row.key().equalsIgnoreCase(value)) throw new IllegalArgumentException("This raw RGB color already exists, ignoring hex letter case");
    }
    public static byte[] enableColors(String path,byte[] original) { authorize(path);var root=new LosslessJson(original);if (root.token("colors")!=null) throw new IllegalArgumentException("Legend is already present or unsupported");return root.enableObject("colors"); }
    private static byte[] editColors(byte[] original,java.util.function.Function<LosslessJson,byte[]> operation) {
        var root=new LosslessJson(original);var rows=colors(root);if (rows==null || rows.size()>MAXIMUM_COLORS) throw new IllegalArgumentException("Enable an inline legend within the 64-color editor limit");return root.objectEdit("colors",operation,false);
    }
    public static byte[] addColor(String path,byte[] original,String key,String value) { authorize(path);color(key);target(value);return editColors(original,map -> { if (map.fields().size()>=MAXIMUM_COLORS) throw new IllegalArgumentException("Legend has a 64-color editor limit");uniqueColor(map,key,null);return map.stringMapAdd(key,value); }); }
    public static byte[] setColor(String path,byte[] original,String previous,String key,String value) {
        authorize(path);color(key);target(value);return editColors(original,map -> { uniqueColor(map,key,previous);String old=map.stringToken(previous);if (old==null) throw new IllegalArgumentException("Choose an existing color");byte[] renamed=map.stringMapRename(previous,key);return old.equals(value) ? renamed : new LosslessJson(renamed).stringValue(key,value); });
    }
    public static byte[] removeColor(String path,byte[] original,String key) {
        authorize(path);var root=new LosslessJson(original);var rows=colors(root);if (rows==null || rows.size()>MAXIMUM_COLORS) throw new IllegalArgumentException("Select an inline legend within the editor limit");var map=new LosslessJson(root.token("colors").getBytes(StandardCharsets.UTF_8));if (map.stringToken(key)==null) throw new IllegalArgumentException("Choose an existing color");return rows.size()==1 ? root.removeField("colors") : root.objectEdit("colors",m -> m.stringMapRemove(key),false);
    }
    public static List<String> findings(LosslessJson root) {
        var findings=new ArrayList<String>();var enums=new HashMap<String,String>();
        for (String field : ENUMS) try { String value=choice(root,field);if (choices(field).contains(value)) enums.put(field,value);else findings.add(field+": unknown enum choice."); } catch (IllegalArgumentException unsupported) { findings.add(field+": unsupported stored type."); }
        for (String field : NUMBERS) try { if (!numericSupported(root,field)) throw new IllegalArgumentException();number(field,root.token(field)==null ? defaultToken(field) : root.token(field)); } catch (IllegalArgumentException unsupported) { findings.add(field+": unsupported value or source bounds."); }
        try { if (number("minimumHeight",height(root,"minimumHeight"))>number("maximumHeight",height(root,"maximumHeight"))) findings.add("Height pair is reversed."); } catch (IllegalArgumentException ignored) { }
        for (String field : ORIGINS) try { coordinate(root,field,"x");coordinate(root,field,"z"); } catch (IllegalArgumentException unsupported) { findings.add(field+": unsupported coordinates."); }
        String type=enums.get("type"),sampling=enums.get("sampling"),alpha=enums.get("alpha");
        if (List.of("COLOR_MAP","BINARY_MASK").contains(type==null ? "" : type) && sampling!=null && !sampling.equals("NEAREST")) findings.add(type+" requires NEAREST sampling.");
        if ("ALPHA_MASK".equals(type) && alpha!=null && !alpha.equals("IGNORE")) findings.add("ALPHA_MASK requires alpha IGNORE.");
        try { if ("COLOR_MAP".equals(type) && number("smoothingRadius",root.token("smoothingRadius")==null ? defaultToken("smoothingRadius") : root.token("smoothingRadius"))!=0) findings.add("COLOR_MAP does not support numeric smoothing."); } catch (IllegalArgumentException ignored) { }
        try {
            var rows=colors(root);
            if (rows!=null && rows.size()>MAXIMUM_COLORS) findings.add("Legend exceeds the 64-color editor limit; legend checks skipped.");
            else {
                if ("COLOR_MAP".equals(type) && (rows==null || rows.isEmpty())) findings.add("COLOR_MAP requires at least one legend entry.");
                if (type!=null && !type.equals("COLOR_MAP") && rows!=null && !rows.isEmpty()) findings.add("Color legends require COLOR_MAP.");
                if (rows!=null) { var seen=new HashSet<String>();for (var row : rows) { try { color(row.key());target(row.value());if (!seen.add(row.key().toLowerCase(Locale.ROOT))) findings.add("Legend contains a duplicate raw RGB color."); } catch (IllegalArgumentException unsupported) { findings.add("Legend contains unsupported color/target syntax."); } } }
            }
        } catch (IllegalArgumentException unsupported) { findings.add("Stored legend is unsupported; legend checks skipped."); }
        try { if (type!=null && !type.equals("COLOR_MAP") && number("colorTolerance",root.token("colorTolerance")==null ? defaultToken("colorTolerance") : root.token("colorTolerance"))!=0) findings.add("Nonzero colorTolerance requires COLOR_MAP."); } catch (IllegalArgumentException ignored) { }
        if ("COLOR_MAP".equals(type) && ("FALLBACK".equals(enums.get("outOfBounds")) || "FALLBACK".equals(enums.get("unknownColor")) || List.of("MASK","TRANSPARENT_IS_FALLBACK").contains(alpha==null ? "" : alpha))) try { if (text(root,"fallbackTarget").isBlank()) findings.add("Current COLOR_MAP fallback policy requires a fallbackTarget."); } catch (IllegalArgumentException unsupported) { findings.add("Unsupported fallbackTarget."); }
        return List.copyOf(findings);
    }
}
