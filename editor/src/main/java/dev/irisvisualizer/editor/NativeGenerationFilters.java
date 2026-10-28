package dev.irisvisualizer.editor;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.function.Function;

/** Registry-key authoring only; native feature passes and structure placements are never executed. */
public final class NativeGenerationFilters {
    public static final List<String> OBJECT_FIELDS = List.of("importedFeatures");
    public static final List<String> FEATURE_FIELDS = List.of("steps", "disabledSteps", "biomeRules");
    public static final List<String> STRUCTURE_FIELDS = List.of("frequencyOverrides");
    public static final List<String> EDITABLE = List.of("biomes", "disabled", "disabledExact");
    public static final List<String> FREQUENCY_FIELDS = List.of("structureSet", "multiplier");
    public static final int MAXIMUM_ENTRIES=64;
    private static final Properties DATA=new Properties();
    static {
        try (var input=NativeGenerationFilters.class.getResourceAsStream("/native-generation-filter-fields.properties")) {
            if (input==null) throw new IOException("Missing native generation filter definitions");DATA.load(input);
            if (!"denyAfterAllow".equals(DATA.getProperty("step.policy")) || !"lastNormalizedKeyWins".equals(DATA.getProperty("frequency.policy"))) throw new IOException("Unsupported native filter policy");
        } catch (IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
    public static String prefix(String list) { return "nativeGeneration#"+list+"."; }
    public static List<String> steps() { return List.of(DATA.getProperty("steps").split(",")); }
    public static String help(String field) { return DATA.getProperty(field+".description",""); }
    public static String stepHelp(String step) { return DATA.getProperty("step."+step+".description",""); }
    private static void authorize(String path) { if (!DimensionFields.dimension(path)) throw new IllegalArgumentException("Native generation filters are dimension-only"); }
    private static String parent(String list) {
        if (list!=null && FEATURE_FIELDS.contains(list)) return "importedFeatures";
        if ("frequencyOverrides".equals(list)) return "importedStructures";
        throw new IllegalArgumentException("Unsupported native filter list");
    }
    static LosslessJson object(LosslessJson root,String parent) { if (!List.of("importedFeatures","importedStructures").contains(parent)) throw new IllegalArgumentException("Unsupported native policy");String token=root.token(parent);return token==null ? null : new LosslessJson(token.getBytes(StandardCharsets.UTF_8)); }
    static List<LosslessJson> entries(LosslessJson root,String list) {
        if (!List.of("biomeRules","frequencyOverrides").contains(list)) throw new IllegalArgumentException("Unsupported native object list");var object=object(root,parent(list));return object==null || object.token(list)==null ? null : object.objectArray(list);
    }
    private static void limit(int size,boolean adding) { if (size>MAXIMUM_ENTRIES || adding && size>=MAXIMUM_ENTRIES) throw new IllegalArgumentException("Native filter lists have a 64-entry editor limit"); }
    static byte[] editEntry(byte[] original,String list,int index,Function<LosslessJson,byte[]> edit) {
        var root=new LosslessJson(original);var entries=entries(root,list);if (entries==null) throw new IllegalArgumentException("Enable the native list first");limit(entries.size(),false);
        return root.objectEdit(parent(list),o -> o.objectArrayEdit(list,index,edit),false);
    }
    public static byte[] change(String path,byte[] original,String list,RiverProfiles.Action action,int index,String structureSet) {
        authorize(path);if (action==null) throw new IllegalArgumentException("Choose a native list action");var root=new LosslessJson(original);var entries=entries(root,list);int size=entries==null ? 0 : entries.size();limit(size,action==RiverProfiles.Action.ADD || action==RiverProfiles.Action.DUPLICATE);
        if (action!=RiverProfiles.Action.ADD && (index<0 || index>=size)) throw new IllegalArgumentException("Select an existing native entry");
        byte[] seed="{}".getBytes(StandardCharsets.UTF_8);
        if (action==RiverProfiles.Action.ADD && list.equals("frequencyOverrides")) { key(structureSet,false,false);seed=new LosslessJson(seed).enableStringPath("structureSet",structureSet); }
        byte[] added=seed;
        return root.objectEdit(parent(list),o -> {
            if (o.token(list)==null) o=new LosslessJson(o.enableArray(list));
            if (action==RiverProfiles.Action.ADD) return o.objectArrayInsert(list,size,added);
            if (action==RiverProfiles.Action.REMOVE && size==1) return o.removeField(list);
            return BiomeLayers.changeLayers(o,list,action,index);
        },true);
    }
    static List<String> strings(LosslessJson owner,String field,boolean stepList) {
        if (field==null || !(stepList ? List.of("steps","disabledSteps") : EDITABLE).contains(field)) throw new IllegalArgumentException("Unsupported native string list");
        return owner.token(field)==null ? null : owner.stringArrayPath(field);
    }
    private static void key(String input,boolean prefix,boolean biome) {
        if (input==null) throw new IllegalArgumentException("Enter a lowercase namespaced registry key");
        String full="[a-z0-9_.-]+:[a-z0-9/._-]+",family="[a-z0-9_.-]+:[a-z0-9/._-]*";
        boolean valid=biome ? input.matches(full) || input.matches("[a-z0-9_.-]+:") || input.startsWith("#") && input.substring(1).matches(full) : input.matches(prefix ? family : full);
        if (!valid) throw new IllegalArgumentException(biome ? "Use a lowercase biome key, namespace: or #namespace:tag" : "Use a lowercase namespace:key"+(prefix ? " (namespace: is allowed)" : ""));
    }
    private static byte[] stringOperation(LosslessJson owner,String field,boolean stepList,RiverProfiles.Action action,int index,String input) {
        if (action==null) throw new IllegalArgumentException("Choose a native string action");var strings=strings(owner,field,stepList);int size=strings==null ? 0 : strings.size();limit(size,action==RiverProfiles.Action.ADD || action==RiverProfiles.Action.DUPLICATE);
        if (action==RiverProfiles.Action.ADD) { validateString(field,stepList,input);if (strings==null) owner=new LosslessJson(owner.enableArray(field));return owner.stringArrayPath(field,size,input); }
        if (index<0 || index>=size) throw new IllegalArgumentException("Select a native string entry");
        return switch (action) {
            case REMOVE -> size==1 ? owner.removeField(field) : owner.stringArrayPath(field,index,null);
            case DUPLICATE -> owner.stringArrayDuplicate(field,index);
            case UP -> { if (index==0) throw new IllegalArgumentException("Already first");yield owner.stringArrayMove(field,index,index-1); }
            case DOWN -> { if (index+1==size) throw new IllegalArgumentException("Already last");yield owner.stringArrayMove(field,index,index+1); }
            default -> throw new IllegalArgumentException("Unsupported string action");
        };
    }
    private static void validateString(String field,boolean stepList,String input) { if (stepList) { if (input==null || !steps().contains(input)) throw new IllegalArgumentException("Choose a pinned decoration step"); } else key(input,field.equals("disabled"),field.equals("biomes")); }
    private static byte[] putString(LosslessJson owner,String field,boolean stepList,int index,String input) {
        validateString(field,stepList,input);var strings=strings(owner,field,stepList);if (strings==null) throw new IllegalArgumentException("Enable the native string list first");limit(strings.size(),false);if (index<0 || index>=strings.size()) throw new IllegalArgumentException("Select a native string entry");return strings.get(index).equals(input) ? owner.sourceBytes() : owner.stringArrayPath(field,index,input);
    }
    public static byte[] changeString(String path,byte[] original,String field,int rule,RiverProfiles.Action action,int index,String input) {
        authorize(path);if (rule< -1) throw new IllegalArgumentException("Select a rule or the step-list context");if (rule>=0) return editEntry(original,"biomeRules",rule,o -> stringOperation(o,field,false,action,index,input));
        var root=new LosslessJson(original);return root.objectEdit("importedFeatures",o -> stringOperation(o,field,true,action,index,input),action==RiverProfiles.Action.ADD);
    }
    public static byte[] setString(String path,byte[] original,String field,int rule,int index,String input) {
        authorize(path);if (rule< -1) throw new IllegalArgumentException("Select a rule or the step-list context");if (rule>=0) return editEntry(original,"biomeRules",rule,o -> putString(o,field,false,index,input));return new LosslessJson(original).objectEdit("importedFeatures",o -> putString(o,field,true,index,input),false);
    }
    static String structureSet(LosslessJson entry) { String value=entry.stringToken("structureSet");return value==null ? "" : value; }
    static String multiplier(LosslessJson entry) {
        String token=entry.token("multiplier");if (token==null) return DATA.getProperty("multiplier.default");
        try { if (!LosslessJson.numericToken(token) || !Double.isFinite(new BigDecimal(token).doubleValue())) throw new NumberFormatException();return token; }
        catch (NumberFormatException invalid) { throw new IllegalArgumentException("Stored multiplier is not a finite number",invalid); }
    }
    public static byte[] setStructureSet(String path,byte[] original,int index,String input) {
        authorize(path);key(input,false,false);return editEntry(original,"frequencyOverrides",index,o -> { String old=structureSet(o);return input.equals(old) ? o.sourceBytes() : o.token("structureSet")==null ? o.enableStringPath("structureSet",input) : o.stringValue("structureSet",input); });
    }
    public static byte[] setMultiplier(String path,byte[] original,int index,String input) {
        authorize(path);BigDecimal value;
        try { value=new BigDecimal(input);if (!Double.isFinite(value.doubleValue()) || value.compareTo(new BigDecimal(DATA.getProperty("multiplier.minimum")))<0 || value.compareTo(new BigDecimal(DATA.getProperty("multiplier.maximum")))>0) throw new NumberFormatException(); }
        catch (NumberFormatException | NullPointerException invalid) { throw new IllegalArgumentException("Multiplier must be finite and within 0.01..16",invalid); }
        return editEntry(original,"frequencyOverrides",index,o -> { multiplier(o);return o.token("multiplier")==null ? o.enable("multiplier",value.toString()) : new BigDecimal(o.token("multiplier")).compareTo(value)==0 ? o.sourceBytes() : o.number("multiplier",value.doubleValue()); });
    }
    public static byte[] resetMultiplier(String path,byte[] original,int index) { authorize(path);return editEntry(original,"frequencyOverrides",index,o -> { multiplier(o);return o.token("multiplier")==null ? o.sourceBytes() : o.removeField("multiplier"); }); }
    static List<String> readOnlyPaths(LosslessJson root,String parent) {
        var paths=new ArrayList<String>();
        try {
            var object=object(root,parent);if (object==null) return List.of();
            for (String field : object.fields()) {
                String path=parent+"."+field;
                if (parent.equals("importedFeatures") && List.of("steps","disabledSteps").contains(field)) { try { var values=strings(object,field,true);limit(values.size(),false); } catch (IllegalArgumentException invalid) { paths.add(path); } }
                else if (parent.equals("importedFeatures") && field.equals("biomeRules") || parent.equals("importedStructures") && field.equals("frequencyOverrides")) {
                    try { var entries=entries(root,field);limit(entries.size(),false);for (int i=0;i<entries.size();i++) for (String child : entries.get(i).fields()) { var entry=entries.get(i);String childPath=path+"["+i+"]."+child;
                        if (field.equals("biomeRules")) { if (!EDITABLE.contains(child)) paths.add(childPath);else try { var values=strings(entry,child,false);limit(values.size(),false); } catch (IllegalArgumentException invalid) { paths.add(childPath); } }
                        else { if (!FREQUENCY_FIELDS.contains(child)) paths.add(childPath);else try { if (child.equals("structureSet")) structureSet(entry);else multiplier(entry); } catch (IllegalArgumentException invalid) { paths.add(childPath); } }
                    } } catch (IllegalArgumentException invalid) { paths.add(path); }
                } else if (!DimensionFields.EDITABLE.contains(path)) paths.add(path);
            }
        } catch (IllegalArgumentException invalid) { paths.add(parent); }
        return List.copyOf(paths);
    }
}
