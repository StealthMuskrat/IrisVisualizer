package dev.irisvisualizer.editor;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/** Common bounded palette operations; owners authorize the resource path and existing parents. */
final class HydrologyFluidPalettes {
    record Target(boolean surfacePool,int index,boolean deepFluid) {
        Target(boolean surfacePool,int index) { this(surfacePool,index,false); }
        static Target deep(int index) { return new Target(false,index,true); }
        Target { if (surfacePool && deepFluid) throw new IllegalArgumentException("Choose one fluid owner");if (index<0) throw new IllegalArgumentException("Select an existing fluid-palette owner"); }
        String defaultFluid() { return deepFluid ? DeepFluids.defaultFluid() : surfacePool ? SurfacePools.defaultFluid() : RiverProfiles.defaultFluid(); }
        String fluidPrefix() { return deepFluid ? DeepFluids.fluidPrefix(index) : surfacePool ? SurfacePools.fluidPrefix(index) : RiverProfiles.fluidPrefix(index); }
        String noisePrefix() { return deepFluid ? DeepFluids.noisePrefix(index) : surfacePool ? SurfacePools.noisePrefix(index) : RiverProfiles.noisePrefix(index); }
        NoiseStyleFields.Target styleTarget() { return deepFluid ? NoiseStyleFields.Target.deepPalette(index) : surfacePool ? NoiseStyleFields.Target.poolPalette(index) : NoiseStyleFields.Target.profilePalette(index); }
        byte[] createPalette(String path,byte[] original) { return deepFluid ? DeepFluids.createPalette(path,original,index) : surfacePool ? SurfacePools.createPalette(path,original,index) : RiverProfiles.createPalette(path,original,index); }
        byte[] initialize(String path,byte[] original) { return deepFluid ? DeepFluids.initializeLava(path,original,index) : surfacePool ? SurfacePools.initializeLava(path,original,index) : RiverProfiles.initializeWater(path,original,index); }
        byte[] setFluid(String path,byte[] original,int entry,String value) { return deepFluid ? DeepFluids.setFluid(path,original,index,entry,value) : surfacePool ? SurfacePools.setFluid(path,original,index,entry,value) : RiverProfiles.setFluid(path,original,index,entry,value); }
        byte[] enableWeight(String path,byte[] original,int entry) { return deepFluid ? DeepFluids.enableWeight(path,original,index,entry) : surfacePool ? SurfacePools.enableWeight(path,original,index,entry) : RiverProfiles.enableWeight(path,original,index,entry); }
        byte[] setWeight(String path,byte[] original,int entry,String value) { return deepFluid ? DeepFluids.setWeight(path,original,index,entry,value) : surfacePool ? SurfacePools.setWeight(path,original,index,entry,value) : RiverProfiles.setWeight(path,original,index,entry,value); }
        byte[] change(String path,byte[] original,RiverProfiles.Action action,int entry,String value) { return deepFluid ? DeepFluids.changeFluid(path,original,index,action,entry,value) : surfacePool ? SurfacePools.changeFluid(path,original,index,action,entry,value) : RiverProfiles.changeFluid(path,original,index,action,entry,value); }
        byte[] enableZoom(String path,byte[] original) { return deepFluid ? DeepFluids.enableZoom(path,original,index) : surfacePool ? SurfacePools.enableZoom(path,original,index) : RiverProfiles.enableZoom(path,original,index); }
        byte[] setZoom(String path,byte[] original,String value) { return deepFluid ? DeepFluids.setZoom(path,original,index,value) : surfacePool ? SurfacePools.setZoom(path,original,index,value) : RiverProfiles.setZoom(path,original,index,value); }
    }
    private static final Properties DATA=new Properties();
    static {
        try (var input=HydrologyFluidPalettes.class.getResourceAsStream("/river-profile-fields.properties")) {
            if (input==null) throw new IOException("Missing native fluid palette rules");DATA.load(input);
            if (!"invertedDefinitelyNotFluid".equals(DATA.getProperty("fluid.rule"))) throw new IOException("Unsupported fluid rule");
        } catch (IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
    static int maximumFluids() { return Integer.parseInt(DATA.getProperty("fluid.maximumEntries")); }
    private static byte[] blockEntry(String value) { return new LosslessJson("{}".getBytes(StandardCharsets.UTF_8)).enableStringPath("block",value); }
    static byte[] seedEntries(LosslessJson palette,String fluid) {
        fluid(fluid);
        if (palette.token("palette")!=null) throw new IllegalArgumentException("Fluid entries already exist or are unsupported");
        return new LosslessJson(palette.enable("palette","[]")).objectArrayInsert("palette",0,blockEntry(fluid));
    }
    static void fluid(String value) {
        if (value==null || value.isBlank()) throw new IllegalArgumentException("Enter a nonblank fluid block");
        String normalized=value.trim().toLowerCase(Locale.ROOT);int state=normalized.indexOf('[');if (state>=0) normalized=normalized.substring(0,state);
        int colon=normalized.indexOf(':');String namespace=colon<0 ? "minecraft" : normalized.substring(0,colon),key=colon<0 ? normalized : normalized.substring(colon+1);
        if ("minecraft".equals(namespace) && !List.of(DATA.getProperty("fluid.minecraftChoices").split(",")).contains(key)) throw new IllegalArgumentException("Minecraft fluids are water, lava, or bubble_column; custom blocks require runtime validation");
    }
    static byte[] setFluid(LosslessJson palette,int index,String value) {
        fluid(value);return palette.objectArrayEdit("palette",index,e -> e.token("block")==null ? e.enableStringPath("block",value) : e.stringValue("block",value));
    }
    static byte[] enableWeight(LosslessJson palette,int index) { return palette.objectArrayEdit("palette",index,e -> e.enable("weight",DATA.getProperty("weight.default"))); }
    static byte[] setWeight(LosslessJson palette,int index,String input) {
        int value;
        try { value=new BigDecimal(input).intValueExact(); } catch (NumberFormatException | ArithmeticException | NullPointerException invalid) { throw new IllegalArgumentException("Enter an exact integer weight",invalid); }
        if (value<Integer.parseInt(DATA.getProperty("weight.minimum")) || value>Integer.parseInt(DATA.getProperty("weight.maximum"))) throw new IllegalArgumentException("Fluid weight must be 1-64");
        return palette.objectArrayEdit("palette",index,e -> e.number("weight",value));
    }
    static byte[] change(LosslessJson p,RiverProfiles.Action action,int index,String value) {
        if (action==null) throw new IllegalArgumentException("Choose a fluid action");if (action==RiverProfiles.Action.ADD) fluid(value);
        var entries=p.objectArray("palette");
        if ((action==RiverProfiles.Action.ADD || action==RiverProfiles.Action.DUPLICATE) && entries.size()>=maximumFluids()) throw new IllegalArgumentException("At most 64 fluid entries");
        if (action==RiverProfiles.Action.ADD) return p.objectArrayInsert("palette",entries.size(),blockEntry(value));
        if (index<0 || index>=entries.size()) throw new IllegalArgumentException("Select an existing fluid");
        return switch(action) {
            case DUPLICATE -> p.objectArrayInsert("palette",index+1,entries.get(index).sourceBytes());
            case REMOVE -> { if (entries.size()<=1) throw new IllegalArgumentException("Keep at least one fluid");yield p.objectArrayRemove("palette",index); }
            case UP -> { if (index==0) throw new IllegalArgumentException("Already first");yield p.objectArrayMove("palette",index,index-1); }
            case DOWN -> { if (index==entries.size()-1) throw new IllegalArgumentException("Already last");yield p.objectArrayMove("palette",index,index+1); }
            default -> throw new IllegalArgumentException("Unsupported fluid action");
        };
    }
}
