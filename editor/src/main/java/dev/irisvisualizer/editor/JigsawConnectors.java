package dev.irisvisualizer.editor;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;

/** Literal connector controls. No object decoding, transforms, state parsing or graph assembly. */
public final class JigsawConnectors {
    public static final List<String> ROOT_FIELDS = List.of("connectors");
    public static final List<String> EDITABLE = List.of("position","direction","top","pool","name","targetName","channel","joint","finalState","selectionPriority","placementPriority");
    public static final List<String> CHOICES = List.of("direction","top","joint");
    public static final List<String> TEXT = List.of("name","targetName","channel","finalState");
    public static final List<String> PRIORITIES = List.of("selectionPriority","placementPriority");
    public static final List<String> AXES = List.of("x","y","z");
    public static final int MAX_ENTRIES=64,MAX_TEXT_CHARACTERS=256,MAX_FINAL_STATE_CHARACTERS=4096;
    private static final Properties DATA=new Properties();
    static { try (var input=JigsawConnectors.class.getResourceAsStream("/jigsaw-connector-fields.properties")) {
        if (input==null) throw new IOException("Missing pinned connector definitions");DATA.load(input);
        if (!"exactCaseAndWhitespace".equals(DATA.getProperty("text.policy")) || !"nonnegativeIntTripleObjectBoundsUnchecked".equals(DATA.getProperty("position.policy")) || !"signedInt32".equals(DATA.getProperty("priority.policy"))) throw new IOException("Unsupported connector authoring policy");
    } catch (IOException failure) { throw new ExceptionInInitializerError(failure); } }
    private static void authorize(String path) { if (!JigsawPieces.supports(path)) throw new IllegalArgumentException("Select a jigsaw-pieces JSON resource"); }
    private static void field(String name,List<String> names) { if (name==null || !names.contains(name)) throw new IllegalArgumentException("Unsupported connector field"); }
    public static String help(String name) { if ("connectors".equals(name)) return DATA.getProperty("connectors.description");field(name,EDITABLE);return DATA.getProperty(name+".description"); }
    public static String defaultValue(String name) { field(name,EDITABLE);return DATA.getProperty(name+".default"); }
    public static List<String> choices(String name) { field(name,CHOICES);return List.of(DATA.getProperty(name+".choices").split(",")); }
    public static String entriesPrefix() { return "jigsawConnector#entry."; }
    public static String prefix(int index) { if (index<0 || index>=MAX_ENTRIES) throw new IllegalArgumentException("Select a connector");return entriesPrefix()+index+"."; }
    public static List<LosslessJson> entries(LosslessJson root) { if (root.token("connectors")==null) return List.of();var rows=root.objectArray("connectors");if (rows.size()>MAX_ENTRIES) throw new IllegalArgumentException("Connector controls have a 64-entry limit; original data remains read-only");return rows; }
    private static LosslessJson row(LosslessJson root,int index) { prefix(index);var rows=entries(root);if (index>=rows.size()) throw new IllegalArgumentException("Select an existing connector");return rows.get(index); }
    private static byte[] edit(byte[] raw,int index,java.util.function.Function<LosslessJson,byte[]> action) { var root=new LosslessJson(raw);row(root,index);return root.objectArrayEdit("connectors",index,action); }
    public static String value(LosslessJson row,String name) { field(name,EDITABLE);if (name.equals("position")) throw new IllegalArgumentException("Read the position triple separately");String token=row.token(name);if (PRIORITIES.contains(name)) { if (token!=null) integer(token);return token==null ? defaultValue(name) : token; }return token==null ? defaultValue(name) : row.stringToken(name); }
    private static int integer(String input) { try { return new BigDecimal(input).intValueExact(); } catch (NumberFormatException | ArithmeticException | NullPointerException invalid) { throw new IllegalArgumentException("Use an exact signed 32-bit connector integer",invalid); } }
    public static LosslessJson position(LosslessJson row) { String token=row.token("position");if (token==null) return null;return new LosslessJson(token.getBytes(StandardCharsets.UTF_8)); }
    public static String axis(LosslessJson position,String axis) { field(axis,AXES);String token=position==null ? null : position.token(axis);if (token!=null) integer(token);return token==null ? DATA.getProperty("position."+axis+".default") : token; }
    public static byte[] setPosition(String path,byte[] raw,int index,String x,String y,String z) {
        authorize(path);int[] values={integer(x),integer(y),integer(z)};for (int value : values) if (value<0) throw new IllegalArgumentException("Connector coordinates must be nonnegative int32 values; object bounds are unchecked");
        return edit(raw,index,r -> { var old=position(r);for (String axis : AXES) axis(old,axis);byte[] initial=old==null ? r.enableObject("position") : r.sourceBytes();return new LosslessJson(initial).objectEdit("position",p -> { byte[] next=p.sourceBytes();for (int i=0;i<3;i++) { String axis=AXES.get(i);var current=new LosslessJson(next);next=current.token(axis)==null ? current.enable(axis,Integer.toString(values[i])) : integer(current.token(axis))==values[i] ? next : current.integer(axis,values[i]); }return next; },false); });
    }
    /** Reset only known axes; preserve unknown inline position data. Null carries no data and may be removed explicitly. */
    public static byte[] resetPosition(String path,byte[] raw,int index) {
        authorize(path);return edit(raw,index,r -> { String token=r.token("position");if (token==null) return r.sourceBytes();if (token.equals("null")) return r.removeField("position");var old=position(r);byte[] next=old.sourceBytes();for (String axis : AXES) { axis(new LosslessJson(next),axis);if (new LosslessJson(next).token(axis)!=null) next=new LosslessJson(next).removeField(axis); }if (new LosslessJson(next).fields().isEmpty()) return r.removeField("position");final byte[] replacement=next;return r.objectEdit("position",ignored -> replacement,false); });
    }
    public static byte[] setChoice(String path,byte[] raw,int index,String name,String next) { authorize(path);if (next==null || !choices(name).contains(next)) throw new IllegalArgumentException("Choose a pinned connector enum");return edit(raw,index,r -> { String old=value(r,name);return r.token(name)==null ? r.enableStringPath(name,next) : old.equals(next) ? r.sourceBytes() : r.stringValue(name,next); }); }
    private static void text(String name,String input) { field(name,TEXT);int maximum=name.equals("finalState") ? MAX_FINAL_STATE_CHARACTERS : MAX_TEXT_CHARACTERS;if (input==null || input.length()>maximum || input.chars().anyMatch(Character::isISOControl) || input.codePoints().anyMatch(c -> c<=0xffff && Character.isSurrogate((char)c)) || name.equals("finalState") && input.isBlank()) throw new IllegalArgumentException("Use valid literal text without controls; finalState must be nonblank and all fields have explicit editor size limits"); }
    public static byte[] setText(String path,byte[] raw,int index,String name,String input) { authorize(path);text(name,input);return edit(raw,index,r -> { String old=value(r,name);return r.token(name)==null ? r.enableStringPath(name,input) : old.equals(input) ? r.sourceBytes() : r.stringValue(name,input); }); }
    public static byte[] setPriority(String path,byte[] raw,int index,String name,String input) { authorize(path);field(name,PRIORITIES);int next=integer(input);return edit(raw,index,r -> { int old=integer(value(r,name));return r.token(name)==null ? r.enable(name,Integer.toString(next)) : old==next ? r.sourceBytes() : r.integer(name,next); }); }
    public static byte[] reset(String path,byte[] raw,int index,String name) { authorize(path);field(name,EDITABLE);if (name.equals("position") || name.equals("pool")) throw new IllegalArgumentException("Use the position reset or choose a required pool explicitly");return edit(raw,index,r -> { if (r.token(name)==null) return r.sourceBytes();if (!r.token(name).equals("null")) value(r,name);return r.removeField(name); }); }
    private static void pool(String key,PackWorkspace workspace) { if (key==null || !ResourceTypes.referenceKey(key) || key.length()>256 || !key.equals(key.trim()) || key.chars().anyMatch(Character::isISOControl) || key.codePoints().anyMatch(c -> c<=0xffff && Character.isSurrogate((char)c)) || !workspace.paths().contains("jigsaw-pools/"+key+".json")) throw new IllegalArgumentException("Choose an exact existing jigsaw-pools JSON key; content and graph validity are unchecked"); }
    public static byte[] setPool(String path,byte[] raw,int index,String key,PackWorkspace workspace) { authorize(path);pool(key,workspace);return edit(raw,index,r -> { String old=value(r,"pool");return r.token("pool")==null ? r.enableStringPath("pool",key) : old.equals(key) ? r.sourceBytes() : r.stringValue("pool",key); }); }
    public static byte[] append(String path,byte[] raw,String key,PackWorkspace workspace) { authorize(path);pool(key,workspace);var root=new LosslessJson(raw);var rows=entries(root);if (rows.size()>=MAX_ENTRIES) throw new IllegalArgumentException("At most 64 connectors");if (root.token("connectors")==null) root=new LosslessJson(root.enableArray("connectors"));byte[] row=new LosslessJson("{}".getBytes(StandardCharsets.UTF_8)).enableStringPath("pool",key);return root.objectArrayInsert("connectors",rows.size(),row); }
    public static byte[] change(String path,byte[] raw,int index,RiverProfiles.Action action) { authorize(path);var root=new LosslessJson(raw);var rows=entries(root);row(root,index);if (action==null || action==RiverProfiles.Action.ADD) throw new IllegalArgumentException("Select copy, remove or move");if (action==RiverProfiles.Action.DUPLICATE && rows.size()>=MAX_ENTRIES) throw new IllegalArgumentException("At most 64 connectors");return action==RiverProfiles.Action.REMOVE && rows.size()==1 ? root.removeField("connectors") : BiomeLayers.changeLayers(root,"connectors",action,index); }
    public static byte[] resetEmpty(String path,byte[] raw) { authorize(path);var root=new LosslessJson(raw);String token=root.token("connectors");if (token==null) return raw.clone();if (!token.equals("null") && !entries(root).isEmpty()) throw new IllegalArgumentException("Remove nonempty connector entries explicitly");return root.removeField("connectors"); }
    public static List<String> otherFields(LosslessJson row) { return row.fields().stream().filter(f -> !EDITABLE.contains(f)).toList(); }
    public static List<String> otherPositionFields(LosslessJson position) { return position==null ? List.of() : position.fields().stream().filter(f -> !AXES.contains(f)).toList(); }
}
