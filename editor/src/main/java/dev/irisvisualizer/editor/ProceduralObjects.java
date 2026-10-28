package dev.irisvisualizer.editor;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.function.Function;

/** Offline procedural collection and placement authoring, without baking variants. */
public final class ProceduralObjects {
    public static final List<String> OBJECT_FIELDS = List.of("proceduralObjects");
    public static final List<String> LIST_FIELDS = List.of("trees", "ruins", "formations", "coral", "fungi", "crystals");
    public static final List<String> EDITABLE = List.of("name", "chance", "density", "variants", "seed", "mode", "carvingSupport", "underwater");
    public static final List<String> TREE_FIELDS = List.of("plausible");
    public static final List<String> FORMATION_FIELDS = List.of("surfaceSupportBuffer");
    public static final int MAXIMUM_ENTRIES=64;
    private static final Properties DATA=new Properties();
    static { try (var input=ProceduralObjects.class.getResourceAsStream("/procedural-object-fields.properties")) { if (input==null) throw new IOException("Missing pinned procedural placement contracts");DATA.load(input);if (!String.join(",",LIST_FIELDS).equals(DATA.getProperty("collections")) || !"exactInt64".equals(DATA.getProperty("seed.policy")) || !"perChunkBeforeDensity".equals(DATA.getProperty("chance.policy"))) throw new IOException("Unsupported procedural policy"); } catch (IOException failure) { throw new ExceptionInInitializerError(failure); } }
    public record Context(String base,String kind,int index) {
        public Context { if (!("biomes".equals(base) || "regions".equals(base)) || kind==null || !LIST_FIELDS.contains(kind) || index<0) throw new IllegalArgumentException("Select a biome or region procedural placement"); }
        public String prefix() { return listPrefix(kind)+index+"."; }
    }
    public static String prefix() { return "proceduralObjects#."; }
    public static String listPrefix(String kind) { kind(kind);return prefix()+kind+"."; }
    private static void kind(String kind) { if (kind==null || !LIST_FIELDS.contains(kind)) throw new IllegalArgumentException("Select a procedural collection"); }
    private static void authorize(String path) { String base=ResourceTypes.base(path);if (!("biomes".equals(base) || "regions".equals(base))) throw new IllegalArgumentException("Procedural placements belong to biomes or regions"); }
    private static void authorize(String path,Context c) { authorize(path);if (c==null || !c.base().equals(ResourceTypes.base(path))) throw new IllegalArgumentException("Procedural context does not match resource host"); }
    public static List<String> fields(String kind) { kind(kind);var fields=new ArrayList<>(EDITABLE);if (kind.equals("trees")) fields.addAll(TREE_FIELDS);if (kind.equals("formations")) fields.addAll(FORMATION_FIELDS);return List.copyOf(fields); }
    public static List<String> editableFields(String kind) { var all=new ArrayList<>(fields(kind));all.addAll(ProceduralShapes.fields(kind));return List.copyOf(all); }
    private static void field(String kind,String field) { if (field==null || !editableFields(kind).contains(field)) throw new IllegalArgumentException("Select a supported procedural placement control"); }
    public static String help(String kind,String field) { kind(kind);if (ProceduralShapes.handles(kind,field)) return ProceduralShapes.property(kind,field,"description");return DATA.getProperty(kind+"."+(field==null ? "description" : field+".description"),""); }
    public static String type(String kind,String field) { field(kind,field);return property(kind,field,"type"); }
    public static String defaultValue(String kind,String field) { field(kind,field);return property(kind,field,"default"); }
    public static List<String> choices(String kind,String field) { field(kind,field);if (ProceduralShapes.handles(kind,field)) return ProceduralShapes.choices(kind,field);String value=DATA.getProperty(field+".choices");return value==null ? List.of() : List.of(value.split(",")); }
    public static String choiceHelp(String field,String choice) { return DATA.getProperty(field+"."+choice+".description",""); }
    public static String choiceHelp(String kind,String field,String choice) { return ProceduralShapes.handles(kind,field) ? ProceduralShapes.choiceHelp(kind,field,choice) : choiceHelp(field,choice); }
    static String property(String kind,String field,String key) { field(kind,field);return ProceduralShapes.handles(kind,field) ? ProceduralShapes.property(kind,field,key) : DATA.getProperty(kind+"."+field+"."+key); }
    public static String constraints(String kind,String field) { String t=type(kind,field),min=property(kind,field,"minimum"),max=property(kind,field,"maximum");return (t.equals("long") ? "Exact signed 64-bit integer" : t.equals("int") ? "Exact signed 32-bit integer" : "Finite number")+(min!=null ? "; minimum "+min : "")+(max!=null ? "; maximum "+max : ""); }

