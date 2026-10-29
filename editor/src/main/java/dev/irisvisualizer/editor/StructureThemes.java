package dev.irisvisualizer.editor;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;

/** Literal theme authoring only. Existing identities are retained; no pieces or native graphs are changed. */
public final class StructureThemes {
    public static final List<String> ROOT_FIELDS = List.of("themeSets");
    public static final List<String> EDITABLE = List.of("weight");
    public static final List<String> CREATION_FIELDS = List.of("key");
    public static final int MAX_ENTRIES=64,MAX_KEY_CHARACTERS=256;
    public enum Direction { UP, DOWN }
    private static final Properties DATA=new Properties();
    static {
        try (var input=StructureThemes.class.getResourceAsStream("/structure-theme-fields.properties")) {
            if (input==null) throw new IOException("Missing pinned structure theme definitions");DATA.load(input);
            if (!"trimExactCaseSensitive".equals(DATA.getProperty("key.policy")) || !"positiveIntWeightsSummedAsLong".equals(DATA.getProperty("total.policy"))) throw new IOException("Unsupported theme authoring policy");
        } catch (IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
    private static void authorize(String path) { if (!StructureDefinitions.supports(path)) throw new IllegalArgumentException("Theme controls require a structures JSON resource"); }
    public static String help(String field) { if (field==null || !ROOT_FIELDS.contains(field) && !EDITABLE.contains(field) && !CREATION_FIELDS.contains(field)) throw new IllegalArgumentException("Unknown theme field");return DATA.getProperty(field+".description"); }
    public static int minimumWeight() { return Integer.parseInt(DATA.getProperty("weight.minimum")); }
    public static int maximumWeight() { return Integer.parseInt(DATA.getProperty("weight.maximum")); }
    public static String entriesPrefix() { return "structureThemes#entry."; }
    public static String newPrefix() { return "structureThemes#new."; }
    public static String prefix(int index) { if (index<0 || index>=MAX_ENTRIES) throw new IllegalArgumentException("Select a theme entry");return entriesPrefix()+index+"."; }
    private static int integer(String text) {
        try { return new BigDecimal(text).intValueExact(); }
        catch (NumberFormatException | ArithmeticException | NullPointerException invalid) { throw new IllegalArgumentException("Enter an exact signed 32-bit theme weight",invalid); }
    }
    private static int positive(String text) { int value=integer(text);if (value<minimumWeight() || value>maximumWeight()) throw new IllegalArgumentException("Theme weights must be 1..2147483647");return value; }
    public static List<LosslessJson> entries(LosslessJson root) {
        String token=root.token("themeSets");if (token==null) return List.of();
        if (token.equals("null")) throw new IllegalArgumentException("Stored null themeSets is rejected by the native compiler; explicitly restore the implicit empty default to repair it");
        var entries=root.objectArray("themeSets");if (entries.size()>MAX_ENTRIES) throw new IllegalArgumentException("Theme authoring is limited to 64 entries; original data remains preserved/read-only");return entries;
    }
    public static String key(LosslessJson row) { return row.token("key")==null ? DATA.getProperty("key.default") : row.stringToken("key"); }
    public static int weight(LosslessJson row) { return integer(row.token("weight")==null ? DATA.getProperty("weight.default") : row.token("weight")); }
    public static long totalWeight(List<LosslessJson> rows) { long total=0;for (var row : rows) total=Math.addExact(total,positive(Integer.toString(weight(row))));return total; }
    private static void newKey(String key) {
        if (key==null || key.length()>MAX_KEY_CHARACTERS || key.isBlank() || !key.equals(key.trim()) || key.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Use a nonblank exact theme key up to 256 characters without control characters or leading/trailing ASCII whitespace");
        for (int offset=0;offset<key.length();) { int code=key.codePointAt(offset);if (code<=0xffff && Character.isSurrogate((char)code)) throw new IllegalArgumentException("Theme keys require valid Unicode without isolated surrogates");offset+=Character.charCount(code); }
    }
    private static void unique(List<LosslessJson> rows,String key) {
        for (var row : rows) {
            String old;
            try { old=key(row); }
            catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("Cannot add a theme while an existing key has an unsupported type",invalid); }
            if (old.trim().equals(key)) throw new IllegalArgumentException("A theme with that exact normalized key already exists");
        }
    }
    private static LosslessJson row(List<LosslessJson> rows,int index) { prefix(index);if (index>=rows.size()) throw new IllegalArgumentException("Select an existing theme entry");return rows.get(index); }
    public static byte[] setWeight(String path,byte[] raw,int index,String text) {
        authorize(path);int next=positive(text);var root=new LosslessJson(raw);var row=row(entries(root),index);int old=weight(row);
        return root.objectArrayEdit("themeSets",index,entry -> entry.token("weight")==null ? entry.enable("weight",Integer.toString(next)) : old==next ? entry.sourceBytes() : entry.integer("weight",next));
    }
    public static byte[] append(String path,byte[] raw,String key,String text) {
        authorize(path);newKey(key);int weight=positive(text);var root=new LosslessJson(raw);var rows=entries(root);if (rows.size()>=MAX_ENTRIES) throw new IllegalArgumentException("At most 64 declared themes");unique(rows,key);
        byte[] row=new LosslessJson("{}".getBytes(StandardCharsets.UTF_8)).enableStringPath("key",key);row=new LosslessJson(row).enable("weight",Integer.toString(weight));
        if (root.token("themeSets")==null) root=new LosslessJson(root.enableArray("themeSets"));return root.objectArrayInsert("themeSets",rows.size(),row);
    }
    /** Copy to the end with a new identity; original unknown fields and existing key references are retained. */
    public static byte[] copy(String path,byte[] raw,int index,String key) {
        authorize(path);newKey(key);var root=new LosslessJson(raw);var rows=entries(root);if (rows.size()>=MAX_ENTRIES) throw new IllegalArgumentException("At most 64 declared themes");var source=row(rows,index);positive(Integer.toString(weight(source)));unique(rows,key);
        byte[] copied=source.token("key")==null ? source.enableStringPath("key",key) : source.stringValue("key",key);return root.objectArrayInsert("themeSets",rows.size(),copied);
    }
    public static byte[] move(String path,byte[] raw,int index,Direction direction) {
        authorize(path);if (direction==null) throw new IllegalArgumentException("Select a move direction");var root=new LosslessJson(raw);var rows=entries(root);row(rows,index);int target=direction==Direction.UP ? index-1 : index+1;
        if (target<0 || target>=rows.size()) throw new IllegalArgumentException("Theme is already at that end of the list");return root.objectArrayMove("themeSets",index,target);
    }
    /** Remove only a null/empty override; declared identities cannot be renamed or removed here. */
    public static byte[] resetEmpty(String path,byte[] raw) {
        authorize(path);var root=new LosslessJson(raw);String token=root.token("themeSets");if (token==null) return raw.clone();
        if (!token.equals("null") && !entries(root).isEmpty()) throw new IllegalArgumentException("Existing theme keys stay fixed until piece-reference maintenance is available");return root.removeField("themeSets");
    }
    public static List<String> otherFields(LosslessJson row) { return row.fields().stream().filter(field -> !EDITABLE.contains(field) && !CREATION_FIELDS.contains(field)).toList(); }
}
