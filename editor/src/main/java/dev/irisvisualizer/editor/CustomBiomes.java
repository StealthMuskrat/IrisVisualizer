package dev.irisvisualizer.editor;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/** Bounded custom-biome datapack authoring; no registries, datapacks or native generation run. */
public final class CustomBiomes {
    public static final List<String> LIST_FIELDS = List.of("customDerivitives");
    public static final List<String> EDITABLE = List.of("id", "temperature", "humidity", "spawns", "tags", "downfallType", "ambientParticle", "category", "spawnRarity", "skyColor", "fogColor", "waterColor", "waterFogColor", "grassColor", "foliageColor");
    public static final List<String> SPAWN_FIELDS = List.of("type", "minCount", "maxCount", "weight", "group");
    public static final List<String> PARTICLE_FIELDS = List.of("particle", "rarity");
    public static final List<String> COLORS = List.of("skyColor", "fogColor", "waterColor", "waterFogColor", "grassColor", "foliageColor");
    public static final int MAXIMUM_ENTRIES=64;
    private static final Properties DATA=new Properties();
    static {
        try (var input=CustomBiomes.class.getResourceAsStream("/custom-biome-fields.properties")) {
            if (input==null) throw new IOException("Missing pinned custom-biome definitions");DATA.load(input);
            if (!"nonemptyCustomList".equals(DATA.getProperty("custom.policy")) || !"independentOfSpawnRarity".equals(DATA.getProperty("spawns.policy")) || !"reciprocalRarity".equals(DATA.getProperty("particle.policy"))) throw new IOException("Unsupported custom-biome policy");
        } catch (IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
    public record Context(int entry,int spawn,boolean particle) {
        public Context { if (entry<0 || spawn< -1 || particle && spawn!=-1) throw new IllegalArgumentException("Select a custom biome entry"); }
        public static Context biome(int entry) { return new Context(entry,-1,false); }
        public Context spawn(int index) { if (index<0) throw new IllegalArgumentException("Select a spawn entry");return new Context(entry,index,false); }
        public Context particleTarget() { return new Context(entry,-1,true); }
        public String group() { return particle ? "particle" : spawn>=0 ? "spawn" : "biome"; }
        public String prefix() { return CustomBiomes.prefix()+entry+"."+(particle ? "ambientParticle." : spawn>=0 ? "spawns#"+spawn+"." : ""); }
    }
    public static String prefix() { return "customDerivitives#"; }
    public static String listPrefix(Context context,String field) { if (!List.of("spawns","tags").contains(field)) throw new IllegalArgumentException("Unsupported custom-biome list");return Context.biome(context.entry()).prefix()+field+"#"; }
    private static void authorize(String path) { if (!BiomeFields.biome(path)) throw new IllegalArgumentException("Custom definitions are biome-only"); }
    private static List<String> fields(Context context) { return context.particle() ? PARTICLE_FIELDS : context.spawn()>=0 ? SPAWN_FIELDS : EDITABLE; }
    private static void field(Context context,String field) { if (context==null || field==null || !fields(context).contains(field)) throw new IllegalArgumentException("Unsupported custom-biome field"); }
    public static String help(Context context,String field) { field(context,field);return DATA.getProperty(context.group()+"."+field+".description",""); }
    public static String defaultToken(Context context,String field) { field(context,field);return DATA.getProperty(context.group()+"."+field+".default"); }
    public static List<String> choices(Context context,String field) { field(context,field);String value=DATA.getProperty(context.group()+"."+field+".choices");return value==null ? List.of() : List.of(value.split(",")); }
    public static String constraints(Context context,String field) {
        field(context,field);String key=context.group()+"."+field;
        return (DATA.getProperty(key+".type").equals("int") ? "Exact signed 32-bit integer" : "Finite number")+"; "+DATA.getProperty(key+".minimum")+".."+DATA.getProperty(key+".maximum");
    }
    static List<LosslessJson> entries(LosslessJson document) { String token=document.token("customDerivitives");return token==null || token.equals("null") ? null : document.objectArray("customDerivitives"); }
    static List<LosslessJson> spawns(LosslessJson entry) { return entry.token("spawns")==null ? null : entry.objectArray("spawns"); }
    static List<String> tags(LosslessJson entry) { return entry.token("tags")==null ? null : entry.stringArrayPath("tags"); }
    static LosslessJson particle(LosslessJson entry) { String token=entry.token("ambientParticle");return token==null || token.equals("null") ? null : new LosslessJson(token.getBytes(StandardCharsets.UTF_8)); }
    static byte[] edit(byte[] original,Context context,java.util.function.Function<LosslessJson,byte[]> operation) {
        var document=new LosslessJson(original);var entries=entries(document);
        if (entries==null || entries.size()>MAXIMUM_ENTRIES) throw new IllegalArgumentException("Select an inline custom list within the editor limit");
        return document.objectArrayEdit("customDerivitives",context.entry(),entry -> {
            if (context.particle()) return entry.objectEdit("ambientParticle",operation,false);
            if (context.spawn()>=0) { var spawns=spawns(entry);if (spawns==null || spawns.size()>MAXIMUM_ENTRIES) throw new IllegalArgumentException("Select an inline spawn list within the editor limit");return entry.objectArrayEdit("spawns",context.spawn(),operation); }
            return operation.apply(entry);
        });
    }
    private static void id(String value) { if (value==null || !value.matches("[a-z0-9_-]+")) throw new IllegalArgumentException("Use a simple lowercase ID with letters, digits, _ or - (editor subset)"); }
    private static void key(String value) {
        if (value==null || !value.matches("[a-z0-9_.-]+:[a-z0-9_./-]+") || value.endsWith("/") || value.contains("//") || value.contains("..") || value.contains(":/")) throw new IllegalArgumentException("Use a fully namespaced lowercase registry key; registry existence is unvalidated");
    }
    private static void unique(List<LosslessJson> entries,String value,int except) {
        if (entries==null) return;
        for (int index=0;index<entries.size();index++) if (index!=except) {
            String token=entries.get(index).token("id");if (token==null) continue;
            String stored=entries.get(index).stringToken("id");if (stored.toLowerCase(Locale.ROOT).equals(value)) throw new IllegalArgumentException("This ID already exists in this custom-biome list");
        }
    }
    public static byte[] change(String path,byte[] original,RiverProfiles.Action action,int index,String newId) {
        authorize(path);if (action==null) throw new IllegalArgumentException("Choose a custom-biome action");var document=new LosslessJson(original);var entries=entries(document);
        if (action==RiverProfiles.Action.ADD || action==RiverProfiles.Action.DUPLICATE) { id(newId);unique(entries,newId,-1); }
        if (entries==null) {
            if (action!=RiverProfiles.Action.ADD) throw new IllegalArgumentException("Custom list is disabled");document=new LosslessJson(document.enableArray("customDerivitives"));entries=entries(document);
        }
        if (entries.size()>MAXIMUM_ENTRIES) throw new IllegalArgumentException("Oversized custom lists remain read-only in this editor");
        if (action==RiverProfiles.Action.ADD) {
            if (entries.size()>=MAXIMUM_ENTRIES) throw new IllegalArgumentException("Editor custom-list limit reached");
            byte[] seed=new LosslessJson("{}".getBytes(StandardCharsets.UTF_8)).enableStringPath("id",newId);return document.objectArrayInsert("customDerivitives",entries.size(),seed);
        }
        if (index<0 || index>=entries.size()) throw new IllegalArgumentException("Select a custom-biome entry");
        return switch (action) {
            case DUPLICATE -> {
                if (entries.size()>=MAXIMUM_ENTRIES) throw new IllegalArgumentException("Editor custom-list limit reached");var entry=entries.get(index);
                byte[] renamed=entry.token("id")==null ? entry.enableStringPath("id",newId) : entry.stringValue("id",newId);
                yield document.objectArrayInsert("customDerivitives",index+1,renamed);
            }
            case REMOVE -> entries.size()==1 ? document.removeField("customDerivitives") : document.objectArrayRemove("customDerivitives",index);
            case UP -> document.objectArrayMove("customDerivitives",index,index-1);
            case DOWN -> document.objectArrayMove("customDerivitives",index,index+1);
            default -> throw new IllegalArgumentException("Unsupported custom-biome action");
        };
    }
    public static byte[] setText(String path,byte[] original,Context context,String field,String value) {
        authorize(path);field(context,field);
        if (context.group().equals("biome") && field.equals("id")) { id(value);unique(entries(new LosslessJson(original)),value,context.entry()); }
        else if (context.group().equals("biome") && COLORS.contains(field)) {
            if (value==null || !value.matches("#[0-9a-fA-F]{6}") && !(List.of("grassColor","foliageColor").contains(field) && value.isEmpty())) throw new IllegalArgumentException("Use #RRGGBB; only grass/foliage may be blank to omit the override");
        } else if (context.particle() && field.equals("particle") || context.spawn()>=0 && field.equals("type")) key(value);
        else throw new IllegalArgumentException("Use the typed scalar, enum or list control");
        return edit(original,context,node -> node.token(field)==null ? node.enableStringPath(field,value) : node.stringValue(field,value));
    }
    public static byte[] setEnum(String path,byte[] original,Context context,String field,String value) {
        authorize(path);if (value==null || !choices(context,field).contains(value)) throw new IllegalArgumentException("Select a pinned enum choice");
        return edit(original,context,node -> node.token(field)==null ? node.enableStringPath(field,value) : context.spawn()>=0 && field.equals("group") && node.token(field).equals("null") ? node.nullableString(field,value) : node.stringValue(field,value));
    }
    static boolean numericSupported(LosslessJson node,Context context,String field) {
        field(context,field);String token=node.token(field);if (token==null) return true;if (!LosslessJson.numericToken(token)) return false;
        if ("int".equals(DATA.getProperty(context.group()+"."+field+".type"))) { try { new BigDecimal(token).intValueExact(); } catch (NumberFormatException | ArithmeticException invalid) { return false; } }return true;
    }
    private static BigDecimal scalar(Context context,String field,String input) {
        field(context,field);String key=context.group()+"."+field,type=DATA.getProperty(key+".type");
        if (!List.of("int","double").contains(type)) throw new IllegalArgumentException("Use a scalar control");
        try {
            var value=new BigDecimal(input);if (type.equals("int")) value.intValueExact();
            if (!Double.isFinite(value.doubleValue()) || value.compareTo(new BigDecimal(DATA.getProperty(key+".minimum")))<0 || value.compareTo(new BigDecimal(DATA.getProperty(key+".maximum")))>0) throw new IllegalArgumentException("Value violates pinned custom-biome bounds");return value;
        } catch (NumberFormatException | ArithmeticException | NullPointerException invalid) { throw new IllegalArgumentException("Enter a number within the pinned bounds",invalid); }
    }
    public static byte[] enableScalar(String path,byte[] original,Context context,String field) {
        authorize(path);String value=defaultToken(context,field);scalar(context,field,value);if (List.of("minCount","maxCount").contains(field)) throw new IllegalArgumentException("Apply spawn counts together");return edit(original,context,node -> node.enable(field,value));
    }
    public static byte[] setScalar(String path,byte[] original,Context context,String field,String input) {
        authorize(path);var value=scalar(context,field,input);if (List.of("minCount","maxCount").contains(field)) throw new IllegalArgumentException("Apply spawn counts together");
        return edit(original,context,node -> { if (node.token(field)==null || !numericSupported(node,context,field)) throw new IllegalArgumentException("Enable a supported scalar first");return DATA.getProperty(context.group()+"."+field+".type").equals("int") ? node.integer(field,value.longValueExact()) : node.number(field,value.doubleValue()); });
    }
    public static byte[] setCounts(String path,byte[] original,Context context,String minimum,String maximum) {
        authorize(path);if (context.spawn()<0) throw new IllegalArgumentException("Select a custom spawn");int min=scalar(context,"minCount",minimum).intValueExact(),max=scalar(context,"maxCount",maximum).intValueExact();
        if (min>max) throw new IllegalArgumentException("Use an ordered positive spawn-count pair (editor guard)");
        return edit(original,context,node -> {
            if (!numericSupported(node,context,"minCount") || !numericSupported(node,context,"maxCount")) throw new IllegalArgumentException("Unsupported stored count pair");
            byte[] first=node.token("minCount")==null ? node.enable("minCount",Integer.toString(min)) : node.integer("minCount",min);var next=new LosslessJson(first);return next.token("maxCount")==null ? next.enable("maxCount",Integer.toString(max)) : next.integer("maxCount",max);
        });
    }
    public static byte[] enableParticle(String path,byte[] original,Context context) { authorize(path);return edit(original,Context.biome(context.entry()),entry -> entry.enableObject("ambientParticle")); }
    public static byte[] disableParticle(String path,byte[] original,Context context) { authorize(path);return edit(original,Context.biome(context.entry()),entry -> entry.nullObject("ambientParticle")); }
    public static byte[] changeSpawn(String path,byte[] original,Context context,RiverProfiles.Action action,int index,String type) {
        authorize(path);if (action==null) throw new IllegalArgumentException("Choose a spawn action");if (action==RiverProfiles.Action.ADD) key(type);
        return edit(original,Context.biome(context.entry()),entry -> {
            var spawns=spawns(entry);if (spawns==null) { if (action!=RiverProfiles.Action.ADD) throw new IllegalArgumentException("Spawn list is absent");entry=new LosslessJson(entry.enable("spawns","[]"));spawns=spawns(entry); }
            if (spawns.size()>MAXIMUM_ENTRIES) throw new IllegalArgumentException("Oversized spawn list remains read-only");
            if (action==RiverProfiles.Action.ADD) { if (spawns.size()>=MAXIMUM_ENTRIES) throw new IllegalArgumentException("Editor spawn-list limit reached");return entry.objectArrayInsert("spawns",spawns.size(),new LosslessJson("{}".getBytes(StandardCharsets.UTF_8)).enableStringPath("type",type)); }
            if (index<0 || index>=spawns.size()) throw new IllegalArgumentException("Select a custom spawn");
            return switch (action) {
                case DUPLICATE -> { if (spawns.size()>=MAXIMUM_ENTRIES) throw new IllegalArgumentException("Editor spawn-list limit reached");yield entry.objectArrayInsert("spawns",index+1,spawns.get(index).sourceBytes()); }
                case REMOVE -> spawns.size()==1 ? entry.removeField("spawns") : entry.objectArrayRemove("spawns",index);
                case UP -> entry.objectArrayMove("spawns",index,index-1);
                case DOWN -> entry.objectArrayMove("spawns",index,index+1);
                default -> throw new IllegalArgumentException("Unsupported spawn action");
            };
        });
    }
    public static byte[] setTag(String path,byte[] original,Context context,int index,String value) { authorize(path);key(value);return edit(original,Context.biome(context.entry()),entry -> { var tags=tags(entry);if (tags==null || tags.size()>MAXIMUM_ENTRIES || index<0 || index>=tags.size()) throw new IllegalArgumentException("Select an inline tag within the editor limit");return entry.stringArrayPath("tags",index,value); }); }
    public static byte[] changeTag(String path,byte[] original,Context context,RiverProfiles.Action action,int index,String value) {
        authorize(path);if (action==null) throw new IllegalArgumentException("Choose a tag action");if (action==RiverProfiles.Action.ADD) key(value);
        return edit(original,Context.biome(context.entry()),entry -> {
            var tags=tags(entry);if (tags==null) { if (action!=RiverProfiles.Action.ADD) throw new IllegalArgumentException("Tags are absent");entry=new LosslessJson(entry.enable("tags","[]"));tags=tags(entry); }
            if (tags.size()>MAXIMUM_ENTRIES) throw new IllegalArgumentException("Oversized tag list remains read-only");
            if (action==RiverProfiles.Action.ADD) { if (tags.size()>=MAXIMUM_ENTRIES) throw new IllegalArgumentException("Editor tag-list limit reached");return entry.stringArrayPath("tags",tags.size(),value); }
            if (index<0 || index>=tags.size()) throw new IllegalArgumentException("Select a tag");
            return switch (action) {
                case DUPLICATE -> { if (tags.size()>=MAXIMUM_ENTRIES) throw new IllegalArgumentException("Editor tag-list limit reached");yield entry.stringArrayDuplicate("tags",index); }
                case REMOVE -> tags.size()==1 ? entry.removeField("tags") : entry.stringArrayPath("tags",index,null);
                case UP -> entry.stringArrayMove("tags",index,index-1);
                case DOWN -> entry.stringArrayMove("tags",index,index+1);
                default -> throw new IllegalArgumentException("Unsupported tag action");
            };
        });
    }
    static List<String> readOnlyPaths(LosslessJson document) {
        var paths=new ArrayList<String>();try {
            var entries=entries(document);if (entries==null) return List.of();if (entries.size()>MAXIMUM_ENTRIES) return List.of("customDerivitives");
            for (int index=0;index<entries.size();index++) readOnly(entries.get(index),Context.biome(index),"customDerivitives["+index+"].",paths);
        } catch (IllegalArgumentException invalid) { paths.add("customDerivitives"); }return List.copyOf(paths);
    }
    private static void readOnly(LosslessJson node,Context context,String prefix,List<String> paths) {
        for (String field : node.fields()) {
            if (!fields(context).contains(field)) { paths.add(prefix+field);continue; }
            if (context.group().equals("biome") && field.equals("spawns")) {
                try { var entries=spawns(node);if (entries.size()>MAXIMUM_ENTRIES) paths.add(prefix+field);else for (int i=0;i<entries.size();i++) readOnly(entries.get(i),context.spawn(i),prefix+"spawns["+i+"].",paths); } catch (IllegalArgumentException invalid) { paths.add(prefix+field); }
            } else if (context.group().equals("biome") && field.equals("ambientParticle")) {
                try { var particle=particle(node);if (particle!=null) readOnly(particle,context.particleTarget(),prefix+"ambientParticle.",paths); } catch (IllegalArgumentException invalid) { paths.add(prefix+field); }
            } else if (context.group().equals("biome") && field.equals("tags")) {
                try { if (tags(node).size()>MAXIMUM_ENTRIES) paths.add(prefix+field); } catch (IllegalArgumentException invalid) { paths.add(prefix+field); }
            } else if (List.of("int","double").contains(DATA.getProperty(context.group()+"."+field+".type"))) {
                if (!numericSupported(node,context,field)) paths.add(prefix+field);
            } else if (!(context.spawn()>=0 && field.equals("group") && node.token(field).equals("null"))) {
                try { node.stringToken(field); } catch (IllegalArgumentException invalid) { paths.add(prefix+field); }
            }
        }
    }
}
