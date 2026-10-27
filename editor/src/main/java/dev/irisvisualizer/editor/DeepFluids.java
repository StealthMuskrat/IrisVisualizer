package dev.irisvisualizer.editor;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.function.Function;

/** Dimension deep fluids; does not resolve policy inheritance or run placement/generation. */
public final class DeepFluids {
    public static final List<String> LIST_FIELDS = List.of("deepFluids");
    public static final List<String> EDITABLE = List.of("id", "fluidPalette", "density", "spacing", "height", "horizontalRadius", "verticalRadius", "channelWidth", "depth", "headroom", "containedPools", "shortChannels");
    public static final List<String> SCALARS = List.of("density", "containedPools", "shortChannels");
    public static final List<String> GEOMETRY = List.of("spacing", "horizontalRadius", "verticalRadius", "channelWidth", "depth", "headroom");
    private static final Properties DATA=new Properties();
    static {
        try (var input=DeepFluids.class.getResourceAsStream("/deep-fluid-fields.properties")) {
            if (input==null) throw new IOException("Missing pinned deep-fluid system definitions");DATA.load(input);
            if (!"0".equals(DATA.getProperty("list.minimum")) || !"spacingAndClearance".equals(DATA.getProperty("geometry.rule"))) throw new IOException("Unsupported deep-fluid list/geometry policy");
        } catch (IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
    private static void authorize(String path) { if (!DimensionFields.dimension(path)) throw new IllegalArgumentException("Deep fluids are dimension-only"); }
    public static String defaultId() { return DATA.getProperty("id.default"); }
    public static String defaultFluid() { return DATA.getProperty("fluid.default"); }
    public static int maximumSystems() { return Integer.parseInt(DATA.getProperty("list.maximum")); }
    public static String description() { return DATA.getProperty("list.description",""); }
    public static String help(String field) { if (!EDITABLE.contains(field)) throw new IllegalArgumentException("Deep-fluid field is read-only");return DATA.getProperty(field+".description",""); }
    public static String defaultToken(String field) { if (!SCALARS.contains(field) && !GEOMETRY.contains(field)) throw new IllegalArgumentException("Unsupported deep-fluid scalar");String value=DATA.getProperty(field+".default");return booleanField(field) ? value : new BigDecimal(value).stripTrailingZeros().toPlainString(); }
    public static boolean booleanField(String field) { return List.of("containedPools","shortChannels").contains(field); }
    public static String constraints(String field) { defaultToken(field);return (field.equals("density") ? "Finite number" : "Exact integer")+", minimum "+DATA.getProperty(field+".minimum")+", maximum "+DATA.getProperty(field+".maximum"); }
    public static String draftPrefix() { return "hydrology#deepFluids#"; }
    public static String systemPrefix(int index) { if (index<0) throw new IllegalArgumentException("Select a deep-fluid system");return draftPrefix()+index+"."; }
    public static String fluidPrefix(int index) { return systemPrefix(index)+"fluidEntries#"; }
    public static String noisePrefix(int index) { return systemPrefix(index)+"paletteNoise."; }
    public static int styleIndex(String path) {
        if (path==null || !path.matches("hydrology\\.deepFluids\\.(0|[1-9][0-9]*)\\.fluidPalette\\.style")) return -1;
        try { return Integer.parseInt(path.split("\\.")[2]); } catch (NumberFormatException unsupported) { return -1; }
    }
    public static int heightStyleIndex(String path) { return path!=null && path.endsWith(".height.style") ? styleIndex(path.substring(0,path.length()-".height.style".length())+".fluidPalette.style") : -1; }
    static List<LosslessJson> entries(LosslessJson hydro) { return hydro.token("deepFluids")==null ? null : hydro.objectArray("deepFluids"); }
    static byte[] editHydrology(byte[] original,Function<LosslessJson,byte[]> action) { return new LosslessJson(original).objectEdit("hydrology",action,false); }
    static byte[] editSystem(byte[] original,int index,Function<LosslessJson,byte[]> action) { return editHydrology(original,hydro -> hydro.objectArrayEdit("deepFluids",index,action)); }
    static byte[] editPalette(byte[] original,int index,Function<LosslessJson,byte[]> action) { return editSystem(original,index,pool -> pool.objectEdit("fluidPalette",action,false)); }
    private static void id(LosslessJson hydro,int except,String value) {
        if (value==null || !value.matches(DATA.getProperty("id.pattern"))) throw new IllegalArgumentException("Use 1-64 lowercase letters, digits, _ or -; start with a letter or digit");
        if (List.of(DATA.getProperty("id.reserved").split(",")).contains(value.replace('-','_'))) throw new IllegalArgumentException("This ID is a reserved hydrology selector");
        var pools=hydro.objectArray("deepFluids");
        for (int index=0;index<pools.size();index++) if (index!=except) {
            var pool=pools.get(index);String token=pool.token("id");
            if (token==null ? defaultId().equals(value) : token.startsWith("\"") && value.equals(pool.stringToken("id"))) throw new IllegalArgumentException("Choose a unique deep-fluid ID");
        }
        String riversToken=hydro.token("rivers");var rivers=new LosslessJson((riversToken==null ? "{}" : riversToken).getBytes(StandardCharsets.UTF_8));
        if (rivers.token("profiles")==null) {
            if (value.equals(RiverProfiles.defaultId())) throw new IllegalArgumentException("Deep-fluid ID collides with the implicit river profile");
        } else for (var profile : rivers.objectArray("profiles")) {
            String token=profile.token("id");
            if (token==null ? value.equals(RiverProfiles.defaultId()) : token.startsWith("\"") && value.equals(profile.stringToken("id"))) throw new IllegalArgumentException("Deep-fluid ID collides with a river profile");
        }
    }
    private static byte[] pool(String id) {
        byte[] next=new LosslessJson("{}".getBytes(StandardCharsets.UTF_8)).enableStringPath("id",id);
        return new LosslessJson(next).objectEdit("fluidPalette",palette -> HydrologyFluidPalettes.seedEntries(palette,defaultFluid()),true);
    }
    public static byte[] enable(String path,byte[] original) {
        authorize(path);return editHydrology(original,hydro -> {
            if (hydro.token("deepFluids")!=null) throw new IllegalArgumentException("Pool list already exists or is unsupported");return hydro.enable("deepFluids","[]");
        });
    }
    public static byte[] setId(String path,byte[] original,int index,String value) {
        authorize(path);return editHydrology(original,hydro -> { id(hydro,index,value);return hydro.objectArrayEdit("deepFluids",index,p -> p.token("id")==null ? p.enableStringPath("id",value) : p.stringValue("id",value)); });
    }
    public static byte[] change(String path,byte[] original,RiverProfiles.Action action,int index,String newId) {
        authorize(path);if (action==null) throw new IllegalArgumentException("Choose a deep-fluid action");
        return editHydrology(original,hydro -> {
            var pools=hydro.objectArray("deepFluids");
            if (action==RiverProfiles.Action.ADD || action==RiverProfiles.Action.DUPLICATE) {
                if (pools.size()>=maximumSystems()) throw new IllegalArgumentException("At most 64 deep fluids");id(hydro,-1,newId);
            }
            if (action==RiverProfiles.Action.ADD) return hydro.objectArrayInsert("deepFluids",pools.size(),pool(newId));
            if (index<0 || index>=pools.size()) throw new IllegalArgumentException("Select an existing deep-fluid system");
            return switch(action) {
                case DUPLICATE -> { var p=pools.get(index);byte[] next=p.token("id")==null ? p.enableStringPath("id",newId) : p.stringValue("id",newId);yield hydro.objectArrayInsert("deepFluids",index+1,next); }
                case REMOVE -> hydro.objectArrayRemove("deepFluids",index);
                case UP -> { if (index==0) throw new IllegalArgumentException("Already first");yield hydro.objectArrayMove("deepFluids",index,index-1); }
                case DOWN -> { if (index==pools.size()-1) throw new IllegalArgumentException("Already last");yield hydro.objectArrayMove("deepFluids",index,index+1); }
                default -> throw new IllegalArgumentException("Unsupported deep-fluid action");
            };
        });
    }
    private static double number(String field,String input) {
        defaultToken(field);double value;
        try { value=field.equals("density") ? Double.parseDouble(input) : new BigDecimal(input).intValueExact(); }
        catch (NumberFormatException | ArithmeticException | NullPointerException invalid) { throw new IllegalArgumentException("Enter a valid "+(field.equals("density") ? "finite number" : "exact integer"),invalid); }
        if (!Double.isFinite(value) || value<Double.parseDouble(DATA.getProperty(field+".minimum")) || value>Double.parseDouble(DATA.getProperty(field+".maximum"))) throw new IllegalArgumentException("Pool "+field+" is outside its pinned bounds");return value;
    }
    private static int effectiveInt(LosslessJson object,String field) {
        String token=object.token(field);if (token!=null && !LosslessJson.numericToken(token)) throw new IllegalArgumentException("Stored "+field+" is not numeric");return (int)number(field,token==null ? defaultToken(field) : token);
    }
    private static void geometry(LosslessJson object) {
        int spacing=effectiveInt(object,"spacing"),horizontal=effectiveInt(object,"horizontalRadius"),width=effectiveInt(object,"channelWidth"),vertical=effectiveInt(object,"verticalRadius"),depth=effectiveInt(object,"depth"),headroom=effectiveInt(object,"headroom");
        if (spacing<(horizontal+width)*2) throw new IllegalArgumentException("Spacing must be at least twice horizontalRadius + channelWidth (required "+((horizontal+width)*2)+")");
        if (depth+headroom>=vertical*2+1) throw new IllegalArgumentException("Depth + headroom must be less than twice verticalRadius + 1");
    }
    public static byte[] enableScalar(String path,byte[] original,int index,String field) {
        authorize(path);defaultToken(field);return editSystem(original,index,p -> {
            byte[] next=p.enable(field,defaultToken(field));if (GEOMETRY.contains(field)) geometry(new LosslessJson(next));return next;
        });
    }
    public static byte[] setScalar(String path,byte[] original,int index,String field,String input) {
        authorize(path);if (booleanField(field)) throw new IllegalArgumentException("Use the deep-fluid toggle");double value=number(field,input);return editSystem(original,index,p -> {
            byte[] next=p.number(field,value);if (GEOMETRY.contains(field)) geometry(new LosslessJson(next));return next;
        });
    }
    public static byte[] setBoolean(String path,byte[] original,int index,String field,boolean value) {
        authorize(path);if (!booleanField(field)) throw new IllegalArgumentException("This deep-fluid field is numeric");return editSystem(original,index,p -> p.token(field)==null ? p.enable(field,Boolean.toString(value)) : p.bool(field,value));
    }
    public static byte[] setGeometry(String path,byte[] original,int index,java.util.Map<String,String> values) {
        authorize(path);if (values==null || !values.keySet().equals(new java.util.HashSet<>(GEOMETRY))) throw new IllegalArgumentException("Enter all six geometry values");
        var checked=new java.util.LinkedHashMap<String,Integer>();for (String field : GEOMETRY) checked.put(field,(int)number(field,values.get(field)));
        return editSystem(original,index,p -> {
            byte[] next=p.sourceBytes();for (var item : checked.entrySet()) { var object=new LosslessJson(next);next=object.token(item.getKey())==null ? object.enable(item.getKey(),Integer.toString(item.getValue())) : object.number(item.getKey(),item.getValue()); }
            geometry(new LosslessJson(next));return next;
        });
    }
    public static String heightDefault(String member,boolean contextual) { if (!List.of("min","max").contains(member)) throw new IllegalArgumentException("Unsupported height bound");return DATA.getProperty((contextual ? "height.context." : "height.class.")+member); }
    public static String heightCreationLabel() { return heightDefault("min",true)+" to "+heightDefault("max",true)+", "+DATA.getProperty("height.context.style")+", style zoom "+DATA.getProperty("height.context.zoom"); }
    public static String heightClassStyle() { return DATA.getProperty("height.class.style"); }
    static LosslessJson height(LosslessJson object) { String token=object.token("height");return token==null ? null : new LosslessJson(token.getBytes(StandardCharsets.UTF_8)); }
    static byte[] editHeight(byte[] original,int index,Function<LosslessJson,byte[]> action) { return editSystem(original,index,p -> p.objectEdit("height",action,false)); }
    public static byte[] createHeight(String path,byte[] original,int index) {
        authorize(path);return editSystem(original,index,p -> {
            if (p.token("height")!=null) throw new IllegalArgumentException("Height is already present or unsupported");
            return p.objectEdit("height",range -> {
                byte[] next=range.enable("min",heightDefault("min",true));next=new LosslessJson(next).enable("max",heightDefault("max",true));
                next=new LosslessJson(next).enableStringPath("style.style",DATA.getProperty("height.context.style"));return new LosslessJson(next).enablePath("style.zoom",DATA.getProperty("height.context.zoom"));
            },true);
        });
    }
    private static double heightBound(String input) {
        double value;try { value=Double.parseDouble(input); } catch (NumberFormatException | NullPointerException invalid) { throw new IllegalArgumentException("Enter a finite height",invalid); }
        if (!Double.isFinite(value) || value<Double.parseDouble(DATA.getProperty("height.minimum")) || value>Double.parseDouble(DATA.getProperty("height.maximum"))) throw new IllegalArgumentException("Height must be between -2048 and 2048");return value;
    }
    private static byte[] bound(LosslessJson range,String field,double value) { return range.token(field)==null ? range.enable(field,BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()) : range.number(field,value); }
    public static byte[] setHeight(String path,byte[] original,int index,String minimum,String maximum) {
        authorize(path);double min=heightBound(minimum),max=heightBound(maximum);if (min>max) throw new IllegalArgumentException("Height minimum must not exceed maximum");
        return editHeight(original,index,r -> bound(new LosslessJson(bound(r,"min",min)),"max",max));
    }
    public static byte[] createPalette(String path,byte[] original,int index) { authorize(path);return editSystem(original,index,p -> { if (p.token("fluidPalette")!=null) throw new IllegalArgumentException("Fluid palette already exists or is unsupported");return p.objectEdit("fluidPalette",palette -> HydrologyFluidPalettes.seedEntries(palette,defaultFluid()),true); }); }
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
                var pool=pools.get(index);String prefix="deepFluids["+index+"]";
                for (String field : pool.fields()) if (!EDITABLE.contains(field)) paths.add(prefix+"."+field);
                try {
                    var height=height(pool);
                    if (height!=null) {
                        for (String field : height.fields()) if (!List.of("min","max","style").contains(field)) paths.add(prefix+".height."+field);
                        try { var style=NoiseStyleFields.object(height,NoiseStyleFields.Target.deepHeight(index));if (style!=null) for (String field : style.fields()) if (NoiseStyleFields.readOnly(style,field)) paths.add(prefix+".height.style."+field); }
                        catch (IllegalArgumentException unsupported) { paths.add(prefix+".height.style"); }
                    }
                } catch (IllegalArgumentException unsupported) { paths.add(prefix+".height"); }
                try {
                    var palette=RiverProfiles.palette(pool);if (palette==null) continue;
                    for (String field : palette.fields()) if (!RiverMaterialFields.PALETTE_FIELDS.contains(field)) paths.add(prefix+".fluidPalette."+field);
                    try { var style=NoiseStyleFields.object(palette,NoiseStyleFields.Target.deepPalette(index));if (style!=null) for (String field : style.fields()) if (NoiseStyleFields.readOnly(style,field)) paths.add(prefix+".fluidPalette.style."+field); }
                    catch (IllegalArgumentException unsupported) { paths.add(prefix+".fluidPalette.style"); }
                    try { var fluids=RiverProfiles.fluids(palette);if (fluids!=null) for (int i=0;i<fluids.size();i++) for (String field : fluids.get(i).fields()) if (!RiverMaterialFields.ENTRY_FIELDS.contains(field)) paths.add(prefix+".fluidPalette.palette["+i+"]."+field); }
                    catch (IllegalArgumentException unsupported) { paths.add(prefix+".fluidPalette.palette"); }
                } catch (IllegalArgumentException unsupported) { paths.add(prefix+".fluidPalette"); }
            }
        } catch (IllegalArgumentException unsupported) { paths.add("deepFluids"); }
        return List.copyOf(paths);
    }
}
