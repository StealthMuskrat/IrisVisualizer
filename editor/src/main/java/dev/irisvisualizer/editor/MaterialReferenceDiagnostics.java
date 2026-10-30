package dev.irisvisualizer.editor;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/** Authored exact blocks/ links in existing block-data contexts. No registry or loader resolution. */
final class MaterialReferenceDiagnostics {
    private static final Set<String> FAMILIES=Set.of("dimensions","regions","biomes","structures","blocks");
    private static final String NUMBER="(?:0|[1-9][0-9]*)";
    private static final Pattern PALETTE=Pattern.compile("(.+)\\.palette\\."+NUMBER);
    private static final Pattern DEPOSIT=Pattern.compile("deposits\\."+NUMBER+"\\.palette\\."+NUMBER);
    private static final Pattern FIND=Pattern.compile("((?:.*\\.)?edit\\."+NUMBER+")\\.find\\."+NUMBER);
    private static final Pattern DECORATOR=Pattern.compile("decorators\\."+NUMBER+"\\.([^.]+)(?:\\."+NUMBER+")?");

    static boolean blockRoot(String base,String path) {
        if (path==null) return false;
        if (base.equals("blocks")) return path.isEmpty();
        var palette=PALETTE.matcher(path);
        if (palette.matches()) try {
            if (PaletteBlockData.supports(new NoiseStyleFields.Target(base,palette.group(1)+".style",null))) return true;
        } catch (IllegalArgumentException unsupported) { /* Another, non-style palette may match below. */ }
        if (OreGenerators.CONTEXTS.contains(base) && DEPOSIT.matcher(path).matches()) return true;
        var find=FIND.matcher(path);
        if (find.matches() && FloatingObjectPalettes.styleContext(base,find.group(1)+".replace.style")!=null) return true;
        if (base.equals("biomes")) {
            var decorator=DECORATOR.matcher(path);
            if (decorator.matches()) {
                String field=decorator.group(1);
                return field.equals("forceBlock") ? path.endsWith(".forceBlock") : BiomeDecorators.BLOCK_LISTS.contains(field) && path.matches(".*\\."+NUMBER);
            }
        }
        return false;
    }
    private static boolean emit(Predicate<ReferenceDiagnostics.Link> output,String field,String detail) {
        return output.test(new ReferenceDiagnostics.Link(field,detail,null,ReferenceDiagnostics.State.UNSUPPORTED));
    }
    static void inspect(PackResourceView workspace,String base,LosslessJson document,Predicate<ReferenceDiagnostics.Link> output) {
        if (!FAMILIES.contains(base)) return;
        var index=document.structure(JsonStructure.MAX_NODES,JsonStructure.MAX_DEPTH);
        var nodes=index.nodes();var paths=new String[nodes.size()];var displays=new String[nodes.size()];var blocks=new boolean[nodes.size()];var depths=new int[nodes.size()];
        paths[0]="";displays[0]="";blocks[0]=base.equals("blocks");
        String source=new String(document.sourceBytes(),StandardCharsets.UTF_8);
        for (int id=1;id<nodes.size();id++) {
            var node=nodes.get(id);var parent=nodes.get(node.parent());String segment=node.segment(),prefix=paths[node.parent()];boolean array=parent.kind()==JsonStructure.Kind.ARRAY;
            // Only actual JSON array slots may supply numeric schema indices. Dotted keys are opaque.
            String path=prefix==null || segment.length()>128 || segment.contains(".") || !array && segment.matches("[0-9]+")
                    ? null : prefix.isEmpty() ? segment : prefix+"."+segment;
            if (path!=null && path.length()>NoiseReferenceDiagnostics.MAX_PATH_CHARACTERS) path=null;
            paths[id]=path;displays[id]=path==null ? null : displays[node.parent()]+(array ? "["+segment+"]" : displays[node.parent()].isEmpty() ? segment : "."+segment);
            if (path==null) continue;
            boolean backup=!array && blocks[node.parent()] && segment.equals("backup");
            if (backup && node.kind()!=JsonStructure.Kind.NULL) {
                if (depths[node.parent()]>=BlockDefinitions.MAX_DEPTH) {
                    if (!emit(output,displays[id],"Beyond eight block backup levels; deeper material links are unknown")) return;
                } else { blocks[id]=true;depths[id]=depths[node.parent()]+1; }
            } else if (blockRoot(base,path) && !(node.kind()==JsonStructure.Kind.NULL && path.endsWith(".forceBlock"))) blocks[id]=true;
            if (blocks[id] && node.kind()!=JsonStructure.Kind.OBJECT) {
                blocks[id]=false;
                if (!emit(output,displays[id],"Non-object block data; material links were not inspected")) return;
            }
            // A malformed known list must remain visible while later independent rows still scan.
            if (blockRoot(base,path+".0") && node.kind()!=JsonStructure.Kind.ARRAY) {
                if (!emit(output,displays[id],"Non-array material list; material links were not inspected")) return;
            }
            if (!array && blocks[node.parent()] && segment.equals("block")) {
                if (node.kind()!=JsonStructure.Kind.STRING) {
                    if (!emit(output,displays[id],"Non-string block key; material link inspection unavailable")) return;
                    continue;
                }
                String key=new LosslessJson(("{\"key\":"+source.substring(node.start(),node.end())+"}").getBytes(StandardCharsets.UTF_8)).stringToken("key");
                // A bare key can also be a vanilla block. Absence of an alias file is not a missing-resource finding.
                String target=BlockDefinitions.exactAlias(workspace,key);
                if (target!=null && !output.test(new ReferenceDiagnostics.Link(displays[id],key,target,ReferenceDiagnostics.State.EXACT_FILE))) return;
            }
        }
        if (index.limited()) emit(output,"Material aliases","Material inspection truncated at "+JsonStructure.MAX_NODES+" indexed nodes / "+JsonStructure.MAX_DEPTH+" JSON levels; other links may exist");
    }
}
