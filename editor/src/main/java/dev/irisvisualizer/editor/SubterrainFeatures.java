package dev.irisvisualizer.editor;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;

/** Dimension-owned underground feature data only; no placement planner, rasterizer or registry runs. */
public final class SubterrainFeatures {
    public static final List<String> LIST_FIELDS = List.of("subterrainFeatures");
    public static final List<String> EDITABLE = List.of("id", "enabled", "family", "biome", "allowedRegions", "worldYRange", "spacing", "probability", "length", "radius", "height", "shapeWarp", "fluid", "fluidDepth", "chimneyHeight", "terraceCount", "pillarSpacing", "formationFraction", "solid", "priority");
    public static final List<String> NUMBERS = List.of("spacing", "probability", "length", "radius", "height", "shapeWarp", "fluidDepth", "chimneyHeight", "terraceCount", "pillarSpacing", "formationFraction", "priority");
    public static final int MAXIMUM_ENTRIES=64;
    private static final Properties DATA=new Properties();
    static {
        try (var input=SubterrainFeatures.class.getResourceAsStream("/subterrain-feature-fields.properties")) {
            if (input==null) throw new IOException("Missing pinned subterrain definitions");DATA.load(input);
            if (!"trimmedCaseSensitive".equals(DATA.getProperty("id.policy")) || !"inclusiveWorldYCeilFloor".equals(DATA.getProperty("range.policy")) || !"familyDefault".equals(DATA.getProperty("fluid.policy"))) throw new IOException("Unsupported subterrain policy");
        } catch (IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
    public static String prefix() { return "subterrainFeature#"; }
    public static String prefix(int index) { if (index<0) throw new IllegalArgumentException("Select an underground feature");return prefix()+index+"."; }
    private static void authorize(String path) { if (!"dimensions".equals(ResourceTypes.base(path))) throw new IllegalArgumentException("Underground features are dimension-only"); }
    private static void field(String field) { if (field==null || !EDITABLE.contains(field)) throw new IllegalArgumentException("Unsupported subterrain field"); }
    public static String help(String field) { field(field);return DATA.getProperty(field+".description"); }
    public static String defaultToken(String field) { field(field);return DATA.getProperty(field+".default"); }
    public static List<String> families() { return List.of(DATA.getProperty("families").split(",")); }
    public static List<String> fluids() { return List.of(DATA.getProperty("fluids").split(",")); }
    static List<LosslessJson> entries(LosslessJson root) { return root.token("subterrainFeatures")==null ? null : root.objectArray("subterrainFeatures"); }
    static List<String> regions(LosslessJson entry) { return entry.token("allowedRegions")==null ? null : entry.stringArrayPath("allowedRegions"); }
    static byte[] edit(byte[] original,int index,Function<LosslessJson,byte[]> operation) {
        var root=new LosslessJson(original);var entries=entries(root);if (entries==null || entries.size()>MAXIMUM_ENTRIES) throw new IllegalArgumentException("Select an inline feature list within the native 64-definition limit");return root.objectArrayEdit("subterrainFeatures",index,operation);
    }
    static String text(LosslessJson entry,String field) {
        if (!List.of("id","biome","solid").contains(field)) throw new IllegalArgumentException("Unsupported feature text");String token=entry.token(field);
        if (field.equals("biome") && "null".equals(token)) return "";return token==null ? defaultToken(field) : entry.stringToken(field);
    }
    static String family(LosslessJson entry) { return entry.token("family")==null ? defaultToken("family") : entry.stringToken("family"); }
    static String fluid(LosslessJson entry) { String token=entry.token("fluid");return token==null || token.equals("null") ? null : entry.stringToken("fluid"); }
    public static String effectiveFluid(LosslessJson entry) { String role=family(entry),value=fluid(entry);if (!families().contains(role)) return "Unknown family (unchecked)";if (role.equals("TECTONIC_FAULT")) return "Dry (native family behavior)";return value==null ? role.equals("LAVA_TUBE") ? "LAVA" : "WATER" : fluids().contains(value) ? value : "Unknown fluid override (unchecked)"; }
    private static byte[] put(LosslessJson entry,String field,String value) { String old=entry.stringToken(field);return old==null ? entry.enableStringPath(field,value) : old.equals(value) ? entry.sourceBytes() : entry.stringValue(field,value); }
    private static String id(String value) { if (value==null || !value.trim().matches(DATA.getProperty("id.pattern"))) throw new IllegalArgumentException("Use native ID syntax: letters, digits, _, / or -; native identity trims whitespace and is case-sensitive");return value.trim(); }
    private static void unique(List<LosslessJson> entries,String value,int except) {
        if (entries==null) return;String desired=id(value);
        for (int index=0;index<entries.size();index++) if (index!=except) { String old;try { old=text(entries.get(index),"id"); } catch (IllegalArgumentException unsupported) { continue; }if (desired.equals(old.trim())) throw new IllegalArgumentException("This trimmed feature ID already exists, including disabled entries (editor guard)"); }
    }
    public static List<String> choices(PackWorkspace workspace,String family) { if (!List.of("biomes","regions").contains(family)) throw new IllegalArgumentException("Unsupported feature reference family");return ResourceTypes.referenceKeys(workspace,family).stream().filter(key -> key.matches(DATA.getProperty("reference.pattern"))).toList(); }
    private static void reference(PackWorkspace workspace,String family,String key) { if (key==null || !choices(workspace,family).contains(key)) throw new IllegalArgumentException("Choose an exact in-pack key using native subterrain key syntax; contents and aliases are unchecked"); }
    public static byte[] change(String path,byte[] original,RiverProfiles.Action action,int index,String newId,String biome,PackWorkspace workspace) {
        authorize(path);if (action==null) throw new IllegalArgumentException("Choose a feature action");var root=new LosslessJson(original);var entries=entries(root);
        if (entries!=null && entries.size()>MAXIMUM_ENTRIES) throw new IllegalArgumentException("Above the native 64-definition limit (read-only)");
        if (action==RiverProfiles.Action.ADD || action==RiverProfiles.Action.DUPLICATE) { id(newId);unique(entries,newId,-1);if (entries!=null && entries.size()>=MAXIMUM_ENTRIES) throw new IllegalArgumentException("Native feature limit reached"); }
        if (entries==null) { if (action!=RiverProfiles.Action.ADD) throw new IllegalArgumentException("No feature list");root=new LosslessJson(root.enableArray("subterrainFeatures"));entries=entries(root); }
        if (action==RiverProfiles.Action.ADD) { reference(workspace,"biomes",biome);byte[] seed=put(new LosslessJson("{}".getBytes(StandardCharsets.UTF_8)),"id",newId);seed=put(new LosslessJson(seed),"biome",biome);return root.objectArrayInsert("subterrainFeatures",entries.size(),seed); }
        if (index<0 || index>=entries.size()) throw new IllegalArgumentException("Select an existing feature");
        if (action==RiverProfiles.Action.DUPLICATE) return root.objectArrayInsert("subterrainFeatures",index+1,put(entries.get(index),"id",newId));
        if (action==RiverProfiles.Action.REMOVE && entries.size()==1) return root.removeField("subterrainFeatures");return BiomeLayers.changeLayers(root,"subterrainFeatures",action,index);
    }
    public static byte[] setId(String path,byte[] original,int index,String value) { authorize(path);id(value);unique(entries(new LosslessJson(original)),value,index);return edit(original,index,e -> put(e,"id",value)); }
    public static byte[] setBiome(String path,byte[] original,int index,String key,PackWorkspace workspace) {
        authorize(path);if (key!=null) reference(workspace,"biomes",key);return edit(original,index,e -> { text(e,"biome");if (key==null) return e.token("biome")==null ? e.sourceBytes() : e.nullableString("biome","");return "null".equals(e.token("biome")) ? e.nullableString("biome",key) : put(e,"biome",key); });
    }
    public static byte[] setFamily(String path,byte[] original,int index,String value) { authorize(path);if (value==null || !families().contains(value)) throw new IllegalArgumentException("Choose a pinned geometry family");return edit(original,index,e -> put(e,"family",value)); }
    public static byte[] setFluid(String path,byte[] original,int index,String value) {
        authorize(path);if (value!=null && !fluids().contains(value)) throw new IllegalArgumentException("Choose WATER, LAVA or family default");return edit(original,index,e -> { fluid(e);if (value==null) return e.token("fluid")==null ? e.sourceBytes() : e.removeField("fluid");return "null".equals(e.token("fluid")) ? e.nullableString("fluid",value) : put(e,"fluid",value); });
    }
    public static byte[] setEnabled(String path,byte[] original,int index,boolean value) { authorize(path);return edit(original,index,e -> e.token("enabled")==null ? e.enable("enabled",Boolean.toString(value)) : e.bool("enabled",value)); }
    private static void solid(String input) {
        if (input==null) throw new IllegalArgumentException("Enter a solid block-state string");String value=input.trim();
        if (!value.matches(DATA.getProperty("solid.pattern")) || value.matches(DATA.getProperty("solid.excluded"))) throw new IllegalArgumentException("Use native solid-state syntax; vanilla air/water/lava are excluded, while registry existence/solidity remain unvalidated");
    }
    public static byte[] setSolid(String path,byte[] original,int index,String value) { authorize(path);solid(value);return edit(original,index,e -> put(e,"solid",value)); }
    private static double number(String field,String input) {
        if (field==null || !NUMBERS.contains(field)) throw new IllegalArgumentException("Unsupported feature scalar");double value;
        try { var decimal=new BigDecimal(input);value=DATA.getProperty(field+".type").equals("int") ? decimal.intValueExact() : decimal.doubleValue(); } catch (NumberFormatException | ArithmeticException | NullPointerException invalid) { throw new IllegalArgumentException("Enter a finite number or exact signed 32-bit integer",invalid); }
        String min=DATA.getProperty(field+".minimum"),max=DATA.getProperty(field+".maximum");if (!Double.isFinite(value) || min!=null && value<Double.parseDouble(min) || max!=null && value>Double.parseDouble(max)) throw new IllegalArgumentException(constraints(field));return value;
    }
    public static String constraints(String field) { if (!NUMBERS.contains(field)) throw new IllegalArgumentException("Unsupported feature scalar");String min=DATA.getProperty(field+".minimum"),max=DATA.getProperty(field+".maximum");return (DATA.getProperty(field+".type").equals("int") ? "Exact signed 32-bit integer" : "Finite number")+(min==null ? "" : "; minimum "+min)+(max==null ? "" : "; maximum "+max); }
    static boolean numericSupported(LosslessJson entry,String field) {
        if (!NUMBERS.contains(field)) return false;String token=entry.token(field);if (token==null) return true;
        try { if (!LosslessJson.numericToken(token)) return false;var value=new BigDecimal(token);if (DATA.getProperty(field+".type").equals("int")) value.intValueExact();return Double.isFinite(value.doubleValue()); } catch (NumberFormatException | ArithmeticException unsupported) { return false; }
    }
    public static byte[] enableScalar(String path,byte[] original,int index,String field) { authorize(path);number(field,defaultToken(field));return edit(original,index,e -> e.enable(field,defaultToken(field))); }
    public static byte[] setScalar(String path,byte[] original,int index,String field,String input) { authorize(path);double value=number(field,input);return edit(original,index,e -> { if (e.token(field)==null || !numericSupported(e,field)) throw new IllegalArgumentException("Enable a supported scalar first");return DATA.getProperty(field+".type").equals("int") ? e.integer(field,(int)value) : e.number(field,value); }); }
    static LosslessJson range(LosslessJson entry) { String token=entry.token("worldYRange");return token==null ? null : new LosslessJson(token.getBytes(StandardCharsets.UTF_8)); }
    static String bound(LosslessJson entry,String field) {
        if (!List.of("min","max").contains(field)) throw new IllegalArgumentException("Unsupported range bound");var range=range(entry);String token=range==null ? null : range.token(field);
        if (token==null) return DATA.getProperty((range==null ? "range.context." : "range.class.")+field);
        try { if (!LosslessJson.numericToken(token) || !Double.isFinite(new BigDecimal(token).doubleValue())) throw new NumberFormatException();return token; } catch (NumberFormatException invalid) { throw new IllegalArgumentException("Stored world-Y bounds must be finite",invalid); }
    }
    private static double worldY(String input) { double value;try { value=new BigDecimal(input).doubleValue(); } catch (NumberFormatException | NullPointerException invalid) { throw new IllegalArgumentException("Enter finite world-Y bounds",invalid); }if (!Double.isFinite(value) || value<Integer.MIN_VALUE || value>Integer.MAX_VALUE) throw new IllegalArgumentException("World-Y bounds must fit the signed 32-bit range");return value; }
    private static byte[] writeBound(LosslessJson range,String field,double value) { return range.token(field)==null ? range.enable(field,BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()) : Double.compare(new BigDecimal(range.token(field)).doubleValue(),value)==0 ? range.sourceBytes() : range.number(field,value); }
    public static byte[] setRange(String path,byte[] original,int index,String min,String max) {
        authorize(path);double low=worldY(min),high=worldY(max);if (low>high) throw new IllegalArgumentException("Apply ordered inclusive world-Y bounds together");return edit(original,index,e -> { bound(e,"min");bound(e,"max");return e.objectEdit("worldYRange",r -> writeBound(new LosslessJson(writeBound(r,"min",low)),"max",high),e.token("worldYRange")==null); });
    }
    public static byte[] removeRange(String path,byte[] original,int index) { authorize(path);return edit(original,index,e -> { range(e);return e.token("worldYRange")==null ? e.sourceBytes() : e.removeField("worldYRange"); }); }
    public static byte[] setRegion(String path,byte[] original,int index,int region,String key,PackWorkspace workspace) {
        authorize(path);reference(workspace,"regions",key);return edit(original,index,e -> { var values=regions(e);if (values==null || values.size()>MAXIMUM_ENTRIES || region<0 || region>=values.size()) throw new IllegalArgumentException("Select an inline region reference within the editor limit");return values.get(region).equals(key) ? e.sourceBytes() : e.stringArrayPath("allowedRegions",region,key); });
    }
    public static byte[] changeRegion(String path,byte[] original,int index,RiverProfiles.Action action,int region,String key,PackWorkspace workspace) {
        authorize(path);if (action==null) throw new IllegalArgumentException("Choose a region-list action");if (action==RiverProfiles.Action.ADD) reference(workspace,"regions",key);
        return edit(original,index,e -> { var values=regions(e);if (values==null) { if (action!=RiverProfiles.Action.ADD) throw new IllegalArgumentException("No explicit region filter");e=new LosslessJson(e.enableArray("allowedRegions"));values=regions(e); }
            if (values.size()>MAXIMUM_ENTRIES || (action==RiverProfiles.Action.ADD || action==RiverProfiles.Action.DUPLICATE) && values.size()>=MAXIMUM_ENTRIES) throw new IllegalArgumentException("Region filters have a 64-entry editor limit");
            if (action==RiverProfiles.Action.ADD) return e.stringArrayPath("allowedRegions",values.size(),key);if (region<0 || region>=values.size()) throw new IllegalArgumentException("Select an existing region reference");
            return switch(action) { case DUPLICATE -> e.stringArrayDuplicate("allowedRegions",region);case UP -> e.stringArrayMove("allowedRegions",region,region-1);case DOWN -> e.stringArrayMove("allowedRegions",region,region+1);case REMOVE -> values.size()==1 ? e.removeField("allowedRegions") : e.stringArrayPath("allowedRegions",region,null);default -> throw new IllegalArgumentException("Unsupported list action"); };
        });
    }
    private static double current(LosslessJson entry,String field) { if (!numericSupported(entry,field)) throw new IllegalArgumentException("Unsupported number");return number(field,entry.token(field)==null ? defaultToken(field) : entry.token(field)); }
    public static List<String> findings(LosslessJson root) {
        List<LosslessJson> entries;try { entries=entries(root); } catch (IllegalArgumentException unsupported) { return List.of("Feature list is unsupported; local checks unavailable."); }
        if (entries==null) return List.of();if (entries.size()>MAXIMUM_ENTRIES) return List.of("Feature list exceeds the native 64-definition limit.");var notices=new ArrayList<String>();var ids=new HashSet<String>();
        for (int index=0;index<entries.size();index++) { var entry=entries.get(index);String label="subterrainFeatures["+index+"]";
            try { String id=id(text(entry,"id"));if (!ids.add(id)) notices.add(label+": duplicate trimmed ID, including disabled entries (editor check)."); } catch (IllegalArgumentException unsupported) { notices.add(label+": unsupported ID."); }
            String family=null;try { family=family(entry);if (!families().contains(family)) notices.add(label+": unknown family."); } catch (IllegalArgumentException unsupported) { notices.add(label+": unsupported family."); }
            try { String fluid=fluid(entry);if (fluid!=null && !fluids().contains(fluid)) notices.add(label+": unknown fluid."); } catch (IllegalArgumentException unsupported) { notices.add(label+": unsupported fluid."); }
            for (String field : NUMBERS) try { current(entry,field); } catch (IllegalArgumentException unsupported) { notices.add(label+"."+field+": unsupported value or source bounds."); }
            try { if (family!=null && families().contains(family)) { double length=current(entry,"length"),radius=current(entry,"radius"),reach=family.equals("CENOTE") ? radius : Math.max(((int)length)/2,radius);double minimum=Math.max(32,2*reach+8);if (current(entry,"spacing")<minimum) notices.add(label+": spacing must be at least "+(int)minimum+" for the current family/extent.");if (family.equals("TECTONIC_FAULT") && length<200) notices.add(label+": tectonic fault length must be at least 200."); } } catch (IllegalArgumentException ignored) { }
            try { if (current(entry,"fluidDepth")>Math.min(32,((int)current(entry,"height"))/3)) notices.add(label+": fluidDepth exceeds the current height/3 bound."); } catch (IllegalArgumentException ignored) { }
            try { double min=worldY(bound(entry,"min")),max=worldY(bound(entry,"max"));if (min>max) notices.add(label+": world-Y bounds are reversed."); } catch (IllegalArgumentException unsupported) { notices.add(label+": unsupported world-Y range."); }
            try { solid(text(entry,"solid")); } catch (IllegalArgumentException unsupported) { notices.add(label+": unsupported solid-state syntax (registry unvalidated)."); }
            try { String biome=text(entry,"biome").trim();if (!biome.isEmpty() && !biome.matches(DATA.getProperty("reference.pattern"))) notices.add(label+": unsupported biome key syntax."); } catch (IllegalArgumentException unsupported) { notices.add(label+": unsupported biome reference."); }
            try { var values=regions(entry);if (values!=null) { if (values.size()>MAXIMUM_ENTRIES) notices.add(label+": region filter exceeds the 64-entry editor limit.");else for (String key : values) if (!key.matches(DATA.getProperty("reference.pattern"))) notices.add(label+": unsupported region key syntax."); } } catch (IllegalArgumentException unsupported) { notices.add(label+": unsupported allowedRegions list."); }
        }
        return List.copyOf(notices);
    }
}
