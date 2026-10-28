package dev.irisvisualizer.editor;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.function.Function;

/** Dimension surface pools; does not resolve policy inheritance or run placement/generation. */
public final class SurfacePools {
    public static final List<String> LIST_FIELDS = List.of("surfacePools");
    public static final List<String> EDITABLE = List.of("id", "fluidPalette", "density", "spacing", "minimumRadius", "maximumRadius", "depth", "biome");
    public static final List<String> SCALARS = List.of("density", "spacing", "depth");
    private static final Properties DATA=new Properties();
    static {
        try (var input=SurfacePools.class.getResourceAsStream("/surface-pool-fields.properties")) {
            if (input==null) throw new IOException("Missing pinned surface pool definitions");DATA.load(input);
            if (!"0".equals(DATA.getProperty("list.minimum")) || !"minimumAtMostMaximum".equals(DATA.getProperty("radius.rule"))) throw new IOException("Unsupported pool list/radius policy");
        } catch (IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
    private static void authorize(String path) { if (!DimensionFields.dimension(path)) throw new IllegalArgumentException("Surface pools are dimension-only"); }
    public static String defaultId() { return DATA.getProperty("id.default"); }
    public static String defaultFluid() { return DATA.getProperty("fluid.default"); }
    public static int maximumPools() { return Integer.parseInt(DATA.getProperty("list.maximum")); }
    public static String description() { return DATA.getProperty("list.description",""); }
    public static String help(String field) { if (!EDITABLE.contains(field)) throw new IllegalArgumentException("Pool field is read-only");return DATA.getProperty(field+".description",""); }
    public static String defaultToken(String field) { if (!List.of("density","spacing","depth","minimumRadius","maximumRadius").contains(field)) throw new IllegalArgumentException("Unsupported pool scalar");return new BigDecimal(DATA.getProperty(field+".default")).stripTrailingZeros().toPlainString(); }
    public static String constraints(String field) { defaultToken(field);return (field.equals("density") ? "Finite number" : "Exact integer")+", minimum "+DATA.getProperty(field+".minimum")+", maximum "+DATA.getProperty(field+".maximum"); }
    public static String draftPrefix() { return "hydrology#surfacePools#"; }
    public static String poolPrefix(int index) { if (index<0) throw new IllegalArgumentException("Select a pool");return draftPrefix()+index+"."; }
    public static String fluidPrefix(int index) { return poolPrefix(index)+"fluidEntries#"; }
    public static String noisePrefix(int index) { return poolPrefix(index)+"paletteNoise."; }
    public static int styleIndex(String path) {
        if (path==null || !path.matches("hydrology\\.surfacePools\\.(0|[1-9][0-9]*)\\.fluidPalette\\.style")) return -1;
        try { return Integer.parseInt(path.split("\\.")[2]); } catch (NumberFormatException unsupported) { return -1; }
    }
    static List<LosslessJson> entries(LosslessJson hydro) { return hydro.token("surfacePools")==null ? null : hydro.objectArray("surfacePools"); }
    static byte[] editHydrology(byte[] original,Function<LosslessJson,byte[]> action) { return new LosslessJson(original).objectEdit("hydrology",action,false); }
    static byte[] editPool(byte[] original,int index,Function<LosslessJson,byte[]> action) { return editHydrology(original,hydro -> hydro.objectArrayEdit("surfacePools",index,action)); }
    static byte[] editPalette(byte[] original,int index,Function<LosslessJson,byte[]> action) { return editPool(original,index,pool -> pool.objectEdit("fluidPalette",action,false)); }
    private static void id(LosslessJson hydro,int except,String value) {
        if (value==null || !value.matches(DATA.getProperty("id.pattern"))) throw new IllegalArgumentException("Use 1-64 lowercase letters, digits, _ or -; start with a letter or digit");
        if (List.of(DATA.getProperty("id.reserved").split(",")).contains(value.replace('-','_'))) throw new IllegalArgumentException("This ID is a reserved hydrology selector");
        var pools=hydro.objectArray("surfacePools");
        for (int index=0;index<pools.size();index++) if (index!=except) {
            var pool=pools.get(index);String token=pool.token("id");
            if (token==null ? defaultId().equals(value) : token.startsWith("\"") && value.equals(pool.stringToken("id"))) throw new IllegalArgumentException("Choose a unique pool ID");
        }
        String riversToken=hydro.token("rivers");var rivers=new LosslessJson((riversToken==null ? "{}" : riversToken).getBytes(StandardCharsets.UTF_8));
        if (rivers.token("profiles")==null) {
            if (value.equals(RiverProfiles.defaultId())) throw new IllegalArgumentException("Pool ID collides with the implicit river profile");
        } else for (var profile : rivers.objectArray("profiles")) {
            String token=profile.token("id");
            if (token==null ? value.equals(RiverProfiles.defaultId()) : token.startsWith("\"") && value.equals(profile.stringToken("id"))) throw new IllegalArgumentException("Pool ID collides with a river profile");
        }
    }
    private static byte[] pool(String id) {
        byte[] next=new LosslessJson("{}".getBytes(StandardCharsets.UTF_8)).enableStringPath("id",id);
        return new LosslessJson(next).objectEdit("fluidPalette",palette -> HydrologyFluidPalettes.seedEntries(palette,defaultFluid()),true);
    }
    public static byte[] enable(String path,byte[] original) {
        authorize(path);return editHydrology(original,hydro -> {
            if (hydro.token("surfacePools")!=null) throw new IllegalArgumentException("Pool list already exists or is unsupported");return hydro.enable("surfacePools","[]");
        });
    }
    public static byte[] setId(String path,byte[] original,int index,String value) {
        authorize(path);return editHydrology(original,hydro -> { id(hydro,index,value);return hydro.objectArrayEdit("surfacePools",index,p -> p.token("id")==null ? p.enableStringPath("id",value) : p.stringValue("id",value)); });
    }
    public static byte[] change(String path,byte[] original,RiverProfiles.Action action,int index,String newId) {
        authorize(path);if (action==null) throw new IllegalArgumentException("Choose a pool action");
        return editHydrology(original,hydro -> {
            var pools=hydro.objectArray("surfacePools");
            if (action==RiverProfiles.Action.ADD || action==RiverProfiles.Action.DUPLICATE) {
                if (pools.size()>=maximumPools()) throw new IllegalArgumentException("At most 64 surface pools");id(hydro,-1,newId);
            }
            if (action==RiverProfiles.Action.ADD) return hydro.objectArrayInsert("surfacePools",pools.size(),pool(newId));
            if (index<0 || index>=pools.size()) throw new IllegalArgumentException("Select an existing pool");
            return switch(action) {
                case DUPLICATE -> { var p=pools.get(index);byte[] next=p.token("id")==null ? p.enableStringPath("id",newId) : p.stringValue("id",newId);yield hydro.objectArrayInsert("surfacePools",index+1,next); }
                case REMOVE -> hydro.objectArrayRemove("surfacePools",index);
                case UP -> { if (index==0) throw new IllegalArgumentException("Already first");yield hydro.objectArrayMove("surfacePools",index,index-1); }
                case DOWN -> { if (index==pools.size()-1) throw new IllegalArgumentException("Already last");yield hydro.objectArrayMove("surfacePools",index,index+1); }
                default -> throw new IllegalArgumentException("Unsupported pool action");
            };
        });
    }
    private static double number(String field,String input) {
        defaultToken(field);double value;
        try { value=field.equals("density") ? Double.parseDouble(input) : new BigDecimal(input).intValueExact(); }
        catch (NumberFormatException | ArithmeticException | NullPointerException invalid) { throw new IllegalArgumentException("Enter a valid "+(field.equals("density") ? "finite number" : "exact integer"),invalid); }
        if (!Double.isFinite(value) || value<Double.parseDouble(DATA.getProperty(field+".minimum")) || value>Double.parseDouble(DATA.getProperty(field+".maximum"))) throw new IllegalArgumentException("Pool "+field+" is outside its pinned bounds");return value;
    }
    private static int radius(LosslessJson pool,String field) {
        String token=pool.token(field);
        if (token!=null && !LosslessJson.numericToken(token)) throw new IllegalArgumentException("Stored "+field+" is not numeric");return (int)number(field,token==null ? defaultToken(field) : token);
    }
    private static void ordered(int min,int max) { if (min>max) throw new IllegalArgumentException("Pool minimumRadius must not exceed maximumRadius"); }
    public static byte[] enableScalar(String path,byte[] original,int index,String field) {
        authorize(path);defaultToken(field);return editPool(original,index,p -> {
            if (field.equals("minimumRadius")) ordered((int)number(field,defaultToken(field)),radius(p,"maximumRadius"));
            if (field.equals("maximumRadius")) ordered(radius(p,"minimumRadius"),(int)number(field,defaultToken(field)));
            return p.enable(field,defaultToken(field));
        });
    }
    public static byte[] setScalar(String path,byte[] original,int index,String field,String input) {
        authorize(path);double value=number(field,input);return editPool(original,index,p -> {
            if (field.equals("minimumRadius")) ordered((int)value,radius(p,"maximumRadius"));
            if (field.equals("maximumRadius")) ordered(radius(p,"minimumRadius"),(int)value);
            return p.number(field,value);
        });
    }
    private static byte[] writeRadius(LosslessJson pool,String field,int value) { return pool.token(field)==null ? pool.enable(field,Integer.toString(value)) : pool.number(field,value); }
    public static byte[] setRadii(String path,byte[] original,int index,String minimum,String maximum) {
        authorize(path);int min=(int)number("minimumRadius",minimum),max=(int)number("maximumRadius",maximum);ordered(min,max);
        return editPool(original,index,p -> writeRadius(new LosslessJson(writeRadius(p,"minimumRadius",min)),"maximumRadius",max));
    }
    public static byte[] setBiome(String path,byte[] original,int index,String value,PackWorkspace workspace) {
        authorize(path);if (value==null || !value.isEmpty() && !ResourceTypes.referenceKeys(workspace,"biomes").contains(value)) throw new IllegalArgumentException("Choose an exact in-pack biome or keep the surrounding biome");
        return editPool(original,index,p -> p.token("biome")==null ? p.enableStringPath("biome",value) : p.stringValue("biome",value));
    }
    public static byte[] createPalette(String path,byte[] original,int index) { authorize(path);return editPool(original,index,p -> { if (p.token("fluidPalette")!=null) throw new IllegalArgumentException("Fluid palette already exists or is unsupported");return p.objectEdit("fluidPalette",palette -> HydrologyFluidPalettes.seedEntries(palette,defaultFluid()),true); }); }
    public static byte[] initializeLava(String path,byte[] original,int index) { authorize(path);return editPalette(original,index,p -> HydrologyFluidPalettes.seedEntries(p,defaultFluid())); }
    public static byte[] setFluid(String path,byte[] original,int pool,int index,String value) { authorize(path);return editPalette(original,pool,p -> HydrologyFluidPalettes.setFluid(p,index,value)); }
    public static byte[] enableWeight(String path,byte[] original,int pool,int index) { authorize(path);return editPalette(original,pool,p -> HydrologyFluidPalettes.enableWeight(p,index)); }
    public static byte[] setWeight(String path,byte[] original,int pool,int index,String input) { authorize(path);return editPalette(original,pool,p -> HydrologyFluidPalettes.setWeight(p,index,input)); }
    public static byte[] changeFluid(String path,byte[] original,int pool,RiverProfiles.Action action,int index,String value) { authorize(path);return editPalette(original,pool,p -> HydrologyFluidPalettes.change(p,action,index,value)); }
    public static byte[] enableZoom(String path,byte[] original,int index) { authorize(path);return editPalette(original,index,p -> p.enable("zoom",RiverPaletteFields.zoomDefault())); }
    public static byte[] setZoom(String path,byte[] original,int index,String input) { authorize(path);double value=RiverPaletteFields.checkedZoom(input);return editPalette(original,index,p -> p.number("zoom",value)); }
    static List<String> readOnlyPaths(LosslessJson hydro) {
        var paths=new ArrayList<String>();
        try {
            var pools=entries(hydro);if (pools==null) return List.of();
            for (int index=0;index<pools.size();index++) {
                var pool=pools.get(index);String prefix="surfacePools["+index+"]";
                for (String field : pool.fields()) if (!EDITABLE.contains(field)) paths.add(prefix+"."+field);
                try {
                    var palette=RiverProfiles.palette(pool);if (palette==null) continue;
                    for (String field : palette.fields()) if (!RiverMaterialFields.PALETTE_FIELDS.contains(field)) paths.add(prefix+".fluidPalette."+field);
                    try { var style=NoiseStyleFields.object(palette,NoiseStyleFields.Target.poolPalette(index));if (style!=null) for (String field : StyleFractures.readOnlyPaths(style)) paths.add(prefix+".fluidPalette.style."+field); }
                    catch (IllegalArgumentException unsupported) { paths.add(prefix+".fluidPalette.style"); }
                    try { PaletteBlockData.report(paths,RiverProfiles.fluids(palette),prefix+".fluidPalette.palette"); }
                    catch (IllegalArgumentException unsupported) { paths.add(prefix+".fluidPalette.palette"); }
                } catch (IllegalArgumentException unsupported) { paths.add(prefix+".fluidPalette"); }
            }
        } catch (IllegalArgumentException unsupported) { paths.add("surfacePools"); }
        return List.copyOf(paths);
    }
}
