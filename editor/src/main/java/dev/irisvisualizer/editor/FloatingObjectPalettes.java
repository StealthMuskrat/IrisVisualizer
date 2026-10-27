package dev.irisvisualizer.editor;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** Shared palette contexts for stilt material overrides and ordered replacement rules. */
public final class FloatingObjectPalettes {
    public static final List<String> STILT_FIELDS = List.of("palette");
    public record Context(int entry,String list,int placement,int rule) {
        public Context { if (entry<0 || placement<0 || rule < -1 || list==null || !FloatingObjectPlacements.LIST_FIELDS.contains(list)) throw new IllegalArgumentException("Select a floating object palette"); }
        public boolean stilt() { return rule==-1; }
        public FloatingObjectReplacements.Context owner() { return new FloatingObjectReplacements.Context(entry,list,placement); }
        public String prefix() { return stilt() ? FloatingObjectPlacements.placementPrefix(entry,list,placement)+"stiltSettings.palette." : owner().rulePrefix(rule)+"replace."; }
        public String stylePath() { return "floatingChildBiomes."+entry+"."+list+"."+placement+(stilt() ? ".stiltSettings.palette.style" : ".edit."+rule+".replace.style"); }
        public GeneralBlockPalettes.Target target() { return GeneralBlockPalettes.Target.objectPalette(this); }
    }
    private static void authorize(String path) { if (!BiomeFields.biome(path)) throw new IllegalArgumentException("Object palettes are biome-only"); }
    private static String member(Context context) { return context.stilt() ? "palette" : "replace"; }
    private static byte[] editOwner(byte[] original,Context context,Function<LosslessJson,byte[]> action) { return context.stilt() ? FloatingObjectPlacements.editPlacement(original,context.entry(),context.list(),context.placement(),p -> p.objectEdit("stiltSettings",action,false)) : FloatingObjectReplacements.editRule(original,context.owner(),context.rule(),action); }
    static byte[] editPalette(byte[] original,Context context,Function<LosslessJson,byte[]> action) { return editOwner(original,context,o -> o.objectEdit(member(context),action,false)); }
    static LosslessJson object(LosslessJson placement,Context context) {
        LosslessJson owner;
        if (context.stilt()) { owner=FloatingPlacementDeformation.object(placement,"stiltSettings");if (owner==null) return null; }
        else { var rules=placement.objectArray("edit");if (context.rule()>=rules.size()) throw new IllegalArgumentException("Select a replacement rule");owner=rules.get(context.rule()); }
        String token=owner.token(member(context));return token==null || context.stilt() && token.equals("null") ? null : new LosslessJson(token.getBytes(StandardCharsets.UTF_8));
    }
    public static byte[] create(String path,byte[] original,Context context) { authorize(path);return editOwner(original,context,o -> { String token=o.token(member(context));if (token!=null && !(context.stilt() && token.equals("null"))) throw new IllegalArgumentException("Palette already exists or has an unsupported type");return o.enableObject(member(context)); }); }
    public static byte[] restoreStilt(String path,byte[] original,Context context) { authorize(path);if (!context.stilt()) throw new IllegalArgumentException("Required replacement palettes cannot be null");return editOwner(original,context,o -> o.nullObject("palette")); }
    public static byte[] enableEntries(String path,byte[] original,Context context) { authorize(path);return editPalette(original,context,p -> GeneralBlockPalettes.seed(p,DimensionPalettes.classBlock())); }
    public static byte[] setBlock(String path,byte[] original,Context context,int index,String value) { authorize(path);return editPalette(original,context,p -> GeneralBlockPalettes.setBlock(p,index,value)); }
    public static byte[] enableWeight(String path,byte[] original,Context context,int index) { authorize(path);return editPalette(original,context,p -> GeneralBlockPalettes.enableWeight(p,index)); }
    public static byte[] setWeight(String path,byte[] original,Context context,int index,String input) { authorize(path);return editPalette(original,context,p -> GeneralBlockPalettes.setWeight(p,index,input)); }
    public static byte[] change(String path,byte[] original,Context context,RiverProfiles.Action action,int index,String value) { authorize(path);return editPalette(original,context,p -> GeneralBlockPalettes.change(p,action,index,value)); }
    public static byte[] enableZoom(String path,byte[] original,Context context) { authorize(path);return editPalette(original,context,p -> p.enable("zoom",RiverPaletteFields.zoomDefault())); }
    public static byte[] setZoom(String path,byte[] original,Context context,String input) { authorize(path);double value=RiverPaletteFields.checkedZoom(input);return editPalette(original,context,p -> p.number("zoom",value)); }
    static Context styleContext(String path) {
        if (path==null) return null;var match=java.util.regex.Pattern.compile("floatingChildBiomes\\.(0|[1-9][0-9]*)\\.([A-Za-z]+)\\.(0|[1-9][0-9]*)\\.(?:stiltSettings\\.palette|edit\\.(0|[1-9][0-9]*)\\.replace)\\.style").matcher(path);
        if (!match.matches() || !FloatingObjectPlacements.LIST_FIELDS.contains(match.group(2))) return null;
        try { return new Context(Integer.parseInt(match.group(1)),match.group(2),Integer.parseInt(match.group(3)),match.group(4)==null ? -1 : Integer.parseInt(match.group(4))); } catch (IllegalArgumentException invalid) { return null; }
    }
    static List<String> readOnlyPaths(LosslessJson placement,Context context) {
        var paths=new ArrayList<String>();try { var palette=object(placement,context);if (palette==null) return List.of();
            for (String field : palette.fields()) if (!RiverMaterialFields.PALETTE_FIELDS.contains(field)) paths.add(field);
            try { var style=NoiseStyleFields.object(palette);if (style!=null) for (String field : style.fields()) if (!NoiseStyleFields.EDITABLE.contains(field)) paths.add("style."+field); } catch (IllegalArgumentException unsupported) { paths.add("style"); }
        } catch (IllegalArgumentException unsupported) { paths.add("palette"); }return List.copyOf(paths);
    }
}
