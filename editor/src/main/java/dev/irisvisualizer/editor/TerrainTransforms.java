package dev.irisvisualizer.editor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;
import java.util.function.Function;

/** External provider descriptor authoring only. No provider discovery, creation or transformation occurs. */
public final class TerrainTransforms {
    public static final List<String> OBJECT_FIELDS = List.of("terrainTransform");
    public static final List<String> EDITABLE = List.of("id", "version", "settings");
    public static final int MAXIMUM_SETTINGS=64;
    private static final Properties DATA=new Properties();
    static {
        try (var input=TerrainTransforms.class.getResourceAsStream("/dimension-terrain-fields.properties")) {
            if (input==null) throw new IOException("Missing pinned terrain controls");DATA.load(input);
            if (!"exactIdentityStringMap".equals(DATA.getProperty("transform.policy"))) throw new IOException("Unsupported transform policy");
        } catch (IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
    public static String draftPair(String left,String right) { return left.length()+":"+left+right; }
    public static String prefix() { return "terrainTransform#"; }
    public static String help(String field) { if (!EDITABLE.contains(field)) throw new IllegalArgumentException("Unsupported transform field");return DATA.getProperty("transform."+field+".description"); }
    private static void authorize(String path) { if (!"dimensions".equals(ResourceTypes.base(path))) throw new IllegalArgumentException("Terrain transforms are dimension-only"); }
    static LosslessJson object(LosslessJson root) { String token=root.token("terrainTransform");return token==null || token.equals("null") ? null : new LosslessJson(token.getBytes(StandardCharsets.UTF_8)); }
    static List<LosslessJson.StringProperty> settings(LosslessJson descriptor) { String token=descriptor.token("settings");return token==null ? null : new LosslessJson(token.getBytes(StandardCharsets.UTF_8)).stringProperties(); }
    static String identity(LosslessJson descriptor,String field) { if (!List.of("id","version").contains(field)) throw new IllegalArgumentException("Unsupported identity field");return descriptor.token(field)==null ? "" : descriptor.stringToken(field); }
    private static void identity(String value) { if (value==null || value.isBlank()) throw new IllegalArgumentException("Enter nonblank exact provider ID and implementation version; availability and compatibility are unvalidated"); }
    private static byte[] put(LosslessJson object,String field,String value) { String old=object.stringToken(field);return old==null ? object.enableStringPath(field,value) : old.equals(value) ? object.sourceBytes() : object.stringValue(field,value); }
    private static byte[] pair(LosslessJson object,String id,String version) { identity(object,"id");identity(object,"version");return put(new LosslessJson(put(object,"id",id)),"version",version); }
    private static byte[] edit(byte[] original,Function<LosslessJson,byte[]> operation) {
        var root=new LosslessJson(original);if (object(root)==null) throw new IllegalArgumentException("Create an explicit descriptor first");return root.objectEdit("terrainTransform",operation,false);
    }
    public static byte[] create(String path,byte[] original,String id,String version) {
        authorize(path);identity(id);identity(version);var root=new LosslessJson(original);if (object(root)!=null) throw new IllegalArgumentException("Descriptor already exists");root=new LosslessJson(root.enableObject("terrainTransform"));return root.objectEdit("terrainTransform",d -> pair(d,id,version),false);
    }
    public static byte[] setIdentity(String path,byte[] original,String id,String version) { authorize(path);identity(id);identity(version);return edit(original,d -> pair(d,id,version)); }
    public static byte[] remove(String path,byte[] original) { authorize(path);var root=new LosslessJson(original);object(root);return root.token("terrainTransform")==null ? original.clone() : root.removeField("terrainTransform"); }
    public static byte[] enableSettings(String path,byte[] original) { authorize(path);return edit(original,d -> { if (d.token("settings")!=null) throw new IllegalArgumentException("Settings are already present or unsupported");return d.enableObject("settings"); }); }
    private static byte[] editSettings(byte[] original,Function<LosslessJson,byte[]> operation) {
        return edit(original,d -> { var entries=settings(d);if (entries==null || entries.size()>MAXIMUM_SETTINGS) throw new IllegalArgumentException("Enable an inline settings map within the 64-entry editor limit");return d.objectEdit("settings",operation,false); });
    }
    public static byte[] add(String path,byte[] original,String key,String value) {
        authorize(path);if (key==null || value==null) throw new IllegalArgumentException("String setting keys and values are required");
        return editSettings(original,m -> { if (m.stringProperties().size()>=MAXIMUM_SETTINGS) throw new IllegalArgumentException("Settings have a 64-entry editor limit");return m.stringMapAdd(key,value); });
    }
    public static byte[] set(String path,byte[] original,String previous,String key,String value) {
        authorize(path);if (previous==null || key==null || value==null) throw new IllegalArgumentException("String setting keys and values are required");
        return editSettings(original,m -> { String old=m.stringToken(previous);if (old==null) throw new IllegalArgumentException("Setting key is absent");byte[] renamed=m.stringMapRename(previous,key);return old.equals(value) ? renamed : new LosslessJson(renamed).stringValue(key,value); });
    }
    public static byte[] removeSetting(String path,byte[] original,String key) {
        authorize(path);return edit(original,d -> { var entries=settings(d);if (entries==null || entries.size()>MAXIMUM_SETTINGS) throw new IllegalArgumentException("Select an inline settings map within the editor limit");var map=new LosslessJson(d.token("settings").getBytes(StandardCharsets.UTF_8));map.stringProperties();if (map.stringToken(key)==null) throw new IllegalArgumentException("Setting is absent");return entries.size()==1 ? d.removeField("settings") : d.objectEdit("settings",m -> m.stringMapRemove(key),false); });
    }
}
