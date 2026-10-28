package dev.irisvisualizer.editor;

import java.util.List;
import java.util.function.Function;

/** Non-style-backed block-data owners. Weights remain scoped to their native consumers. */
final class InlineBlockData {
    static final int MAX_ENTRIES=64;
    record Context(DepositGenerators.Context deposit,FloatingObjectReplacements.Context replacement,int rule,BiomeDecorators.Context decorator,String field,int index) implements BlockDefinitions.Context {
        Context {
            if ((deposit==null ? 0 : 1)+(replacement==null ? 0 : 1)+(decorator==null ? 0 : 1)!=1) throw new IllegalArgumentException("Select one inline block-data owner");
            if (deposit!=null && (rule!=-1 || field!=null || index<0) || replacement!=null && (rule<0 || field!=null || index<0) || decorator!=null && (rule!=-1 || field==null || !(BiomeDecorators.BLOCK_LISTS.contains(field) && index>=0 || field.equals("forceBlock") && index==-1))) throw new IllegalArgumentException("Select a supported block list and index");
        }
        static Context deposit(DepositGenerators.Context c,int index) { return new Context(java.util.Objects.requireNonNull(c),null,-1,null,null,index); }
        static Context find(FloatingObjectReplacements.Context c,int rule,int index) { return new Context(null,java.util.Objects.requireNonNull(c),rule,null,null,index); }
        static Context decorator(BiomeDecorators.Context c,String field,int index) { return new Context(null,null,-1,java.util.Objects.requireNonNull(c),field,index); }
        static Context forced(BiomeDecorators.Context c) { return decorator(c,"forceBlock",-1); }
        boolean forced() { return decorator!=null && field.equals("forceBlock"); }
        String resourceBase() { return deposit!=null ? deposit.resourceBase() : replacement!=null ? replacement.resourceBase() : "biomes"; }
        String listPrefix() { return deposit!=null ? deposit.palettePrefix() : replacement!=null ? replacement.findPrefix(rule) : forced() ? decorator.prefix()+"forceBlock." : decorator.blocksPrefix(field); }
        public String prefix() { return listPrefix()+(forced() ? "" : index+".")+"blockData."; }
        public boolean fluid() { return false; }
        public boolean weightedRoot() { return decorator!=null && !forced() && BiomeDecorators.weighted(field); }
        public String seedBlock() { return "minecraft:stone"; }
        public void checkBlock(String value) { if (!BlockDefinitions.blockSyntax(value)) throw new IllegalArgumentException("Use a lowercase Minecraft base block or unnamespaced alias key"); }
        BlockDefinitions.Scope scope() { return new BlockDefinitions.Scope(this,0); }
        public byte[] edit(String path,byte[] raw,Function<LosslessJson,byte[]> op) {
            if (!resourceBase().equals(ResourceTypes.base(path))) throw new IllegalArgumentException("Inline block-data owner does not match this resource");
            if (deposit!=null) return DepositGenerators.edit(raw,deposit,d -> editList(d,"palette",index,op));
            if (replacement!=null) { FloatingObjectReplacements.authorize(path,replacement);return FloatingObjectReplacements.editRule(raw,replacement,rule,r -> editList(r,"find",index,op)); }
            return BiomeDecorators.editEntry(raw,decorator,d -> { if (!forced()) return editList(d,field,index,op);if (BiomeDecorators.forceBlock(d)==null) throw new IllegalArgumentException("Enable the forced block explicitly first");return d.objectEdit("forceBlock",op,false); });
        }
    }
    private static byte[] editList(LosslessJson parent,String field,int index,Function<LosslessJson,byte[]> op) { var rows=parent.objectArray(field);if (rows.size()>MAX_ENTRIES) throw new IllegalArgumentException("Inline block details have a 64-entry editor limit");return parent.objectArrayEdit(field,index,op); }
    static List<String> readOnlyPaths(LosslessJson entry,boolean weighted) { return weighted ? PaletteBlockData.readOnlyPaths(entry) : BlockDefinitions.readOnlyPaths(entry); }
}
