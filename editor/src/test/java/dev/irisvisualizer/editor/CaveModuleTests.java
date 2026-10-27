package dev.irisvisualizer.editor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class CaveModuleTests {
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static String text(byte[] source) { return new String(source,StandardCharsets.UTF_8); }
    private static void check(boolean condition) { if (!condition) throw new AssertionError("Cave module contract failed"); }
    private static void rejects(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Expected rejected cave module operation");
    }
    private static List<LosslessJson> modules(byte[] source) { return CaveModules.entries(CaveProfileFields.object(new LosslessJson(source))); }
    private static LosslessJson style(byte[] source,NoiseStyleFields.Target target) { return NoiseStyleFields.object(modules(source).get(target.moduleIndex()),target); }
    static void run(Path temp) throws Exception {
        check(CaveModules.minimumEntries()==1 && !CaveModules.description().isBlank());
        check(CaveModules.defaultToken("weight").equals("1") && CaveModules.defaultToken("threshold").equals("0") && CaveModules.defaultToken("invert").equals("false"));
        check(CaveModules.rangeDefault("min",true).equals("0") && CaveModules.rangeDefault("max",true).equals("384"));
        check(CaveModules.rangeDefault("min",false).equals("16") && CaveModules.rangeDefault("max",false).equals("32"));
        String first = "{\r\n \"weight\":1e0,\"threshold\":0.00,\"invert\":false,\"style\":{\"style\":\"FUTURE\\u005fSTYLE\",\"zoom\":1e0,\"exponent\":1,\"cellularFrequency\":0,\"cellularZoom\":1,\"expression\":\"unresolved\",\"fracture\":{\"style\":\"FUTURE\"}},\"verticalRange\":{\"min\":0e0,\"max\":384.00,\"future\":true},\"future\":\"雪\\u0061\"}";
        String second = "{ \"weight\":2e0,\"future\":9007199254740993 }";
        String original = "{\r\n \"caveProfile\":{\"modules\":["+first+","+second+"],\"baseWeight\":1e0},\"unknown\":9007199254740993\r\n}\r\n";
        byte[] source = bytes(original), copy = source.clone();
        var prefixes = new HashSet<String>();
        for (String context : CaveProfileFields.CONTEXTS) {
            String path = context+"/test.json";
            var target = NoiseStyleFields.Target.module(context,0);
            check(target.module() && target.moduleIndex()==0 && target.cave() && !target.threshold() && !target.terrain() && target.member().equals("style"));
            check(NoiseStyleFields.contextDefault(target).equals("CELLULAR_IRIS_DOUBLE") && !NoiseStyleFields.contextDescription(target).isBlank());
            check(prefixes.add(CaveModules.draftPrefix(context,0)) && prefixes.add(CaveModules.draftPrefix(context,1)));
            check(!CaveModules.draftPrefix(context,10).startsWith(CaveModules.draftPrefix(context,1)));
            byte[] added = CaveModules.change(path,bytes("{\"caveProfile\":{\"future\":true}}"),CaveModules.Action.ADD,-1);
            check(modules(added).size()==1 && modules(added).getFirst().fields().isEmpty());
            check(new LosslessJson(added).tokenPath("caveProfile.enabled")==null && new LosslessJson(added).tokenPath("caveProfile.future").equals("true"));
            rejects(() -> CaveModules.change(path,added,CaveModules.Action.REMOVE,0));
            byte[] appended = CaveModules.change(path,source,CaveModules.Action.ADD,-1);
            check(modules(appended).size()==3 && text(modules(appended).get(0).sourceBytes()).equals(first) && text(modules(appended).get(1).sourceBytes()).equals(second) && modules(appended).get(2).fields().isEmpty());
            byte[] duplicated = CaveModules.change(path,source,CaveModules.Action.DUPLICATE,0);
            check(modules(duplicated).size()==3 && Arrays.equals(modules(duplicated).get(0).sourceBytes(),modules(duplicated).get(1).sourceBytes()) && text(modules(duplicated).get(2).sourceBytes()).equals(second));
            byte[] moved = CaveModules.change(path,source,CaveModules.Action.DOWN,0);
            check(text(modules(moved).get(0).sourceBytes()).equals(second) && text(modules(moved).get(1).sourceBytes()).equals(first));
            check(Arrays.equals(CaveModules.change(path,moved,CaveModules.Action.UP,1),source));
            byte[] removed = CaveModules.change(path,source,CaveModules.Action.REMOVE,0);
            check(modules(removed).size()==1 && text(modules(removed).getFirst().sourceBytes()).equals(second));
            for (byte[] changed : List.of(appended,duplicated,moved,removed)) {
                check(new LosslessJson(changed).tokenPath("caveProfile.baseWeight").equals("1e0") && new LosslessJson(changed).token("unknown").equals("9007199254740993"));
            }
            rejects(() -> CaveModules.change(path,source,CaveModules.Action.UP,0));
            rejects(() -> CaveModules.change(path,source,CaveModules.Action.DOWN,1));
            rejects(() -> CaveModules.change(path,source,CaveModules.Action.REMOVE,2));
            rejects(() -> CaveModules.change(path,source,null,0));
            byte[] emptyList = bytes("{\"caveProfile\":{\"modules\":[]}}");
            check(modules(CaveModules.change(path,emptyList,CaveModules.Action.ADD,-1)).getFirst().fields().isEmpty());
            rejects(() -> CaveModules.change(path,emptyList,CaveModules.Action.REMOVE,0));
            for (String field : CaveModules.EDITABLE) {
                check(!CaveModules.help(field).isBlank());
                byte[] enabled = CaveModules.enableScalar(path,added,0,field);
                check(modules(enabled).getFirst().fields().equals(Set.of(field)) && modules(enabled).getFirst().token(field).equals(CaveModules.defaultToken(field)));
                rejects(() -> CaveModules.enableScalar(path,enabled,0,field));
            }
            check(text(CaveModules.editText(path,source,0,"weight","1.5")).equals(original.replace("\"weight\":1e0","\"weight\":1.5")));
            check(text(CaveModules.editText(path,source,0,"threshold","-0.5")).equals(original.replace("\"threshold\":0.00","\"threshold\":-0.5")));
            check(text(CaveModules.editBoolean(path,source,0,"invert",true)).equals(original.replace("\"invert\":false","\"invert\":true")));
            check(modules(CaveModules.editBoolean(path,added,0,"invert",true)).getFirst().fields().equals(Set.of("invert")));
            CaveModules.editText(path,source,0,"weight",Double.toString(Double.MAX_VALUE));
            CaveModules.editText(path,source,0,"threshold","-1"); CaveModules.editText(path,source,0,"threshold","1");
            rejects(() -> CaveModules.editText(path,source,0,"weight","-0.01"));
            rejects(() -> CaveModules.editText(path,source,0,"threshold","-1.01"));
            rejects(() -> CaveModules.editText(path,source,0,"threshold","1.01"));
            for (String invalid : List.of("NaN","Infinity","1e999","\"1\"")) rejects(() -> CaveModules.editText(path,source,0,"weight",invalid));
            rejects(() -> CaveModules.editText(path,added,0,"weight","2"));
            rejects(() -> CaveModules.editText(path,source,0,"invert","1"));
            rejects(() -> CaveModules.editBoolean(path,source,0,"weight",true));
            rejects(() -> CaveModules.enableScalar(path,source,0,"style"));
            byte[] wrongScalars = bytes("{\"caveProfile\":{\"modules\":[{\"weight\":\"1\",\"invert\":null}]}}");
            rejects(() -> CaveModules.editText(path,wrongScalars,0,"weight","2"));
            rejects(() -> CaveModules.editBoolean(path,wrongScalars,0,"invert",true));
            byte[] rangeEnabled = CaveModules.enableRange(path,added,0);
            var range = CaveModules.range(modules(rangeEnabled).getFirst());
            check(range.token("min").equals("0") && range.token("max").equals("384") && modules(rangeEnabled).getFirst().fields().equals(Set.of("verticalRange")));
            rejects(() -> CaveModules.enableRange(path,rangeEnabled,0));
            rejects(() -> CaveModules.editRange(path,added,0,"0","384"));
            check(text(CaveModules.editRange(path,source,0,"0","400")).equals(original.replace("\"max\":384.00","\"max\":400")));
            check(Arrays.equals(CaveModules.editRange(path,source,0,"0","384"),source));
            CaveModules.editRange(path,source,0,"-0.5","-0.5");
            rejects(() -> CaveModules.editRange(path,source,0,"385","384"));
            rejects(() -> CaveModules.editRange(path,source,0,"NaN","384"));
            rejects(() -> CaveModules.editRange(path,source,0,"0","Infinity"));
            byte[] emptyRange = bytes("{\"caveProfile\":{\"modules\":[{\"verticalRange\":{}}]}}");
            var filled = CaveModules.range(modules(CaveModules.editRange(path,emptyRange,0,"16","32")).getFirst());
            check(filled.token("min").equals("16") && filled.token("max").equals("32"));
            byte[] partialRange = bytes("{\"caveProfile\":{\"modules\":[{\"verticalRange\":{\"min\":1e0,\"future\":true},\"style\":\"snippet\"}]}}");
            byte[] rangeRepaired = CaveModules.editRange(path,partialRange,0,"1","32");
            check(CaveModules.range(modules(rangeRepaired).getFirst()).token("min").equals("1e0") && modules(rangeRepaired).getFirst().token("style").equals("\"snippet\""));
            byte[] badBound = bytes("{\"caveProfile\":{\"modules\":[{\"verticalRange\":{\"min\":\"0\",\"max\":384}}]}}");
            rejects(() -> CaveModules.editRange(path,badBound,0,"0","384"));
            check(style(NoiseStyleFields.enable(path,badBound,target,"style"),target).stringToken("style").equals("CELLULAR_IRIS_DOUBLE"));
            for (String choice : NoiseStyleFields.choices()) check(text(NoiseStyleFields.editStyle(path,source,target,choice)).equals(original.replace("FUTURE\\u005fSTYLE",choice)));
            check(text(NoiseStyleFields.editText(path,source,target,"zoom","2.5")).equals(original.replace("\"zoom\":1e0","\"zoom\":2.5")));
            NoiseStyleFields.editText(path,source,target,"cellularZoom","-1");
            rejects(() -> NoiseStyleFields.editText(path,source,target,"zoom","0"));
            rejects(() -> NoiseStyleFields.editStyle(path,source,target,"unknown"));
            for (String field : NoiseStyleFields.EDITABLE) {
                byte[] enabled = NoiseStyleFields.enable(path,added,target,field);
                var expected = new HashSet<String>(); expected.add("style"); expected.add(field);
                check(modules(enabled).getFirst().fields().equals(Set.of("style")) && style(enabled,target).fields().equals(expected));
                check(style(enabled,target).stringToken("style").equals("CELLULAR_IRIS_DOUBLE"));
                if (field.equals("zoom")) check(style(enabled,target).token("zoom").equals("1"));
            }
            byte[] emptyStyle = bytes("{\"caveProfile\":{\"modules\":[{\"style\":{}}]}}");
            check(style(NoiseStyleFields.enable(path,emptyStyle,target,"style"),target).stringToken("style").equals("FLAT"));
            var secondTarget = NoiseStyleFields.Target.module(context,1);
            byte[] secondEnabled = NoiseStyleFields.enable(path,source,secondTarget,"style");
            check(text(modules(secondEnabled).getFirst().sourceBytes()).equals(first) && style(secondEnabled,secondTarget).stringToken("style").equals("CELLULAR_IRIS_DOUBLE"));
            for (String bad : List.of("null","[]","1","true","\"snippet\"")) {
                byte[] wrongStyle = bytes("{\"caveProfile\":{\"modules\":[{\"style\":"+bad+"}]}}");
                rejects(() -> NoiseStyleFields.enable(path,wrongStyle,target,"style"));
                byte[] wrongRange = bytes("{\"caveProfile\":{\"modules\":[{\"verticalRange\":"+bad+"}]}}");
                rejects(() -> CaveModules.enableRange(path,wrongRange,0));
                rejects(() -> CaveModules.editRange(path,wrongRange,0,"0","384"));
            }
            for (String badList : List.of("null","{}","1","\"snippet\"","[{},\"snippet\"]","[{},null]")) {
                byte[] wrong = bytes("{\"caveProfile\":{\"modules\":"+badList+"}}");
                for (CaveModules.Action action : CaveModules.Action.values()) rejects(() -> CaveModules.change(path,wrong,action,0));
                rejects(() -> CaveModules.enableScalar(path,wrong,0,"weight"));
                rejects(() -> CaveModules.enableRange(path,wrong,0));
                rejects(() -> NoiseStyleFields.enable(path,wrong,target,"style"));
            }
            for (String missing : List.of("{}","{\"caveProfile\":null}","{\"caveProfile\":\"snippet\"}")) {
                rejects(() -> CaveModules.change(path,bytes(missing),CaveModules.Action.ADD,-1));
                rejects(() -> CaveModules.enableScalar(path,bytes(missing),0,"weight"));
                rejects(() -> NoiseStyleFields.enable(path,bytes(missing),target,"style"));
            }
            byte[] missingList = bytes("{\"caveProfile\":{}}");
            rejects(() -> CaveModules.enableScalar(path,missingList,0,"weight"));
            rejects(() -> NoiseStyleFields.enable(path,missingList,target,"style"));
            for (int badIndex : List.of(-1,2,Integer.MAX_VALUE)) {
                rejects(() -> CaveModules.enableScalar(path,source,badIndex,"weight"));
                rejects(() -> CaveModules.enableRange(path,source,badIndex));
                if (badIndex>=0) rejects(() -> NoiseStyleFields.enable(path,source,NoiseStyleFields.Target.module(context,badIndex),"style"));
            }
        }
        check(prefixes.size()==6 && Arrays.equals(source,copy));
        for (String path : List.of("generators/test.json","dimensions/nested/test.json","test.json")) {
            rejects(() -> CaveModules.change(path,source,CaveModules.Action.ADD,-1));
            rejects(() -> CaveModules.enableScalar(path,source,0,"weight"));
            rejects(() -> CaveModules.editRange(path,source,0,"0","384"));
        }
        rejects(() -> NoiseStyleFields.Target.module("generators",0));
        rejects(() -> NoiseStyleFields.Target.module("biomes",-1));
        rejects(() -> new NoiseStyleFields.Target("biomes","terrain3D.densityStyle",null,0));
        rejects(() -> new NoiseStyleFields.Target("biomes","caveProfile.modules.style",null,-1));
        rejects(() -> new NoiseStyleFields.Target("biomes","caveProfile.modules.style.fracture",null,0));
        rejects(() -> CaveModules.defaultToken("modules"));
        var paths = DimensionFields.readOnlyPaths(new LosslessJson(source));
        check(paths.equals(List.of("caveProfile.modules[0].style.fracture","caveProfile.modules[0].verticalRange.future","caveProfile.modules[0].future","caveProfile.modules[1].future","unknown")));
        var drafts = new InspectorDrafts();
        String scope = CaveModules.draftPrefix("biomes");
        drafts.update(CaveModules.draftPrefix("biomes",0)+"weight","1","2");
        drafts.update(CaveModules.draftPrefix("biomes",1)+"verticalRange.min","0","1");
        drafts.update(NoiseStyleFields.Target.module("biomes",1).draftPrefix()+"zoom","1","2");
        drafts.update(CaveThresholdFields.draftPrefix("biomes")+"min","0","1");
        drafts.update(CaveModules.draftPrefix("regions",0)+"weight","1","2");
        check(!drafts.transitionUnder(scope,() -> false,() -> { throw new AssertionError("Cancelled module transition ran"); }) && drafts.count()==5);
        try { drafts.transitionUnder(scope,() -> true,() -> { throw new IOException("Simulated failed module transition"); }); throw new AssertionError("Expected failed transition"); }
        catch (IOException expected) { check(drafts.count()==5); }
        byte[][] applied = {source};
        drafts.transitionUnder(scope,() -> true,() -> applied[0]=CaveModules.change("biomes/test.json",source,CaveModules.Action.DOWN,0));
        check(drafts.count()==2 && drafts.contains(CaveThresholdFields.draftPrefix("biomes")+"min") && drafts.contains(CaveModules.draftPrefix("regions",0)+"weight") && text(modules(applied[0]).getFirst().sourceBytes()).equals(second));
        check(FieldCoverage.fields("caveModule").size()==5 && FieldCoverage.fields("caveModule").stream().allMatch(FieldCoverage.Field::implemented));
        check(FieldCoverage.fields("range").size()==2 && FieldCoverage.fields("range").stream().allMatch(FieldCoverage.Field::implemented));
        var workspace = StarterPack.create("cave_modules");
        for (String path : List.of("dimensions/cave_modules.json","regions/starter.json","biomes/starter.json")) {
            byte[] changed = CaveModules.change(path,source,CaveModules.Action.DUPLICATE,0);
            changed = CaveModules.editText(path,changed,1,"weight","2.5");
            changed = NoiseStyleFields.editStyle(path,changed,NoiseStyleFields.Target.module(ResourceTypes.base(path),1),"SIMPLEX");
            changed = CaveModules.editRange(path,changed,1,"-8.5","320");
            workspace.replace(path,changed);
        }
        workspace.save(temp.resolve("cave-modules")); workspace.exportZip(temp.resolve("cave-modules.zip"));
        var reopened = PackWorkspace.open(temp.resolve("cave-modules")); var imported = PackWorkspace.importZip(temp.resolve("cave-modules.zip"));
        for (String path : workspace.paths()) {
            check(Arrays.equals(workspace.read(path),reopened.read(path)));
            check(Arrays.equals(workspace.read(path),imported.read(path)));
        }
        workspace.replace("biomes/starter.json",bytes("{\"caveProfile\":{\"modules\":[{},\"unresolved-snippet\"]}}"));
        workspace.save(temp.resolve("cave-modules"));
        check(PackWorkspace.open(temp.resolve("cave-modules")).read("biomes/starter.json").length>0);
        System.out.println("PASS: cave module lists/defaults, scalar/style/range edits, lossless structure changes, parent/owner/draft guards and Save/ZIP contracts");
    }
}
