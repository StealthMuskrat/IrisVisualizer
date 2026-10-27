package dev.irisvisualizer.editor;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

/** General palette entry operations; owner APIs authorize paths and existing containers. */
final class GeneralBlockPalettes {
    record Target(String dimensionField,String layerList,int layerIndex) {
        Target {
            if (dimensionField!=null) { if (!DimensionPalettes.OBJECT_FIELDS.contains(dimensionField) || layerList!=null || layerIndex!=-1) throw new IllegalArgumentException("Unsupported dimension palette target"); }
            else { BiomeLayers.field(layerList);if (layerIndex<0) throw new IllegalArgumentException("Select a layer"); }
        }
        static Target dimension(String field) { return new Target(field,null,-1); }
        static Target layer(String list,int index) { return new Target(null,list,index); }
        boolean layer() { return dimensionField==null; }
        String defaultBlock() { return layer() ? BiomeLayers.defaultBlock() : DimensionPalettes.classBlock(); }
        String listPrefix() { return layer() ? BiomeLayers.blockPrefix(layerList,layerIndex) : DimensionPalettes.listPrefix(dimensionField); }
        String noisePrefix() { return layer() ? BiomeLayers.noisePrefix(layerList,layerIndex) : DimensionPalettes.noisePrefix(dimensionField); }
        String zoomDefault() { return layer() ? BiomeLayers.zoomDefault() : RiverPaletteFields.zoomDefault(); }
        String zoomConstraints() { return layer() ? BiomeLayers.zoomConstraints() : RiverPaletteFields.zoomConstraints(); }
        NoiseStyleFields.Target styleTarget() { return layer() ? NoiseStyleFields.Target.biomeLayer(layerList,layerIndex) : NoiseStyleFields.Target.dimensionPalette(dimensionField); }
        byte[] enableEntries(String path,byte[] original) { return layer() ? BiomeLayers.enableBlocks(path,original,layerList,layerIndex) : DimensionPalettes.enableEntries(path,original,dimensionField); }
        byte[] setBlock(String path,byte[] original,int index,String value) { return layer() ? BiomeLayers.setBlock(path,original,layerList,layerIndex,index,value) : DimensionPalettes.setBlock(path,original,dimensionField,index,value); }
        byte[] enableWeight(String path,byte[] original,int index) { return layer() ? BiomeLayers.enableWeight(path,original,layerList,layerIndex,index) : DimensionPalettes.enableWeight(path,original,dimensionField,index); }
        byte[] setWeight(String path,byte[] original,int index,String value) { return layer() ? BiomeLayers.setWeight(path,original,layerList,layerIndex,index,value) : DimensionPalettes.setWeight(path,original,dimensionField,index,value); }
        byte[] change(String path,byte[] original,RiverProfiles.Action action,int index,String value) { return layer() ? BiomeLayers.changeBlock(path,original,layerList,layerIndex,action,index,value) : DimensionPalettes.change(path,original,dimensionField,action,index,value); }
        byte[] enableZoom(String path,byte[] original) { return layer() ? BiomeLayers.enableZoom(path,original,layerList,layerIndex) : DimensionPalettes.enableZoom(path,original,dimensionField); }
        byte[] setZoom(String path,byte[] original,String value) { return layer() ? BiomeLayers.setZoom(path,original,layerList,layerIndex,value) : DimensionPalettes.setZoom(path,original,dimensionField,value); }
    }
    private static void block(String value) { if (value==null || value.isBlank()) throw new IllegalArgumentException("Enter a nonblank block name; registry/state validation is pending"); }
    private static byte[] entry(String value) { block(value);return new LosslessJson("{}".getBytes(StandardCharsets.UTF_8)).enableStringPath("block",value); }
    static byte[] seed(LosslessJson palette,String block) {
        if (palette.token("palette")!=null) throw new IllegalArgumentException("Entries already exist or have an unsupported type");
        return new LosslessJson(palette.enable("palette","[]")).objectArrayInsert("palette",0,entry(block));
    }
    static byte[] setBlock(LosslessJson p,int index,String value) {
        block(value);return p.objectArrayEdit("palette",index,e -> e.token("block")==null ? e.enableStringPath("block",value) : e.stringValue("block",value));
    }
    static byte[] enableWeight(LosslessJson p,int index) { return p.objectArrayEdit("palette",index,e -> e.enable("weight",DimensionPalettes.weightDefault())); }
    static byte[] setWeight(LosslessJson p,int index,String input) {
        int weight;
        try { weight=new BigDecimal(input).intValueExact(); } catch (NumberFormatException | ArithmeticException | NullPointerException invalid) { throw new IllegalArgumentException("Enter an exact integer weight",invalid); }
        if (weight<DimensionPalettes.minimumWeight() || weight>DimensionPalettes.maximumWeight()) throw new IllegalArgumentException("General palette weights must be 1-"+DimensionPalettes.maximumWeight());
        return p.objectArrayEdit("palette",index,e -> e.number("weight",weight));
    }
    static byte[] change(LosslessJson p,RiverProfiles.Action action,int index,String value) {
        if (action==null) throw new IllegalArgumentException("Choose a palette action");if (action==RiverProfiles.Action.ADD) block(value);
        var entries=p.objectArray("palette");
        if (action==RiverProfiles.Action.ADD) return p.objectArrayInsert("palette",entries.size(),entry(value));
        if (index<0 || index>=entries.size()) throw new IllegalArgumentException("Select an existing block");
        return switch(action) {
            case DUPLICATE -> p.objectArrayInsert("palette",index+1,entries.get(index).sourceBytes());
            case REMOVE -> { if (entries.size()<=DimensionPalettes.minimumEntries()) throw new IllegalArgumentException("Keep at least one block");yield p.objectArrayRemove("palette",index); }
            case UP -> { if (index==0) throw new IllegalArgumentException("Already first");yield p.objectArrayMove("palette",index,index-1); }
            case DOWN -> { if (index==entries.size()-1) throw new IllegalArgumentException("Already last");yield p.objectArrayMove("palette",index,index+1); }
            default -> throw new IllegalArgumentException("Unsupported palette action");
        };
    }
}