    static LosslessJson object(LosslessJson root) { String token=root.token("proceduralObjects");return token==null || token.equals("null") ? null : new LosslessJson(token.getBytes(StandardCharsets.UTF_8)); }
    static List<LosslessJson> entries(LosslessJson root,String kind) { kind(kind);var o=object(root);if (o==null) return null;String token=o.token(kind);return token==null || token.equals("null") ? null : o.objectArray(kind); }
    private static void limit(List<LosslessJson> entries,boolean add) { if (entries!=null && (entries.size()>MAXIMUM_ENTRIES || add && entries.size()>=MAXIMUM_ENTRIES)) throw new IllegalArgumentException("Procedural collections have a 64-entry editor limit"); }
    private static byte[] editOwner(byte[] raw,Function<LosslessJson,byte[]> operation) { var root=new LosslessJson(raw);if (object(root)==null) throw new IllegalArgumentException("Enable inline procedural objects first");return root.objectEdit("proceduralObjects",operation,false); }
    private static byte[] edit(byte[] raw,Context c,Function<LosslessJson,byte[]> operation) { var rows=entries(new LosslessJson(raw),c.kind());limit(rows,false);if (rows==null || c.index()>=rows.size()) throw new IllegalArgumentException("Select an existing procedural placement");return editOwner(raw,o -> o.objectArrayEdit(c.kind(),c.index(),operation)); }
    public static byte[] enable(String path,byte[] raw) { authorize(path);var root=new LosslessJson(raw);if (object(root)!=null) throw new IllegalArgumentException("Procedural owner already exists");return root.enableObject("proceduralObjects"); }
    public static byte[] reset(String path,byte[] raw) { authorize(path);var root=new LosslessJson(raw);object(root);return root.token("proceduralObjects")==null ? raw : root.removeField("proceduralObjects"); }
    public static byte[] resetList(String path,byte[] raw,String kind) { authorize(path);kind(kind);var rows=entries(new LosslessJson(raw),kind);limit(rows,false);return editOwner(raw,o -> o.token(kind)==null ? o.sourceBytes() : o.removeField(kind)); }
    public static byte[] change(String path,byte[] raw,String kind,RiverProfiles.Action action,int index) {
        authorize(path);kind(kind);if (action==null) throw new IllegalArgumentException("Choose a procedural placement action");var rows=entries(new LosslessJson(raw),kind);limit(rows,action==RiverProfiles.Action.ADD || action==RiverProfiles.Action.DUPLICATE);
        if (rows==null && action!=RiverProfiles.Action.ADD) throw new IllegalArgumentException("No procedural list to edit");
        return editOwner(raw,o -> { if (action==RiverProfiles.Action.ADD) { if (rows==null) o=new LosslessJson(o.enableArray(kind));return o.objectArrayInsert(kind,rows==null ? 0 : rows.size(),"{}".getBytes(StandardCharsets.UTF_8)); }if (index<0 || index>=rows.size()) throw new IllegalArgumentException("Select an existing procedural placement");if (action==RiverProfiles.Action.REMOVE && rows.size()==1) return o.removeField(kind);return BiomeLayers.changeLayers(o,kind,action,index); });
    }
    static String value(LosslessJson entry,String kind,String field) {
        String type=type(kind,field),token=entry.token(field);if (token==null) return defaultValue(kind,field);
        if (type.equals("String") || !choices(kind,field).isEmpty()) return entry.stringToken(field);
        if (type.equals("boolean")) { if (!List.of("true","false").contains(token)) throw new IllegalArgumentException("Stored field is not boolean");return token; }
        try { if (!LosslessJson.numericToken(token)) throw new NumberFormatException();var number=new BigDecimal(token);if (type.equals("int")) number.intValueExact();else if (type.equals("long")) number.longValueExact();else if (!Double.isFinite(number.doubleValue())) throw new NumberFormatException();return token; } catch (NumberFormatException | ArithmeticException invalid) { throw new IllegalArgumentException("Stored field has unsupported numeric type or width",invalid); }
    }
    public static byte[] set(String path,byte[] raw,Context c,String field,String input) {
        authorize(path,c);field(c.kind(),field);if (input==null || input.indexOf(0)>=0) throw new IllegalArgumentException("Enter a supported value without NUL");String type=type(c.kind(),field);BigDecimal number=null;
        if (!choices(c.kind(),field).isEmpty()) { if (!choices(c.kind(),field).contains(input)) throw new IllegalArgumentException("Choose a pinned native option"); }
        else if (type.equals("boolean")) { if (!List.of("true","false").contains(input)) throw new IllegalArgumentException("Choose true or false"); }
        else if (!type.equals("String")) { try { number=new BigDecimal(input);if (type.equals("int")) number.intValueExact();else if (type.equals("long")) number.longValueExact();else if (!Double.isFinite(number.doubleValue()) || number.signum()!=0 && number.doubleValue()==0) throw new ArithmeticException(); } catch (NumberFormatException | ArithmeticException invalid) { throw new IllegalArgumentException("Enter a finite value with the native numeric width",invalid); }String min=property(c.kind(),field,"minimum"),max=property(c.kind(),field,"maximum");if (min!=null && number.compareTo(new BigDecimal(min))<0 || max!=null && number.compareTo(new BigDecimal(max))>0) throw new IllegalArgumentException(constraints(c.kind(),field)); }
        BigDecimal desired=number;return edit(raw,c,e -> { String old=value(e,c.kind(),field);if (e.token(field)!=null && (desired!=null ? new BigDecimal(old).compareTo(desired)==0 : old.equals(input))) return e.sourceBytes();if (desired!=null) return type.equals("long") || type.equals("int") ? e.nullableInteger(field,desired.longValueExact()) : e.token(field)==null ? e.enable(field,desired.toString()) : e.number(field,desired.doubleValue());if (type.equals("boolean")) return e.token(field)==null ? e.enable(field,input) : e.bool(field,Boolean.parseBoolean(input));return e.nullableString(field,input); });
    }
    public static byte[] resetLeaf(String path,byte[] raw,Context c,String field) { authorize(path,c);field(c.kind(),field);return edit(raw,c,e -> { value(e,c.kind(),field);return e.token(field)==null ? e.sourceBytes() : e.removeField(field); }); }
    static List<String> readOnlyPaths(LosslessJson root) { var paths=new ArrayList<String>();try { var o=object(root);if (o==null) return List.of();for (String kind : o.fields()) { if (!LIST_FIELDS.contains(kind)) { paths.add(kind);continue; }try { var rows=entries(root,kind);limit(rows,false);if (rows!=null) for (int index=0;index<rows.size();index++) for (String field : rows.get(index).fields()) { String at=kind+"["+index+"]."+field;if (!editableFields(kind).contains(field)) paths.add(at);else try { value(rows.get(index),kind,field); } catch (IllegalArgumentException invalid) { paths.add(at); } } } catch (IllegalArgumentException invalid) { paths.add(kind); } } } catch (IllegalArgumentException invalid) { paths.add("proceduralObjects"); }return List.copyOf(paths); }
}
