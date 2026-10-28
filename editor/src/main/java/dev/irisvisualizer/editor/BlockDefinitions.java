package dev.irisvisualizer.editor;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.function.Function;

/** Literal blocks/ aliases, state primitives and bounded backup chains; never resolves block states. */
public final class BlockDefinitions {
    public static final List<String> EDITABLE = List.of("block", "data", "backup");
    public static final int MAX_DEPTH=8,MAX_STATES=64;
    private static final Properties DATA=new Properties();
    static { try (var input=BlockDefinitions.class.getResourceAsStream("/block-definition-fields.properties")) { if (input==null) throw new IOException("Missing pinned block definitions");DATA.load(input);if (!"blocks".equals(DATA.getProperty("folder")) || !"aliasThenStateThenBackupThenAir".equals(DATA.getProperty("resolution.policy"))) throw new IOException("Unsupported block definition contract"); } catch (IOException failure) { throw new ExceptionInInitializerError(failure); } }
    public enum Kind { STRING, BOOLEAN, NUMBER }
    public record State(String key,Kind kind,String value) {}
    public static boolean supports(String path) { return "blocks".equals(ResourceTypes.base(path)); }
    private static void authorize(String path,int depth) { if (!supports(path)) throw new IllegalArgumentException("Select a block alias definition");scope(depth); }
    public static String scope(int depth) { if (depth<0 || depth>MAX_DEPTH) throw new IllegalArgumentException("Block backup depth exceeds the editor limit");return "blockDefinition."+"backup.".repeat(depth); }
    public static boolean blockSyntax(String value) { return value!=null && value.matches("(?:minecraft:)?[a-z0-9_./-]+") && (value.startsWith("minecraft:") || ResourceTypes.referenceKey(value)); }
    public static String help(String field) { if (field==null || !EDITABLE.contains(field)) throw new IllegalArgumentException("Unknown block definition field");return DATA.getProperty(field+".description"); }
    public static String defaultBlock() { return DATA.getProperty("block.default"); }
    static LosslessJson backup(LosslessJson owner) { String token=owner.token("backup");return token==null || token.equals("null") ? null : new LosslessJson(token.getBytes(StandardCharsets.UTF_8)); }
    static LosslessJson owner(LosslessJson root,int depth) { scope(depth);var node=root;for (int i=0;i<depth;i++) { node=backup(node);if (node==null) throw new IllegalArgumentException("Select an existing backup"); }return node; }
    private static byte[] edit(LosslessJson node,int depth,Function<LosslessJson,byte[]> op) { if (depth==0) return op.apply(node);if (backup(node)==null) throw new IllegalArgumentException("Enable a backup explicitly first");return node.objectEdit("backup",child -> edit(child,depth-1,op),false); }
    private static byte[] edit(String path,byte[] raw,int depth,Function<LosslessJson,byte[]> op) { authorize(path,depth);return edit(new LosslessJson(raw),depth,op); }
    static String block(LosslessJson owner) { String token=owner.token("block");String value=token==null ? defaultBlock() : owner.stringToken("block");if (value.indexOf(0)>=0) throw new IllegalArgumentException("Stored block contains NUL");return value; }
    public static byte[] setBlock(String path,byte[] raw,int depth,String value) { if (!blockSyntax(value)) throw new IllegalArgumentException("Use a lowercase Minecraft base block or unnamespaced alias key; edit properties separately");return edit(path,raw,depth,o -> { String current=block(o);return value.equals(current) && o.token("block")!=null ? o.sourceBytes() : o.nullableString("block",value); }); }
    public static byte[] resetBlock(String path,byte[] raw,int depth) { return edit(path,raw,depth,o -> { block(o);return o.token("block")==null ? o.sourceBytes() : o.removeField("block"); }); }
    public static byte[] createBackup(String path,byte[] raw,int depth,String block) { if (depth>=MAX_DEPTH || !blockSyntax(block)) throw new IllegalArgumentException("Choose a supported backup block within the eight-level editor limit");return edit(path,raw,depth,o -> { if (backup(o)!=null) throw new IllegalArgumentException("Backup already exists");byte[] enabled=o.enableObject("backup");return new LosslessJson(enabled).objectEdit("backup",child -> child.enableStringPath("block",block),false); }); }
    public static byte[] resetBackup(String path,byte[] raw,int depth) { return edit(path,raw,depth,o -> { backup(o);return o.token("backup")==null ? o.sourceBytes() : o.removeField("backup"); }); }
    static LosslessJson data(LosslessJson owner) { String token=owner.token("data");return token==null ? null : new LosslessJson(token.getBytes(StandardCharsets.UTF_8)); }
    private static void limit(LosslessJson map,boolean adding) { if (map!=null && (map.fields().size()>MAX_STATES || adding && map.fields().size()>=MAX_STATES)) throw new IllegalArgumentException("Block state maps have a 64-property editor limit"); }
    private static void key(String key) { if (key==null || !key.matches("[a-z][a-z0-9_]*")) throw new IllegalArgumentException("Use a lowercase vanilla state-property name"); }
    private static BigDecimal number(String input) { try { if (!LosslessJson.numericToken(input)) throw new NumberFormatException();var n=new BigDecimal(input);if (n.stripTrailingZeros().scale()<=0) n.intValueExact();else if (!Double.isFinite(n.doubleValue()) || n.signum()!=0 && n.doubleValue()==0) throw new ArithmeticException();return n; } catch (NumberFormatException | ArithmeticException invalid) { throw new IllegalArgumentException("Use an exact int32 or finite fractional number without underflow",invalid); } }
    static State state(LosslessJson map,String key) { key(key);String token=map.token(key);if (token==null) throw new IllegalArgumentException("State property is absent");if (token.startsWith("\"")) { String value=map.stringToken(key);if (value.indexOf(0)>=0) throw new IllegalArgumentException("State text contains NUL");return new State(key,Kind.STRING,value); }if (token.equals("true") || token.equals("false")) return new State(key,Kind.BOOLEAN,token);number(token);return new State(key,Kind.NUMBER,token); }
    private static String token(Kind kind,String value) { if (kind==null || value==null) throw new IllegalArgumentException("Choose a state type and value");return switch(kind) { case STRING -> { if (!value.matches("[a-z0-9_.-]+")) throw new IllegalArgumentException("Use a nonempty lowercase vanilla state value");yield "\""+value+"\""; }case BOOLEAN -> { if (!List.of("true","false").contains(value)) throw new IllegalArgumentException("Use true or false");yield value; }case NUMBER -> { number(value);yield value; } }; }
    public static byte[] createData(String path,byte[] raw,int depth) { return edit(path,raw,depth,o -> { if (o.token("data")!=null) throw new IllegalArgumentException("State map already exists or has unsupported type");return o.enableObject("data"); }); }
    public static byte[] resetData(String path,byte[] raw,int depth) { return edit(path,raw,depth,o -> { limit(data(o),false);return o.token("data")==null ? o.sourceBytes() : o.removeField("data"); }); }
    /** previous == null adds; otherwise edits/renames just the selected primitive. */
    public static byte[] setState(String path,byte[] raw,int depth,String previous,String next,Kind kind,String value) { key(next);String desired=token(kind,value);return edit(path,raw,depth,o -> o.objectEdit("data",m -> { limit(m,previous==null);if (previous!=null) { var old=state(m,previous);boolean same=old.kind()==kind && (kind==Kind.NUMBER ? new BigDecimal(old.value()).compareTo(new BigDecimal(value))==0 : old.value().equals(value));if (same) return m.primitiveMapSet(previous,next,m.token(previous)); }return m.primitiveMapSet(previous,next,desired); },false)); }
    public static byte[] removeState(String path,byte[] raw,int depth,String key) { return edit(path,raw,depth,o -> o.objectEdit("data",m -> { limit(m,false);state(m,key);return m.removeField(key); },false)); }
    public static byte[] template() { return ("{\n  \"block\": \"minecraft:stone\"\n}\n").getBytes(StandardCharsets.UTF_8); }
    public static List<String> aliases(PackWorkspace workspace) { return ResourceTypes.referenceKeys(workspace,"blocks"); }
    public static String exactAlias(PackWorkspace workspace,String value) { if (value==null || !ResourceTypes.referenceKey(value)) return null;String target="blocks/"+value+".json";return workspace.paths().contains(target) ? target : null; }
    static List<String> readOnlyPaths(LosslessJson root) { var paths=new ArrayList<String>();scan(root,0,"",paths);return List.copyOf(paths); }
    private static void scan(LosslessJson node,int depth,String prefix,List<String> paths) {
        for (String f : node.fields()) if (!EDITABLE.contains(f)) paths.add(prefix+f);
        try { block(node); } catch (IllegalArgumentException bad) { paths.add(prefix+"block"); }
        try { var map=data(node);limit(map,false);if (map!=null) for (String key : map.fields()) try { state(map,key); } catch (IllegalArgumentException bad) { paths.add(prefix+"data."+key); } } catch (IllegalArgumentException bad) { paths.add(prefix+"data"); }
        try { var child=backup(node);if (child!=null) { if (depth>=MAX_DEPTH) paths.add(prefix+"backup");else scan(child,depth+1,prefix+"backup.",paths); } } catch (IllegalArgumentException bad) { paths.add(prefix+"backup"); }
    }
}
