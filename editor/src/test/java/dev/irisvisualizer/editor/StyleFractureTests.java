package dev.irisvisualizer.editor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

final class StyleFractureTests {
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static String text(byte[] value) { return new String(value,StandardCharsets.UTF_8); }
    private static void check(boolean value) { if (!value) throw new AssertionError("Style-fracture contract failed"); }
    private static void rejects(Runnable action) { try { action.run(); } catch (IllegalArgumentException expected) { return; }throw new AssertionError("Expected rejected style-fracture operation"); }
    private static LosslessJson child(NoiseStyleSourceTests.Case c,byte[] source,int depth) { var style=c.style(source);for (int level=0;level<depth;level++) style=new LosslessJson(bytes(style.token("fracture")));return style; }
    private static List<NoiseStyleSourceTests.Case> cases() {
        var result=new ArrayList<>(NoiseStyleSourceTests.cases());
        for (var root : NoiseStyleSourceTests.cases()) if (root.target().noise()!=null) {
            var noise=root.target().noise().child(0);String path=root.stylePath();result.add(new NoiseStyleSourceTests.Case(NoiseStyleFields.Target.noise(noise),path.substring(0,path.length()-"style".length())+"fracture.0.style"));
        }
        return result;
    }
    static void run(Path temp) throws Exception {
        check(cases().size()==115 && StyleFractures.MAXIMUM_DEPTH==8 && StyleFractures.multiplierDefault().equals("1"));
        var sources=PackWorkspace.newPack(Map.of("expressions/formula.json",bytes("opaque expression"),"image-maps/map.json",bytes("opaque map")));
        int ordinal=0;
        for (var c : cases()) {
            String path=c.path();var root=c.target();var target=root.child();var grandchild=target.child();
            check(target.fractureDepth()==1 && target.member().equals("fracture") && target.root().equals(root) && !target.optionalFloating() && !target.strictBounds() && NoiseStyleFields.contextDefaultLabel(target).equals("FLAT"));
            check(target.draftPrefix().equals(root.draftPrefix()+"fracture.") && grandchild.draftPrefix().equals(target.draftPrefix()+"fracture."));
            byte[] original=c.document("{\"style\":\"UNKNOWN\",\"zoom\":1e0,\"multiplier\":9e0,\"futureParent\":\"雪\\u0061\"}"),copy=original.clone();
            check(Arrays.equals(original,StyleFractures.disable(path,original,target)));
            byte[] enabled=StyleFractures.enable(path,original,target);check(child(c,enabled,1).stringToken("style").equals("FLAT") && child(c,enabled,1).fields().size()==1 && c.style(enabled).token("multiplier").equals("9e0"));
            rejects(() -> StyleFractures.enable(path,enabled,target));rejects(() -> StyleFractures.enable(path,original,grandchild));
            byte[] disabled=StyleFractures.disable(path,enabled,target);check(c.style(disabled).token("fracture").equals("null") && c.style(disabled).token("zoom").equals("1e0"));check(Arrays.equals(disabled,StyleFractures.disable(path,disabled,target)));check(child(c,StyleFractures.enable(path,disabled,target),1).stringToken("style").equals("FLAT"));
            rejects(() -> NoiseStyleSources.enableCache(path,original,target));rejects(() -> StyleFractures.enableMultiplier(path,original,target));
            for (String choice : NoiseStyleFields.choices()) check(child(c,NoiseStyleFields.editStyle(path,enabled,target,choice),1).stringToken("style").equals(choice));
            byte[] edited=NoiseStyleFields.enable(path,enabled,target,"zoom");check(child(c,edited,1).token("zoom").equals("1"));edited=NoiseStyleFields.editText(path,edited,target,"zoom","0.00001");
            edited=NoiseStyleFields.enable(path,edited,target,"cellularZoom");edited=NoiseStyleFields.editText(path,edited,target,"cellularZoom","-2");edited=NoiseStyleFields.enable(path,edited,target,"cellularFrequency");
            byte[] inactive=edited;rejects(() -> NoiseStyleFields.editText(path,inactive,target,"cellularFrequency","1"));
            edited=NoiseStyleFields.editText(path,edited,target,"cellularZoom","2");edited=NoiseStyleFields.editText(path,edited,target,"cellularFrequency","1");byte[] active=edited;rejects(() -> NoiseStyleFields.editText(path,active,target,"cellularZoom","0"));
            edited=NoiseStyleFields.enable(path,edited,target,"exponent");edited=NoiseStyleFields.editText(path,edited,target,"exponent","64");
            edited=StyleFractures.enableMultiplier(path,edited,target);byte[] multiplier=edited;rejects(() -> StyleFractures.enableMultiplier(path,multiplier,target));
            for (String good : List.of("0.00001","2.5","1.7976931348623157e308")) StyleFractures.setMultiplier(path,multiplier,target,good);
            for (String bad : List.of("0","-1","0.000001","4.9e-324","NaN","Infinity","1e999","0x1p0","")) rejects(() -> StyleFractures.setMultiplier(path,multiplier,target,bad));
            edited=StyleFractures.setMultiplier(path,edited,target,"2.5");check(child(c,edited,1).token("multiplier").equals("2.5") && c.style(edited).token("multiplier").equals("9e0"));
            edited=NoiseStyleSources.enableCache(path,edited,target);edited=NoiseStyleSources.setCache(path,edited,target,"8192");edited=NoiseStyleSources.setReference(path,edited,target,"expression","formula",sources);edited=NoiseStyleSources.setReference(path,edited,target,"imageMap","map",sources);
            check(child(c,edited,1).token("cacheSize").equals("8192") && child(c,edited,1).stringToken("expression").equals("formula"));
            edited=StyleFractures.enable(path,edited,grandchild);edited=StyleFractures.enableMultiplier(path,edited,grandchild);edited=StyleFractures.setMultiplier(path,edited,grandchild,"3");edited=NoiseStyleSources.enableCache(path,edited,grandchild);edited=NoiseStyleSources.setCache(path,edited,grandchild,"7");
            check(child(c,edited,2).token("multiplier").equals("3") && child(c,edited,2).token("cacheSize").equals("7") && child(c,edited,1).token("cacheSize").equals("8192"));
            byte[] before=edited;rejects(() -> StyleFractures.enable(path,before,root));rejects(() -> StyleFractures.enableMultiplier(path,before,root));rejects(() -> StyleFractures.setMultiplier(path,before,root,"2"));
            for (String bad : List.of("[]","true","1","\"snippet/style/x\"")) {
                byte[] malformed=c.document("{\"fracture\":"+bad+"}");rejects(() -> StyleFractures.enable(path,malformed,target));rejects(() -> StyleFractures.disable(path,malformed,target));rejects(() -> StyleFractures.enableMultiplier(path,malformed,target));rejects(() -> NoiseStyleSources.enableCache(path,malformed,target));check(StyleFractures.readOnlyPaths(c.style(malformed)).contains("fracture"));
            }
            for (String bad : List.of("null","[]","true","1","\"snippet/style/root\"")) {
                byte[] malformed=c.document(bad);rejects(() -> StyleFractures.enable(path,malformed,target));rejects(() -> StyleFractures.disable(path,malformed,target));rejects(() -> StyleFractures.enableMultiplier(path,malformed,target));
            }
            for (String bad : List.of("null","[]","{}","true","\"1\"")) {
                byte[] malformed=c.document("{\"fracture\":{\"multiplier\":"+bad+"}}");rejects(() -> StyleFractures.setMultiplier(path,malformed,target,"2"));check(StyleFractures.readOnlyPaths(c.style(malformed)).contains("fracture.multiplier"));
            }
            for (String bad : List.of("null","\"snippet/style/parent\"")) {
                byte[] missing=c.document("{\"fracture\":"+bad+"}");rejects(() -> StyleFractures.enable(path,missing,grandchild));rejects(() -> NoiseStyleFields.enable(path,missing,grandchild,"zoom"));rejects(() -> NoiseStyleSources.setReference(path,missing,grandchild,"expression",null,sources));
            }
            rejects(() -> StyleFractures.enable(path,bytes("{}"),target));rejects(() -> StyleFractures.disable(path,bytes("{}"),target));
            String owner=root.resourceBase().equals("generators") ? "biomes/wrong.json" : "generators/wrong.json";rejects(() -> StyleFractures.enable(owner,original,target));rejects(() -> StyleFractures.disable(owner,enabled,target));rejects(() -> StyleFractures.setMultiplier(owner,multiplier,target,"2"));
            String deep="{\"style\":\"FLAT\",\"fracture\":{\"futureDeep\":9007199254740993}}";for (int level=0;level<8;level++) deep="{\"fracture\":"+deep+"}";
            byte[] deepSource=c.document(deep);var deepest=root;for (int level=0;level<8;level++) deepest=deepest.child();var limit=deepest;
            byte[] deepEdited=NoiseStyleFields.enable(path,deepSource,limit,"zoom");deepEdited=NoiseStyleFields.editText(path,deepEdited,limit,"zoom","2");check(child(c,deepEdited,8).token("zoom").equals("2") && child(c,deepEdited,8).token("fracture").equals(child(c,deepSource,8).token("fracture")));rejects(() -> limit.child());
            check(StyleFractures.readOnlyPaths(c.style(deepSource)).contains("fracture.".repeat(8)+"fracture"));
            byte[] advanced=c.document("{\"fracture\":{\"style\":\"UNKNOWN\",\"multiplier\":2e0,\"futureChild\":\"雪\\u0061\",\"fracture\":{\"futureGrandchild\":true}},\"futureParent\":9007199254740993}");check(StyleFractures.readOnlyPaths(c.style(advanced)).containsAll(List.of("fracture.futureChild","fracture.fracture.futureGrandchild","futureParent")));
            check(text(StyleFractures.setMultiplier(path,advanced,target,"4")).equals(text(advanced).replace("\"multiplier\":2e0","\"multiplier\":4")));
            check(c.style(StyleFractures.disable(path,advanced,target)).token("fracture").equals("null") && c.style(StyleFractures.disable(path,advanced,target)).token("futureParent").equals("9007199254740993"));
            check(Arrays.equals(original,copy));var saved=PackWorkspace.newPack(Map.of(path,edited,"expressions/formula.json",sources.read("expressions/formula.json"),"image-maps/map.json",sources.read("image-maps/map.json"),"objects/opaque.iob",new byte[]{0,127,-1,42}));
            saved.save(temp.resolve("style-fracture-"+ordinal));saved.exportZip(temp.resolve("style-fracture-"+ordinal+".zip"));var reopened=PackWorkspace.open(temp.resolve("style-fracture-"+ordinal));var imported=PackWorkspace.importZip(temp.resolve("style-fracture-"+ordinal+".zip"));for (String resource : saved.paths()) check(Arrays.equals(saved.read(resource),reopened.read(resource)) && Arrays.equals(saved.read(resource),imported.read(resource)));ordinal++;
        }
        var root=NoiseStyleFields.Target.dimension("regionStyle");rejects(() -> new NoiseStyleFields.Target("dimensions","regionStyle",null,-1,-1));rejects(() -> new NoiseStyleFields.Target("dimensions","regionStyle",null,-1,9));
        var target=root.child();var drafts=new InspectorDrafts();String parent=root.draftPrefix()+"zoom",multiplier=target.draftPrefix()+"multiplier",cache=target.draftPrefix()+"cacheSize",nested=target.child().draftPrefix()+"zoom",other="outside-style#zoom";
        for (String key : List.of(parent,multiplier,cache,nested,other)) drafts.update(key,"1","2");drafts.applied(multiplier);check(drafts.count()==4 && drafts.contains(cache) && drafts.contains(parent));
        check(!drafts.transitionUnder(target.draftPrefix(),() -> false,() -> { throw new AssertionError(); }) && drafts.count()==4);
        try { drafts.transitionUnder(target.draftPrefix(),() -> true,() -> { throw new IOException("failed write"); });throw new AssertionError(); } catch (IOException expected) { check(drafts.count()==4); }
        check(drafts.transitionUnder(target.draftPrefix(),() -> true,() -> {}) && drafts.count()==2 && drafts.contains(parent) && drafts.contains(other));
        check(FieldCoverage.fields("noiseStyle").size()==10 && FieldCoverage.fields("noiseStyle").stream().allMatch(FieldCoverage.Field::implemented));
        System.out.println("PASS: recursive style fractures across 113 owner contexts, FLAT child defaults/generic bounds, multiplier and shared source/cache routing, strict parents/types/owners, depth preservation, scoped drafts and lossless Save/ZIP");
    }
}
