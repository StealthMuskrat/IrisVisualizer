package dev.irisvisualizer.editor;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;

/** Bounded authoring of structure root controls; never assembles or loads native resources. */
public final class StructureDefinitions {
    public static final List<String> EDITABLE = List.of("startPool","maxDepth","maxSizeChunks","mode","compatibility","branchFailurePolicy","requireCaps","placeMode");
    public static final List<String> NUMBERS = List.of("maxDepth","maxSizeChunks");
    public static final List<String> ENUMS = List.of("mode","compatibility","branchFailurePolicy","placeMode");
    public static final int MAX_DISPLAYED_POOLS=200,MAX_POOL_QUERY=256;
    private static final List<String> NULL_DEFAULTS=List.of("mode","compatibility","branchFailurePolicy");
    private static final Properties DATA=new Properties();
    static {
        try (var input=StructureDefinitions.class.getResourceAsStream("/structure-definition-fields.properties")) {
            if (input==null) throw new IOException("Missing pinned structure definitions");DATA.load(input);
            if (!"structures".equals(DATA.getProperty("folder")) || !"jigsaw-pools".equals(DATA.getProperty("pool.folder")) || !"themesCapsAndBranchTermination".equals(DATA.getProperty("portable.policy"))) throw new IOException("Unsupported structure definition policy");
        } catch (IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
    public static boolean supports(String path) { return "structures".equals(ResourceTypes.base(path)); }
    private static void authorize(String path) { if (!supports(path)) throw new IllegalArgumentException("Select a structures JSON resource"); }
    private static void field(String field) { if (field==null || !EDITABLE.contains(field)) throw new IllegalArgumentException("Structure field is read-only"); }
    public static String help(String field) { field(field);return DATA.getProperty(field+".description"); }
    public static String defaultValue(String field) { field(field);return DATA.getProperty(field+".default"); }
    public static int minimum(String field) { if (field==null || !NUMBERS.contains(field)) throw new IllegalArgumentException("Select a structure integer");return Integer.parseInt(DATA.getProperty(field+".minimum")); }
    public static int maximum(String field) { minimum(field);return Integer.parseInt(DATA.getProperty(field+".maximum")); }
    public static List<String> choices(String field) { if (field==null || !ENUMS.contains(field)) throw new IllegalArgumentException("Select a structure enum");return List.of(DATA.getProperty(field+".choices").split(",")); }
    /** Absent defaults are display-only. Only the three resolved enums support stored null defaults. */
    public static String value(LosslessJson root,String field) {
        field(field);String token=root.token(field);
        if (token==null || token.equals("null") && NULL_DEFAULTS.contains(field)) return defaultValue(field);
        if (NUMBERS.contains(field)) {
            try { return Integer.toString(new BigDecimal(token).intValueExact()); }
            catch (NumberFormatException | ArithmeticException invalid) { throw new IllegalArgumentException("Stored structure integer is unsupported (read-only)",invalid); }
        }
        if (field.equals("requireCaps")) { if (!token.equals("true") && !token.equals("false")) throw new IllegalArgumentException("Stored cap flag is unsupported (read-only)");return token; }
        try { return root.stringToken(field); }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("Stored "+field+" is not a supported string (read-only)",invalid); }
    }
    public static byte[] setInteger(String path,byte[] raw,String field,String input) {
        authorize(path);minimum(field);int next;
        try { next=new BigDecimal(input).intValueExact(); }
        catch (NumberFormatException | ArithmeticException | NullPointerException invalid) { throw new IllegalArgumentException("Enter an exact signed 32-bit integer",invalid); }
        if (next<minimum(field) || next>maximum(field)) throw new IllegalArgumentException("Structure integer must be within "+minimum(field)+".."+maximum(field));
        var root=new LosslessJson(raw);String old=value(root,field);
        return root.token(field)==null ? root.enable(field,Integer.toString(next)) : Integer.parseInt(old)==next ? raw.clone() : root.integer(field,next);
    }
    public static byte[] setChoice(String path,byte[] raw,String field,String next) {
        authorize(path);if (next==null || !choices(field).contains(next)) throw new IllegalArgumentException("Choose a pinned structure enum");var root=new LosslessJson(raw);String old=value(root,field);
        if (root.token(field)!=null && !root.token(field).equals("null") && old.equals(next)) return raw.clone();
        return NULL_DEFAULTS.contains(field) ? root.nullableString(field,next) : root.token(field)==null ? root.enableStringPath(field,next) : root.stringValue(field,next);
    }
    public static byte[] setCaps(String path,byte[] raw,boolean next) {
        authorize(path);var root=new LosslessJson(raw);String old=value(root,"requireCaps");return root.token("requireCaps")==null ? root.enable("requireCaps",Boolean.toString(next)) : old.equals(Boolean.toString(next)) ? raw.clone() : root.bool("requireCaps",next);
    }
    /** Restore class default by removing a supported optional field. Required startPool is never removed. */
    public static byte[] reset(String path,byte[] raw,String field) {
        authorize(path);field(field);if (field.equals("startPool")) throw new IllegalArgumentException("The start pool is required");var root=new LosslessJson(raw);value(root,field);return root.token(field)==null ? raw.clone() : root.removeField(field);
    }
    public static List<String> pools(PackWorkspace workspace) {
        String prefix=DATA.getProperty("pool.folder")+"/";
        return workspace.paths().stream().filter(path -> path.startsWith(prefix) && path.endsWith(".json")).map(path -> path.substring(prefix.length(),path.length()-5)).filter(ResourceTypes::referenceKey).sorted().toList();
    }
    public static final class PoolCache {
        private PackWorkspace workspace;private long revision=-1;private List<String> pools;
        private String query;private PoolMatches matches;
        public void clear() { workspace=null;revision=-1;pools=null;query=null;matches=null; }
        public List<String> choices(PackWorkspace current) { Objects.requireNonNull(current);if (current!=workspace || current.resourceRevision()!=revision) { workspace=current;revision=current.resourceRevision();pools=StructureDefinitions.pools(current);query=null;matches=null; }return pools; }
        public PoolMatches filter(PackWorkspace current,String text) {
            if (text==null || text.length()>MAX_POOL_QUERY || text.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Use up to 256 pool-filter characters without controls");
            var keys=choices(current);String normalized=text.strip().toLowerCase(Locale.ROOT);
            if (matches==null || !normalized.equals(query)) { int count=0;var found=new ArrayList<String>();for (String key : keys) if (key.toLowerCase(Locale.ROOT).contains(normalized)) { count++;if (found.size()<MAX_DISPLAYED_POOLS) found.add(key); }matches=new PoolMatches(count,found);query=normalized; }
            return matches;
        }
    }
    public record PoolMatches(int count,List<String> keys) { public PoolMatches { keys=List.copyOf(keys); } }
    public static byte[] setPool(String path,byte[] raw,String key,PackWorkspace workspace) {
        authorize(path);if (key==null || !pools(workspace).contains(key)) throw new IllegalArgumentException("Choose an exact in-pack jigsaw-pools JSON key; contents and graph compatibility are unchecked");var root=new LosslessJson(raw);String old=value(root,"startPool");return root.token("startPool")==null ? root.enableStringPath("startPool",key) : old.equals(key) ? raw.clone() : root.stringValue("startPool",key);
    }
    public static byte[] template(PackWorkspace workspace,String key) { return setPool("structures/template.json","{}".getBytes(StandardCharsets.UTF_8),key,workspace); }
    public static List<String> readOnlyFields(LosslessJson root) { return root.fields().stream().filter(field -> !EDITABLE.contains(field) && !StructureWorkcells.ROOT_FIELDS.contains(field)).toList(); }
    /** Only three local native portability constraints. Never gates authoring or file operations. */
    public static List<String> advisories(LosslessJson root) {
        var result=new ArrayList<String>();
        try {
            String compatibility=value(root,"compatibility");if (!choices("compatibility").contains(compatibility)) return List.of("Portability checks unavailable for the stored compatibility value.");
            if (!compatibility.equals("VANILLA_PORTABLE")) return List.of();
            try { if (value(root,"requireCaps").equals("true")) result.add("VANILLA_PORTABLE rejects requireCaps=true in the native graph compiler."); } catch (IllegalArgumentException invalid) { result.add("Cap portability check unavailable for the stored value."); }
            try { String branch=value(root,"branchFailurePolicy");if (!choices("branchFailurePolicy").contains(branch)) result.add("Branch portability check unavailable for the stored value.");else if (!branch.equals("TERMINATE_BRANCH")) result.add("VANILLA_PORTABLE requires TERMINATE_BRANCH in the native graph compiler."); } catch (IllegalArgumentException invalid) { result.add("Branch portability check unavailable for the stored value."); }
            String themes=root.token("themeSets");if (themes!=null) { try { if (!root.objectArray("themeSets").isEmpty()) result.add("VANILLA_PORTABLE rejects nonempty themeSets in the native graph compiler."); } catch (IllegalArgumentException invalid) { result.add("Theme portability check unavailable for the stored value."); } }
        } catch (IllegalArgumentException invalid) { result.add("Portability checks unavailable for the stored compatibility value."); }
        return List.copyOf(result);
    }
}
