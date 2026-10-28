package dev.irisvisualizer.editor;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;

/** Dimension image-map binding data only; map definitions, pixels and native sampling are never loaded. */
public final class ImageMapBindings {
    public static final List<String> LIST_FIELDS = List.of("imageMaps");
    public static final List<String> EDITABLE = List.of("key", "map", "application", "masks");
    public static final List<String> MASK_FIELDS = List.of("map", "operation", "inverted", "threshold", "falloff");
    public static final int MAXIMUM_ENTRIES=64;
    private static final Properties DATA=new Properties();
    static {
        try (var input=ImageMapBindings.class.getResourceAsStream("/image-map-binding-fields.properties")) {
            if (input==null) throw new IOException("Missing pinned image-map binding definitions");DATA.load(input);
            if (!"trimmedCaseSensitive".equals(DATA.getProperty("key.policy")) || !"namedMaskBindings".equals(DATA.getProperty("mask.policy")) || !"orderedLayers".equals(DATA.getProperty("composition.policy"))) throw new IOException("Unsupported image-map binding policy");
        } catch (IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
    public record Context(int binding,int mask) {
        public Context { if (binding<0 || mask< -1) throw new IllegalArgumentException("Select a binding or mask layer"); }
        public static Context binding(int index) { return new Context(index,-1); }
        public Context mask(int index) { if (index<0) throw new IllegalArgumentException("Select a mask layer");return new Context(binding,index); }
        public String prefix() { return ImageMapBindings.prefix()+binding+"."+(mask<0 ? "" : "masks#"+mask+"."); }
        public String group() { return mask<0 ? "binding" : "mask"; }
    }
    public static String prefix() { return "imageMapBinding#"; }
    public static String masksPrefix(int binding) { return Context.binding(binding).prefix()+"masks#"; }
    public static List<String> applications() { return List.of(DATA.getProperty("applications").split(",")); }
    public static List<String> operations() { return List.of(DATA.getProperty("operations").split(",")); }
    private static void field(Context context,String field) { if (context==null || field==null || !(context.mask()<0 ? EDITABLE : MASK_FIELDS).contains(field)) throw new IllegalArgumentException("Unsupported binding field"); }
    public static String help(Context context,String field) { field(context,field);return DATA.getProperty(context.group()+"."+field+".description"); }
    public static String defaultToken(Context context,String field) { field(context,field);return DATA.getProperty(context.group()+"."+field+".default"); }
    private static void authorize(String path) { if (!"dimensions".equals(ResourceTypes.base(path))) throw new IllegalArgumentException("Image-map bindings are dimension-only"); }
    static List<LosslessJson> entries(LosslessJson root) { return root.token("imageMaps")==null ? null : root.objectArray("imageMaps"); }
    static List<LosslessJson> masks(LosslessJson binding) { String token=binding.token("masks");return token==null || token.equals("null") ? null : binding.objectArray("masks"); }
    private static String text(LosslessJson node,String field) { return node.token(field)==null ? "" : node.stringToken(field); }
    static String choice(LosslessJson node,Context context,String field) { field(context,field);return node.token(field)==null ? defaultToken(context,field) : node.stringToken(field); }
    static byte[] edit(byte[] original,Context context,Function<LosslessJson,byte[]> operation) {
        var root=new LosslessJson(original);var entries=entries(root);if (entries==null || entries.size()>MAXIMUM_ENTRIES) throw new IllegalArgumentException("Select an inline binding list within the editor limit");
        return root.objectArrayEdit("imageMaps",context.binding(),binding -> {
            if (context.mask()<0) return operation.apply(binding);
            var masks=masks(binding);if (masks==null || masks.size()>MAXIMUM_ENTRIES) throw new IllegalArgumentException("Select an inline mask list within the editor limit");return binding.objectArrayEdit("masks",context.mask(),operation);
        });
    }
    private static String normalized(String value) { if (value==null || value.isBlank()) throw new IllegalArgumentException("Enter a nonblank local key; native lookup trims whitespace and is case-sensitive");return value.trim(); }
    private static byte[] put(LosslessJson node,String field,String value) { String old=text(node,field);return node.token(field)==null ? node.enableStringPath(field,value) : old.equals(value) ? node.sourceBytes() : node.stringValue(field,value); }
    private static void unique(List<LosslessJson> entries,String key,int except) {
        if (entries==null) return;String desired=normalized(key);
        for (int index=0;index<entries.size();index++) if (index!=except) {
            String stored;try { stored=text(entries.get(index),"key"); } catch (IllegalArgumentException unsupported) { continue; }
            if (!stored.isBlank() && desired.equals(stored.trim())) throw new IllegalArgumentException("Duplicate trimmed image-map key");
        }
    }
    private static boolean singleton(String application) { return !List.of("CUSTOM","MASK").contains(application); }
    private static void application(List<LosslessJson> entries,LosslessJson node,int except,String value) {
        if (value==null || !applications().contains(value)) throw new IllegalArgumentException("Choose a pinned image-map application");
        if (value.equals("MASK")) { var masks=masks(node);if (masks!=null && !masks.isEmpty()) throw new IllegalArgumentException("A MASK binding cannot have additional masks; remove its layers first"); }
        if (entries!=null && singleton(value)) for (int index=0;index<entries.size();index++) if (index!=except) {
            String stored;try { stored=choice(entries.get(index),Context.binding(index),"application"); } catch (IllegalArgumentException unsupported) { continue; }
            if (stored.equals(value)) throw new IllegalArgumentException("Only one "+value+" binding is allowed; existing data is preserved");
        }
    }
    public static List<String> choices(PackWorkspace workspace) { return NoiseStyleSources.choices(workspace,"imageMap"); }
    private static void map(String key,PackWorkspace workspace) { if (key==null || !choices(workspace).contains(key)) throw new IllegalArgumentException("Choose an exact in-pack image-maps JSON key; definition contents, PNGs and application compatibility are unchecked"); }
    public static byte[] change(String path,byte[] original,RiverProfiles.Action action,int index,String key,String map,String application,PackWorkspace workspace) {
        authorize(path);if (action==null) throw new IllegalArgumentException("Choose a binding action");var root=new LosslessJson(original);var entries=entries(root);
        if (entries!=null && entries.size()>MAXIMUM_ENTRIES) throw new IllegalArgumentException("Binding lists have a 64-entry editor limit");
        if (action==RiverProfiles.Action.ADD || action==RiverProfiles.Action.DUPLICATE) { normalized(key);unique(entries,key,-1);if (entries!=null && entries.size()>=MAXIMUM_ENTRIES) throw new IllegalArgumentException("Binding list editor limit reached"); }
        if (entries==null) { if (action!=RiverProfiles.Action.ADD) throw new IllegalArgumentException("No binding list");root=new LosslessJson(root.enableArray("imageMaps"));entries=entries(root); }
        if (action==RiverProfiles.Action.ADD) {
            map(map,workspace);var empty=new LosslessJson("{}".getBytes(StandardCharsets.UTF_8));application(entries,empty,-1,application);
            byte[] seed=put(empty,"key",key);seed=put(new LosslessJson(seed),"map",map);seed=put(new LosslessJson(seed),"application",application);return root.objectArrayInsert("imageMaps",entries.size(),seed);
        }
        if (index<0 || index>=entries.size()) throw new IllegalArgumentException("Select an existing binding");
        if (action==RiverProfiles.Action.DUPLICATE) {
            var node=entries.get(index);application(entries,node,-1,application);byte[] copy=put(node,"key",key);copy=put(new LosslessJson(copy),"application",application);return root.objectArrayInsert("imageMaps",index+1,copy);
        }
        if (action==RiverProfiles.Action.REMOVE && entries.size()==1) return root.removeField("imageMaps");return BiomeLayers.changeLayers(root,"imageMaps",action,index);
    }
    public static byte[] setKey(String path,byte[] original,int binding,String key) { authorize(path);normalized(key);unique(entries(new LosslessJson(original)),key,binding);return edit(original,Context.binding(binding),node -> put(node,"key",key)); }
    public static byte[] setMap(String path,byte[] original,Context context,String key,PackWorkspace workspace) {
        authorize(path);if (context==null) throw new IllegalArgumentException("Select a binding or mask layer");if (context.mask()<0) map(key,workspace);else if (key==null || !maskKeys(new LosslessJson(original)).contains(key)) throw new IllegalArgumentException("Choose an unambiguous local MASK binding key");return edit(original,context,node -> put(node,"map",key));
    }
    public static byte[] setChoice(String path,byte[] original,Context context,String field,String value) {
        authorize(path);field(context,field);if (context.mask()<0 ? !field.equals("application") : !field.equals("operation")) throw new IllegalArgumentException("Choose a binding role or mask operation");
        if (value==null || !(context.mask()<0 ? applications() : operations()).contains(value)) throw new IllegalArgumentException("Choose a pinned enum value");
        return edit(original,context,node -> { if (context.mask()<0) application(entries(new LosslessJson(original)),node,context.binding(),value);return put(node,field,value); });
    }
    public static List<String> maskKeys(LosslessJson root) {
        var entries=entries(root);if (entries==null || entries.size()>MAXIMUM_ENTRIES) return List.of();var counts=new HashMap<String,Integer>();var maskKeys=new TreeSet<String>();
        for (int index=0;index<entries.size();index++) try { var entry=entries.get(index);String key=normalized(text(entry,"key"));counts.merge(key,1,Integer::sum);if (choice(entry,Context.binding(index),"application").equals("MASK")) maskKeys.add(key); } catch (IllegalArgumentException ignored) { }
        maskKeys.removeIf(key -> counts.getOrDefault(key,0)!=1);return List.copyOf(maskKeys);
    }
    public static byte[] changeMask(String path,byte[] original,int binding,RiverProfiles.Action action,int index,String key) {
        authorize(path);if (action==null) throw new IllegalArgumentException("Choose a mask action");if (action==RiverProfiles.Action.ADD && (key==null || !maskKeys(new LosslessJson(original)).contains(key))) throw new IllegalArgumentException("Choose an unambiguous local MASK binding key");
        return edit(original,Context.binding(binding),owner -> {
            if ((action==RiverProfiles.Action.ADD || action==RiverProfiles.Action.DUPLICATE) && choice(owner,Context.binding(binding),"application").equals("MASK")) throw new IllegalArgumentException("A MASK binding cannot have additional masks");
            var masks=masks(owner);if (masks==null) { if (action!=RiverProfiles.Action.ADD) throw new IllegalArgumentException("No mask layers");owner=new LosslessJson(owner.enableArray("masks"));masks=masks(owner); }
            if (masks.size()>MAXIMUM_ENTRIES || (action==RiverProfiles.Action.ADD || action==RiverProfiles.Action.DUPLICATE) && masks.size()>=MAXIMUM_ENTRIES) throw new IllegalArgumentException("Mask lists have a 64-entry editor limit");
            if (action==RiverProfiles.Action.ADD) { byte[] seed=new LosslessJson("{}".getBytes(StandardCharsets.UTF_8)).enableStringPath("map",key);return owner.objectArrayInsert("masks",masks.size(),seed); }
            if (index<0 || index>=masks.size()) throw new IllegalArgumentException("Select an existing mask layer");if (action==RiverProfiles.Action.REMOVE && masks.size()==1) return owner.removeField("masks");return BiomeLayers.changeLayers(owner,"masks",action,index);
        });
    }
    private static void mask(Context context) { if (context==null || context.mask()<0) throw new IllegalArgumentException("Select a mask layer"); }
    public static byte[] setInverted(String path,byte[] original,Context context,boolean value) { authorize(path);mask(context);return edit(original,context,node -> node.token("inverted")==null ? node.enable("inverted",Boolean.toString(value)) : node.bool("inverted",value)); }
    static boolean numericSupported(LosslessJson node,String field) {
        if (!List.of("threshold","falloff").contains(field)) return false;String token=node.token(field);if (token==null) return true;
        try { return LosslessJson.numericToken(token) && Double.isFinite(new BigDecimal(token).doubleValue()); } catch (NumberFormatException invalid) { return false; }
    }
    public static byte[] enableScalar(String path,byte[] original,Context context,String field) { authorize(path);mask(context);if (!List.of("threshold","falloff").contains(field)) throw new IllegalArgumentException("Unsupported mask scalar");return edit(original,context,node -> node.enable(field,defaultToken(context,field))); }
    public static byte[] setScalar(String path,byte[] original,Context context,String field,String input) {
        authorize(path);mask(context);if (!List.of("threshold","falloff").contains(field)) throw new IllegalArgumentException("Unsupported mask scalar");double value;
        try { value=new BigDecimal(input).doubleValue(); } catch (NumberFormatException | NullPointerException invalid) { throw new IllegalArgumentException("Enter a finite 0..1 value",invalid); }
        if (!Double.isFinite(value) || value<0 || value>1) throw new IllegalArgumentException("Mask threshold/falloff must be finite within 0..1");double next=value;
        return edit(original,context,node -> { if (node.token(field)==null || !numericSupported(node,field)) throw new IllegalArgumentException("Enable a supported mask scalar first");return node.number(field,next); });
    }
    public static List<String> findings(LosslessJson root) {
        List<LosslessJson> entries;try { entries=entries(root); } catch (IllegalArgumentException unsupported) { return List.of("Unsupported binding list; local checks unavailable."); }
        if (entries==null) return List.of();if (entries.size()>MAXIMUM_ENTRIES) return List.of("Above the 64-binding editor limit; local checks skipped.");
        var findings=new ArrayList<String>();var keys=new HashMap<String,Integer>();var roles=new HashMap<String,Integer>();
        for (int index=0;index<entries.size();index++) {var node=entries.get(index);String label="imageMaps["+index+"]";
            try { String key=normalized(text(node,"key"));if (keys.merge(key,1,Integer::sum)>1) findings.add(label+": duplicate trimmed local key."); } catch (IllegalArgumentException unsupported) { findings.add(label+": missing/unsupported local key."); }
            try { String role=choice(node,Context.binding(index),"application");if (!applications().contains(role)) findings.add(label+": unknown application.");else if (singleton(role) && roles.merge(role,1,Integer::sum)>1) findings.add(label+": duplicate singleton application "+role+"."); } catch (IllegalArgumentException unsupported) { findings.add(label+": unsupported application."); }
        }
        var choices=maskKeys(root);
        for (int index=0;index<entries.size();index++) {var node=entries.get(index);String label="imageMaps["+index+"]";
            try { var masks=masks(node);if (masks==null) continue;if (masks.size()>MAXIMUM_ENTRIES) { findings.add(label+": above the 64-mask editor limit; layer checks skipped.");continue; }
                try { if (!masks.isEmpty() && choice(node,Context.binding(index),"application").equals("MASK")) findings.add(label+": a MASK binding cannot have additional masks."); } catch (IllegalArgumentException ignored) { }
                for (int mask=0;mask<masks.size();mask++) try { String key=normalized(text(masks.get(mask),"map"));if (!choices.contains(key)) findings.add(label+".masks["+mask+"]: no unambiguous local MASK key match."); } catch (IllegalArgumentException unsupported) { findings.add(label+".masks["+mask+"]: missing/unsupported mask reference."); }
            } catch (IllegalArgumentException unsupported) { findings.add(label+": unsupported masks list."); }
        }
        return List.copyOf(findings);
    }
}
