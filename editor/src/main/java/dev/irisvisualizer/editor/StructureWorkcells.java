package dev.irisvisualizer.editor;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;

/** Authored workcell bounds only. Does not resolve piece topology, load objects or assemble graphs. */
public final class StructureWorkcells {
    public static final List<String> ROOT_FIELDS = List.of("cellSize","planarWorkcells");
    public static final List<String> EDITABLE = List.of("width","height","depth","enabled");
    public static final List<String> CREATION_FIELDS = List.of("archetype");
    public static final List<String> DIMENSIONS = List.of("width","height","depth");
    private static final List<String> AXES=List.of("x","y","z");
    private static final Properties DATA=new Properties();
    static {
        try (var input=StructureWorkcells.class.getResourceAsStream("/structure-workcell-fields.properties")) {
            if (input==null) throw new IOException("Missing pinned structure workcell definitions");DATA.load(input);
            if (!"sixCompleteUniqueArchetypes".equals(DATA.getProperty("identity.policy")) || !"stillRequired".equals(DATA.getProperty("disabled.bounds")) || archetypes().size()!=6) throw new IOException("Unsupported workcell authoring policy");
        } catch (IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
    public record Size(int width,int height,int depth) {
        public String key() { return width+"|"+height+"|"+depth; }
        public long volume() { return Math.multiplyExact(Math.multiplyExact((long)width,height),depth); }
    }
    public static List<String> archetypes() { return List.of(DATA.getProperty("archetypes").split(",")); }
    public static int minimum(String field) { dimension(field);return Integer.parseInt(DATA.getProperty(field+".minimum")); }
    public static int maximum(String field) { dimension(field);return Integer.parseInt(DATA.getProperty(field+".maximum")); }
    public static long maximumVolume() { return Long.parseLong(DATA.getProperty("native.MAX_VOLUME")); }
    public static String help(String field) { if (field==null || !ROOT_FIELDS.contains(field) && !EDITABLE.contains(field) && !CREATION_FIELDS.contains(field)) throw new IllegalArgumentException("Unknown workcell field");return DATA.getProperty(field+".description"); }
    private static void dimension(String field) { if (field==null || !DIMENSIONS.contains(field)) throw new IllegalArgumentException("Select a workcell dimension"); }
    private static void authorize(String path) { if (!StructureDefinitions.supports(path)) throw new IllegalArgumentException("Workcell controls require a structures JSON file"); }
    private static void index(int index) { if (index< -1 || index>=6) throw new IllegalArgumentException("Select the legacy bounds or one of six workcells"); }
    public static String entriesPrefix() { return "structureWorkcells#entry."; }
    public static String prefix(int index) { index(index);return index<0 ? "structureWorkcells#legacy." : entriesPrefix()+index+"."; }
    private static int integer(String token) {
        try { return new BigDecimal(token).intValueExact(); }
        catch (NumberFormatException | ArithmeticException | NullPointerException invalid) { throw new IllegalArgumentException("Use exact signed 32-bit workcell integers; unsupported stored values remain read-only",invalid); }
    }
    private static int member(LosslessJson object,String field,String defaults) { String token=object.token(field);return integer(token==null ? DATA.getProperty(defaults+".default") : token); }
    public static String archetype(LosslessJson row) {
        String value=row.token("archetype")==null ? DATA.getProperty("archetype.default") : row.stringToken("archetype");
        if (!archetypes().contains(value)) throw new IllegalArgumentException("Unsupported planar workcell archetype; original data remains read-only");return value;
    }
    /** Empty, absent and null arrays use the native legacy fallback. Nonempty maps must be complete and unique. */
    public static List<LosslessJson> entries(LosslessJson root) {
        String token=root.token("planarWorkcells");if (token==null || token.equals("null")) return List.of();
        var rows=root.objectArray("planarWorkcells");if (rows.isEmpty()) return rows;
        if (rows.size()!=archetypes().size()) throw new IllegalArgumentException("Existing planarWorkcells must contain all six archetypes exactly once; this list remains read-only");
        var seen=new HashSet<String>();for (var row : rows) if (!seen.add(archetype(row))) throw new IllegalArgumentException("Duplicate planar workcell archetype; this list remains read-only");return rows;
    }
    private static LosslessJson row(LosslessJson root,int index) { index(index);var rows=entries(root);if (index<0 || index>=rows.size()) throw new IllegalArgumentException("Select an existing workcell");return rows.get(index); }
    private static LosslessJson legacy(LosslessJson root) { return new LosslessJson(root.token("cellSize").getBytes(StandardCharsets.UTF_8)); }
    public static Size size(LosslessJson root,int index) {
        index(index);
        if (index<0) {
            String token=root.token("cellSize");if (token==null) return new Size(integer(DATA.getProperty("legacy.x.default")),integer(DATA.getProperty("legacy.y.default")),integer(DATA.getProperty("legacy.z.default")));
            if (token.equals("null")) { int fallback=integer(DATA.getProperty("native.DEFAULT_SIZE"));return new Size(fallback,fallback,fallback); }
            var object=legacy(root);return new Size(member(object,"x","position.x"),member(object,"y","position.y"),member(object,"z","position.z"));
        }
        return entrySize(row(root,index));
    }
    static Size entrySize(LosslessJson row) { return new Size(member(row,"width","width"),member(row,"height","height"),member(row,"depth","depth")); }
    private static void bounds(Size size) {
        int[] values={size.width(),size.height(),size.depth()};for (int i=0;i<3;i++) if (values[i]<minimum(DIMENSIONS.get(i)) || values[i]>maximum(DIMENSIONS.get(i))) throw new IllegalArgumentException("Workcell width/depth must be 3..128 and height 1..192");
        if (size.volume()>maximumVolume()) throw new IllegalArgumentException("Workcell volume exceeds "+maximumVolume()+" blocks, including disabled cells");
    }
    private static byte[] put(LosslessJson object,String field,int value) {
        String old=object.token(field);return old==null ? object.enable(field,Integer.toString(value)) : integer(old)==value ? object.sourceBytes() : object.integer(field,value);
    }
    private static byte[] dimensions(LosslessJson object,List<String> fields,Size value) {
        byte[] raw=put(object,fields.get(0),value.width());raw=put(new LosslessJson(raw),fields.get(1),value.height());return put(new LosslessJson(raw),fields.get(2),value.depth());
    }
    /** Three dimensions are validated before a single source edit is returned. Missing scalar defaults are stored only on Apply. */
    public static byte[] setBounds(String path,byte[] raw,int index,String width,String height,String depth) {
        authorize(path);index(index);var next=new Size(integer(width),integer(height),integer(depth));bounds(next);var root=new LosslessJson(raw);size(root,index);
        if (index<0) {
            String old=root.token("cellSize");if (old==null || old.equals("null")) root=new LosslessJson(root.enableObject("cellSize"));
            return root.objectEdit("cellSize",object -> dimensions(object,AXES,next),false);
        }
        return root.objectArrayEdit("planarWorkcells",index,object -> dimensions(object,DIMENSIONS,next));
    }
    public static boolean enabled(LosslessJson root,int index) {
        return entryEnabled(row(root,index));
    }
    static boolean entryEnabled(LosslessJson row) {
        String token=row.token("enabled");if (token==null) return Boolean.parseBoolean(DATA.getProperty("enabled.default"));
        if (!token.equals("true") && !token.equals("false")) throw new IllegalArgumentException("Stored workcell enabled flag is unsupported (read-only)");return Boolean.parseBoolean(token);
    }
    public static byte[] setEnabled(String path,byte[] raw,int index,boolean value) {
        authorize(path);var root=new LosslessJson(raw);enabled(root,index);
        return root.objectArrayEdit("planarWorkcells",index,object -> object.token("enabled")==null ? object.enable("enabled",Boolean.toString(value)) : Boolean.toString(value).equals(object.token("enabled")) ? object.sourceBytes() : object.bool("enabled",value));
    }
    /** Explicitly materialize all six identities from applied legacy bounds; never overwrites a nonempty override. */
    public static byte[] create(String path,byte[] raw) {
        authorize(path);var root=new LosslessJson(raw);if (!entries(root).isEmpty()) throw new IllegalArgumentException("Workcell overrides already exist");var fallback=size(root,-1);bounds(fallback);
        String token=root.token("planarWorkcells");byte[] next=token==null || token.equals("null") ? root.enableArray("planarWorkcells") : raw.clone();
        for (String archetype : archetypes()) {
            byte[] row=new LosslessJson("{}".getBytes(StandardCharsets.UTF_8)).enableStringPath("archetype",archetype);row=dimensions(new LosslessJson(row),DIMENSIONS,fallback);
            var current=new LosslessJson(next);next=current.objectArrayInsert("planarWorkcells",current.objectArray("planarWorkcells").size(),row);
        }
        return next;
    }
    public static List<String> otherFields(LosslessJson root,int index) {
        index(index);if (index<0) { String token=root.token("cellSize");return token==null || token.equals("null") ? List.of() : legacy(root).fields().stream().filter(field -> !AXES.contains(field)).toList(); }
        return entryOtherFields(row(root,index));
    }
    static List<String> entryOtherFields(LosslessJson row) { return row.fields().stream().filter(field -> !EDITABLE.contains(field) && !CREATION_FIELDS.contains(field)).toList(); }
}
