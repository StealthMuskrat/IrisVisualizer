package dev.irisvisualizer.editor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

final class NoiseFractureTests {
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static String text(byte[] value) { return new String(value,StandardCharsets.UTF_8); }
    private static void check(boolean value) { if (!value) throw new AssertionError("Noise-fracture contract failed"); }
    private static void rejects(Runnable action) { try { action.run(); } catch (IllegalArgumentException expected) { return; }throw new AssertionError("Expected rejected noise-fracture operation"); }
    private record Case(NoiseFields.Target root,String nativePath,int id) {
        String path() { return root.resourceBase()+"/fracture-"+id+".json"; }
        byte[] document(String noise) { return bytes(wrap(nativePath.split("\\."),0,noise)); }
        LosslessJson node(byte[] source,NoiseFields.Target target) {
            var noise=at(new LosslessJson(source),nativePath.split("\\."),0);
            for (int index : target.fracturePath()) noise=noise.objectArray("fracture").get(index);return noise;
        }
    }
    private static String wrap(String[] parts,int index,String leaf) {
        if (index==parts.length) return leaf;
        if (parts[index].matches("[0-9]+")) return "["+"{},".repeat(Integer.parseInt(parts[index]))+wrap(parts,index+1,leaf)+"]";
        return "{\r\n \""+parts[index]+"\":"+wrap(parts,index+1,leaf)+",\"futureSibling\":9007199254740993,\"escaped\":\"雪\\u0061\"\r\n}";
    }
    private static LosslessJson at(LosslessJson object,String[] parts,int index) {
        if (index==parts.length) return object;
        String field=parts[index];if (index+1<parts.length && parts[index+1].matches("[0-9]+")) return at(object.objectArray(field).get(Integer.parseInt(parts[index+1])),parts,index+2);
        var child=new LosslessJson(bytes(object.token(field)));return index+1==parts.length ? child : at(child,parts,index+1);
    }
    private static List<Case> cases() {
        var cases=new ArrayList<Case>();cases.add(new Case(NoiseFields.Target.composite(0),"composite.0",0));cases.add(new Case(NoiseFields.Target.cliffHeight(),"cliffHeightGenerator",1));
        for (int owner : List.of(FloatingObjectPlacements.BIOME_ROOT,FloatingObjectPlacements.REGION_ROOT,0)) for (String list : owner<0 ? List.of("objects") : FloatingObjectPlacements.LIST_FIELDS) {
            var context=new FloatingPlacementOverrides.Context(owner,list,0);cases.add(new Case(context.noiseTarget(),FloatingObjectPlacements.contextPath(owner,list,0)+".heightmap",cases.size()));
        }
        return cases;
    }
    static void run(Path temp) throws Exception {
        check(cases().size()==8 && NoiseFractures.MAXIMUM_DEPTH==8 && NoiseFractures.MAXIMUM_CHILDREN==64);
        for (Case c : cases()) {
            String path=c.path();var root=c.root();var child=root.child(0);byte[] original=c.document("{\"seed\":9007199254740993,\"offsetY\":1e2,\"futureParent\":\"雪\\u0061\"}"),copy=original.clone();
            byte[] one=NoiseFractures.change(path,original,root,CompositeNoise.Action.ADD,-1);check(NoiseFractures.entries(c.node(one,root)).size()==1 && c.node(one,child).fields().isEmpty());
            check(Arrays.equals(original,NoiseFractures.change(path,one,root,CompositeNoise.Action.REMOVE,0)));
            byte[] edited=NoiseFields.enable(path,one,child,"seed");edited=NoiseFields.editText(path,edited,child,"seed","-9223372036854775808");edited=NoiseFields.enable(path,edited,child,"octaves");edited=NoiseFields.editText(path,edited,child,"octaves","16");edited=NoiseFields.enable(path,edited,child,"enabled");edited=NoiseFields.editBoolean(path,edited,child,"enabled",false);
            check(c.node(edited,child).token("seed").equals("-9223372036854775808") && c.node(edited,child).token("octaves").equals("16") && c.node(edited,child).token("enabled").equals("false") && c.node(edited,root).token("seed").equals("9007199254740993"));
            var style=NoiseStyleFields.Target.noise(child);edited=NoiseStyleFields.enable(path,edited,style,"style");check(NoiseStyleFields.object(c.node(edited,child)).stringToken("style").equals("IRIS"));
            for (String choice : NoiseStyleFields.choices()) check(NoiseStyleFields.object(c.node(NoiseStyleFields.editStyle(path,edited,style,choice),child)).stringToken("style").equals(choice));
            edited=NoiseStyleFields.enable(path,edited,style,"zoom");edited=NoiseStyleFields.editText(path,edited,style,"zoom","2");edited=NoiseStyleSources.enableCache(path,edited,style);edited=NoiseStyleSources.setCache(path,edited,style,"8192");
            var pack=PackWorkspace.newPack(Map.of(path,edited,"expressions/noise/formula.json",bytes("opaque expression"),"image-maps/noise/map.json",bytes("opaque image map"),"objects/opaque.iob",new byte[]{0,127,-1,42}));
            edited=NoiseStyleSources.setReference(path,edited,style,"expression","noise/formula",pack);edited=NoiseStyleSources.setReference(path,edited,style,"imageMap","noise/map",pack);
            check(NoiseStyleFields.object(c.node(edited,child)).stringToken("expression").equals("noise/formula") && NoiseStyleFields.object(c.node(edited,child)).token("cacheSize").equals("8192"));
            var parent=child;
            for (int depth=1;depth<3;depth++) { edited=NoiseFractures.change(path,edited,parent,CompositeNoise.Action.ADD,-1);parent=parent.child(0);edited=NoiseFields.enable(path,edited,parent,"seed");edited=NoiseFields.editText(path,edited,parent,"seed",Long.toString(100+depth)); }
            check(c.node(edited,parent).token("seed").equals("102") && !parent.draftPrefix().equals(child.draftPrefix()));
            String subtree=text(c.node(edited,child).sourceBytes());byte[] duplicate=NoiseFractures.change(path,edited,root,CompositeNoise.Action.DUPLICATE,0);check(text(c.node(duplicate,root.child(1)).sourceBytes()).equals(subtree));
            duplicate=NoiseFields.editText(path,duplicate,root.child(1),"seed","9223372036854775807");byte[] reordered=NoiseFractures.change(path,duplicate,root,CompositeNoise.Action.DOWN,0);check(c.node(reordered,child).token("seed").equals("9223372036854775807") && text(c.node(reordered,root.child(1)).sourceBytes()).equals(subtree));
            reordered=NoiseFractures.change(path,reordered,root,CompositeNoise.Action.UP,1);check(text(c.node(reordered,child).sourceBytes()).equals(subtree));reordered=NoiseFractures.change(path,reordered,root,CompositeNoise.Action.REMOVE,1);check(text(c.node(reordered,child).sourceBytes()).equals(subtree));
            byte[] finalEdited=edited;rejects(() -> NoiseFields.editText(path,finalEdited,child,"seed","9223372036854775808"));rejects(() -> NoiseFields.editText(path,finalEdited,child,"octaves","16.000000000000000001"));rejects(() -> NoiseFields.editText(path,finalEdited,child,"octaves","17"));
            rejects(() -> NoiseFields.enable(path,original,child,"seed"));rejects(() -> NoiseStyleFields.enable(path,original,style,"style"));rejects(() -> NoiseFractures.change(path,original,child,CompositeNoise.Action.ADD,-1));rejects(() -> NoiseFields.enable(path,one,root.child(1),"seed"));
            for (String token : List.of("null","{}","true","1","\"snippet/generator/x\"","[null]","[{},true]","[{},\"snippet/generator/x\"]")) {
                byte[] wrong=c.document("{\"fracture\":"+token+"}");
                for (var action : CompositeNoise.Action.values()) rejects(() -> NoiseFractures.change(path,wrong,root,action,0));
                rejects(() -> NoiseFields.enable(path,wrong,child,"seed"));rejects(() -> NoiseStyleFields.enable(path,wrong,style,"style"));check(NoiseFractures.readOnlyPaths(c.node(wrong,root)).contains("fracture"));
            }
            byte[] oversized=c.document("{\"seed\":0,\"fracture\":["+"{},".repeat(64)+"{}]}");rejects(() -> NoiseFractures.change(path,oversized,root,CompositeNoise.Action.ADD,-1));rejects(() -> NoiseFractures.change(path,oversized,root,CompositeNoise.Action.REMOVE,0));rejects(() -> NoiseFields.enable(path,oversized,child,"seed"));rejects(() -> NoiseStyleFields.enable(path,oversized,style,"style"));check(NoiseFractures.readOnlyPaths(c.node(oversized,root)).contains("fracture"));
            check(c.node(NoiseFields.editText(path,oversized,root,"seed","1"),root).token("fracture").equals(c.node(oversized,root).token("fracture")));
            byte[] maximum=c.document("{\"fracture\":["+"{},".repeat(63)+"{}]}");rejects(() -> NoiseFractures.change(path,maximum,root,CompositeNoise.Action.ADD,-1));rejects(() -> NoiseFractures.change(path,maximum,root,CompositeNoise.Action.DUPLICATE,0));check(NoiseFractures.entries(c.node(NoiseFractures.change(path,maximum,root,CompositeNoise.Action.REMOVE,0),root)).size()==63);
            String deep="{\"seed\":7,\"fracture\":[{\"future\":true}]}";for (int level=0;level<8;level++) deep="{\"fracture\":["+deep+"]}";byte[] deepSource=c.document(deep);var deepest=root;for (int level=0;level<8;level++) deepest=deepest.child(0);var limit=deepest;
            check(c.node(NoiseFields.editText(path,deepSource,limit,"seed","9"),limit).token("seed").equals("9"));rejects(() -> NoiseFractures.change(path,deepSource,limit,CompositeNoise.Action.ADD,-1));rejects(() -> limit.child(0));check(NoiseFractures.readOnlyPaths(c.node(deepSource,root)).stream().anyMatch(p -> p.endsWith("fracture")));
            byte[] empty=c.document("{\"fracture\":[]}");check(NoiseFractures.entries(c.node(NoiseFractures.change(path,empty,root,CompositeNoise.Action.ADD,-1),root)).size()==1);rejects(() -> NoiseFractures.change(path,empty,root,CompositeNoise.Action.REMOVE,0));
            String wrongOwner=root.resourceBase().equals("generators") ? "biomes/wrong.json" : "generators/wrong.json";rejects(() -> NoiseFractures.change(wrongOwner,one,root,CompositeNoise.Action.ADD,-1));rejects(() -> NoiseFields.enable(wrongOwner,one,child,"seed"));rejects(() -> NoiseStyleFields.enable(wrongOwner,one,style,"style"));
            byte[] advanced=c.document("{\"fracture\":[{\"future\":true,\"style\":{\"fracture\":{\"style\":\"PERLIN\"},\"expression\":\"missing legacy\",\"cacheSize\":9007199254740993}}],\"unknown\":\"雪\\u0061\"}");check(NoiseFractures.readOnlyPaths(c.node(advanced,root)).containsAll(List.of("unknown","fracture[0].future","fracture[0].style.cacheSize")) && !NoiseFractures.readOnlyPaths(c.node(advanced,root)).contains("fracture[0].style.expression"));
            check(Arrays.equals(original,copy));pack.replace(path,edited);pack.save(temp.resolve("noise-fractures-"+c.id()));pack.exportZip(temp.resolve("noise-fractures-"+c.id()+".zip"));var reopened=PackWorkspace.open(temp.resolve("noise-fractures-"+c.id()));var imported=PackWorkspace.importZip(temp.resolve("noise-fractures-"+c.id()+".zip"));for (String resource : pack.paths()) check(Arrays.equals(pack.read(resource),reopened.read(resource)) && Arrays.equals(pack.read(resource),imported.read(resource)));
        }
        // Child paths are immutable, canonical and bounded independently of native optimization limits.
        var mutable=new ArrayList<>(List.of(0,1));var immutable=new NoiseFields.Target("composite",0,null,mutable);mutable.set(0,9);check(immutable.fracturePath().equals(List.of(0,1)));
        rejects(() -> new NoiseFields.Target("composite",0,null,List.of(-1)));rejects(() -> new NoiseFields.Target("composite",0,null,List.of(0,0,0,0,0,0,0,0,0)));rejects(() -> new NoiseFields.Target("heightmap",-1,null,List.of(0)));
        byte[] absent=bytes("{}");check(new LosslessJson(NoiseFractures.change("generators/cliff.json",absent,NoiseFields.Target.cliffHeight(),CompositeNoise.Action.ADD,-1)).tokenPath("cliffHeightGenerator.fracture").equals("[{}]"));
        var missing=NoiseFields.Target.heightmap(new FloatingPlacementOverrides.Context(FloatingObjectPlacements.BIOME_ROOT,"objects",0));rejects(() -> NoiseFractures.change("biomes/missing.json",bytes("{\"objects\":[{}]}"),missing,CompositeNoise.Action.ADD,-1));rejects(() -> NoiseFractures.change("generators/missing.json",absent,NoiseFields.Target.composite(0),CompositeNoise.Action.ADD,-1));
        // Removing the final list uses a literal member splice, including escaped keys and all positions.
        for (String source : List.of("{\"fracture\":[{}],\"escaped\":\"雪\\u0061\",\"big\":9007199254740993}","{\"escaped\":\"雪\\u0061\",\"fracture\":[{}],\"big\":9007199254740993}","{\"escaped\":\"雪\\u0061\",\"big\":9007199254740993,\"fracture\":[{}]}")) {
            var removed=new LosslessJson(new LosslessJson(bytes(source)).removeField("fracture"));check(removed.token("fracture")==null && removed.token("escaped").equals("\"雪\\u0061\"") && removed.token("big").equals("9007199254740993"));
        }
        check(new LosslessJson(new LosslessJson(bytes("{\"fr\\u0061cture\":[{}]}" )).removeField("fracture")).fields().isEmpty());rejects(() -> new LosslessJson(absent).removeField("fracture"));
        var root=NoiseFields.Target.composite(0);var child=root.child(0);var drafts=new InspectorDrafts();String rootSeed=root.draftPrefix()+"seed",childSeed=child.draftPrefix()+"seed",nestedSeed=child.child(0).draftPrefix()+"seed",style=child.child(0).draftPrefix()+"style.zoom",other=NoiseFields.Target.composite(1).draftPrefix()+"seed";
        for (String key : List.of(rootSeed,childSeed,nestedSeed,style,other)) drafts.update(key,"1","2");String nestedPrefix=NoiseFractures.listPrefix(child);check(!drafts.transitionUnder(nestedPrefix,() -> false,() -> { throw new AssertionError(); }) && drafts.count()==5);
        try { drafts.transitionUnder(nestedPrefix,() -> true,() -> { throw new IOException("failed write"); });throw new AssertionError(); } catch (IOException expected) { check(drafts.count()==5); }
        check(drafts.transitionUnder(nestedPrefix,() -> true,() -> {}) && drafts.count()==3 && drafts.contains(rootSeed) && drafts.contains(childSeed) && drafts.contains(other));check(drafts.transitionUnder(NoiseFractures.listPrefix(root),() -> true,() -> {}) && drafts.count()==2);
        check(FieldCoverage.fields("noise").size()==15 && FieldCoverage.fields("noise").stream().allMatch(FieldCoverage.Field::implemented));
        System.out.println("PASS: recursive noise fractures across eight owner contexts, scalar/style/source/cache routing, exact seeds/octaves, ordered lossless list lifecycle, final-child absence, depth/size/type/owner guards, scoped drafts and Save/ZIP");
    }
}
