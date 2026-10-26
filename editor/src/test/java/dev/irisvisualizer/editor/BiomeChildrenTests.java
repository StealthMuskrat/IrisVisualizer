package dev.irisvisualizer.editor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class BiomeChildrenTests {
    private static final String PATH = "biomes/parent.json";
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static String text(byte[] bytes) { return new String(bytes,StandardCharsets.UTF_8); }
    private static void check(boolean value) { if (!value) throw new AssertionError("Child biome contract failed"); }
    private static void rejects(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Expected rejected child biome operation");
    }
    static void run(Path temp) throws Exception {
        var workspace = PackWorkspace.newPack(Map.ofEntries(
                Map.entry(PATH,bytes("{\"generators\":[{\"generator\":\"flat\"}],\"children\":[\"nested/child\"]}")),
                Map.entry("biomes/nested/child.json",bytes("{\"children\":[\"parent\"]}")),
                Map.entry("biomes/not_json.json",bytes("invalid target content")),
                Map.entry("biomes/.json",bytes("{}")), Map.entry("biomes/..json",bytes("{}")),
                Map.entry("regions/.json",bytes("{}")), Map.entry("generators/flat.json",bytes("{}")),
                Map.entry("dimensions/top.json",bytes("{}")), Map.entry("dimensions/nested/ignored.json",bytes("{}"))));
        check(BiomeChildren.choices(workspace).equals(List.of("nested/child","not_json","parent")));
        check(RegionFields.biomeKeys(workspace).equals(BiomeChildren.choices(workspace)));
        check(ResourceCreation.choices(workspace,"regions").equals(BiomeChildren.choices(workspace)));
        check(ResourceCreation.choices(workspace,"biomes").equals(List.of("flat")));
        check(DimensionFields.regionKeys(workspace).isEmpty());
        check(ResourceTypes.referenceKeys(workspace,"dimensions").equals(List.of("top")));
        rejects(() -> ResourceTypes.referenceKeys(workspace,"images"));
        check(!BiomeChildren.required());
        byte[] enabled = BiomeChildren.enable(PATH,bytes("{\r\n \"unknown\":\"雪\"\r\n}\r\n"));
        check(new LosslessJson(enabled).fields().equals(Set.of("children","unknown")));
        check(new LosslessJson(enabled).stringArrayPath("children").isEmpty());
        rejects(() -> BiomeChildren.enable(PATH,enabled));
        rejects(() -> BiomeChildren.edit(PATH,bytes("{}"),0,"parent",workspace));
        String first="\"nested\\u002fchild\"",second="\"unresolved\"",third="\"parent\"";
        String prefix="{\r\n \"children\":[\r\n  ",gap=",\r\n  ",suffix="\r\n ],\"unknown\":{\"雪\":9007199254740993}\r\n}\r\n";
        byte[] original=bytes(prefix+first+gap+second+gap+third+suffix),copy=original.clone();
        byte[] assigned=BiomeChildren.edit(PATH,original,1,"not_json",workspace);
        check(text(assigned).equals(prefix+first+gap+"\"not_json\""+gap+third+suffix));
        // Self and cyclic links are allowed by the pinned Iris description; no traversal occurs here.
        BiomeChildren.edit(PATH,original,0,"parent",workspace);
        BiomeChildren.edit("biomes/nested/child.json",original,0,"parent",workspace);
        byte[] duplicate=BiomeChildren.change(PATH,original,0,BiomeChildren.Action.DUPLICATE);
        check(text(duplicate).equals(prefix+first+gap+first+", "+second+gap+third+suffix));
        check(Arrays.equals(BiomeChildren.edit(PATH,duplicate,1,null,workspace),original));
        byte[] moved=BiomeChildren.change(PATH,original,0,BiomeChildren.Action.DOWN);
        check(text(moved).equals(prefix+second+gap+first+gap+third+suffix));
        check(Arrays.equals(BiomeChildren.change(PATH,moved,1,BiomeChildren.Action.UP),original));
        check(text(BiomeChildren.edit(PATH,original,0,null,workspace)).equals(prefix+second+gap+third+suffix));
        check(text(BiomeChildren.edit(PATH,original,1,null,workspace)).equals(prefix+first+gap+third+suffix));
        check(text(BiomeChildren.edit(PATH,original,2,null,workspace)).equals(prefix+first+gap+second+suffix));
        byte[] only=bytes("{\"children\":[\"parent\"],\"unknown\":true}");
        check(text(BiomeChildren.edit(PATH,only,0,null,workspace)).equals("{\"children\":[],\"unknown\":true}"));
        check(new LosslessJson(BiomeChildren.edit(PATH,enabled,0,"nested/child",workspace)).stringArrayPath("children").equals(List.of("nested/child")));
        for(String key:List.of(""," ",".","..","../parent","missing","parent.json","biomes/parent","namespace:alias","nested\\child"))
            rejects(() -> BiomeChildren.edit(PATH,original,0,key,workspace));
        for(String bad:List.of("null","{}","\"snippet\"","[\"parent\",null]","[{},\"parent\"]")) {
            byte[] wrong=bytes("{\"children\":"+bad+"}");
            rejects(() -> BiomeChildren.edit(PATH,wrong,0,"parent",workspace));
            rejects(() -> BiomeChildren.change(PATH,wrong,0,BiomeChildren.Action.DUPLICATE));
        }
        rejects(() -> BiomeChildren.change(PATH,original,0,BiomeChildren.Action.UP));
        rejects(() -> BiomeChildren.change(PATH,original,2,BiomeChildren.Action.DOWN));
        rejects(() -> BiomeChildren.change(PATH,original,3,BiomeChildren.Action.DUPLICATE));
        rejects(() -> BiomeChildren.edit(PATH,original,-1,null,workspace));
        rejects(() -> BiomeChildren.enable("regions/test.json",bytes("{}")));
        check(Arrays.equals(original,copy));
        check(ReferenceDiagnostics.inspect(workspace,PATH).stream().allMatch(link -> link.state()==ReferenceDiagnostics.State.EXACT_FILE));
        var cycle=ReferenceDiagnostics.inspect(workspace,"biomes/nested/child.json");
        check(cycle.size()==2 && cycle.get(0).state()==ReferenceDiagnostics.State.UNSUPPORTED && cycle.get(1).target().equals(PATH));
        workspace.replace(PATH,bytes("{\"generators\":[\"snippet\"],\"children\":[\"nested/child\",\" \" ,\"missing\"]}"));
        var findings=ReferenceDiagnostics.inspect(workspace,PATH);
        check(findings.size()==4 && findings.get(0).state()==ReferenceDiagnostics.State.UNSUPPORTED);
        check(findings.get(1).state()==ReferenceDiagnostics.State.EXACT_FILE && findings.get(2).state()==ReferenceDiagnostics.State.INVALID_KEY && findings.get(3).state()==ReferenceDiagnostics.State.NO_EXACT_FILE);
        var drafts=new InspectorDrafts();
        drafts.update("children#0","parent","nested/child"); drafts.update("children#new","","parent"); drafts.update("rootStyle#biomes.childStyle.zoom","1","2");
        check(!drafts.transitionUnder("children#",()->false,()->{throw new AssertionError("Cancelled mutation ran");}));
        check(drafts.count()==3);
        try {drafts.transitionUnder("children#",()->true,()->{throw new java.io.IOException("Failed write");});}
        catch(java.io.IOException expected){check(drafts.count()==3);}
        drafts.transitionUnder("children#",()->true,()->{});
        check(drafts.count()==1 && drafts.contains("rootStyle#biomes.childStyle.zoom"));
        workspace.replace(PATH,assigned);
        workspace.save(temp.resolve("child-biome-links")); workspace.exportZip(temp.resolve("child-biome-links.zip"));
        check(Arrays.equals(PackWorkspace.open(temp.resolve("child-biome-links")).read(PATH),assigned));
        check(Arrays.equals(PackWorkspace.importZip(temp.resolve("child-biome-links.zip")).read(PATH),assigned));
        workspace.replace(PATH,bytes("{\"children\":[\"unknown\",null]}"));
        workspace.save(temp.resolve("child-biome-links"));
        check(new LosslessJson(PackWorkspace.open(temp.resolve("child-biome-links")).read(PATH)).token("children").equals("[\"unknown\",null]"));
    }
}
