package dev.irisvisualizer.editor;

import java.util.Set;
import java.util.function.Predicate;

/** Read-only links in already-audited style contexts; no resource loading or expression evaluation. */
final class NoiseReferenceDiagnostics {
    private enum Role { NONE, NOISE, NOISE_LIST, STYLE }
    private static final Set<String> FAMILIES=Set.of("dimensions","regions","biomes","generators","expressions","structures");
    static final int MAX_PATH_CHARACTERS=2048;

    private static boolean style(String base,String path) {
        if (path==null) return false;
        String member=path.substring(path.lastIndexOf('.')+1);
        if (!(member.endsWith("Style") || Set.of("style","warp","generator","styleValue").contains(member) || BiomeDecorators.STYLES.contains(member))) return false;
        try {
            // Module indices live separately in the existing authoring target.
            var module=java.util.regex.Pattern.compile("caveProfile\\.modules\\.(0|[1-9][0-9]*)\\.style").matcher(path);
            if (module.matches()) NoiseStyleFields.Target.module(base,Integer.parseInt(module.group(1)));
            else new NoiseStyleFields.Target(base,path,null);
            return true;
        } catch (IllegalArgumentException unsupported) { return false; }
    }
    private static boolean noise(String base,String path) {
        if (path==null) return false;
        if (base.equals("generators") && path.equals("cliffHeightGenerator")) return true;
        var placement=FloatingObjectPlacements.parsePath(base,path);
        return placement!=null && placement.suffix().equals("heightmap");
    }
    private static boolean emit(Predicate<ReferenceDiagnostics.Link> output,String field,String detail) {
        return output.test(new ReferenceDiagnostics.Link(field,detail,null,ReferenceDiagnostics.State.UNSUPPORTED));
    }
    static void inspect(PackResourceView workspace,String base,LosslessJson document,Predicate<ReferenceDiagnostics.Link> output) {
        if (!FAMILIES.contains(base)) return;
        var index=document.structure(JsonStructure.MAX_NODES,JsonStructure.MAX_DEPTH);
        String source=new String(document.sourceBytes(),java.nio.charset.StandardCharsets.UTF_8);
        var nodes=index.nodes();var roles=new Role[nodes.size()];var paths=new String[nodes.size()];var displays=new String[nodes.size()];
        roles[0]=Role.NONE;paths[0]="";displays[0]="";
        for (int id=1;id<nodes.size();id++) {
            var node=nodes.get(id);var parent=nodes.get(node.parent());String segment=node.segment();
            Role parentRole=roles[node.parent()],role=Role.NONE;
            // Never reinterpret dotted/numbered object keys as nested fields or array slots.
            boolean array=parent.kind()==JsonStructure.Kind.ARRAY;
            String prefix=paths[node.parent()];
            String path=prefix==null || segment.length()>128 || segment.contains(".") || !array && segment.matches("[0-9]+")
                    ? null : prefix.isEmpty() ? segment : prefix+"."+segment;
            if (path!=null && path.length()>MAX_PATH_CHARACTERS) path=null;
            paths[id]=path;
            displays[id]=path==null ? null : displays[node.parent()]+(array ? "["+segment+"]" : displays[node.parent()].isEmpty() ? segment : "."+segment);
            if (array && parentRole==Role.NOISE_LIST) role=Role.NOISE;
            else if (!array && parentRole==Role.NOISE && segment.equals("style")) role=Role.STYLE;
            else if (!array && parentRole==Role.STYLE && segment.equals("fracture")) {
                // A null style fracture is the native no-fracture override.
                if (node.kind()!=JsonStructure.Kind.NULL) role=Role.STYLE;
            }
            else if (!array && parentRole==Role.NOISE && segment.equals("fracture")) role=Role.NOISE_LIST;
            else if (path!=null && base.equals("generators") && path.equals("composite")) role=Role.NOISE_LIST;
            else if (noise(base,path)) {
                // A null placement heightmap delegates to the underlying height queries.
                if (node.kind()!=JsonStructure.Kind.NULL || base.equals("generators")) role=Role.NOISE;
            }
            else if (style(base,path)) role=Role.STYLE;
            roles[id]=role;
            if (role!=Role.NONE) {
                boolean valid=role==Role.NOISE_LIST ? node.kind()==JsonStructure.Kind.ARRAY : node.kind()==JsonStructure.Kind.OBJECT;
                if (!valid) {
                    roles[id]=Role.NONE;
                    if (!emit(output,displays[id],role==Role.NOISE_LIST ? "Non-array noise list; nested source links were not inspected" : "Non-object noise/style; nested source links were not inspected")) return;
                }
            }
            if (!array && parentRole==Role.STYLE && NoiseStyleSources.REFERENCES.contains(segment)) {
                if (node.kind()==JsonStructure.Kind.NULL) continue; // Native nullable source default.
                if (node.kind()!=JsonStructure.Kind.STRING) {
                    if (!emit(output,displays[id],"Non-string style source reference")) return;
                    continue;
                }
                String key=new LosslessJson(("{\"key\":"+source.substring(node.start(),node.end())+"}").getBytes(java.nio.charset.StandardCharsets.UTF_8)).stringToken("key");
                if (!output.test(ReferenceDiagnostics.link(workspace,displays[id],key,NoiseStyleSources.family(segment)))) return;
            }
        }
        if (index.limited()) emit(output,"Noise sources","Noise source inspection truncated at "+JsonStructure.MAX_NODES+" indexed nodes / "+JsonStructure.MAX_DEPTH+" JSON levels; other links may exist");
    }
}
