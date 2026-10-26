package dev.irisvisualizer.editor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class BiomeGeneratorTests {
    private static final String PATH = "biomes/nested/test.json";
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static String text(byte[] bytes) { return new String(bytes,StandardCharsets.UTF_8); }
    private static void check(boolean value) { if (!value) throw new AssertionError("Biome generator contract failed"); }
    private static void rejects(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Expected rejected biome generator operation");
    }
    static void run(Path temp) throws Exception {
        var workspace = PackWorkspace.newPack(Map.of("generators/flat.json",bytes("{}"),"generators/nested/hills.json",bytes("invalid target content"),"generators/.json",bytes("{}"),"generators/..json",bytes("{}"),"biomes/excluded.json",bytes("{}"),"other/generators/fake.json",bytes("{}")));
        check(BiomeGeneratorLinks.choices(workspace).equals(List.of("flat","nested/hills")));
        check(BiomeGeneratorLinks.defaultGenerator().equals("default") && BiomeGeneratorLinks.minimumEntries()==1);
        byte[] absent = bytes("{\r\n \"unknown\":\"雪\"\r\n}\r\n");
        byte[] enabled = BiomeGeneratorLinks.enable(PATH,absent);
        var defaultLink = new LosslessJson(enabled).objectArray("generators").getFirst();
        check(defaultLink.fields().equals(Set.of("generator","min","max")));
        check(defaultLink.stringToken("generator").equals("default") && defaultLink.token("min").equals("0") && defaultLink.token("max").equals("0"));
        check(new LosslessJson(enabled).token("unknown").equals("\"雪\""));
        check(text(absent).equals("{\r\n \"unknown\":\"雪\"\r\n}\r\n"));
        rejects(() -> BiomeGeneratorLinks.enable(PATH,enabled));
        rejects(() -> BiomeGeneratorLinks.add(PATH,absent,workspace,"flat",0,10));

        String first = "{\"generator\":\"unknown\\u005fkey\",\"min\":-2e1,\"max\":4e1,\"future\":{\"雪\":[true,null]},\"seed\":9007199254740993}";
        String second = "{ \"generator\":\"flat\",\"min\":1,\"max\":2 }";
        String prefix = "{\r\n \"generators\":[\r\n  ", gap = ",\r\n  ", suffix = "\r\n ],\"unknown\":\"雪\"\r\n}\r\n";
        byte[] original = bytes(prefix+first+gap+second+suffix), copy=original.clone();
        byte[] assigned = BiomeGeneratorLinks.assign(PATH,original,0,workspace,"nested/hills");
        check(text(assigned).equals(prefix+first.replace("unknown\\u005fkey","nested/hills")+gap+second+suffix));
        byte[] duplicate = BiomeGeneratorLinks.change(PATH,original,0,BiomeGeneratorLinks.Action.DUPLICATE);
        check(text(duplicate).equals(prefix+first+gap+first+", "+second+suffix));
        check(Arrays.equals(BiomeGeneratorLinks.change(PATH,duplicate,1,BiomeGeneratorLinks.Action.REMOVE),original));
        byte[] moved = BiomeGeneratorLinks.change(PATH,original,0,BiomeGeneratorLinks.Action.DOWN);
        check(text(moved).equals(prefix+second+gap+first+suffix));
        check(Arrays.equals(BiomeGeneratorLinks.change(PATH,moved,1,BiomeGeneratorLinks.Action.UP),original));
        check(text(BiomeGeneratorLinks.change(PATH,original,0,BiomeGeneratorLinks.Action.REMOVE)).equals(prefix+second+suffix));
        check(text(BiomeGeneratorLinks.change(PATH,original,1,BiomeGeneratorLinks.Action.REMOVE)).equals(prefix+first+suffix));
        byte[] range = BiomeGeneratorLinks.range(PATH,original,0,-30,80);
        check(text(range).equals(prefix+first.replace("-2e1","-30").replace("4e1","80")+gap+second+suffix));
        byte[] added = BiomeGeneratorLinks.add(PATH,original,workspace,"nested/hills",-2032,2032);
        var newLink = new LosslessJson(added).objectArray("generators").getLast();
        check(newLink.fields().equals(Set.of("generator","min","max")) && newLink.stringToken("generator").equals("nested/hills"));
        check(newLink.token("min").equals("-2032") && newLink.token("max").equals("2032"));
        check(text(added).startsWith(prefix+first+gap+second+", "));
        check(Arrays.equals(original,copy));
        byte[] missingMembers = bytes("{\"generators\":[{\"unknown\":\"雪\"}]}");
        byte[] linked = BiomeGeneratorLinks.assign(PATH,missingMembers,0,workspace,"flat");
        check(new LosslessJson(linked).objectArray("generators").getFirst().fields().equals(Set.of("generator","unknown")));
        byte[] ranged = BiomeGeneratorLinks.range(PATH,linked,0,10,20);
        var repaired = new LosslessJson(ranged).objectArray("generators").getFirst();
        check(repaired.token("min").equals("10") && repaired.token("max").equals("20") && repaired.token("unknown").equals("\"雪\""));
        check(new LosslessJson(BiomeGeneratorLinks.add(PATH,bytes("{\"generators\":[]}"),workspace,"flat",0,0)).objectArray("generators").size()==1);
        rejects(() -> BiomeGeneratorLinks.change(PATH,enabled,0,BiomeGeneratorLinks.Action.REMOVE));
        rejects(() -> BiomeGeneratorLinks.change(PATH,original,0,BiomeGeneratorLinks.Action.UP));
        rejects(() -> BiomeGeneratorLinks.change(PATH,original,1,BiomeGeneratorLinks.Action.DOWN));
        rejects(() -> BiomeGeneratorLinks.change(PATH,original,2,BiomeGeneratorLinks.Action.DUPLICATE));
        for (String key : List.of("", ".", "..", "missing", "../flat", "generators/flat", "flat.json", "FLAT")) {
            rejects(() -> BiomeGeneratorLinks.assign(PATH,original,0,workspace,key));
            rejects(() -> BiomeGeneratorLinks.add(PATH,original,workspace,key,0,10));
        }
        for (double[] values : List.of(new double[]{1,0},new double[]{-2033,1},new double[]{0,2033},new double[]{1.5,2},new double[]{0,Double.NaN})) {
            rejects(() -> BiomeGeneratorLinks.add(PATH,original,workspace,"flat",values[0],values[1]));
            rejects(() -> BiomeGeneratorLinks.range(PATH,original,0,values[0],values[1]));
        }
        for (String invalid : List.of("null","{}","\"snippet\"","[{},\"snippet\"]","[{},null]")) {
            byte[] bad = bytes("{\"generators\":"+invalid+"}");
            rejects(() -> BiomeGeneratorLinks.add(PATH,bad,workspace,"flat",0,0));
            rejects(() -> BiomeGeneratorLinks.assign(PATH,bad,0,workspace,"flat"));
            rejects(() -> BiomeGeneratorLinks.range(PATH,bad,0,0,0));
            rejects(() -> BiomeGeneratorLinks.change(PATH,bad,0,BiomeGeneratorLinks.Action.DUPLICATE));
        }
        rejects(() -> BiomeGeneratorLinks.assign(PATH,bytes("{\"generators\":[{\"generator\":null}]}"),0,workspace,"flat"));
        rejects(() -> BiomeGeneratorLinks.range(PATH,bytes("{\"generators\":[{\"min\":\"0\"}]}"),0,0,10));
        rejects(() -> BiomeGeneratorLinks.enable("regions/a.json",absent));
        rejects(() -> BiomeGeneratorLinks.assign("generators/a.json",original,0,workspace,"flat"));
        var drafts = new InspectorDrafts();
        drafts.update("generator#0.min","0","1"); drafts.update("generator#new.ref","","flat"); drafts.update("biome#rarity","1","2");
        check(!drafts.transitionUnder("generator#",()->false,()->{throw new AssertionError("Cancelled write ran");}));
        check(drafts.count()==3);
        try { drafts.transitionUnder("generator#",()->true,()->{throw new java.io.IOException("Failed write");}); }
        catch (java.io.IOException expected) {check(drafts.count()==3);}
        drafts.transitionUnder("generator#",()->true,()->{});
        check(drafts.count()==1 && drafts.contains("biome#rarity"));
        var pack = StarterPack.create("generator_links");
        pack.replace("biomes/starter.json",assigned);
        pack.save(temp.resolve("biome-generator-links")); pack.exportZip(temp.resolve("biome-generator-links.zip"));
        check(Arrays.equals(PackWorkspace.open(temp.resolve("biome-generator-links")).read("biomes/starter.json"),assigned));
        check(Arrays.equals(PackWorkspace.importZip(temp.resolve("biome-generator-links.zip")).read("biomes/starter.json"),assigned));
        check(ReferenceDiagnostics.inspect(pack,"biomes/starter.json").stream().anyMatch(link -> link.key().equals("nested/hills") && link.state()==ReferenceDiagnostics.State.NO_EXACT_FILE));
        pack.replace("biomes/starter.json",bytes("{\"generators\":[\"snippet\"]}"));
        pack.save(temp.resolve("biome-generator-links"));
        check(new LosslessJson(PackWorkspace.open(temp.resolve("biome-generator-links")).read("biomes/starter.json")).token("generators").equals("[\"snippet\"]"));
    }
}
