package dev.irisvisualizer.editor;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.function.Function;

/** Lossless river material authoring. Runtime registry resolution remains outside this editor. */
public final class RiverMaterialFields {
    public static final List<String> MATERIAL_TARGETS = List.of("surface.banks.shoreMaterial", "surface.banks.bankMaterial", "surface.bed.material", "underground.bedMaterial");
    public static final List<String> EDITABLE = List.of("enabled", "depth", "palette");
    public static final List<String> PALETTE_FIELDS = List.of("palette", "style", "zoom");
    public static final List<String> ENTRY_FIELDS = List.of("block", "weight");
    public static final List<String> BANK_FIELDS = List.of("shoreMaterial", "bankMaterial");
    public static final List<String> BED_FIELDS = List.of("material", "paddingPalette");
    public static final List<String> UNDERGROUND_FIELDS = List.of("bedMaterial");
    public static final String PADDING="surface.bed.paddingPalette";
    public enum Action { ADD, DUPLICATE, REMOVE, UP, DOWN }
    private static final Properties DATA=new Properties();
    static {
        try (var input=RiverMaterialFields.class.getResourceAsStream("/river-material-fields.properties")) {
            if (input==null) throw new IOException("Missing pinned river material definitions");
            DATA.load(input);
            if (!"minecraft".equals(DATA.getProperty("block.namespace")) || !"water,lava,bubble_column".equals(DATA.getProperty("block.excluded"))) throw new IOException("Unsupported material block rule");
        } catch (IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
    private static void target(String target) { if (!MATERIAL_TARGETS.contains(target) && !PADDING.equals(target)) throw new IllegalArgumentException("Unsupported river palette target"); }
    private static void authorize(String path,String target) { if (!DimensionFields.dimension(path)) throw new IllegalArgumentException("River materials are dimension-only"); target(target); }
    private static void materialTarget(String target) { if (!MATERIAL_TARGETS.contains(target)) throw new IllegalArgumentException("This target is a standalone palette"); }
    public static String defaultToken(String field) {
        if (!List.of("enabled","depth","weight").contains(field)) throw new IllegalArgumentException("Unsupported material scalar");
        return DATA.getProperty(field+".default");
    }
    public static String draftPrefix(String target) { target(target); return "hydrology#rivers."+target+"."; }
    public static String listDraftPrefix(String target) { return draftPrefix(target)+"entries#"; }
    public static String creationDefaults(String target) { target(target); return DATA.getProperty(target+".summary"); }
    private static String leaf(String target) { return target.substring(target.lastIndexOf('.')+1); }
    private static byte[] descend(LosslessJson object,String[] path,int index,Function<LosslessJson,byte[]> action) {
        return index==path.length ? action.apply(object) : object.objectEdit(path[index],next -> descend(next,path,index+1,action),false);
    }
    private static byte[] editOwner(byte[] original,String target,Function<LosslessJson,byte[]> action) {
        String parent=target.substring(0,target.lastIndexOf('.'));
        return HydrologyFields.editRivers(original,rivers -> descend(rivers,parent.split("\\."),0,action));
    }
    private static byte[] editMaterial(byte[] original,String target,Function<LosslessJson,byte[]> action) {
        materialTarget(target); return editOwner(original,target,owner -> owner.objectEdit(leaf(target),action,false));
    }
    static byte[] editPalette(byte[] original,String target,Function<LosslessJson,byte[]> action) {
        return target.equals(PADDING) ? editOwner(original,target,owner -> owner.objectEdit(leaf(target),action,false))
                : editMaterial(original,target,material -> material.objectEdit("palette",action,false));
    }
    private static LosslessJson child(LosslessJson object,String field) {
        String token=object.token(field); return token==null ? null : new LosslessJson(token.getBytes(StandardCharsets.UTF_8));
    }
    static LosslessJson object(LosslessJson rivers,String target) {
        target(target); LosslessJson result=rivers;
        for (String part : target.split("\\.")) { result=child(result,part); if (result==null) return null; }
        return result;
    }
    static LosslessJson palette(LosslessJson object,String target) { target(target); return target.equals(PADDING) ? object : child(object,"palette"); }
    static List<LosslessJson> entries(LosslessJson palette) { return palette.token("palette")==null ? null : palette.objectArray("palette"); }
    private static byte[] entry(String block) { return new LosslessJson("{}".getBytes(StandardCharsets.UTF_8)).enableStringPath("block",block); }
    private static byte[] seedEntries(LosslessJson palette,String blocks) {
        if (palette.token("palette")!=null) throw new IllegalArgumentException("Palette entries already exist or have an unsupported type");
        byte[] next=palette.enable("palette","[]"); int index=0;
        for (String block : blocks.split(",")) next=new LosslessJson(next).objectArrayInsert("palette",index++,entry(block));
        return next;
    }
    public static byte[] createMaterial(String path,byte[] original,String target) {
        authorize(path,target); materialTarget(target);
        return editOwner(original,target,owner -> {
            String field=leaf(target);
            if (owner.token(field)!=null) throw new IllegalArgumentException("Material is already present or unsupported");
            return owner.objectEdit(field,material -> {
                if (!target.equals("surface.banks.shoreMaterial")) return material.sourceBytes();
                byte[] next=material.enable("enabled",DATA.getProperty("shore.enabled"));
                next=new LosslessJson(next).enable("depth",DATA.getProperty("shore.depth"));
                return new LosslessJson(next).objectEdit("palette",p -> seedEntries(p,DATA.getProperty("shore.blocks")),true);
            },true);
        });
    }
    public static byte[] createPalette(String path,byte[] original,String target) {
        authorize(path,target);
        Function<LosslessJson,byte[]> create=owner -> {
            String field=target.equals(PADDING) ? leaf(target) : "palette";
            if (owner.token(field)!=null) throw new IllegalArgumentException("Palette already exists or has an unsupported type");
            return owner.objectEdit(field,p -> seedEntries(p,DATA.getProperty(target.equals(PADDING) ? "padding.blocks" : "palette.blocks")),true);
        };
        return target.equals(PADDING) ? editOwner(original,target,create) : editMaterial(original,target,create);
    }
    public static byte[] enableEntries(String path,byte[] original,String target) {
        authorize(path,target); return editPalette(original,target,p -> seedEntries(p,DATA.getProperty("palette.blocks")));
    }
    public static byte[] setEnabled(String path,byte[] original,String target,boolean value) {
        authorize(path,target); return editMaterial(original,target,m -> m.token("enabled")==null ? m.enable("enabled",Boolean.toString(value)) : m.bool("enabled",value));
    }
    private static int bounded(String field,String text) {
        int value;
        try { value=new BigDecimal(text).intValueExact(); }
        catch (NumberFormatException | ArithmeticException | NullPointerException invalid) { throw new IllegalArgumentException("Enter an exact signed 32-bit integer",invalid); }
        if (value<Integer.parseInt(DATA.getProperty(field+".minimum")) || value>Integer.parseInt(DATA.getProperty(field+".maximum"))) throw new IllegalArgumentException(field+" must be between "+DATA.getProperty(field+".minimum")+" and "+DATA.getProperty(field+".maximum"));
        return value;
    }
    public static byte[] enableDepth(String path,byte[] original,String target) {
        authorize(path,target); return editMaterial(original,target,m -> m.enable("depth",defaultToken("depth")));
    }
    public static byte[] setDepth(String path,byte[] original,String target,String input) {
        authorize(path,target); int value=bounded("depth",input); return editMaterial(original,target,m -> m.number("depth",value));
    }
    private static void block(String value) {
        if (value==null || value.isBlank()) throw new IllegalArgumentException("Enter a nonblank Minecraft block name");
        String normalized=value.trim().toLowerCase(Locale.ROOT);
        int state=normalized.indexOf('['); if (state>=0) normalized=normalized.substring(0,state);
        int separator=normalized.indexOf(':'); String namespace=separator<0 ? "minecraft" : normalized.substring(0,separator);
        String key=separator<0 ? normalized : normalized.substring(separator+1);
        if (!DATA.getProperty("block.namespace").equals(namespace) || List.of(DATA.getProperty("block.excluded").split(",")).contains(key)) throw new IllegalArgumentException("River palettes require Minecraft blocks other than water, lava, or bubble_column");
    }
    public static byte[] setBlock(String path,byte[] original,String target,int index,String value) {
        authorize(path,target); block(value);
        return editPalette(original,target,p -> p.objectArrayEdit("palette",index,e -> e.token("block")==null ? e.enableStringPath("block",value) : e.stringValue("block",value)));
    }
    public static byte[] enableWeight(String path,byte[] original,String target,int index) {
        authorize(path,target); return editPalette(original,target,p -> p.objectArrayEdit("palette",index,e -> e.enable("weight",defaultToken("weight"))));
    }
    public static byte[] setWeight(String path,byte[] original,String target,int index,String input) {
        authorize(path,target); int value=bounded("weight",input); return editPalette(original,target,p -> p.objectArrayEdit("palette",index,e -> e.number("weight",value)));
    }
    public static byte[] change(String path,byte[] original,String target,Action action,int index,String newBlock) {
        authorize(path,target); if (action==null) throw new IllegalArgumentException("Choose a palette action");
        if (action==Action.ADD) block(newBlock);
        return editPalette(original,target,p -> {
            var entries=p.objectArray("palette");
            if (action==Action.ADD) return p.objectArrayInsert("palette",entries.size(),entry(newBlock));
            if (index<0 || index>=entries.size()) throw new IllegalArgumentException("Select an existing block");
            return switch(action) {
                case DUPLICATE -> p.objectArrayInsert("palette",index+1,entries.get(index).sourceBytes());
                case REMOVE -> { if (entries.size()<=1) throw new IllegalArgumentException("A river palette needs at least one block"); yield p.objectArrayRemove("palette",index); }
                case UP -> { if (index==0) throw new IllegalArgumentException("Already first"); yield p.objectArrayMove("palette",index,index-1); }
                case DOWN -> { if (index==entries.size()-1) throw new IllegalArgumentException("Already last"); yield p.objectArrayMove("palette",index,index+1); }
                default -> throw new IllegalArgumentException("Unsupported palette action");
            };
        });
    }
    static List<String> readOnlyPaths(LosslessJson rivers,String target) {
        target(target); var paths=new ArrayList<String>();
        try {
            var object=object(rivers,target); if (object==null) return List.of();
            if (!target.equals(PADDING)) for (String field : object.fields()) if (!EDITABLE.contains(field)) paths.add(target+"."+field);
            var palette=palette(object,target); if (palette==null) return paths;
            String prefix=target+(target.equals(PADDING) ? "" : ".palette");
            for (String field : palette.fields()) if (!PALETTE_FIELDS.contains(field)) paths.add(prefix+"."+field);
            for (String path : RiverPaletteFields.readOnlyStylePaths(palette,target)) paths.add(prefix+"."+path);
            try {
                var entries=entries(palette);
                if (entries!=null) for (int i=0;i<entries.size();i++) for (String field : entries.get(i).fields()) if (!ENTRY_FIELDS.contains(field)) paths.add(prefix+".palette["+i+"]."+field);
            } catch (IllegalArgumentException mismatch) { paths.add(prefix+".palette"); }
        } catch (IllegalArgumentException mismatch) { paths.add(target); }
        return List.copyOf(paths);
    }
}
