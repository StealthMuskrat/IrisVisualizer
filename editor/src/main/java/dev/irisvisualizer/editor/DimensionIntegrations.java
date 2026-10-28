package dev.irisvisualizer.editor;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/** Platform integration data only: no imports, file access, registry lookup or component discovery. */
public final class DimensionIntegrations {
    public static final List<String> EDITABLE = List.of("borrowedDimensionType", "datapackImports", "disabledComponents");
    public static final List<String> LIST_FIELDS = List.of("datapackImports", "disabledComponents");
    public static final int MAXIMUM_ENTRIES=64;
    private static final Properties DATA=new Properties();
    static {
        try (var input=DimensionIntegrations.class.getResourceAsStream("/dimension-integration-fields.properties")) {
            if (input==null) throw new IOException("Missing pinned dimension integrations");DATA.load(input);
            if (!"moddedOnly".equals(DATA.getProperty("borrowed.policy")) || !"64".equals(DATA.getProperty("custom.minimum")) || !"255".equals(DATA.getProperty("custom.maximum")) || !"emptySources".equals(DATA.getProperty("datapackImports.null"))) throw new IOException("Unsupported dimension integration policy");
        } catch (IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
    private static void authorize(String path) { if (!DimensionFields.dimension(path)) throw new IllegalArgumentException("Dimension integrations are dimension-only"); }
    private static void list(String field) { if (field==null || !LIST_FIELDS.contains(field)) throw new IllegalArgumentException("Unsupported integration list"); }
    public static String prefix(String field) { if (field==null || !EDITABLE.contains(field)) throw new IllegalArgumentException("Unsupported dimension integration");return "dimensionIntegration#"+field+"."; }
    public static String help(String field) { if (field==null || !EDITABLE.contains(field)) throw new IllegalArgumentException("Unsupported dimension integration");return DATA.getProperty(field+".description"); }
    public static List<String> flags() { return List.of(DATA.getProperty("reservedFlags").split(",")); }
    static String borrowed(LosslessJson root) { return "null".equals(root.token("borrowedDimensionType")) ? null : root.stringToken("borrowedDimensionType"); }
    public static byte[] setBorrowed(String path,byte[] original,String value) {
        authorize(path);if (value==null || !value.isEmpty() && !value.matches(DATA.getProperty("borrowed.pattern"))) throw new IllegalArgumentException("Use an exact lowercase namespace:key, or empty to generate a type");
        var root=new LosslessJson(original);String old=borrowed(root);return value.equals(old) ? original : root.nullableString("borrowedDimensionType",value);
    }
    public static byte[] resetBorrowed(String path,byte[] original) { authorize(path);var root=new LosslessJson(original);borrowed(root);return root.token("borrowedDimensionType")==null ? original : root.removeField("borrowedDimensionType"); }
    static List<String> entries(LosslessJson root,String field) { list(field);String token=root.token(field);return token==null || field.equals("datapackImports") && token.equals("null") ? null : root.stringArrayPath(field); }
    private static void limit(int size,boolean adding) { if (size>MAXIMUM_ENTRIES || adding && size>=MAXIMUM_ENTRIES) throw new IllegalArgumentException("Integration lists have a 64-entry editor limit"); }
    private static void source(String input) {
        if (input==null || input.isBlank() || input.indexOf(0)>=0) throw new IllegalArgumentException("Enter an absolute HTTP(S) URL or local file URI");
        try {
            URI uri=new URI(input.trim());String scheme=uri.getScheme()==null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!uri.isAbsolute() || uri.isOpaque()) throw new URISyntaxException(input,"Absolute hierarchical URI required");
            if (scheme.equals("http") || scheme.equals("https")) {
                if (uri.getHost()==null || uri.getHost().isEmpty() || uri.getPort()< -1 || uri.getPort()>65535) throw new URISyntaxException(input,"HTTP(S) host/port required");
            } else if (scheme.equals("file")) {
                if (uri.getRawAuthority()!=null || uri.getRawQuery()!=null || uri.getRawFragment()!=null || uri.getPath()==null || !uri.getPath().startsWith("/") || uri.getPath().indexOf(0)>=0) throw new URISyntaxException(input,"Absolute local file URI without authority/query/fragment required");
            } else throw new URISyntaxException(input,"Use HTTP, HTTPS or file");
        } catch (URISyntaxException invalid) { throw new IllegalArgumentException("Enter a supported absolute HTTP(S) URL or absolute local file URI; existence/content are unchecked",invalid); }
    }
    private static void flag(String input) {
        if (input!=null && flags().contains(input)) return;
        if (input!=null && input.matches("CUSTOM:(0|[1-9][0-9]*)")) {
            try { int value=Integer.parseInt(input.substring("CUSTOM:".length()));if (value>=Integer.parseInt(DATA.getProperty("custom.minimum")) && value<=Integer.parseInt(DATA.getProperty("custom.maximum"))) return; }
            catch (NumberFormatException ignored) { }
        }
        throw new IllegalArgumentException("Choose a pinned reserved flag or canonical CUSTOM:64..255; active components are unverified");
    }
    private static void input(String field,String value) { list(field);if (field.equals("datapackImports")) source(value);else flag(value); }
    public static byte[] setEntry(String path,byte[] original,String field,int index,String value) {
        authorize(path);input(field,value);var root=new LosslessJson(original);var entries=entries(root,field);if (entries==null) throw new IllegalArgumentException("Add a list entry first");limit(entries.size(),false);if (index<0 || index>=entries.size()) throw new IllegalArgumentException("Select an integration entry");return entries.get(index).equals(value) ? original : root.stringArrayPath(field,index,value);
    }
    public static byte[] change(String path,byte[] original,String field,RiverProfiles.Action action,int index,String value) {
        authorize(path);list(field);if (action==null) throw new IllegalArgumentException("Choose an integration action");var root=new LosslessJson(original);var entries=entries(root,field);int size=entries==null ? 0 : entries.size();limit(size,action==RiverProfiles.Action.ADD || action==RiverProfiles.Action.DUPLICATE);
        if (action==RiverProfiles.Action.ADD) { input(field,value);return new LosslessJson(entries==null ? root.enableArray(field) : original).stringArrayPath(field,size,value); }
        if (index<0 || index>=size) throw new IllegalArgumentException("Select an integration entry");
        return switch(action) { case REMOVE -> size==1 ? root.removeField(field) : root.stringArrayPath(field,index,null);case DUPLICATE -> root.stringArrayDuplicate(field,index);case UP -> { if (index==0) throw new IllegalArgumentException("Already first");yield root.stringArrayMove(field,index,index-1); }case DOWN -> { if (index+1==size) throw new IllegalArgumentException("Already last");yield root.stringArrayMove(field,index,index+1); }default -> throw new IllegalArgumentException("Unsupported integration action"); };
    }
    public static byte[] resetList(String path,byte[] original,String field) { authorize(path);var root=new LosslessJson(original);var entries=entries(root,field);limit(entries==null ? 0 : entries.size(),false);return root.token(field)==null ? original : root.removeField(field); }
    static List<String> readOnlyPaths(LosslessJson root) {
        var paths=new ArrayList<String>();try { borrowed(root); } catch (IllegalArgumentException invalid) { paths.add("borrowedDimensionType"); }
        for (String field : LIST_FIELDS) try { var entries=entries(root,field);if (entries!=null) limit(entries.size(),false); } catch (IllegalArgumentException invalid) { paths.add(field); }return List.copyOf(paths);
    }
}
