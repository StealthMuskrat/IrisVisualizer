package dev.irisvisualizer.editor;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

/** General palette entry operations; owner APIs authorize paths and existing containers. */
final class GeneralBlockPalettes {
    record Target(String dimensionField,String layerList,int layerIndex,String biomeMaterial,int floatingEntry,FloatingObjectPalettes.Context objectPaletteContext,OreGenerators.Context oreContext) {
        Target(String dimensionField,String layerList,int layerIndex,String biomeMaterial,int floatingEntry,FloatingObjectPalettes.Context objectPaletteContext) { this(dimensionField,layerList,layerIndex,biomeMaterial,floatingEntry,objectPaletteContext,null); }
        Target(String dimensionField,String layerList,int layerIndex,String biomeMaterial,int floatingEntry) { this(dimensionField,layerList,layerIndex,biomeMaterial,floatingEntry,null); }
        Target(String dimensionField,String layerList,int layerIndex,String biomeMaterial) { this(dimensionField,layerList,layerIndex,biomeMaterial,-1); }
        Target(String dimensionField,String layerList,int layerIndex) { this(dimensionField,layerList,layerIndex,null); }
        Target {
            if (floatingEntry < -1) throw new IllegalArgumentException("Unsupported floating entry index");
            if (oreContext!=null) { if (dimensionField!=null || layerList!=null || layerIndex!=-1 || biomeMaterial!=null || floatingEntry!=-1 || objectPaletteContext!=null) throw new IllegalArgumentException("Unsupported ore palette target"); }
            else if (objectPaletteContext!=null) { if (dimensionField!=null || layerList!=null || layerIndex!=-1 || biomeMaterial!=null || floatingEntry!=-1) throw new IllegalArgumentException("Unsupported object palette target"); }
            else if (floatingEntry>=0) { if (dimensionField!=null || layerList!=null || biomeMaterial!=null || layerIndex<0) throw new IllegalArgumentException("Unsupported floating underside target"); }
            else if (biomeMaterial!=null) { BiomeSlabWall.field(biomeMaterial);if (dimensionField!=null || layerList!=null || layerIndex!=-1) throw new IllegalArgumentException("Unsupported slab/wall palette target"); }
            else if (dimensionField!=null) { if (!DimensionPalettes.OBJECT_FIELDS.contains(dimensionField) || layerList!=null || layerIndex!=-1) throw new IllegalArgumentException("Unsupported dimension palette target"); }
            else { BiomeLayers.field(layerList);if (layerIndex<0) throw new IllegalArgumentException("Select a layer"); }
        }
        static Target dimension(String field) { return new Target(field,null,-1); }
        static Target layer(String list,int index) { return new Target(null,list,index); }
        static Target slabWall(String field) { return new Target(null,null,-1,field); }
        static Target floatingBottom(int entry,int layer) { if (entry<0) throw new IllegalArgumentException("Select a floating entry");return new Target(null,null,layer,null,entry); }
        static Target objectPalette(FloatingObjectPalettes.Context context) { return new Target(null,null,-1,null,-1,java.util.Objects.requireNonNull(context)); }
        static Target ore(OreGenerators.Context context) { return new Target(null,null,-1,null,-1,null,java.util.Objects.requireNonNull(context)); }
        boolean ore() { return oreContext!=null; }
        boolean objectPalette() { return objectPaletteContext!=null; }
        boolean floatingBottom() { return floatingEntry>=0; }
        boolean slabWall() { return biomeMaterial!=null; }
        boolean layer() { return dimensionField==null && !slabWall() && !floatingBottom() && !objectPalette() && !ore(); }
        int minimumEntries() { return slabWall() || ore() ? 0 : DimensionPalettes.minimumEntries(); }
        String defaultBlock() { return objectPalette() ? DimensionPalettes.classBlock() : floatingBottom() ? BiomeLayers.defaultBlock() : slabWall() ? BiomeSlabWall.classBlock() : layer() ? BiomeLayers.defaultBlock() : DimensionPalettes.classBlock(); }
        String listPrefix() { return ore() ? oreContext.prefix("palette")+"entries#" : objectPalette() ? objectPaletteContext.prefix()+"entries#" : floatingBottom() ? FloatingBottomPalettes.blockPrefix(floatingEntry,layerIndex) : slabWall() ? BiomeSlabWall.blockPrefix(biomeMaterial) : layer() ? BiomeLayers.blockPrefix(layerList,layerIndex) : DimensionPalettes.listPrefix(dimensionField); }
        String noisePrefix() { return ore() ? oreContext.prefix("palette")+"noise." : objectPalette() ? objectPaletteContext.prefix()+"noise." : floatingBottom() ? FloatingBottomPalettes.noisePrefix(floatingEntry,layerIndex) : slabWall() ? BiomeSlabWall.noisePrefix(biomeMaterial) : layer() ? BiomeLayers.noisePrefix(layerList,layerIndex) : DimensionPalettes.noisePrefix(dimensionField); }
        String zoomDefault() { return floatingBottom() || layer() || slabWall() ? BiomeLayers.zoomDefault() : RiverPaletteFields.zoomDefault(); }
        String zoomConstraints() { return floatingBottom() || layer() || slabWall() ? BiomeLayers.zoomConstraints() : RiverPaletteFields.zoomConstraints(); }
        NoiseStyleFields.Target styleTarget() { return ore() ? oreContext.styleTarget("palette") : objectPalette() ? NoiseStyleFields.Target.objectPalette(objectPaletteContext) : floatingBottom() ? NoiseStyleFields.Target.floatingBottom(floatingEntry,layerIndex) : slabWall() ? NoiseStyleFields.Target.slabWall(biomeMaterial) : layer() ? NoiseStyleFields.Target.biomeLayer(layerList,layerIndex) : NoiseStyleFields.Target.dimensionPalette(dimensionField); }
        byte[] enableEntries(String path,byte[] original) { return ore() ? OreGenerators.enablePaletteEntries(path,original,oreContext) : objectPalette() ? FloatingObjectPalettes.enableEntries(path,original,objectPaletteContext) : floatingBottom() ? FloatingBottomPalettes.enableBlocks(path,original,floatingEntry,layerIndex) : slabWall() ? BiomeSlabWall.enableEntries(path,original,biomeMaterial) : layer() ? BiomeLayers.enableBlocks(path,original,layerList,layerIndex) : DimensionPalettes.enableEntries(path,original,dimensionField); }
        byte[] setBlock(String path,byte[] original,int index,String value) { return ore() ? OreGenerators.setBlock(path,original,oreContext,index,value) : objectPalette() ? FloatingObjectPalettes.setBlock(path,original,objectPaletteContext,index,value) : floatingBottom() ? FloatingBottomPalettes.setBlock(path,original,floatingEntry,layerIndex,index,value) : slabWall() ? BiomeSlabWall.setBlock(path,original,biomeMaterial,index,value) : layer() ? BiomeLayers.setBlock(path,original,layerList,layerIndex,index,value) : DimensionPalettes.setBlock(path,original,dimensionField,index,value); }
        byte[] enableWeight(String path,byte[] original,int index) { return ore() ? OreGenerators.enableWeight(path,original,oreContext,index) : objectPalette() ? FloatingObjectPalettes.enableWeight(path,original,objectPaletteContext,index) : floatingBottom() ? FloatingBottomPalettes.enableWeight(path,original,floatingEntry,layerIndex,index) : slabWall() ? BiomeSlabWall.enableWeight(path,original,biomeMaterial,index) : layer() ? BiomeLayers.enableWeight(path,original,layerList,layerIndex,index) : DimensionPalettes.enableWeight(path,original,dimensionField,index); }
        byte[] setWeight(String path,byte[] original,int index,String value) { return ore() ? OreGenerators.setWeight(path,original,oreContext,index,value) : objectPalette() ? FloatingObjectPalettes.setWeight(path,original,objectPaletteContext,index,value) : floatingBottom() ? FloatingBottomPalettes.setWeight(path,original,floatingEntry,layerIndex,index,value) : slabWall() ? BiomeSlabWall.setWeight(path,original,biomeMaterial,index,value) : layer() ? BiomeLayers.setWeight(path,original,layerList,layerIndex,index,value) : DimensionPalettes.setWeight(path,original,dimensionField,index,value); }
        byte[] change(String path,byte[] original,RiverProfiles.Action action,int index,String value) { return ore() ? OreGenerators.changePalette(path,original,oreContext,action,index,value) : objectPalette() ? FloatingObjectPalettes.change(path,original,objectPaletteContext,action,index,value) : floatingBottom() ? FloatingBottomPalettes.changeBlock(path,original,floatingEntry,layerIndex,action,index,value) : slabWall() ? BiomeSlabWall.change(path,original,biomeMaterial,action,index,value) : layer() ? BiomeLayers.changeBlock(path,original,layerList,layerIndex,action,index,value) : DimensionPalettes.change(path,original,dimensionField,action,index,value); }
        byte[] enableZoom(String path,byte[] original) { return ore() ? OreGenerators.enableZoom(path,original,oreContext) : objectPalette() ? FloatingObjectPalettes.enableZoom(path,original,objectPaletteContext) : floatingBottom() ? FloatingBottomPalettes.enableZoom(path,original,floatingEntry,layerIndex) : slabWall() ? BiomeSlabWall.enableZoom(path,original,biomeMaterial) : layer() ? BiomeLayers.enableZoom(path,original,layerList,layerIndex) : DimensionPalettes.enableZoom(path,original,dimensionField); }
        byte[] setZoom(String path,byte[] original,String value) { return ore() ? OreGenerators.setZoom(path,original,oreContext,value) : objectPalette() ? FloatingObjectPalettes.setZoom(path,original,objectPaletteContext,value) : floatingBottom() ? FloatingBottomPalettes.setZoom(path,original,floatingEntry,layerIndex,value) : slabWall() ? BiomeSlabWall.setZoom(path,original,biomeMaterial,value) : layer() ? BiomeLayers.setZoom(path,original,layerList,layerIndex,value) : DimensionPalettes.setZoom(path,original,dimensionField,value); }
    }
    private static void block(String value) { if (value==null || value.isBlank()) throw new IllegalArgumentException("Enter a nonblank block name; registry/state validation is pending"); }
    private static byte[] entry(String value) { block(value);return new LosslessJson("{}".getBytes(StandardCharsets.UTF_8)).enableStringPath("block",value); }
    static byte[] seed(LosslessJson palette,String block) {
        if (palette.token("palette")!=null) throw new IllegalArgumentException("Entries already exist or have an unsupported type");
        return new LosslessJson(palette.enable("palette","[]")).objectArrayInsert("palette",0,entry(block));
    }
    static byte[] setBlock(LosslessJson p,int index,String value) {
        return setBlock(p,"palette",index,value);
    }
    static byte[] setBlock(LosslessJson p,String field,int index,String value) { block(value);return p.objectArrayEdit(field,index,e -> e.token("block")==null ? e.enableStringPath("block",value) : e.stringValue("block",value)); }
    static byte[] enableWeight(LosslessJson p,int index) { return enableWeight(p,"palette",index); }
    static byte[] enableWeight(LosslessJson p,String field,int index) { return p.objectArrayEdit(field,index,e -> e.enable("weight",DimensionPalettes.weightDefault())); }
    static byte[] setWeight(LosslessJson p,int index,String input) {
        return setWeight(p,"palette",index,input);
    }
    static byte[] setWeight(LosslessJson p,String field,int index,String input) {
        int weight;
        try { weight=new BigDecimal(input).intValueExact(); } catch (NumberFormatException | ArithmeticException | NullPointerException invalid) { throw new IllegalArgumentException("Enter an exact integer weight",invalid); }
        if (weight<DimensionPalettes.minimumWeight() || weight>DimensionPalettes.maximumWeight()) throw new IllegalArgumentException("General palette weights must be 1-"+DimensionPalettes.maximumWeight());
        return p.objectArrayEdit(field,index,e -> e.number("weight",weight));
    }
    static byte[] change(LosslessJson p,RiverProfiles.Action action,int index,String value) { return change(p,action,index,value,DimensionPalettes.minimumEntries()); }
    static byte[] change(LosslessJson p,RiverProfiles.Action action,int index,String value,int minimumEntries) {
        return change(p,"palette",action,index,value,minimumEntries);
    }
    static byte[] change(LosslessJson p,String field,RiverProfiles.Action action,int index,String value,int minimumEntries) {
        if (minimumEntries<0 || minimumEntries>1) throw new IllegalArgumentException("Unsupported palette minimum");
        if (action==null) throw new IllegalArgumentException("Choose a palette action");if (action==RiverProfiles.Action.ADD) block(value);
        var entries=p.objectArray(field);
        if (action==RiverProfiles.Action.ADD) return p.objectArrayInsert(field,entries.size(),entry(value));
        if (index<0 || index>=entries.size()) throw new IllegalArgumentException("Select an existing block");
        return switch(action) {
            case DUPLICATE -> p.objectArrayInsert(field,index+1,entries.get(index).sourceBytes());
            case REMOVE -> { if (entries.size()<=minimumEntries) throw new IllegalArgumentException("Keep at least one block");yield p.objectArrayRemove(field,index); }
            case UP -> { if (index==0) throw new IllegalArgumentException("Already first");yield p.objectArrayMove(field,index,index-1); }
            case DOWN -> { if (index==entries.size()-1) throw new IllegalArgumentException("Already last");yield p.objectArrayMove(field,index,index+1); }
            default -> throw new IllegalArgumentException("Unsupported palette action");
        };
    }
}
