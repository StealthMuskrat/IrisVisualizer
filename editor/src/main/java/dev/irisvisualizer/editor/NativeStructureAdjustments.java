package dev.irisvisualizer.editor;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.function.Function;

/** Native structure adjustment data only; no registry, terrain integration or placement is executed. */
public final class NativeStructureAdjustments {
    public static final List<String> ROOT_FIELDS = List.of("importedStructures");
    public static final List<String> LIST_FIELDS = List.of("adjustments");
    public static final List<String> EDITABLE = List.of("match", "yShift", "yBand", "preserveSourceY", "stilt", "terrain");
    public static final List<String> STILT_FIELDS = List.of("maxDepth", "spacing", "supportNonOccluding");
    public static final List<String> TERRAIN_FIELDS = List.of("mode", "horizontalPadding", "flattenRange", "ceilingPadding", "floorPadding", "shape", "erosionStrength", "erosionFrequency", "lobeFrequency", "lobeStrength");
    public static final List<String> BAND_FIELDS = List.of("min", "max");
    public static final List<String> OPTIONAL = List.of("yBand", "stilt", "terrain");
    public static final int MAXIMUM_ENTRIES=64;
    private static final Properties DATA=new Properties();
    static {
        try (var input=NativeStructureAdjustments.class.getResourceAsStream("/native-structure-adjustment-fields.properties")) {
            if (input==null) throw new IOException("Missing pinned native adjustment definitions");DATA.load(input);
            if (!"stackShiftsLastNonNullOverrides".equals(DATA.getProperty("resolution.policy")) || !"sortedEndpoints".equals(DATA.getProperty("band.policy"))) throw new IOException("Unsupported adjustment policy");
        } catch (IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
    private static void authorize(String path) { if (!DimensionFields.dimension(path)) throw new IllegalArgumentException("Native structure adjustments are dimension-only"); }
    public static String prefix() { return "nativeAdjustment#"; }
    public static String prefix(int index) { if (index<0) throw new IllegalArgumentException("Select an adjustment");return prefix()+index+"."; }
    static List<LosslessJson> entries(LosslessJson root) { var object=NativeGenerationFilters.object(root,"importedStructures");return object==null || object.token("adjustments")==null ? null : object.objectArray("adjustments"); }
    private static void limit(int size,boolean adding) { if (size>MAXIMUM_ENTRIES || adding && size>=MAXIMUM_ENTRIES) throw new IllegalArgumentException("Adjustment lists have a 64-entry editor limit"); }
    static byte[] edit(byte[] original,int index,Function<LosslessJson,byte[]> operation) {
        var root=new LosslessJson(original);var entries=entries(root);if (entries==null) throw new IllegalArgumentException("Enable the adjustment list first");limit(entries.size(),false);return root.objectEdit("importedStructures",p -> p.objectArrayEdit("adjustments",index,operation),false);
    }
    public static byte[] change(String path,byte[] original,RiverProfiles.Action action,int index) {
        authorize(path);if (action==null) throw new IllegalArgumentException("Choose an adjustment action");var root=new LosslessJson(original);var entries=entries(root);int size=entries==null ? 0 : entries.size();limit(size,action==RiverProfiles.Action.ADD || action==RiverProfiles.Action.DUPLICATE);
        if (action!=RiverProfiles.Action.ADD && (index<0 || index>=size)) throw new IllegalArgumentException("Select an existing adjustment");
        return root.objectEdit("importedStructures",p -> { if (p.token("adjustments")==null) p=new LosslessJson(p.enableArray("adjustments"));if (action==RiverProfiles.Action.REMOVE && size==1) return p.removeField("adjustments");return BiomeLayers.changeLayers(p,"adjustments",action,index); },true);
    }
    private static List<String> fields(String context) { if (context==null) throw new IllegalArgumentException("Select an adjustment context");return switch(context) { case "" -> List.of("yShift","preserveSourceY");case "stilt" -> STILT_FIELDS;case "terrain" -> TERRAIN_FIELDS;case "yBand" -> BAND_FIELDS;default -> throw new IllegalArgumentException("Unsupported adjustment context"); }; }
    private static String key(String context,String field) { if (field==null || !fields(context).contains(field)) throw new IllegalArgumentException("Unsupported adjustment field");return context.isEmpty() ? field : context+"."+field; }
    public static String help(String context,String field) { return DATA.getProperty((context==null || context.isEmpty() ? "" : context+".")+field+".description",""); }
    public static String defaultToken(String context,String field) { return DATA.getProperty(key(context,field)+".default"); }
    public static boolean booleanField(String context,String field) { return "boolean".equals(DATA.getProperty(key(context,field)+".type")); }
    public static boolean enumField(String context,String field) { return "enum".equals(DATA.getProperty(key(context,field)+".type")); }
    public static List<String> choices(String field) { if (!List.of("mode","shape").contains(field)) throw new IllegalArgumentException("Unsupported terrain enum");return List.of(DATA.getProperty("terrain."+field+".choices").split(",")); }
    public static String choiceHelp(String field,String choice) { return DATA.getProperty("terrain."+field+"."+choice+".description",""); }
    public static String constraints(String context,String field) { String key=key(context,field);return ("int".equals(DATA.getProperty(key+".type")) ? "Exact integer " : "Finite number ")+DATA.getProperty(key+".minimum")+".."+DATA.getProperty(key+".maximum")+"."; }
    static LosslessJson object(LosslessJson entry,String context) { if (context.isEmpty()) return entry;if (!OPTIONAL.contains(context)) throw new IllegalArgumentException("Unsupported optional adjustment");String token=entry.token(context);return token==null || token.equals("null") ? null : new LosslessJson(token.getBytes(StandardCharsets.UTF_8)); }
    private static byte[] editOwner(byte[] original,int index,String context,Function<LosslessJson,byte[]> operation) { fields(context);return edit(original,index,e -> { if (context.isEmpty()) return operation.apply(e);if (object(e,context)==null) throw new IllegalArgumentException("Enable this inline adjustment object first");return e.objectEdit(context,operation,false); }); }
    public static byte[] enableOptional(String path,byte[] original,int index,String context) { authorize(path);if (!OPTIONAL.contains(context)) throw new IllegalArgumentException("Unsupported optional adjustment");return edit(original,index,e -> { if (object(e,context)!=null) throw new IllegalArgumentException("Optional object already exists");return e.enableObject(context); }); }
    public static byte[] removeOptional(String path,byte[] original,int index,String context) { authorize(path);if (!OPTIONAL.contains(context)) throw new IllegalArgumentException("Unsupported optional adjustment");return edit(original,index,e -> { object(e,context);return e.token(context)==null ? e.sourceBytes() : e.removeField(context); }); }
    static String value(LosslessJson owner,String context,String field) {
        String key=key(context,field),token=owner.token(field);if (token==null) return DATA.getProperty(key+".default");String type=DATA.getProperty(key+".type");
        if (type.equals("boolean")) { if (!token.equals("true") && !token.equals("false")) throw new IllegalArgumentException("Stored adjustment toggle is not boolean");return token; }
        if (type.equals("enum")) return owner.stringToken(field);
        try { if (!LosslessJson.numericToken(token)) throw new NumberFormatException();var number=new BigDecimal(token);if (type.equals("int")) number.intValueExact();else if (!Double.isFinite(number.doubleValue())) throw new NumberFormatException();return token; }
        catch (NumberFormatException | ArithmeticException invalid) { throw new IllegalArgumentException("Stored adjustment scalar has an unsupported numeric type or width",invalid); }
    }
    private static BigDecimal input(String context,String field,String input) {
        String key=key(context,field),type=DATA.getProperty(key+".type");if (!List.of("int","double").contains(type)) throw new IllegalArgumentException("Select an adjustment number");
        try { var number=new BigDecimal(input);if (type.equals("int")) number.intValueExact();if (!Double.isFinite(number.doubleValue()) || number.compareTo(new BigDecimal(DATA.getProperty(key+".minimum")))<0 || number.compareTo(new BigDecimal(DATA.getProperty(key+".maximum")))>0) throw new NumberFormatException();return number; }
        catch (NumberFormatException | ArithmeticException | NullPointerException invalid) { throw new IllegalArgumentException(constraints(context,field),invalid); }
    }
    private static byte[] putNumber(LosslessJson owner,String context,String field,BigDecimal number) { value(owner,context,field);if (owner.token(field)==null) return owner.enable(field,number.toString());return new BigDecimal(owner.token(field)).compareTo(number)==0 ? owner.sourceBytes() : "int".equals(DATA.getProperty(key(context,field)+".type")) ? owner.integer(field,number.intValueExact()) : owner.number(field,number.doubleValue()); }
    public static byte[] setNumber(String path,byte[] original,int index,String context,String field,String text) { authorize(path);var number=input(context,field,text);return editOwner(original,index,context,o -> putNumber(o,context,field,number)); }
    public static byte[] setBoolean(String path,byte[] original,int index,String context,String field,boolean flag) { authorize(path);if (!booleanField(context,field)) throw new IllegalArgumentException("Select an adjustment toggle");return editOwner(original,index,context,o -> { value(o,context,field);return o.token(field)==null ? o.enable(field,Boolean.toString(flag)) : o.bool(field,flag); }); }
    public static byte[] setChoice(String path,byte[] original,int index,String field,String choice) { authorize(path);if (choice==null || !choices(field).contains(choice)) throw new IllegalArgumentException("Choose a pinned terrain option");return editOwner(original,index,"terrain",o -> { String old=value(o,"terrain",field);return o.token(field)!=null && old.equals(choice) ? o.sourceBytes() : o.token(field)==null ? o.enableStringPath(field,choice) : o.stringValue(field,choice); }); }
    public static byte[] resetLeaf(String path,byte[] original,int index,String context,String field) { authorize(path);key(context,field);return editOwner(original,index,context,o -> { value(o,context,field);return o.token(field)==null ? o.sourceBytes() : o.removeField(field); }); }
    public static byte[] setBand(String path,byte[] original,int index,String min,String max) { authorize(path);var low=input("yBand","min",min);var high=input("yBand","max",max);return editOwner(original,index,"yBand",o -> { value(o,"yBand","min");value(o,"yBand","max");return putNumber(new LosslessJson(putNumber(o,"yBand","min",low)),"yBand","max",high); }); }
    static List<String> matches(LosslessJson entry) { return entry.token("match")==null ? null : entry.stringArrayPath("match"); }
    private static void matchKey(String input) { if (input==null || !input.matches("[a-z0-9_.-]+:[a-z0-9/._-]*")) throw new IllegalArgumentException("Use a lowercase structure namespace:key or namespace: family"); }
    public static byte[] setMatch(String path,byte[] original,int index,int row,String input) { authorize(path);matchKey(input);return edit(original,index,e -> { var list=matches(e);if (list==null) throw new IllegalArgumentException("Enable match entries first");limit(list.size(),false);if (row<0 || row>=list.size()) throw new IllegalArgumentException("Select a match entry");return list.get(row).equals(input) ? e.sourceBytes() : e.stringArrayPath("match",row,input); }); }
    public static byte[] changeMatch(String path,byte[] original,int index,RiverProfiles.Action action,int row,String input) {
        authorize(path);if (action==null) throw new IllegalArgumentException("Choose a match action");if (action==RiverProfiles.Action.ADD) matchKey(input);
        return edit(original,index,e -> { var list=matches(e);int size=list==null ? 0 : list.size();limit(size,action==RiverProfiles.Action.ADD || action==RiverProfiles.Action.DUPLICATE);if (action==RiverProfiles.Action.ADD) return new LosslessJson(list==null ? e.enableArray("match") : e.sourceBytes()).stringArrayPath("match",size,input);if (row<0 || row>=size) throw new IllegalArgumentException("Select a match entry");return switch(action) { case REMOVE -> size==1 ? e.removeField("match") : e.stringArrayPath("match",row,null);case DUPLICATE -> e.stringArrayDuplicate("match",row);case UP -> { if (row==0) throw new IllegalArgumentException("Already first");yield e.stringArrayMove("match",row,row-1); }case DOWN -> { if (row+1==size) throw new IllegalArgumentException("Already last");yield e.stringArrayMove("match",row,row+1); }default -> throw new IllegalArgumentException("Unsupported match action"); }; });
    }
    static List<String> readOnlyPaths(LosslessJson root) {
        var paths=new ArrayList<String>();String base="importedStructures.adjustments";
        try { var entries=entries(root);if (entries==null) return List.of();limit(entries.size(),false);for (int i=0;i<entries.size();i++) { var entry=entries.get(i);String at=base+"["+i+"].";
            for (String field : entry.fields()) {
                if (field.equals("match")) { try { limit(matches(entry).size(),false); } catch (IllegalArgumentException invalid) { paths.add(at+field); } }
                else if (OPTIONAL.contains(field)) { try { var object=object(entry,field);if (object!=null) for (String child : object.fields()) { if (!fields(field).contains(child)) paths.add(at+field+"."+child);else try { value(object,field,child); } catch (IllegalArgumentException invalid) { paths.add(at+field+"."+child); } } } catch (IllegalArgumentException invalid) { paths.add(at+field); } }
                else if (!EDITABLE.contains(field)) paths.add(at+field);else try { value(entry,"",field); } catch (IllegalArgumentException invalid) { paths.add(at+field); }
            }
        } } catch (IllegalArgumentException invalid) { paths.add(base); }return List.copyOf(paths);
    }
}
