package dev.irisvisualizer.editor;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.function.Function;

/** Literal piece settings only; no binary object reads, connector transforms or native graph evaluation. */
public final class JigsawPieces {
    public static final List<String> EDITABLE = List.of("object","rotatable","collidable","themes","rules");
    public static final List<String> FLAGS = List.of("rotatable","collidable");
    public static final List<String> RULE_FIELDS = List.of("minimumDepth","maximumDepth","minimumPlacements","maximumPlacements","terminal");
    public static final int MAX_THEMES=64,MAX_KEY_CHARACTERS=256,MAX_RESULTS=200;
    private static final Properties DATA=new Properties();
    static { try (var input=JigsawPieces.class.getResourceAsStream("/jigsaw-piece-fields.properties")) {
        if (input==null) throw new IOException("Missing pinned jigsaw piece definitions");DATA.load(input);
        if (!"jigsaw-pieces".equals(DATA.getProperty("folder")) || !"pairedDepthAndZeroUnboundedCounts".equals(DATA.getProperty("rules.policy")) || !"trimExactCaseSensitive".equals(DATA.getProperty("themes.policy"))) throw new IOException("Unsupported piece authoring policy");
    } catch (IOException failure) { throw new ExceptionInInitializerError(failure); } }
    public static boolean supports(String path) { return "jigsaw-pieces".equals(ResourceTypes.base(path)); }
    private static void authorize(String path) { if (!supports(path)) throw new IllegalArgumentException("Select a jigsaw-pieces JSON resource"); }
    private static void field(String name,List<String> names) { if (name==null || !names.contains(name)) throw new IllegalArgumentException("Unsupported piece field"); }
    public static String help(String name) { if (name==null || !EDITABLE.contains(name) && !RULE_FIELDS.contains(name)) throw new IllegalArgumentException("Unsupported piece field");return DATA.getProperty(name+".description"); }
    private static boolean bool(LosslessJson object,String field) { String token=object.token(field);if (token==null) return Boolean.parseBoolean(DATA.getProperty(field+".default"));if (!token.equals("true") && !token.equals("false")) throw new IllegalArgumentException("Stored "+field+" is unsupported (read-only)");return token.equals("true"); }
    public static String object(LosslessJson root) { return root.token("object")==null ? DATA.getProperty("object.default") : root.stringToken("object"); }
    public static boolean flag(LosslessJson root,String name) { field(name,FLAGS);return bool(root,name); }
    private static boolean key(String key) { return key!=null && !key.isBlank() && key.length()<=MAX_KEY_CHARACTERS && key.equals(key.trim()) && key.chars().noneMatch(Character::isISOControl) && key.codePoints().noneMatch(c -> c<=0xffff && Character.isSurrogate((char)c)); }
    public static List<String> objects(PackWorkspace workspace) { return FloatingObjectPlacements.objectChoices(workspace).stream().filter(JigsawPieces::key).toList(); }
    private static void objectKey(String key,PackWorkspace workspace) { if (!key(key) || !ResourceTypes.referenceKey(key) || !workspace.paths().contains("objects/"+key+".iob")) throw new IllegalArgumentException("Choose an exact existing lowercase .iob file key; content and aliases are unchecked"); }
    public static byte[] setObject(String path,byte[] raw,String key,PackWorkspace workspace) { authorize(path);objectKey(key,workspace);var root=new LosslessJson(raw);String old=object(root);return root.token("object")==null ? root.enableStringPath("object",key) : old.equals(key) ? raw.clone() : root.stringValue("object",key); }
    public static byte[] template(PackWorkspace workspace,String key) { return setObject("jigsaw-pieces/template.json","{}".getBytes(StandardCharsets.UTF_8),key,workspace); }
    public static byte[] setFlag(String path,byte[] raw,String name,boolean next) { authorize(path);field(name,FLAGS);var root=new LosslessJson(raw);boolean old=flag(root,name);return root.token(name)==null ? root.enable(name,Boolean.toString(next)) : old==next ? raw.clone() : root.bool(name,next); }
    public static byte[] resetFlag(String path,byte[] raw,String name) { authorize(path);var root=new LosslessJson(raw);flag(root,name);return root.token(name)==null ? raw.clone() : root.removeField(name); }
    public record Matches(int count,List<String> keys) { public Matches { keys=List.copyOf(keys); } }
    public static final class Catalog {
        private PackWorkspace workspace;private long revision=-1;private ReferenceChoices.Catalog catalog;
        public void clear(){workspace=null;revision=-1;catalog=null;}
        public ReferenceChoices.Catalog catalog(PackWorkspace current){
            java.util.Objects.requireNonNull(current);if(workspace!=current || revision!=current.resourceRevision()){workspace=current;revision=current.resourceRevision();catalog=new ReferenceChoices.Catalog(objects(current));}return catalog;
        }
        public ReviewPagination.Page<String> page(PackWorkspace current,String query,int requested){ReferenceChoices.query(query);return catalog(current).page(query,requested);}
        public Matches filter(PackWorkspace current,String query){var page=page(current,query,0);return new Matches(page.window().matches(),page.entries());}
    }
    public static LosslessJson rules(LosslessJson root) { String token=root.token("rules");return token==null || token.equals("null") ? null : new LosslessJson(token.getBytes(StandardCharsets.UTF_8)); }
    public static byte[] createRules(String path,byte[] raw) { authorize(path);var root=new LosslessJson(raw);if (rules(root)!=null) throw new IllegalArgumentException("Rule overrides already exist");return root.enableObject("rules"); }
    public static byte[] resetEmptyRules(String path,byte[] raw) { authorize(path);var root=new LosslessJson(raw);var rules=rules(root);if (rules!=null && !rules.fields().isEmpty()) throw new IllegalArgumentException("Only null or empty rule overrides can be removed here");return root.token("rules")==null ? raw.clone() : root.removeField("rules"); }
    private static byte[] editRules(byte[] raw,Function<LosslessJson,byte[]> edit) { var root=new LosslessJson(raw);if (rules(root)==null) throw new IllegalArgumentException("Create explicit inline rule overrides first");return root.objectEdit("rules",edit,false); }
    public static List<String> pairFields(String group) { if ("depth".equals(group)) return List.of("minimumDepth","maximumDepth");if ("placements".equals(group)) return List.of("minimumPlacements","maximumPlacements");throw new IllegalArgumentException("Select depth or placement-count bounds"); }
    private static int integer(String input) { try { return new BigDecimal(input).intValueExact(); } catch (NumberFormatException | ArithmeticException | NullPointerException invalid) { throw new IllegalArgumentException("Use an exact signed 32-bit rule integer",invalid); } }
    public static String ruleToken(LosslessJson rules,String name) { field(name,RULE_FIELDS);if (name.equals("terminal")) throw new IllegalArgumentException("Select a rule integer");String token=rules==null ? null : rules.token(name);if (token!=null) integer(token);return token==null ? DATA.getProperty(name+".default") : token; }
    public static int bound(String name,String kind) { field(name,RULE_FIELDS);if (name.equals("terminal") || !("minimum".equals(kind) || "maximum".equals(kind))) throw new IllegalArgumentException("Select a numeric rule bound");return Integer.parseInt(DATA.getProperty(name+"."+kind)); }
    public static boolean terminal(LosslessJson rules) { return rules==null ? Boolean.parseBoolean(DATA.getProperty("terminal.default")) : bool(rules,"terminal"); }
    public static byte[] setPair(String path,byte[] raw,String group,String minimum,String maximum) {
        authorize(path);var names=pairFields(group);int low=integer(minimum),high=integer(maximum);
        if (low<bound(names.get(0),"minimum") || low>bound(names.get(0),"maximum") || high<bound(names.get(1),"minimum") || high>bound(names.get(1),"maximum") || !(group.equals("placements") && high==0) && low>high) throw new IllegalArgumentException("Use ordered pinned bounds; maximumPlacements=0 means unbounded");
        return editRules(raw,r -> { ruleToken(r,names.get(0));ruleToken(r,names.get(1));byte[] next=r.sourceBytes();for (int index=0;index<2;index++) { var current=new LosslessJson(next);String name=names.get(index);int value=index==0 ? low : high;next=current.token(name)==null ? current.enable(name,Integer.toString(value)) : integer(current.token(name))==value ? next : current.integer(name,value); }return next; });
    }
    public static byte[] resetPair(String path,byte[] raw,String group) { authorize(path);var names=pairFields(group);return editRules(raw,r -> { byte[] next=r.sourceBytes();for (String name : names) { var current=new LosslessJson(next);ruleToken(current,name);if (current.token(name)!=null) next=current.removeField(name); }return next; }); }
    public static byte[] setTerminal(String path,byte[] raw,boolean next) { authorize(path);return editRules(raw,r -> { boolean old=terminal(r);return r.token("terminal")==null ? r.enable("terminal",Boolean.toString(next)) : old==next ? r.sourceBytes() : r.bool("terminal",next); }); }
    public static byte[] resetTerminal(String path,byte[] raw) { authorize(path);return editRules(raw,r -> { terminal(r);return r.token("terminal")==null ? r.sourceBytes() : r.removeField("terminal"); }); }
    public static String themesPrefix() { return "jigsawPiece#themes.entry."; }
    public static List<String> themes(LosslessJson root) { if (root.token("themes")==null) return List.of();if (root.token("themes").equals("null")) throw new IllegalArgumentException("Native filtering treats null as unfiltered, but the graph compiler rejects a null theme list; remove this override explicitly to repair it");var tags=root.stringArrayPath("themes");if (tags.size()>MAX_THEMES) throw new IllegalArgumentException("Theme controls have a 64-tag editor limit; original data remains read-only");return tags; }
    private static void theme(String key,List<String> tags,int selected) { if (!key(key)) throw new IllegalArgumentException("Use a nonblank exact case-sensitive theme key up to 256 characters without controls or leading/trailing ASCII whitespace");for (int index=0;index<tags.size();index++) if (index!=selected && tags.get(index).trim().equals(key)) throw new IllegalArgumentException("That exact normalized theme tag already exists"); }
    public static byte[] setTheme(String path,byte[] raw,int index,String key) { authorize(path);var root=new LosslessJson(raw);var tags=themes(root);if (index<0 || index>=tags.size()) throw new IllegalArgumentException("Select a theme tag");theme(key,tags,index);return tags.get(index).equals(key) ? raw.clone() : root.stringArrayPath("themes",index,key); }
    public static byte[] appendTheme(String path,byte[] raw,String key) { authorize(path);var root=new LosslessJson(raw);var tags=themes(root);if (tags.size()>=MAX_THEMES) throw new IllegalArgumentException("At most 64 theme tags");theme(key,tags,-1);if (root.token("themes")==null) root=new LosslessJson(root.enableArray("themes"));return root.stringArrayPath("themes",tags.size(),key); }
    public static byte[] changeTheme(String path,byte[] raw,int index,RiverProfiles.Action action) { authorize(path);var root=new LosslessJson(raw);var tags=themes(root);if (index<0 || index>=tags.size() || action==null || !(action==RiverProfiles.Action.REMOVE || action==RiverProfiles.Action.UP || action==RiverProfiles.Action.DOWN)) throw new IllegalArgumentException("Select a tag and remove/move action");return action==RiverProfiles.Action.REMOVE ? tags.size()==1 ? root.removeField("themes") : root.stringArrayPath("themes",index,null) : root.stringArrayMove("themes",index,index+(action==RiverProfiles.Action.UP ? -1 : 1)); }
    public static byte[] resetEmptyThemes(String path,byte[] raw) { authorize(path);var root=new LosslessJson(raw);String token=root.token("themes");if (token==null) return raw.clone();if (!token.equals("null") && !themes(root).isEmpty()) throw new IllegalArgumentException("Remove declared tags explicitly; nonempty lists cannot be reset");return root.removeField("themes"); }
    public static List<String> readOnlyFields(LosslessJson root) { return root.fields().stream().filter(f -> !EDITABLE.contains(f) && !JigsawConnectors.ROOT_FIELDS.contains(f)).toList(); }
    public static List<String> otherRules(LosslessJson rules) { return rules.fields().stream().filter(f -> !RULE_FIELDS.contains(f)).toList(); }
}
