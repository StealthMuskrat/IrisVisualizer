package dev.irisvisualizer.editor;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.function.Function;
import java.util.regex.Pattern;

/** Procedural material/palette authoring without block resolution or sampling. */
public final class ProceduralMaterials {
    public static final List<String> TREE_FIELDS = List.of("trunk", "trunkPalette", "leaves", "leavesPalette", "secondaryLeaves", "secondaryLeavesPalette", "secondaryTrunk", "secondaryTrunkPalette");
    public static final List<String> RUIN_FIELDS = List.of("block", "blockPalette", "weatheringPalette", "weatheredBlock");
    public static final List<String> FORMATION_FIELDS = List.of("block", "blockPalette", "capBlock", "capPalette", "strataPalette");
    public static final List<String> CORAL_FIELDS = List.of("block", "blockPalette", "tipBlock", "tipPalette");
    public static final List<String> FUNGUS_FIELDS = List.of("stem", "stemPalette", "cap", "capPalette", "gillBlock", "gillPalette", "spotBlock", "spotPalette");
    public static final List<String> CRYSTAL_FIELDS = List.of("block", "blockPalette", "tipBlock", "tipPalette", "glowBlock", "baseBlock", "basePalette");
    public static final List<String> STILT_FIELDS = List.of("stiltSettings.palette");
    public static final int MAXIMUM_ENTRIES=64;
    private static final Properties DATA=new Properties();
    static { try (var input=ProceduralMaterials.class.getResourceAsStream("/procedural-material-fields.properties")) { if (input==null) throw new IOException("Missing procedural material contracts");DATA.load(input);if (!"STONE".equals(DATA.getProperty("palette.classBlock")) || !"nonempty".equals(DATA.getProperty("palette.activation"))) throw new IOException("Unsupported procedural palette defaults"); } catch (IOException failure) { throw new ExceptionInInitializerError(failure); } }
    public static List<String> fields(String kind) { if (kind==null) throw new IllegalArgumentException("Select a procedural kind");return switch(kind) {
        case "trees" -> TREE_FIELDS;
        case "ruins" -> RUIN_FIELDS;
        case "formations" -> FORMATION_FIELDS;
        case "coral" -> CORAL_FIELDS;
        case "fungi" -> FUNGUS_FIELDS;
        case "crystals" -> CRYSTAL_FIELDS;
        default -> throw new IllegalArgumentException("Select a procedural kind");
    }; }
    public static List<String> materials(String kind) { return fields(kind).stream().filter(f -> "String".equals(DATA.getProperty(kind+"."+f+".type"))).toList(); }
    public static List<String> palettes(String kind) { return fields(kind).stream().filter(f -> "IrisMaterialPalette".equals(DATA.getProperty(kind+"."+f+".type"))).toList(); }
    public static boolean handles(String kind,String field) { return field!=null && fields(kind).contains(field); }
    public static String help(String kind,String field) { if (!handles(kind,field)) throw new IllegalArgumentException("Select a material role");return DATA.getProperty(kind+"."+field+".description"); }
    public static String defaultBlock(String kind,String field) { if (!materials(kind).contains(field)) throw new IllegalArgumentException("Select a single material role");return DATA.getProperty(kind+"."+field+".default"); }
    public static boolean optional(String kind,String field) { return defaultBlock(kind,field).equals("null"); }
    public static boolean blockSyntax(String text) { return text!=null && text.indexOf(0)<0 && text.toLowerCase(java.util.Locale.ROOT).matches("(?:minecraft:)?[a-z0-9_./-]+(?:\\[[a-z0-9_]+=[a-z0-9_.-]+(?:,[a-z0-9_]+=[a-z0-9_.-]+)*\\])?"); }
    static String material(LosslessJson entry,String kind,String field) { String fallback=defaultBlock(kind,field),token=entry.token(field);return token==null ? fallback.equals("null") ? null : fallback : token.equals("null") ? null : entry.stringToken(field); }
    public static byte[] setMaterial(String path,byte[] raw,ProceduralObjects.Context c,String field,String value) { ProceduralObjects.authorize(path,c);defaultBlock(c.kind(),field);if (!blockSyntax(value)) throw new IllegalArgumentException("Use Minecraft or unnamespaced block/alias syntax; external namespaces and state existence are unsupported/unverified");return ProceduralObjects.edit(raw,c,e -> { String old=material(e,c.kind(),field);return value.equals(old) && e.token(field)!=null ? e.sourceBytes() : e.nullableString(field,value); }); }
    public static byte[] clearMaterial(String path,byte[] raw,ProceduralObjects.Context c,String field) { ProceduralObjects.authorize(path,c);if (!optional(c.kind(),field)) throw new IllegalArgumentException("This material has a non-null native default; reset to restore it");return ProceduralObjects.edit(raw,c,e -> { material(e,c.kind(),field);return "null".equals(e.token(field)) ? e.sourceBytes() : e.nullableString(field,null); }); }
    public static byte[] resetMaterial(String path,byte[] raw,ProceduralObjects.Context c,String field) { ProceduralObjects.authorize(path,c);defaultBlock(c.kind(),field);return ProceduralObjects.edit(raw,c,e -> { material(e,c.kind(),field);return e.token(field)==null ? e.sourceBytes() : e.removeField(field); }); }
    public record Context(String resourceBase,String kind,int index,String field,int element) {
        public Context(String resourceBase,String kind,int index,String field) { this(resourceBase,kind,index,field,-1); }
        public Context(ProceduralObjects.Context owner,String field) { this(owner.base(),owner.kind(),owner.index(),field); }
        public Context { new ProceduralObjects.Context(resourceBase,kind,index);if (field==null || element < -1 || (element>=0 ? !(kind.equals("trees") && field.equals("decorators.palette") || kind.equals("ruins") && field.equals("accents.palette")) : !palettes(kind).contains(field) && !STILT_FIELDS.contains(field))) throw new IllegalArgumentException("Select a procedural material palette"); }
        public ProceduralObjects.Context owner() { return new ProceduralObjects.Context(resourceBase,kind,index); }
        public static Context accent(ProceduralAccents.Context c) { if (c==null || !c.palette()) throw new IllegalArgumentException("Select a decorator palette");return new Context(c.owner().base(),c.owner().kind(),c.owner().index(),c.list()+".palette",c.index()); }
        public boolean accent() { return element>=0; }
        public ProceduralAccents.Context accentContext() { if (!accent()) throw new IllegalArgumentException("Not an accent palette");return new ProceduralAccents.Context(owner(),field.substring(0,field.indexOf('.')),element); }
        public String palettePath() { return accent() ? accentContext().list()+"."+element+".palette" : field; }
        public String diagnosticPath() { return accent() ? accentContext().list()+"["+element+"].palette" : field; }
        public boolean stilt() { return field.equals("stiltSettings.palette"); }
        public String member() { return stilt() || accent() ? "palette" : field; }
        public String prefix() { return owner().prefix()+palettePath()+"."; }
        public String stylePath() { return "proceduralObjects."+kind+"."+index+"."+palettePath()+".style"; }
        public NoiseStyleFields.Target styleTarget() { return new NoiseStyleFields.Target(resourceBase,stylePath(),null); }
        GeneralBlockPalettes.Target target() { return GeneralBlockPalettes.Target.procedural(this); }
    }
    private static void authorize(String path,Context c) { if (c==null) throw new IllegalArgumentException("Select a palette context");ProceduralObjects.authorize(path,c.owner()); }
    static Context styleContext(String base,String path) {
        if (!("biomes".equals(base) || "regions".equals(base)) || path==null) return null;
        var accent=Pattern.compile("proceduralObjects\\.(trees|ruins)\\.(0|[1-9][0-9]*)\\.(decorators|accents)\\.(0|[1-9][0-9]*)\\.palette\\.style").matcher(path);
        if (accent.matches()) try { return new Context(base,accent.group(1),Integer.parseInt(accent.group(2)),accent.group(3)+".palette",Integer.parseInt(accent.group(4))); } catch (IllegalArgumentException bad) { return null; }
        var m=Pattern.compile("proceduralObjects\\.(trees|ruins|formations|coral|fungi|crystals)\\.(0|[1-9][0-9]*)\\.([A-Za-z]+|stiltSettings\\.palette)\\.style").matcher(path);if (!m.matches()) return null;try { return new Context(base,m.group(1),Integer.parseInt(m.group(2)),m.group(3)); } catch (IllegalArgumentException bad) { return null; }
    }

