package dev.irisvisualizer.editor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

final class BiomeOreHostTests {
    private static final String PATH="biomes/nested/hosts.json",FIELD="surfaceOreReplaceableBlocks";
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static String text(byte[] value) { return new String(value,StandardCharsets.UTF_8); }
    private static void check(boolean condition) { if (!condition) throw new AssertionError("Biome ore host contract failed"); }
    private static void rejects(Runnable action) { try { action.run(); } catch (IllegalArgumentException expected) { return; }throw new AssertionError("Expected rejected host operation"); }
    static void run(Path temp) throws Exception {
        byte[] absent=bytes("{\"future\":9007199254740993}"),storedNull=bytes("{\"surfaceOreReplaceableBlocks\":null,\"future\":1e2}");
        check(BiomeOreHosts.entries(new LosslessJson(absent))==null && BiomeOreHosts.entries(new LosslessJson(storedNull))==null);
        check(Arrays.equals(absent,BiomeOreHosts.inherit(PATH,absent)) && Arrays.equals(storedNull,BiomeOreHosts.inherit(PATH,storedNull)));
        for (byte[] source : List.of(absent,storedNull)) check(BiomeOreHosts.entries(new LosslessJson(BiomeOreHosts.restrict(PATH,source))).isEmpty());
        byte[] empty=BiomeOreHosts.restrict(PATH,absent);byte[] one=BiomeOreHosts.change(PATH,empty,RiverProfiles.Action.ADD,-1,"minecraft:stone");
        check(new LosslessJson(BiomeOreHosts.inherit(PATH,one)).token(FIELD).equals("null"));check(BiomeOreHosts.entries(new LosslessJson(BiomeOreHosts.restrict(PATH,one))).isEmpty());
        byte[] removed=BiomeOreHosts.change(PATH,one,RiverProfiles.Action.REMOVE,0,null);check(new LosslessJson(removed).token(FIELD).equals("[]") && BiomeOreHosts.entries(new LosslessJson(removed)).isEmpty());
        byte[] original=bytes("{\r\n \"surfaceOreReplaceableBlocks\":[ \"legacy\\u0061\",\"Mixed:Old Key\" ],\"depositVariants\":[{\"future\":true}],\"future\":9007199254740993,\"escaped\":\"雪\\u0061\"\r\n}\r\n");
        check(text(BiomeOreHosts.set(PATH,original,0,"minecraft:stone")).equals(text(original).replace("\"legacy\\u0061\"","\"minecraft:stone\"")));
        byte[] down=BiomeOreHosts.change(PATH,original,RiverProfiles.Action.DOWN,0,null);check(Arrays.equals(original,BiomeOreHosts.change(PATH,down,RiverProfiles.Action.UP,1,null)));
        check(BiomeOreHosts.entries(new LosslessJson(BiomeOreHosts.change(PATH,original,RiverProfiles.Action.DUPLICATE,0,null))).equals(List.of("legacya","legacya","Mixed:Old Key")));
        for (String bad : List.of(""," ","Minecraft:stone","minecraft:stone[axis=x]")) { rejects(() -> BiomeOreHosts.set(PATH,original,0,bad));rejects(() -> BiomeOreHosts.change(PATH,empty,RiverProfiles.Action.ADD,-1,bad)); }
        rejects(() -> BiomeOreHosts.set(PATH,original,2,"stone"));rejects(() -> BiomeOreHosts.change(PATH,storedNull,RiverProfiles.Action.ADD,-1,"stone"));
        for (String bad : List.of("true","{}","[null]","[\"stone\",1]","\"snippet\"")) {
            byte[] source=bytes("{\"surfaceOreReplaceableBlocks\":"+bad+"}");rejects(() -> BiomeOreHosts.entries(new LosslessJson(source)));rejects(() -> BiomeOreHosts.inherit(PATH,source));rejects(() -> BiomeOreHosts.restrict(PATH,source));rejects(() -> BiomeOreHosts.change(PATH,source,RiverProfiles.Action.ADD,-1,"stone"));
        }
        for (String path : List.of("regions/hosts.json","dimensions/hosts.json","generators/hosts.json","biomes/hosts.txt","hosts.json")) { rejects(() -> BiomeOreHosts.inherit(path,original));rejects(() -> BiomeOreHosts.restrict(path,absent));rejects(() -> BiomeOreHosts.set(path,original,0,"stone"));rejects(() -> BiomeOreHosts.change(path,original,RiverProfiles.Action.DUPLICATE,0,null)); }
        var drafts=new InspectorDrafts();String prefix=BiomeOreHosts.prefix(),host=prefix+"0",other=DepositVariants.listPrefix("biomes")+"0.minHeight",deposit=DepositGenerators.listPrefix("biomes")+"0.minHeight";
        for (String key : List.of(host,other,deposit)) drafts.update(key,"1","2");
        check(!drafts.transitionUnder(prefix,() -> false,() -> { throw new AssertionError(); }) && drafts.count()==3);
        try { drafts.transitionUnder(prefix,() -> true,() -> { throw new IOException("failed write"); });throw new AssertionError(); } catch (IOException expected) { check(drafts.count()==3); }
        check(drafts.transitionUnder(prefix,() -> true,() -> {}) && drafts.count()==2 && drafts.contains(other) && drafts.contains(deposit));
        var workspace=PackWorkspace.newPack(Map.of(PATH,original,"objects/opaque.iob",new byte[]{0,127,-1,42}));workspace.replace(PATH,BiomeOreHosts.set(PATH,original,0,"minecraft:stone"));workspace.save(temp.resolve("biome-ore-hosts"));workspace.exportZip(temp.resolve("biome-ore-hosts.zip"));
        var reopened=PackWorkspace.open(temp.resolve("biome-ore-hosts"));var imported=PackWorkspace.importZip(temp.resolve("biome-ore-hosts.zip"));for (String resource : workspace.paths()) check(Arrays.equals(workspace.read(resource),reopened.read(resource)) && Arrays.equals(workspace.read(resource),imported.read(resource)));
        check(FieldCoverage.fields("biome").stream().anyMatch(f -> f.name().equals(FIELD) && f.implemented()));
        System.out.println("PASS: biome surface ore hosts distinguish absent/null inheritance from empty forbidden override, preserve raw host lists, enforce owner/type guards and isolate drafts through Save/ZIP contracts");
    }
}
