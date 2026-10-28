package dev.irisvisualizer.editor;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.function.Function;

/** Literal expression authoring only. No parsing, function loading, engine lookup or evaluation. */
public final class ExpressionDefinitions {
    public static final List<String> ROOT_FIELDS = List.of("expression", "variables", "functions");
    public static final List<String> VARIABLE_FIELDS = List.of("name", "staticValue", "styleValue", "engineStreamValue", "engineValue");
    public static final List<String> FUNCTION_FIELDS = List.of("name", "styleValue", "engineStreamValue", "args");
    public static final List<String> LISTS = List.of("variables", "functions");
    public static final int MAX_ENTRIES=64,MAX_TEXT=65_536;
    private static final Properties DATA=new Properties();
    static { try (var input=ExpressionDefinitions.class.getResourceAsStream("/expression-fields.properties")) { if (input==null) throw new IOException("Missing pinned expression definitions");DATA.load(input);if (!"engineValueThenStreamThenStyleThenStatic".equals(DATA.getProperty("variable.policy")) || !"streamThenStyle".equals(DATA.getProperty("function.policy")) || !"x,z,-1".equals(DATA.getProperty("coordinates.2d"))) throw new IOException("Unsupported expression contracts"); } catch (IOException failure) { throw new ExceptionInInitializerError(failure); } }
    public static boolean supports(String path) { return "expressions".equals(ResourceTypes.base(path)); }
    private static void authorize(String path) { if (!supports(path)) throw new IllegalArgumentException("Select an expression JSON resource"); }
    private static void list(String list) { if (list==null || !LISTS.contains(list)) throw new IllegalArgumentException("Select expression variables or functions"); }
    public record Context(String list,int index) {
        public Context { ExpressionDefinitions.list(list);if (index<0) throw new IllegalArgumentException("Select an expression source entry"); }
        public String prefix() { return listPrefix(list)+index+"."; }
        public boolean variable() { return list.equals("variables"); }
        public List<String> fields() { return variable() ? VARIABLE_FIELDS : FUNCTION_FIELDS; }
        public NoiseStyleFields.Target styleTarget() { return new NoiseStyleFields.Target("expressions",list+"."+index+".styleValue",null); }
    }
    public static String listPrefix(String list) { list(list);return "expression#"+list+"."; }
    public static String bodyPrefix() { return "expression#body"; }
    public static String help(String group,String field) { if (!(group.equals("root") ? ROOT_FIELDS : group.equals("variables") ? VARIABLE_FIELDS : group.equals("functions") ? FUNCTION_FIELDS : List.of()).contains(field)) throw new IllegalArgumentException("Unknown expression field");return DATA.getProperty(group+"."+field+".description"); }
    public static String defaultValue(Context context,String field) { field(context,field);return DATA.getProperty(context.list()+"."+field+".default"); }
    private static void field(Context context,String field) { if (context==null || field==null || !context.fields().contains(field)) throw new IllegalArgumentException("Select an expression source field"); }
    static List<LosslessJson> entries(LosslessJson root,String list) { list(list);return root.token(list)==null ? null : root.objectArray(list); }
    private static void limit(List<LosslessJson> rows,boolean adding) { if (rows!=null && (rows.size()>MAX_ENTRIES || adding && rows.size()>=MAX_ENTRIES)) throw new IllegalArgumentException("Expression source lists have a 64-entry editor limit"); }
    static LosslessJson entry(LosslessJson root,Context context) { var rows=entries(root,context.list());limit(rows,false);if (rows==null || context.index()>=rows.size()) throw new IllegalArgumentException("Select an existing expression source entry");return rows.get(context.index()); }
    static byte[] edit(byte[] raw,Context context,Function<LosslessJson,byte[]> op) { var root=new LosslessJson(raw);entry(root,context);return root.objectArrayEdit(context.list(),context.index(),op); }
    static String body(LosslessJson root) { String token=root.token("expression");if (token==null || token.equals("null")) return "";String value=root.stringToken("expression");if (value.indexOf(0)>=0) throw new IllegalArgumentException("Stored expression contains NUL");return value; }
    public static byte[] setBody(String path,byte[] raw,String value) { authorize(path);if (value==null || value.isBlank() || value.indexOf(0)>=0 || value.length()>MAX_TEXT) throw new IllegalArgumentException("Enter nonblank expression text without NUL, at most 65536 UTF-16 characters; grammar is unchecked");var root=new LosslessJson(raw);String old=body(root);return value.equals(old) && root.token("expression")!=null ? raw : root.nullableString("expression",value); }
    private static void nameSyntax(String name) { if (name==null || !name.matches("[A-Za-z_][A-Za-z0-9_]*") || List.of("x","y","z").contains(name)) throw new IllegalArgumentException("Use an ASCII identifier other than x, y or z"); }
    static String name(LosslessJson entry,Context context) { String token=entry.token("name");if (token==null || token.equals("null")) return context.variable() ? "" : null;String value=entry.stringToken("name");if (value.indexOf(0)>=0) throw new IllegalArgumentException("Stored name contains NUL");return value; }
    private static void unique(LosslessJson root,String name,Context excluded) { nameSyntax(name);for (String list : LISTS) { var rows=entries(root,list);limit(rows,false);if (rows!=null) for (int i=0;i<rows.size();i++) if (excluded==null || !excluded.list().equals(list) || excluded.index()!=i) { String old=name(rows.get(i),new Context(list,i));if (name.equals(old)) throw new IllegalArgumentException("New names must be unique across expression variables and functions"); } } }
    public static byte[] change(String path,byte[] raw,String list,RiverProfiles.Action action,int index,String newName) {
        authorize(path);list(list);if (action==null) throw new IllegalArgumentException("Choose an expression list action");var root=new LosslessJson(raw);var rows=entries(root,list);limit(rows,action==RiverProfiles.Action.ADD || action==RiverProfiles.Action.DUPLICATE);
        if (action==RiverProfiles.Action.ADD) { unique(root,newName,null);if (rows==null) root=new LosslessJson(root.enableArray(list));byte[] seed=new LosslessJson("{}".getBytes(StandardCharsets.UTF_8)).enableStringPath("name",newName);return root.objectArrayInsert(list,rows==null ? 0 : rows.size(),seed); }
        if (rows==null || index<0 || index>=rows.size()) throw new IllegalArgumentException("Select an existing expression source entry");
        if (action==RiverProfiles.Action.DUPLICATE) { unique(root,newName,null);name(rows.get(index),new Context(list,index));return root.objectArrayInsert(list,index+1,rows.get(index).nullableString("name",newName)); }
        if (action==RiverProfiles.Action.REMOVE && rows.size()==1) return root.removeField(list);
        return BiomeLayers.changeLayers(root,list,action,index);
    }
    public static byte[] resetList(String path,byte[] raw,String list) { authorize(path);list(list);var root=new LosslessJson(raw);limit(entries(root,list),false);return root.token(list)==null ? raw : root.removeField(list); }
    public static byte[] setName(String path,byte[] raw,Context context,String value) { authorize(path);var root=new LosslessJson(raw);var entry=entry(root,context);name(entry,context);unique(root,value,context);return value.equals(name(entry,context)) && entry.token("name")!=null ? raw : root.objectArrayEdit(context.list(),context.index(),e -> e.nullableString("name",value)); }
    private static BigDecimal number(String token,boolean integer,boolean input) { try { if (!LosslessJson.numericToken(token)) throw new NumberFormatException();var value=new BigDecimal(token);if (integer) value.intValueExact();else if (!Double.isFinite(value.doubleValue()) || input && value.signum()!=0 && value.doubleValue()==0) throw new ArithmeticException();return value; } catch (NumberFormatException | ArithmeticException bad) { throw new IllegalArgumentException("Use an exact int32 or finite native double without input underflow",bad); } }
    static String number(LosslessJson entry,Context context,String field) { field(context,field);if (!(context.variable() && field.equals("staticValue") || !context.variable() && field.equals("args"))) throw new IllegalArgumentException("Select a numeric expression source field");String old=entry.token(field);if (old==null) return defaultValue(context,field);number(old,field.equals("args"),false);return old; }
    public static byte[] setNumber(String path,byte[] raw,Context context,String field,String input) { authorize(path);field(context,field);boolean integer=field.equals("args");var next=number(input,integer,true);if (integer && next.compareTo(new BigDecimal(DATA.getProperty("functions.args.minimum")))<0) throw new IllegalArgumentException("Function arguments must be at least two");return edit(raw,context,e -> { String old=number(e,context,field);if (e.token(field)!=null && new BigDecimal(old).compareTo(next)==0) return e.sourceBytes();return integer ? e.nullableInteger(field,next.longValueExact()) : e.token(field)==null ? e.enable(field,next.toString()) : e.number(field,next.doubleValue()); }); }
    public static List<String> choices(Context context,String field) { field(context,field);if (!field.equals("engineStreamValue") && !field.equals("engineValue")) throw new IllegalArgumentException("Select an engine source");return List.of(DATA.getProperty(field+".choices").split(",")); }
    public static String choiceHelp(Context context,String field,String choice) { choices(context,field);return DATA.getProperty(field+"."+choice+".description"); }
    static String source(LosslessJson entry,Context context,String field) { choices(context,field);String token=entry.token(field);if (token==null || token.equals("null")) return null;String value=entry.stringToken(field);if (value.indexOf(0)>=0) throw new IllegalArgumentException("Stored source contains NUL");return value; }
    public static byte[] setSource(String path,byte[] raw,Context context,String field,String value) { authorize(path);if (value!=null && !choices(context,field).contains(value)) throw new IllegalArgumentException("Choose a pinned engine source");choices(context,field);return edit(raw,context,e -> { String old=source(e,context,field);return java.util.Objects.equals(old,value) && e.token(field)!=null ? e.sourceBytes() : e.nullableString(field,value); }); }
    public static byte[] resetLeaf(String path,byte[] raw,Context context,String field) { authorize(path);field(context,field);if (field.equals("name") || field.equals("styleValue")) throw new IllegalArgumentException("Use name or style controls");return edit(raw,context,e -> { if (field.equals("staticValue") || field.equals("args")) number(e,context,field);else source(e,context,field);return e.token(field)==null ? e.sourceBytes() : e.removeField(field); }); }
    static Context styleContext(String base,String path) { if (!"expressions".equals(base) || path==null) return null;var m=java.util.regex.Pattern.compile("(variables|functions)\\.(0|[1-9][0-9]*)\\.styleValue").matcher(path);if (!m.matches()) return null;try { return new Context(m.group(1),Integer.parseInt(m.group(2))); } catch (IllegalArgumentException bad) { return null; } }
    static LosslessJson style(LosslessJson entry) { String token=entry.token("styleValue");return token==null || token.equals("null") ? null : new LosslessJson(token.getBytes(StandardCharsets.UTF_8)); }
    public static byte[] resetStyle(String path,byte[] raw,Context context) { authorize(path);return edit(raw,context,e -> { style(e);return e.token("styleValue")==null ? e.sourceBytes() : e.removeField("styleValue"); }); }
    static List<String> readOnlyPaths(LosslessJson root) { var paths=new ArrayList<String>();for (String field : root.fields()) if (!ROOT_FIELDS.contains(field)) paths.add(field);try { body(root); } catch (IllegalArgumentException bad) { paths.add("expression"); }for (String list : LISTS) try { var rows=entries(root,list);limit(rows,false);if (rows!=null) for (int i=0;i<rows.size();i++) { var c=new Context(list,i);var row=rows.get(i);String prefix=list+"["+i+"].";for (String field : row.fields()) if (!c.fields().contains(field)) paths.add(prefix+field);else try { if (field.equals("name")) name(row,c);else if (field.equals("styleValue")) { var style=style(row);if (style!=null) for (String p : StyleFractures.readOnlyPaths(style)) paths.add(prefix+"styleValue."+p); }else if (field.equals("args") || field.equals("staticValue")) number(row,c,field);else source(row,c,field); } catch (IllegalArgumentException bad) { paths.add(prefix+field); } } } catch (IllegalArgumentException bad) { paths.add(list); }return List.copyOf(paths); }
    public static byte[] template() { return "{\n  \"expression\": \"0\"\n}\n".getBytes(StandardCharsets.UTF_8); }
}