    static LosslessJson parent(LosslessJson entry,Context c) { if (c.accent()) return ProceduralAccents.entry(entry,c.accentContext());if (!c.stilt()) return entry;var stilt=ProceduralTransforms.object(entry,"stiltSettings");if (stilt==null) throw new IllegalArgumentException("Enable inline stilt settings first");return stilt; }
    static LosslessJson object(LosslessJson entry,Context c) { String token=parent(entry,c).token(c.member());return token==null || token.equals("null") ? null : new LosslessJson(token.getBytes(StandardCharsets.UTF_8)); }
    private static byte[] editOwner(byte[] raw,Context c,Function<LosslessJson,byte[]> op) { if (c.accent()) return ProceduralAccents.edit(raw,c.accentContext(),op);return ProceduralObjects.edit(raw,c.owner(),entry -> { if (!c.stilt()) return op.apply(entry);parent(entry,c);return entry.objectEdit("stiltSettings",op,false); }); }
    public static String creationBlock(Context c) { return DATA.getProperty("palette.classBlock"); }
    static List<LosslessJson> entries(LosslessJson palette) { return palette.token("palette")==null ? null : palette.objectArray("palette"); }
    static void checkEntries(LosslessJson palette) { var entries=entries(palette);if (entries!=null && entries.size()>MAXIMUM_ENTRIES) throw new IllegalArgumentException("Above the 64-entry procedural palette editor limit"); }
    static byte[] editPalette(byte[] original,Context context,Function<LosslessJson,byte[]> operation) { return editOwner(original,context,o -> { String token=o.token(context.member());if (token==null || token.equals("null")) throw new IllegalArgumentException("Enable an inline procedural palette first");return o.objectEdit(context.member(),p -> { checkEntries(p);return operation.apply(p); },false); }); }
    public static byte[] create(String path,byte[] original,Context context) { authorize(path,context);return editOwner(original,context,o -> { if (o.token(context.member())!=null && !o.token(context.member()).equals("null")) throw new IllegalArgumentException("Palette already exists or has an unsupported type");var enabled=new LosslessJson(o.enableObject(context.member()));return enabled.objectEdit(context.member(),p -> GeneralBlockPalettes.seed(p,creationBlock(context)),false); }); }
    public static byte[] reset(String path,byte[] original,Context context) { authorize(path,context);return editOwner(original,context,o -> { String token=o.token(context.member());if (token==null) return o.sourceBytes();if (!token.equals("null")) checkEntries(new LosslessJson(token.getBytes(StandardCharsets.UTF_8)));return o.removeField(context.member()); }); }
    public static byte[] enableEntries(String path,byte[] original,Context context) { authorize(path,context);return editPalette(original,context,p -> GeneralBlockPalettes.seed(p,DimensionPalettes.classBlock())); }
    private static void block(String input) { if (!blockSyntax(input)) throw new IllegalArgumentException("Use Minecraft or unnamespaced block/alias syntax; external blocks and registry/state validation remain unsupported"); }
    public static byte[] setBlock(String path,byte[] original,Context context,int index,String input) { authorize(path,context);block(input);return editPalette(original,context,p -> p.objectArrayEdit("palette",index,e -> { String old=e.stringToken("block");return input.equals(old) ? e.sourceBytes() : old==null ? e.enableStringPath("block",input) : e.stringValue("block",input); })); }
    private static String weight(LosslessJson entry) { String token=entry.token("weight");if (token==null) return DimensionPalettes.weightDefault();try { if (!LosslessJson.numericToken(token)) throw new NumberFormatException();new BigDecimal(token).intValueExact();return token; } catch (NumberFormatException | ArithmeticException invalid) { throw new IllegalArgumentException("Stored weight is not an exact signed 32-bit integer",invalid); } }
    static boolean supportedWeight(LosslessJson entry) { try { weight(entry);return true; } catch (IllegalArgumentException invalid) { return false; } }
    public static byte[] enableWeight(String path,byte[] original,Context context,int index) { authorize(path,context);return editPalette(original,context,p -> GeneralBlockPalettes.enableWeight(p,index)); }
    public static byte[] setWeight(String path,byte[] original,Context context,int index,String input) {
        authorize(path,context);int value;try { value=new BigDecimal(input).intValueExact(); } catch (NumberFormatException | ArithmeticException | NullPointerException invalid) { throw new IllegalArgumentException("Enter an exact integer weight",invalid); }
        if (value<DimensionPalettes.minimumWeight() || value>DimensionPalettes.maximumWeight()) throw new IllegalArgumentException("Weight violates native palette bounds");
        return editPalette(original,context,p -> p.objectArrayEdit("palette",index,e -> { weight(e);if (e.token("weight")==null) throw new IllegalArgumentException("Enable weight first");return new BigDecimal(e.token("weight")).intValueExact()==value ? e.sourceBytes() : e.integer("weight",value); }));
    }
    public static byte[] change(String path,byte[] original,Context context,RiverProfiles.Action action,int index,String input) { authorize(path,context);if (action==null) throw new IllegalArgumentException("Choose a palette action");if (action==RiverProfiles.Action.ADD) block(input);return editPalette(original,context,p -> { var entries=entries(p);if (entries==null) throw new IllegalArgumentException("Enable entries first");if ((action==RiverProfiles.Action.ADD || action==RiverProfiles.Action.DUPLICATE) && entries.size()>=MAXIMUM_ENTRIES) throw new IllegalArgumentException("Procedural palettes have a 64-entry editor limit");return GeneralBlockPalettes.change(p,action,index,input); }); }
    public static byte[] enableZoom(String path,byte[] original,Context context) { authorize(path,context);return editPalette(original,context,p -> p.enable("zoom",RiverPaletteFields.zoomDefault())); }
    private static String zoom(LosslessJson palette) { String token=palette.token("zoom");if (token==null) return RiverPaletteFields.zoomDefault();try { if (!LosslessJson.numericToken(token) || !Double.isFinite(new BigDecimal(token).doubleValue())) throw new NumberFormatException();return token; } catch (NumberFormatException invalid) { throw new IllegalArgumentException("Stored palette zoom is not finite numeric data",invalid); } }
    static boolean supportedZoom(LosslessJson palette) { try { zoom(palette);return true; } catch (IllegalArgumentException invalid) { return false; } }
    public static byte[] setZoom(String path,byte[] original,Context context,String input) {
        authorize(path,context);BigDecimal value;try { value=new BigDecimal(input);if (value.compareTo(new BigDecimal(RiverPaletteFields.zoomMinimum()))<0 || !Double.isFinite(value.doubleValue())) throw new NumberFormatException(); } catch (NumberFormatException | NullPointerException invalid) { throw new IllegalArgumentException("Enter a finite palette zoom at least "+RiverPaletteFields.zoomMinimum(),invalid); }
        return editPalette(original,context,p -> { zoom(p);if (p.token("zoom")==null) throw new IllegalArgumentException("Enable zoom first");return new BigDecimal(p.token("zoom")).compareTo(value)==0 ? p.sourceBytes() : p.number("zoom",value.doubleValue()); });
    }
    static List<String> readOnlyPaths(LosslessJson entry,Context context) {
        var paths=new ArrayList<String>();String prefix=context.diagnosticPath();
        try { var palette=object(entry,context);if (palette==null) return List.of();checkEntries(palette);
            for (String field : palette.fields()) if (!RiverMaterialFields.PALETTE_FIELDS.contains(field)) paths.add(prefix+"."+field);
            try { zoom(palette); } catch (IllegalArgumentException invalid) { paths.add(prefix+".zoom"); }
            try { var style=NoiseStyleFields.object(palette,context.styleTarget());if (style!=null) for (String field : StyleFractures.readOnlyPaths(style)) paths.add(prefix+".style."+field); } catch (IllegalArgumentException invalid) { paths.add(prefix+".style"); }
            var entries=entries(palette);if (entries!=null) for (int i=0;i<entries.size();i++) for (String field : entries.get(i).fields()) { var block=entries.get(i);String at=prefix+".palette["+i+"]."+field;
                if (!RiverMaterialFields.ENTRY_FIELDS.contains(field)) paths.add(at);else try { if (field.equals("weight")) weight(block);else block.stringToken("block"); } catch (IllegalArgumentException invalid) { paths.add(at); }
            }
        } catch (IllegalArgumentException invalid) { paths.add(prefix); }return List.copyOf(paths);
    }
}
