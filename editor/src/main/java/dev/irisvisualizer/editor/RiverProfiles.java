package dev.irisvisualizer.editor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.function.Function;

/** Dimension-owned profiles; literal policy references are never rewritten by these operations. */
public final class RiverProfiles {
    public static final List<String> LIST_FIELDS = List.of("profiles");
    public static final List<String> EDITABLE = List.of("id", "fluidPalette");
    private static final Properties DATA=new Properties();
    public enum Action { ADD, DUPLICATE, REMOVE, UP, DOWN }
    static {
        try (var input=RiverProfiles.class.getResourceAsStream("/river-profile-fields.properties")) {
            if (input==null) throw new IOException("Missing pinned river profile definitions");
            DATA.load(input);
            if (!"invertedDefinitelyNotFluid".equals(DATA.getProperty("fluid.rule"))) throw new IOException("Unsupported native fluid rule");
        } catch (IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
    private static void authorize(String path) { if (!DimensionFields.dimension(path)) throw new IllegalArgumentException("River profiles are dimension-only"); }
    public static String defaultId() { return DATA.getProperty("id.default"); }
    public static String defaultFluid() { return DATA.getProperty("fluid.default"); }
    public static int maximumProfiles() { return Integer.parseInt(DATA.getProperty("profiles.maximum")); }
    public static int minimumProfiles() { return Integer.parseInt(DATA.getProperty("profiles.minimum")); }
    public static int maximumFluids() { return Integer.parseInt(DATA.getProperty("fluid.maximumEntries")); }
    public static String description() { return DATA.getProperty("profiles.description",""); }
    public static String draftPrefix() { return "hydrology#rivers.profiles#"; }
    public static String profilePrefix(int index) { if (index<0) throw new IllegalArgumentException("Select a profile"); return draftPrefix()+index+"."; }
    public static String fluidPrefix(int index) { return profilePrefix(index)+"fluidEntries#"; }
    public static String noisePrefix(int index) { return profilePrefix(index)+"paletteNoise."; }
    public static int styleIndex(String path) {
        if (path==null || !path.matches("hydrology\\.rivers\\.profiles\\.(0|[1-9][0-9]*)\\.fluidPalette\\.style")) return -1;
        try { return Integer.parseInt(path.split("\\.")[3]); } catch (NumberFormatException unsupported) { return -1; }
    }
    static List<LosslessJson> entries(LosslessJson rivers) { return rivers.token("profiles")==null ? null : rivers.objectArray("profiles"); }
    static byte[] editProfile(byte[] original,int index,Function<LosslessJson,byte[]> action) { return HydrologyFields.editRivers(original,rivers -> rivers.objectArrayEdit("profiles",index,action)); }
    static byte[] editPalette(byte[] original,int index,Function<LosslessJson,byte[]> action) { return editProfile(original,index,profile -> profile.objectEdit("fluidPalette",action,false)); }
    static LosslessJson palette(LosslessJson profile) {
        String token=profile.token("fluidPalette");return token==null ? null : new LosslessJson(token.getBytes(StandardCharsets.UTF_8));
    }
    static List<LosslessJson> fluids(LosslessJson palette) { return palette.token("palette")==null ? null : palette.objectArray("palette"); }
    private static void id(String id) { if (id==null || !id.matches(DATA.getProperty("id.pattern"))) throw new IllegalArgumentException("Use 1-64 lowercase letters, digits, _ or -; start with a letter or digit"); }
    private static void unique(List<LosslessJson> profiles,int except,String value) {
        for (int i=0;i<profiles.size();i++) if (i!=except) {
            var other=profiles.get(i);String token=other.token("id");
            // Missing IDs deserialize to the class default even though native pack validation requires them.
            if (token==null ? defaultId().equals(value) : token.startsWith("\"") && value.equals(other.stringToken("id"))) throw new IllegalArgumentException("Choose a unique profile ID");
        }
    }
    private static byte[] blockEntry(String block) { return new LosslessJson("{}".getBytes(StandardCharsets.UTF_8)).enableStringPath("block",block); }
    private static byte[] waterEntries(LosslessJson palette) {
        if (palette.token("palette")!=null) throw new IllegalArgumentException("Fluid entries already exist or are unsupported");
        return new LosslessJson(palette.enable("palette","[]")).objectArrayInsert("palette",0,blockEntry(defaultFluid()));
    }
    private static byte[] profile(String id) {
        byte[] bytes=new LosslessJson("{}".getBytes(StandardCharsets.UTF_8)).enableStringPath("id",id);
        return new LosslessJson(bytes).objectEdit("fluidPalette",RiverProfiles::waterEntries,true);
    }
    public static byte[] enable(String path,byte[] original) {
        authorize(path);return HydrologyFields.editRivers(original,rivers -> {
            if (rivers.token("profiles")!=null) throw new IllegalArgumentException("Profiles already exist or have an unsupported type");
            return new LosslessJson(rivers.enable("profiles","[]")).objectArrayInsert("profiles",0,profile(defaultId()));
        });
    }
    public static byte[] setId(String path,byte[] original,int index,String value) {
        authorize(path);id(value);return HydrologyFields.editRivers(original,rivers -> {
            var profiles=rivers.objectArray("profiles");unique(profiles,index,value);
            return rivers.objectArrayEdit("profiles",index,p -> p.token("id")==null ? p.enableStringPath("id",value) : p.stringValue("id",value));
        });
    }
    public static byte[] change(String path,byte[] original,Action action,int index,String newId) {
        authorize(path);if (action==null) throw new IllegalArgumentException("Choose a profile action");
        if (action==Action.ADD || action==Action.DUPLICATE) id(newId);
        return HydrologyFields.editRivers(original,rivers -> {
            var profiles=rivers.objectArray("profiles");
            if (action==Action.ADD || action==Action.DUPLICATE) {
                if (profiles.size()>=maximumProfiles()) throw new IllegalArgumentException("At most "+maximumProfiles()+" river profiles");
                unique(profiles,-1,newId);
            }
            if (action==Action.ADD) return rivers.objectArrayInsert("profiles",profiles.size(),profile(newId));
            if (index<0 || index>=profiles.size()) throw new IllegalArgumentException("Select an existing profile");
            return switch(action) {
                case DUPLICATE -> {
                    var source=profiles.get(index);byte[] copy=source.token("id")==null ? source.enableStringPath("id",newId) : source.stringValue("id",newId);
                    yield rivers.objectArrayInsert("profiles",index+1,copy);
                }
                case REMOVE -> { if (profiles.size()<=minimumProfiles()) throw new IllegalArgumentException("Keep at least one explicit profile");yield rivers.objectArrayRemove("profiles",index); }
                case UP -> { if (index==0) throw new IllegalArgumentException("Already first");yield rivers.objectArrayMove("profiles",index,index-1); }
                case DOWN -> { if (index==profiles.size()-1) throw new IllegalArgumentException("Already last");yield rivers.objectArrayMove("profiles",index,index+1); }
                default -> throw new IllegalArgumentException("Unsupported profile action");
            };
        });
    }
    public static byte[] createPalette(String path,byte[] original,int index) {
        authorize(path);return editProfile(original,index,p -> {
            if (p.token("fluidPalette")!=null) throw new IllegalArgumentException("Fluid palette already exists or is unsupported");
            return p.objectEdit("fluidPalette",RiverProfiles::waterEntries,true);
        });
    }
    public static byte[] initializeWater(String path,byte[] original,int index) { authorize(path);return editPalette(original,index,RiverProfiles::waterEntries); }
    public static byte[] setFluid(String path,byte[] original,int profile,int index,String value) { authorize(path);return editPalette(original,profile,p -> HydrologyFluidPalettes.setFluid(p,index,value)); }
    public static byte[] enableWeight(String path,byte[] original,int profile,int index) { authorize(path);return editPalette(original,profile,p -> HydrologyFluidPalettes.enableWeight(p,index)); }
    public static byte[] setWeight(String path,byte[] original,int profile,int index,String input) { authorize(path);return editPalette(original,profile,p -> HydrologyFluidPalettes.setWeight(p,index,input)); }
    public static byte[] changeFluid(String path,byte[] original,int profile,Action action,int index,String value) { authorize(path);return editPalette(original,profile,p -> HydrologyFluidPalettes.change(p,action,index,value)); }
    public static byte[] enableZoom(String path,byte[] original,int index) { authorize(path);return editPalette(original,index,p -> p.enable("zoom",RiverPaletteFields.zoomDefault())); }
    public static byte[] setZoom(String path,byte[] original,int index,String input) { authorize(path);double value=RiverPaletteFields.checkedZoom(input);return editPalette(original,index,p -> p.number("zoom",value)); }
    static List<String> readOnlyPaths(LosslessJson rivers) {
        var paths=new ArrayList<String>();
        try {
            var profiles=entries(rivers);if (profiles==null) return List.of();
            for (int index=0;index<profiles.size();index++) {
                var profile=profiles.get(index);String prefix="profiles["+index+"]";
                for (String field : profile.fields()) if (!EDITABLE.contains(field)) paths.add(prefix+"."+field);
                try {
                    var palette=palette(profile);if (palette==null) continue;
                    for (String field : palette.fields()) if (!RiverMaterialFields.PALETTE_FIELDS.contains(field)) paths.add(prefix+".fluidPalette."+field);
                    try {
                        var style=NoiseStyleFields.object(palette,NoiseStyleFields.Target.profilePalette(index));
                        if (style!=null) for (String field : StyleFractures.readOnlyPaths(style)) paths.add(prefix+".fluidPalette.style."+field);
                    } catch (IllegalArgumentException unsupported) { paths.add(prefix+".fluidPalette.style"); }
                    try {
                        var fluids=fluids(palette);
                        if (fluids!=null) for (int i=0;i<fluids.size();i++) for (String field : fluids.get(i).fields()) if (!RiverMaterialFields.ENTRY_FIELDS.contains(field)) paths.add(prefix+".fluidPalette.palette["+i+"]."+field);
                    } catch (IllegalArgumentException unsupported) { paths.add(prefix+".fluidPalette.palette"); }
                } catch (IllegalArgumentException unsupported) { paths.add(prefix+".fluidPalette"); }
            }
        } catch (IllegalArgumentException unsupported) { paths.add("profiles"); }
        return List.copyOf(paths);
    }
}
