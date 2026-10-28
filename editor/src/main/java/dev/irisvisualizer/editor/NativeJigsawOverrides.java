package dev.irisvisualizer.editor;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.function.Function;

/** Optional native jigsaw data; never resolves a registry or assembles a structure. */
public final class NativeJigsawOverrides {
    public static final List<String> OBJECT_FIELDS = List.of("jigsaw");
    public static final List<String> EDITABLE = List.of("startPool", "startJigsawName", "maxDepth", "maxDistanceHorizontal", "maxDistanceVertical", "useExpansionHack", "projectStartToHeightmap", "dimensionPaddingBottom", "dimensionPaddingTop", "liquidSettings");
    public static final List<String> NUMBERS = List.of("maxDepth", "maxDistanceHorizontal", "maxDistanceVertical", "dimensionPaddingBottom", "dimensionPaddingTop");
    public static final List<String> STRINGS = List.of("startPool", "startJigsawName");
    private static final Properties DATA=new Properties();
    static { try (var input=NativeJigsawOverrides.class.getResourceAsStream("/native-jigsaw-fields.properties")) { if (input==null) throw new IOException("Missing pinned jigsaw contracts");DATA.load(input);if (!"removeField".equals(DATA.getProperty("reset.policy"))) throw new IOException("Unsupported jigsaw inheritance policy"); } catch (IOException failure) { throw new ExceptionInInitializerError(failure); } }
    public record Context(String base,int placement,int source) {
        public Context { if (base==null || !StructurePlacements.CONTEXTS.contains(base) || placement<0 || source<0) throw new IllegalArgumentException("Select a native placement source"); }
        public String prefix() { return StructurePlacements.prefix(placement)+"nativeStructures."+source+".jigsaw."; }
    }
    private static void authorize(String path,Context c) { if (c==null || !c.base().equals(ResourceTypes.base(path))) throw new IllegalArgumentException("Jigsaw context does not match the resource host"); }
    private static void field(String field) { if (field==null || !EDITABLE.contains(field)) throw new IllegalArgumentException("Select a supported jigsaw override"); }
    static LosslessJson object(LosslessJson source) { String token=source.token("jigsaw");return token==null || token.equals("null") ? null : new LosslessJson(token.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
    private static byte[] editSource(byte[] raw,Context c,Function<LosslessJson,byte[]> operation) { return StructurePlacements.edit(raw,c.placement(),entry -> { StructurePlacements.sourceList(entry);return entry.objectArrayEdit("nativeStructures",c.source(),operation); }); }
    private static byte[] edit(byte[] raw,Context c,Function<LosslessJson,byte[]> operation) { return editSource(raw,c,source -> { if (object(source)==null) throw new IllegalArgumentException("Enable inline jigsaw overrides first");return source.objectEdit("jigsaw",operation,false); }); }
    public static byte[] enable(String path,byte[] raw,Context c) { authorize(path,c);return editSource(raw,c,s -> { if (object(s)!=null) throw new IllegalArgumentException("Jigsaw overrides already exist");return s.enableObject("jigsaw"); }); }
    public static byte[] reset(String path,byte[] raw,Context c) { authorize(path,c);return editSource(raw,c,s -> { object(s);return s.token("jigsaw")==null ? s.sourceBytes() : s.removeField("jigsaw"); }); }
    public static String help(String field) { field(field);return DATA.getProperty(field+".description"); }
    public static List<String> choices(String field) { field(field);String items=DATA.getProperty(field+".choices");return items==null ? List.of() : List.of(items.split(",")); }
    public static String bounds(String field) { return DATA.getProperty(field+".minimum")+".."+DATA.getProperty(field+".maximum"); }
    static String value(LosslessJson object,String field) {
        field(field);String token=object.token(field);if (token==null || token.equals("null")) return choices(field).isEmpty() ? "" : "SOURCE";
        if (NUMBERS.contains(field)) { try { return new BigDecimal(token).toBigIntegerExact().intValueExact()+""; } catch (NumberFormatException | ArithmeticException invalid) { throw new IllegalArgumentException("Stored override is not a signed 32-bit integer",invalid); } }
        if (field.equals("useExpansionHack")) { if (!List.of("true","false").contains(token)) throw new IllegalArgumentException("Stored override is not boolean");return token; }
        return object.stringToken(field);
    }
    public static byte[] set(String path,byte[] raw,Context c,String field,String input) {
        authorize(path,c);field(field);if (input==null || input.indexOf(0)>=0) throw new IllegalArgumentException("Enter an override value");
        String next=input;
        if (NUMBERS.contains(field)) { try { int number=new BigDecimal(input).toBigIntegerExact().intValueExact();if (number<Integer.parseInt(DATA.getProperty(field+".minimum")) || number>Integer.parseInt(DATA.getProperty(field+".maximum"))) throw new IllegalArgumentException("Override must be in "+bounds(field));next=Integer.toString(number); } catch (NumberFormatException | ArithmeticException invalid) { throw new IllegalArgumentException("Enter an exact signed 32-bit integer",invalid); } }
        else if (field.equals("useExpansionHack")) { if (!List.of("true","false").contains(input)) throw new IllegalArgumentException("Choose true, false or reset to inherit"); }
        else if (!choices(field).isEmpty()) { if (!choices(field).contains(input)) throw new IllegalArgumentException("Choose a pinned jigsaw option"); }
        else if (!input.isEmpty() && !(field.equals("startJigsawName") && input.equals("NONE")) && !input.matches("[a-z0-9_.-]+:[a-z0-9/._-]+")) throw new IllegalArgumentException("Use an empty inherited value, namespace:key, or NONE for the connector");
        String desired=next;return edit(raw,c,o -> { String old=value(o,field);if (o.token(field)!=null && !o.token(field).equals("null") && old.equals(desired)) return o.sourceBytes();if (NUMBERS.contains(field)) return o.nullableInteger(field,Long.valueOf(desired));if (field.equals("useExpansionHack")) return o.nullableBoolean(field,Boolean.valueOf(desired));return o.nullableString(field,desired); });
    }
    public static byte[] resetLeaf(String path,byte[] raw,Context c,String field) { authorize(path,c);field(field);return edit(raw,c,o -> { value(o,field);return o.token(field)==null ? o.sourceBytes() : o.removeField(field); }); }
    static List<String> readOnlyPaths(LosslessJson source) { var paths=new ArrayList<String>();try { var o=object(source);if (o!=null) for (String f : o.fields()) { if (!EDITABLE.contains(f)) paths.add("jigsaw."+f);else try { value(o,f); } catch (IllegalArgumentException invalid) { paths.add("jigsaw."+f); } } } catch (IllegalArgumentException invalid) { paths.add("jigsaw"); }return List.copyOf(paths); }
}
