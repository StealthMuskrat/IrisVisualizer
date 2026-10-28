package dev.irisvisualizer.editor;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;

/** Authored dimension carving entries only; no biome loading or native selection runs. */
public final class DimensionCarvings {
    public static final List<String> LIST_FIELDS = List.of("carving");
    public static final List<String> EDITABLE = List.of("id", "enabled", "biome", "worldYRange", "childShrinkFactor", "childStyle", "children", "childRecursionDepth");
    public static final List<String> SCALARS = List.of("childShrinkFactor", "childRecursionDepth");
    public static final int MAXIMUM_ENTRIES=64;
    private static final Properties DATA=new Properties();
    static {
        try (var input=DimensionCarvings.class.getResourceAsStream("/dimension-carving-fields.properties")) {
            if (input==null) throw new IOException("Missing pinned dimension-carving definitions");DATA.load(input);
            if (!"lastUsableMatchingRoot".equals(DATA.getProperty("root.policy")) || !"inclusiveAbsoluteWorldY".equals(DATA.getProperty("range.policy")) || !"parentPlusChildrenIgnoreChildY".equals(DATA.getProperty("children.policy"))) throw new IOException("Unsupported carving policy");
        } catch (IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
    public static String prefix() { return "dimensionCarving#"; }
    public static String prefix(int index) { if (index<0) throw new IllegalArgumentException("Select a carving entry");return prefix()+index+"."; }
    public static String help(String field) { field(field);return DATA.getProperty(field+".description",""); }
    private static void field(String field) { if (field==null || !EDITABLE.contains(field)) throw new IllegalArgumentException("Unsupported carving field"); }
    public static String defaultToken(String field) { field(field);return DATA.getProperty(field+".default"); }
    public static String styleDefault() { return DATA.getProperty("childStyle.default"); }
    private static void authorize(String path) { if (!"dimensions".equals(ResourceTypes.base(path))) throw new IllegalArgumentException("Carving entries are dimension-only"); }
    static List<LosslessJson> entries(LosslessJson root) { String token=root.token("carving");return token==null || token.equals("null") ? null : root.objectArray("carving"); }
    static List<String> children(LosslessJson entry) { String token=entry.token("children");return token==null || token.equals("null") ? null : entry.stringArrayPath("children"); }
    static byte[] edit(byte[] original,int index,Function<LosslessJson,byte[]> operation) {
        var root=new LosslessJson(original);var entries=entries(root);if (entries==null || entries.size()>MAXIMUM_ENTRIES) throw new IllegalArgumentException("Select an inline carving list within the editor limit");
        return root.objectArrayEdit("carving",index,operation);
    }
    private static void id(String value) { if (value==null || !value.matches("[a-z0-9_-]+")) throw new IllegalArgumentException("Use lowercase letters, digits, _ or - for new IDs (editor subset)"); }
    private static void unique(List<LosslessJson> entries,String value,int except) {
        if (entries==null) return;
        for (int index=0;index<entries.size();index++) if (index!=except) {
            String token=entries.get(index).token("id");if (token==null || token.equals("null")) continue;
            if (value.equals(entries.get(index).stringToken("id").trim())) throw new IllegalArgumentException("This trimmed case-sensitive carving ID already exists");
        }
    }
    public static List<String> ids(LosslessJson root) {
        var entries=entries(root);if (entries==null || entries.size()>MAXIMUM_ENTRIES) return List.of();var ids=new TreeSet<String>();
        for (var entry : entries) try { String value=entry.stringToken("id");if (value!=null && !value.isBlank()) ids.add(value.trim()); } catch (IllegalArgumentException ignored) { }
        return List.copyOf(ids);
    }
    public static byte[] change(String path,byte[] original,RiverProfiles.Action action,int index,String newId,String biome,PackWorkspace workspace) {
        authorize(path);if (action==null) throw new IllegalArgumentException("Choose a carving action");var root=new LosslessJson(original);var entries=entries(root);
        if (action==RiverProfiles.Action.ADD || action==RiverProfiles.Action.DUPLICATE) { id(newId);unique(entries,newId,-1); }
        if (entries==null) { if (action!=RiverProfiles.Action.ADD) throw new IllegalArgumentException("No carving entries");root=new LosslessJson(root.enableArray("carving"));entries=entries(root); }
        if (entries.size()>MAXIMUM_ENTRIES || (action==RiverProfiles.Action.ADD || action==RiverProfiles.Action.DUPLICATE) && entries.size()>=MAXIMUM_ENTRIES) throw new IllegalArgumentException("Carving lists have a 64-entry editor limit");
        if (action==RiverProfiles.Action.ADD) {
            reference(biome,workspace);byte[] entry=new LosslessJson("{}".getBytes(StandardCharsets.UTF_8)).enableStringPath("id",newId);entry=new LosslessJson(entry).enableStringPath("biome",biome);return root.objectArrayInsert("carving",entries.size(),entry);
        }
        if (index<0 || index>=entries.size()) throw new IllegalArgumentException("Select an existing carving entry");
        if (action==RiverProfiles.Action.DUPLICATE) { var entry=entries.get(index);String token=entry.token("id");byte[] copy=token==null ? entry.enableStringPath("id",newId) : entry.stringValue("id",newId);return root.objectArrayInsert("carving",index+1,copy); }
        if (action==RiverProfiles.Action.REMOVE && entries.size()==1) return root.removeField("carving");
        return BiomeLayers.changeLayers(root,"carving",action,index);
    }
    public static byte[] setId(String path,byte[] original,int index,String value) {
        authorize(path);id(value);unique(entries(new LosslessJson(original)),value,index);return edit(original,index,e -> e.token("id")==null ? e.enableStringPath("id",value) : e.stringValue("id",value));
    }
    private static void reference(String key,PackWorkspace workspace) { if (key==null || !ResourceTypes.referenceKeys(workspace,"biomes").contains(key)) throw new IllegalArgumentException("Choose an exact in-pack biome key; target contents and aliases are unchecked"); }
    public static byte[] setBiome(String path,byte[] original,int index,String key,PackWorkspace workspace) { authorize(path);reference(key,workspace);return edit(original,index,e -> e.token("biome")==null ? e.enableStringPath("biome",key) : e.stringValue("biome",key)); }
    public static byte[] setEnabled(String path,byte[] original,int index,boolean value) { authorize(path);return edit(original,index,e -> e.token("enabled")==null ? e.enable("enabled",Boolean.toString(value)) : e.bool("enabled",value)); }
    private static BigDecimal scalar(String field,String input) {
        if (!SCALARS.contains(field)) throw new IllegalArgumentException("Unsupported carving scalar");
        try {
            var value=new BigDecimal(input);
            if (field.equals("childRecursionDepth")) { int depth=value.intValueExact();if (depth<0 || depth>Integer.parseInt(DATA.getProperty("depth.maximum"))) throw new IllegalArgumentException("Depth must be 0..32 (native clamp, editor guard)"); }
            else if (!Double.isFinite(value.doubleValue()) || value.doubleValue()<Double.parseDouble(DATA.getProperty("shrink.minimum"))) throw new IllegalArgumentException("Enter a finite shrink factor of at least 0.0001 (native clamp, editor guard)");
            return value;
        } catch (NumberFormatException | ArithmeticException | NullPointerException invalid) { throw new IllegalArgumentException("Enter a finite number or exact signed 32-bit depth",invalid); }
    }
    static boolean numericSupported(LosslessJson entry,String field) {
        String token=entry.token(field);if (token==null) return true;if (!LosslessJson.numericToken(token)) return false;
        try { if (field.equals("childRecursionDepth")) new BigDecimal(token).intValueExact();else if (!Double.isFinite(new BigDecimal(token).doubleValue())) return false;return true; } catch (ArithmeticException invalid) { return false; }
    }
    public static byte[] enableScalar(String path,byte[] original,int index,String field) { authorize(path);scalar(field,defaultToken(field));return edit(original,index,e -> e.enable(field,defaultToken(field))); }
    public static byte[] setScalar(String path,byte[] original,int index,String field,String input) { authorize(path);var value=scalar(field,input);return edit(original,index,e -> { if (e.token(field)==null || !numericSupported(e,field)) throw new IllegalArgumentException("Enable a supported scalar first");return field.equals("childRecursionDepth") ? e.integer(field,value.intValueExact()) : e.number(field,value.doubleValue()); }); }
    static LosslessJson range(LosslessJson entry) { String token=entry.token("worldYRange");return token==null || token.equals("null") ? null : new LosslessJson(token.getBytes(StandardCharsets.UTF_8)); }
    static String rangeBound(LosslessJson entry,String field) {
        if (!List.of("min","max").contains(field)) throw new IllegalArgumentException("Unsupported range bound");
        var range=range(entry);String token=range==null ? null : range.token(field);
        if (token==null) return DATA.getProperty((entry.token("worldYRange")==null ? "range.context." : "range.class.")+field);
        if (!LosslessJson.numericToken(token) || !Double.isFinite(new BigDecimal(token).doubleValue())) throw new IllegalArgumentException("Unsupported world-Y bound");return token;
    }
    public static byte[] setRange(String path,byte[] original,int index,String min,String max) {
        authorize(path);double low=finite(min),high=finite(max);if (low>high) throw new IllegalArgumentException("Apply ordered inclusive world-Y bounds together (editor guard)");
        return edit(original,index,e -> { rangeBound(e,"min");rangeBound(e,"max");if ("null".equals(e.token("worldYRange"))) e=new LosslessJson(e.enableObject("worldYRange"));return e.objectEdit("worldYRange",r -> { byte[] next=r.token("min")==null ? r.enable("min",BigDecimal.valueOf(low).toPlainString()) : r.number("min",low);r=new LosslessJson(next);return r.token("max")==null ? r.enable("max",BigDecimal.valueOf(high).toPlainString()) : r.number("max",high); },e.token("worldYRange")==null || e.token("worldYRange").equals("null")); });
    }
    private static double finite(String input) { try { double value=Double.parseDouble(input);if (!Double.isFinite(value)) throw new IllegalArgumentException("Enter finite world-Y bounds");return value; } catch (NumberFormatException | NullPointerException invalid) { throw new IllegalArgumentException("Enter finite world-Y bounds",invalid); } }
    public static byte[] unbounded(String path,byte[] original,int index) { authorize(path);return edit(original,index,e -> e.token("worldYRange")==null ? new LosslessJson(e.enableObject("worldYRange")).nullObject("worldYRange") : e.nullObject("worldYRange")); }
    public static byte[] changeChild(String path,byte[] original,int index,RiverProfiles.Action action,int childIndex,String key) {
        authorize(path);if (action==null) throw new IllegalArgumentException("Choose a child action");
        if (action==RiverProfiles.Action.ADD && (key==null || !ids(new LosslessJson(original)).contains(key))) throw new IllegalArgumentException("Choose a trimmed local carving ID; cycles and self-links are allowed");
        return edit(original,index,e -> {
            var children=children(e);if (children==null) { if (action!=RiverProfiles.Action.ADD) throw new IllegalArgumentException("No children");e=new LosslessJson(e.enableArray("children"));children=children(e); }
            if (children.size()>MAXIMUM_ENTRIES || (action==RiverProfiles.Action.ADD || action==RiverProfiles.Action.DUPLICATE) && children.size()>=MAXIMUM_ENTRIES) throw new IllegalArgumentException("Child lists have a 64-entry editor limit");
            if (action==RiverProfiles.Action.ADD) return e.stringArrayPath("children",children.size(),key);
            if (childIndex<0 || childIndex>=children.size()) throw new IllegalArgumentException("Select a child link");
            return switch(action) { case DUPLICATE -> e.stringArrayDuplicate("children",childIndex);case UP -> e.stringArrayMove("children",childIndex,childIndex-1);case DOWN -> e.stringArrayMove("children",childIndex,childIndex+1);case REMOVE -> children.size()==1 ? e.removeField("children") : e.stringArrayPath("children",childIndex,null);default -> throw new IllegalArgumentException("Unsupported action"); };
        });
    }
    public static int styleIndex(String path) { if (path==null || !path.matches("carving\\.(0|[1-9][0-9]*)\\.childStyle")) return -1;try { return Integer.parseInt(path.split("\\.")[1]); } catch (NumberFormatException invalid) { return -1; } }
    public static NoiseStyleFields.Target styleTarget(int index) { prefix(index);return new NoiseStyleFields.Target("dimensions","carving."+index+".childStyle",null); }
    public static List<String> findings(LosslessJson root) {
        var findings=new ArrayList<String>();List<LosslessJson> entries;
        try { entries=entries(root); } catch (IllegalArgumentException wrong) { return List.of("Carving list is not an inline object list."); }
        if (entries==null) return List.of();if (entries.size()>MAXIMUM_ENTRIES) return List.of("Carving list exceeds the 64-entry editor limit; local ID inspection skipped.");
        var counts=new HashMap<String,Integer>();for (var e : entries) try { String id=e.stringToken("id");if (id!=null && !id.isBlank()) counts.merge(id.trim(),1,Integer::sum); } catch (IllegalArgumentException ignored) { }
        for (int i=0;i<entries.size();i++) {var e=entries.get(i);String label="carving["+i+"]";
            try { String id=e.stringToken("id");if (id==null || id.isBlank()) findings.add(label+": blank/missing ID cannot be selected as a child.");else if (counts.getOrDefault(id.trim(),0)>1) findings.add(label+": duplicate trimmed ID; native lookup uses the last entry."); } catch (IllegalArgumentException wrong) { findings.add(label+": unsupported ID."); }
            try { var children=children(e);if (children!=null) { if (children.size()>MAXIMUM_ENTRIES) findings.add(label+": oversized child list; child inspection skipped.");else for (String child : children) if (child.isBlank() || !counts.containsKey(child.trim())) findings.add(label+": child ID has no exact trimmed local match."); } } catch (IllegalArgumentException wrong) { findings.add(label+": unsupported children list."); }
        }
        return List.copyOf(findings);
    }
}
