package dev.irisvisualizer.editor;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/** Literal pool authoring. Does not resolve graphs, filter eligibility or consume RNG. */
public final class JigsawPools {
    public static final List<String> EDITABLE = List.of("pieces","fallback","mandatoryFallback");
    public static final List<String> ENTRY_FIELDS = List.of("piece","weight","chance","empty");
    public static final int MAX_ENTRIES=64,MAX_KEY_CHARACTERS=256,MAX_RESULTS=200;
    private static final Properties DATA=new Properties();
    static { try (var input=JigsawPools.class.getResourceAsStream("/jigsaw-pool-fields.properties")) {
        if (input==null) throw new IOException("Missing pinned jigsaw pool definitions");DATA.load(input);
        if (!"jigsaw-pools".equals(DATA.getProperty("folder")) || !"jigsaw-pieces".equals(DATA.getProperty("piece.folder")) || !"primaryThenDirectFallbackOnly".equals(DATA.getProperty("fallback.policy")) || !"removePieceWhenEmpty".equals(DATA.getProperty("empty.policy"))) throw new IOException("Unsupported pool authoring policy");
    } catch (IOException failure) { throw new ExceptionInInitializerError(failure); } }
    public static boolean supports(String path) { return "jigsaw-pools".equals(ResourceTypes.base(path)); }
    private static void authorize(String path) { if (!supports(path)) throw new IllegalArgumentException("Select a jigsaw-pools JSON resource"); }
    public static String help(String field) { if (field==null || !EDITABLE.contains(field) && !ENTRY_FIELDS.contains(field)) throw new IllegalArgumentException("Unknown pool field");return DATA.getProperty(field+".description"); }
    public static String entriesPrefix() { return "jigsawPool#entry."; }
    public static String prefix(int index) { if (index<0 || index>=MAX_ENTRIES) throw new IllegalArgumentException("Select a pool membership");return entriesPrefix()+index+"."; }
    public static List<LosslessJson> entries(LosslessJson root) {
        if (root.token("pieces")==null) return List.of();var rows=root.objectArray("pieces");if (rows.size()>MAX_ENTRIES) throw new IllegalArgumentException("Pool controls have a 64-membership editor limit; original data is preserved read-only");return rows;
    }
    private static LosslessJson row(LosslessJson root,int index) { prefix(index);var rows=entries(root);if (index>=rows.size()) throw new IllegalArgumentException("Select an existing membership");return rows.get(index); }
    private static String optionalString(LosslessJson root,String field,String fallback) { String token=root.token(field);return token==null || token.equals("null") ? fallback : root.stringToken(field); }
    private static boolean bool(LosslessJson root,String field) { String token=root.token(field);if (token==null) return Boolean.parseBoolean(DATA.getProperty(field+".default"));if (!token.equals("true") && !token.equals("false")) throw new IllegalArgumentException("Stored "+field+" is unsupported (read-only)");return token.equals("true"); }
    public static String fallback(LosslessJson root) { return optionalString(root,"fallback",DATA.getProperty("fallback.default")); }
    public static String piece(LosslessJson row) { return optionalString(row,"piece",""); }
    public static boolean empty(LosslessJson row) { return bool(row,"empty"); }
    public static boolean mandatory(LosslessJson root) { return bool(root,"mandatoryFallback"); }
    private static int integer(String value) { try { return new BigDecimal(value).intValueExact(); } catch (NumberFormatException | ArithmeticException | NullPointerException failure) { throw new IllegalArgumentException("Use an exact signed 32-bit weight",failure); } }
    public static int weight(LosslessJson row) { return integer(row.token("weight")==null ? DATA.getProperty("weight.default") : row.token("weight")); }
    private static int positive(String value) { int weight=integer(value);if (weight<Integer.parseInt(DATA.getProperty("weight.minimum"))) throw new IllegalArgumentException("Pool weights must be 1..2147483647");return weight; }
    private static double number(String value) { try { double next=new BigDecimal(value).doubleValue();if (!Double.isFinite(next)) throw new NumberFormatException();return next; } catch (NumberFormatException | NullPointerException failure) { throw new IllegalArgumentException("Use a finite decimal chance",failure); } }
    public static double chance(LosslessJson row) { return number(row.token("chance")==null ? DATA.getProperty("chance.default") : row.token("chance")); }
    private static double checkedChance(String input) { double value=number(input);var exact=new BigDecimal(input);if (exact.compareTo(new BigDecimal(DATA.getProperty("chance.minimum")))<0 || exact.compareTo(new BigDecimal(DATA.getProperty("chance.maximum")))>0 || exact.signum()!=0 && value==0) throw new IllegalArgumentException("Chance must be 0..1 without underflow");return value; }
    public static long totalWeight(List<LosslessJson> rows) { long total=0;for (var row : rows) total=Math.addExact(total,positive(Integer.toString(weight(row))));return total; }
    private static boolean referenceKey(String key) { return ResourceTypes.referenceKey(key) && key.codePoints().noneMatch(c -> c<=0xffff && Character.isSurrogate((char)c)) && key.length()<=MAX_KEY_CHARACTERS && key.equals(key.trim()) && key.chars().noneMatch(Character::isISOControl); }
    private static List<String> references(PackWorkspace workspace,String family) {
        if (!List.of("jigsaw-pools","jigsaw-pieces").contains(family)) throw new IllegalArgumentException("Select a pool or piece catalog");String prefix=family+"/";
        return workspace.paths().stream().filter(p -> p.startsWith(prefix) && p.endsWith(".json")).map(p -> p.substring(prefix.length(),p.length()-5)).filter(JigsawPools::referenceKey).sorted().toList();
    }
    private static void reference(PackWorkspace workspace,String family,String key) { if (!referenceKey(key) || !workspace.paths().contains(family+"/"+key+".json")) throw new IllegalArgumentException("Choose an exact existing "+family+" JSON key; file contents are unchecked"); }
    public record Matches(int count,List<String> keys) { public Matches { keys=List.copyOf(keys); } }
    public static final class Catalog {
        private PackWorkspace workspace;private long revision=-1;private final HashMap<String,ReferenceChoices.Catalog> catalogs=new HashMap<>();
        public void clear(){workspace=null;revision=-1;catalogs.clear();}
        public ReferenceChoices.Catalog catalog(PackWorkspace current,String family){
            if(!("jigsaw-pools".equals(family) || "jigsaw-pieces".equals(family)))throw new IllegalArgumentException("Select a pool or piece catalog");java.util.Objects.requireNonNull(current);
            if(workspace!=current || revision!=current.resourceRevision()){clear();workspace=current;revision=current.resourceRevision();}
            return catalogs.computeIfAbsent(family,f->new ReferenceChoices.Catalog(references(current,f)));
        }
        public ReviewPagination.Page<String> page(PackWorkspace current,String family,String query,int requested){ReferenceChoices.query(query);return catalog(current,family).page(query,requested);}
        /** Compatibility first-page view; exact keys and original ordering are unchanged. */
        public Matches filter(PackWorkspace current,String family,String query){var page=page(current,family,query,0);return new Matches(page.window().matches(),page.entries());}
    }
    public static byte[] setFallback(String path,byte[] raw,String key,PackWorkspace workspace) {
        authorize(path);if (key==null) throw new IllegalArgumentException("Choose a fallback key or explicit empty fallback");if (!key.isEmpty()) reference(workspace,"jigsaw-pools",key);var root=new LosslessJson(raw);String old=fallback(root);return old.equals(key) && root.token("fallback")!=null && !root.token("fallback").equals("null") ? raw.clone() : root.token("fallback")==null ? root.enableStringPath("fallback",key) : root.nullableString("fallback",key);
    }
    public static byte[] setMandatory(String path,byte[] raw,boolean next) { authorize(path);var root=new LosslessJson(raw);boolean old=mandatory(root);return root.token("mandatoryFallback")==null ? root.enable("mandatoryFallback",Boolean.toString(next)) : old==next ? raw.clone() : root.bool("mandatoryFallback",next); }
    public static byte[] reset(String path,byte[] raw,String field) { authorize(path);var root=new LosslessJson(raw);if ("fallback".equals(field)) fallback(root);else if ("mandatoryFallback".equals(field)) mandatory(root);else throw new IllegalArgumentException("Select an optional pool setting");return root.token(field)==null ? raw.clone() : root.removeField(field); }
    public static byte[] setWeight(String path,byte[] raw,int index,String input) { authorize(path);int next=positive(input);var root=new LosslessJson(raw);var row=row(root,index);int old=weight(row);return root.objectArrayEdit("pieces",index,r -> r.token("weight")==null ? r.enable("weight",Integer.toString(next)) : old==next ? r.sourceBytes() : r.integer("weight",next)); }
    public static byte[] setChance(String path,byte[] raw,int index,String input) { authorize(path);double next=checkedChance(input);var root=new LosslessJson(raw);var row=row(root,index);double old=chance(row);return root.objectArrayEdit("pieces",index,r -> r.token("chance")==null ? r.enable("chance",Double.toString(next)) : old==next ? r.sourceBytes() : r.number("chance",next)); }
    /** Explicit kind conversion is one byte edit; dormant/ambiguous piece references are removed for empty memberships. */
    public static byte[] setMembership(String path,byte[] raw,int index,boolean empty,String key,PackWorkspace workspace) {
        authorize(path);if (!empty) reference(workspace,"jigsaw-pieces",key);var root=new LosslessJson(raw);var row=row(root,index);piece(row);boolean old=empty(row);
        return root.objectArrayEdit("pieces",index,r -> {
            byte[] next;
            if (empty) next=r.token("piece")==null ? r.sourceBytes() : r.removeField("piece");
            else next=r.token("piece")==null ? r.enableStringPath("piece",key) : piece(r).equals(key) && !r.token("piece").equals("null") ? r.sourceBytes() : r.nullableString("piece",key);
            var updated=new LosslessJson(next);return updated.token("empty")==null ? empty ? updated.enable("empty","true") : next : old==empty ? next : updated.bool("empty",empty);
        });
    }
    public static byte[] append(String path,byte[] raw,boolean empty,String key,PackWorkspace workspace) {
        authorize(path);if (!empty) reference(workspace,"jigsaw-pieces",key);var root=new LosslessJson(raw);var rows=entries(root);if (rows.size()>=MAX_ENTRIES) throw new IllegalArgumentException("At most 64 memberships");
        byte[] row=empty ? new LosslessJson("{}".getBytes(StandardCharsets.UTF_8)).enable("empty","true") : new LosslessJson("{}".getBytes(StandardCharsets.UTF_8)).enableStringPath("piece",key);if (root.token("pieces")==null) root=new LosslessJson(root.enableArray("pieces"));return root.objectArrayInsert("pieces",rows.size(),row);
    }
    public static byte[] change(String path,byte[] raw,RiverProfiles.Action action,int index) {
        authorize(path);var root=new LosslessJson(raw);var rows=entries(root);row(root,index);if (action==null || action==RiverProfiles.Action.ADD) throw new IllegalArgumentException("Select copy, remove or move");
        if (action==RiverProfiles.Action.DUPLICATE && rows.size()>=MAX_ENTRIES) throw new IllegalArgumentException("At most 64 memberships");if (action==RiverProfiles.Action.REMOVE && rows.size()<=Integer.parseInt(DATA.getProperty("pieces.minimum"))) throw new IllegalArgumentException("Keep one membership; use an explicit empty entry to terminate a branch");return BiomeLayers.changeLayers(root,"pieces",action,index);
    }
    public static byte[] resetEmpty(String path,byte[] raw) { authorize(path);var root=new LosslessJson(raw);String token=root.token("pieces");if (token==null) return raw.clone();if (!token.equals("null") && !entries(root).isEmpty()) throw new IllegalArgumentException("Remove memberships explicitly; nonempty lists cannot be reset");return root.removeField("pieces"); }
    public static byte[] template() { return append("jigsaw-pools/template.json","{}".getBytes(StandardCharsets.UTF_8),true,null,PackWorkspace.empty()); }
    public static List<String> otherFields(LosslessJson row,boolean entry) { return row.fields().stream().filter(f -> !(entry ? ENTRY_FIELDS : EDITABLE).contains(f)).toList(); }
}
