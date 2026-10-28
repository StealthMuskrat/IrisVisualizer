package dev.irisvisualizer.editor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** Existing style-backed material palette entries, with exact owner authorization and no implicit parents. */
final class PaletteBlockData {
    static final int MAX_ENTRIES=64;
    static boolean supports(NoiseStyleFields.Target t) { return t!=null && !t.optionalFracture() && t.noise()==null && (t.proceduralPalette() || t.nativeStructurePalette() || t.objectPalette() || t.floatingBottom() || t.slabWall() || t.biomeLayer() || t.dimensionPalette() || t.ore() && t.oreContext().field().equals("palette") || t.deepPalette() || t.poolPalette() || t.profilePalette() || t.palette()); }
    record Context(NoiseStyleFields.Target target,int index) implements BlockDefinitions.Context {
        Context { if (!supports(target) || index<0) throw new IllegalArgumentException("Select a supported inline palette block"); }
        public boolean weightedRoot() { return true; }
        public boolean fluid() { return target.deepPalette() || target.poolPalette() || target.profilePalette(); }
        HydrologyFluidPalettes.Target fluidTarget() { if (!fluid()) throw new IllegalArgumentException("Not a hydrology fluid palette");return target.deepPalette() ? HydrologyFluidPalettes.Target.deep(target.deepIndex()) : new HydrologyFluidPalettes.Target(target.poolPalette(),target.poolPalette() ? target.poolIndex() : target.profileIndex()); }
        String listPrefix() { return fluid() ? fluidTarget().fluidPrefix() : target.palette() ? RiverMaterialFields.listDraftPrefix(target.paletteTarget()) : generalTarget(target).listPrefix(); }
        public String prefix() { return listPrefix()+index+".blockData."; }
        BlockDefinitions.Scope scope() { return new BlockDefinitions.Scope(this,0); }
        public String seedBlock() { return fluid() ? fluidTarget().defaultFluid().toLowerCase(java.util.Locale.ROOT) : "minecraft:stone"; }
        public void checkBlock(String value) { if (!BlockDefinitions.blockSyntax(value)) throw new IllegalArgumentException("Use a lowercase Minecraft base block or unnamespaced alias key");if (fluid()) HydrologyFluidPalettes.fluid(value); }
        public byte[] edit(String path,byte[] raw,Function<LosslessJson,byte[]> op) {
            if (!target.resourceBase().equals(ResourceTypes.base(path))) throw new IllegalArgumentException("Palette block owner does not match this resource");
            return NoiseStyleFields.editContainer(raw,target,p -> { var entries=p.objectArray("palette");if (entries.size()>MAX_ENTRIES) throw new IllegalArgumentException("Palette block details have a 64-entry editor limit");return p.objectArrayEdit("palette",index,op); },false);
        }
    }
    static GeneralBlockPalettes.Target generalTarget(NoiseStyleFields.Target t) {
        if (!supports(t) || t.palette() || t.deepPalette() || t.poolPalette() || t.profilePalette()) throw new IllegalArgumentException("Not a general block palette");
        if (t.proceduralPalette()) return GeneralBlockPalettes.Target.procedural(t.proceduralContext());
        if (t.nativeStructurePalette()) return GeneralBlockPalettes.Target.nativeStructure(t.nativeStructureContext());
        if (t.ore()) return GeneralBlockPalettes.Target.ore(t.oreContext().owner());
        if (t.objectPalette()) return GeneralBlockPalettes.Target.objectPalette(t.objectPaletteContext());
        if (t.floatingBottom()) return GeneralBlockPalettes.Target.floatingBottom(t.floatingBottomContext().entry(),t.floatingBottomContext().layer());
        if (t.slabWall()) return GeneralBlockPalettes.Target.slabWall(t.slabWallField());
        if (t.biomeLayer()) return GeneralBlockPalettes.Target.layer(t.layerTarget().field(),t.layerTarget().index());
        return GeneralBlockPalettes.Target.dimension(t.dimensionPaletteField());
    }
    static List<String> readOnlyPaths(LosslessJson entry) {
        var paths=new ArrayList<>(BlockDefinitions.readOnlyPaths(entry,true));String weight=entry.token("weight");if (weight!=null) try { if (!LosslessJson.numericToken(weight)) throw new NumberFormatException();new BigDecimal(weight).intValueExact(); } catch (NumberFormatException | ArithmeticException bad) { paths.add("weight"); }return List.copyOf(paths);
    }
    static void report(List<String> paths,List<LosslessJson> entries,String prefix) { if (entries==null) return;for (int i=0;i<entries.size();i++) { String at=prefix+"["+i+"]";if (entries.size()>MAX_ENTRIES) { for (String f : entries.get(i).fields()) if (!RiverMaterialFields.ENTRY_FIELDS.contains(f)) paths.add(at+"."+f); }else for (String f : readOnlyPaths(entries.get(i))) paths.add(at+"."+f); } }
}
