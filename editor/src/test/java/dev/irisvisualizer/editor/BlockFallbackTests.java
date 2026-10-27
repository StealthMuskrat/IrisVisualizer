package dev.irisvisualizer.editor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class BlockFallbackTests {
    private static final String PATH="dimensions/fallbacks.json";
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static String text(byte[] value) { return new String(value,StandardCharsets.UTF_8); }
    private static void check(boolean condition) { if (!condition) throw new AssertionError("Block fallback contract failed"); }
    private static void rejects(Runnable action) { try { action.run(); } catch (IllegalArgumentException expected) { return; }throw new AssertionError("Expected rejected fallback operation"); }
    private static byte[] document(String map) { return bytes("{\r\n \"blockFallbacks\":"+map+",\"staticObjects\":[{\"seed\":9007199254740993,\"edit\":[{\"futureRule\":1e2}]}],\"futureRoot\":9007199254740993,\"escaped\":\"雪\\u0061\"\r\n}\r\n"); }
    private static LosslessJson map(byte[] value) { return BlockFallbacks.object(new LosslessJson(value)); }
    static void run(Path temp) throws Exception {
        check(BlockFallbacks.entries(new LosslessJson(bytes("{}")))==null);byte[] enabled=BlockFallbacks.enable(PATH,bytes("{}"));check(map(enabled).fields().isEmpty());rejects(() -> BlockFallbacks.enable(PATH,enabled));
        byte[] added=BlockFallbacks.add(PATH,enabled,"your.mod:ore/deep","other.mod:block[State=MixedCase]");check(map(added).stringToken("your.mod:ore/deep").equals("other.mod:block[State=MixedCase]") && map(added).token("your")==null);
        rejects(() -> BlockFallbacks.add(PATH,added,"your.mod:ore/deep","STONE"));check(map(BlockFallbacks.remove(PATH,added,"your.mod:ore/deep")).fields().isEmpty());
        check(new LosslessJson(BlockFallbacks.remove(PATH,added,"your.mod:ore/deep")).token("blockFallbacks").startsWith("{"));
        for (String bad : List.of("","ore","Minecraft:ore","minecraft:ore[axis=x]","minecraft:ore key","minecraft:")) { rejects(() -> BlockFallbacks.add(PATH,enabled,bad,"STONE"));rejects(() -> BlockFallbacks.set(PATH,added,"your.mod:ore/deep",bad,"STONE")); }
        for (String bad : List.of(""," ","\u2003")) { rejects(() -> BlockFallbacks.add(PATH,enabled,"minecraft:ore",bad));rejects(() -> BlockFallbacks.set(PATH,added,"your.mod:ore/deep","your.mod:ore/deep",bad)); }
        byte[] original=document("{ \"minecraft:iron\\u005fore\" : \"legacy\\u0061\",\r\n \"your.mod:ore/deep\":\"other.mod:block[State=MixedCase]\",\" OLD SOURCE[axis=X] \":\"future target\",\"雪:key\":\"keep\" }"),copy=original.clone();
        check(text(BlockFallbacks.set(PATH,original,"minecraft:iron_ore","minecraft:iron_ore","minecraft:gold_ore")).equals(text(original).replace("\"legacy\\u0061\"","\"minecraft:gold_ore\"")));
        check(text(BlockFallbacks.set(PATH,original,"minecraft:iron_ore","minecraft:gold_ore","legacya")).equals(text(original).replace("\"minecraft:iron\\u005fore\"","\"minecraft:gold_ore\"")));
        check(Arrays.equals(original,BlockFallbacks.set(PATH,original,"minecraft:iron_ore","minecraft:iron_ore","legacya")));
        check(map(BlockFallbacks.set(PATH,original," OLD SOURCE[axis=X] "," OLD SOURCE[axis=X] ","minecraft:dirt")).stringToken(" OLD SOURCE[axis=X] ").equals("minecraft:dirt"));
        rejects(() -> BlockFallbacks.set(PATH,original,"minecraft:iron_ore","your.mod:ore/deep","STONE"));rejects(() -> BlockFallbacks.add(PATH,original,"minecraft:iron_ore","STONE"));rejects(() -> BlockFallbacks.remove(PATH,original,"absent"));rejects(() -> BlockFallbacks.set(PATH,original,"absent","minecraft:gold_ore","STONE"));
        for (String removed : List.of("minecraft:iron_ore","your.mod:ore/deep"," OLD SOURCE[axis=X] ","雪:key")) {
            var next=map(BlockFallbacks.remove(PATH,original,removed));check(next.fields().size()==3 && !next.fields().contains(removed));for (String key : map(original).fields()) if (!key.equals(removed)) check(next.token(key).equals(map(original).token(key)));
        }
        for (String bad : List.of("null","[]","true","1","\"snippet/fallback\"","{\"minecraft:ore\":null}","{\"good\":\"STONE\",\"future\":{}}")) {
            byte[] invalid=document(bad);rejects(() -> BlockFallbacks.entries(new LosslessJson(invalid)));rejects(() -> BlockFallbacks.enable(PATH,invalid));rejects(() -> BlockFallbacks.add(PATH,invalid,"minecraft:ore","STONE"));rejects(() -> BlockFallbacks.set(PATH,invalid,"good","good","STONE"));rejects(() -> BlockFallbacks.remove(PATH,invalid,"good"));
        }
        for (String wrong : List.of("biomes/fallbacks.json","regions/fallbacks.json","generators/fallbacks.json","dimensions/nested/fallbacks.json","dimensions/fallbacks.txt","fallbacks.json")) {
            rejects(() -> BlockFallbacks.enable(wrong,bytes("{}")));rejects(() -> BlockFallbacks.add(wrong,enabled,"minecraft:ore","STONE"));rejects(() -> BlockFallbacks.set(wrong,original,"minecraft:iron_ore","minecraft:iron_ore","STONE"));rejects(() -> BlockFallbacks.remove(wrong,original,"minecraft:iron_ore"));
        }
        rejects(() -> BlockFallbacks.add(PATH,bytes("{}"),"minecraft:ore","STONE"));rejects(() -> new LosslessJson(document("{\"a.b:key\":\"STONE\",\"a\\u002eb:key\":\"DIRT\"}")));
        check(BlockFallbacks.normalizedSource(null)==null && BlockFallbacks.normalizedSource(" \t\n ")==null);check(BlockFallbacks.normalizedSource(" STONE[axis=Y] ").equals("minecraft:stone"));
        check(BlockFallbacks.normalizedSource("Your.Mod:Ore/Deep[State=MixedCase]").equals("your.mod:ore/deep"));
        Locale previous=Locale.getDefault();try { Locale.setDefault(Locale.forLanguageTag("tr-TR"));check(BlockFallbacks.normalizedSource("IRON_ORE").equals("minecraft:iron_ore")); } finally { Locale.setDefault(previous); }
        var drafts=new InspectorDrafts();String row=BlockFallbacks.prefix()+"0",otherMap="depositVariants#dimensions.0.remap#.0",staticSeed="staticObjects#.0.seed";
        for (String key : List.of(row,otherMap,staticSeed)) drafts.update(key,"1","2");check(!drafts.transitionUnder(BlockFallbacks.prefix(),() -> false,() -> { throw new AssertionError(); }) && drafts.count()==3);
        try { drafts.transitionUnder(BlockFallbacks.prefix(),() -> true,() -> { throw new IOException("failed write"); });throw new AssertionError(); } catch (IOException expected) { check(drafts.count()==3); }
        check(drafts.transitionUnder(BlockFallbacks.prefix(),() -> true,() -> {}) && drafts.count()==2 && drafts.contains(otherMap) && drafts.contains(staticSeed));
        byte[] edited=BlockFallbacks.set(PATH,original,"minecraft:iron_ore","minecraft:gold_ore","minecraft:deepslate_gold_ore");
        for (String token : List.of("\"staticObjects\":[{\"seed\":9007199254740993,\"edit\":[{\"futureRule\":1e2}]}]","\"futureRoot\":9007199254740993","\"escaped\":\"雪\\u0061\"","\"your.mod:ore/deep\":\"other.mod:block[State=MixedCase]\"","\" OLD SOURCE[axis=X] \":\"future target\"")) check(text(edited).contains(token));check(Arrays.equals(original,copy));
        var pack=PackWorkspace.newPack(Map.of(PATH,edited,"dimensions/other.json",document("{\"minecraft:gold_ore\":\"minecraft:dirt\"}"),"objects/opaque.iob",new byte[]{0,127,-1,42}));
        check(BlockFallbacks.inspect(pack).overlaps().size()==1);pack.save(temp.resolve("fallbacks"));pack.exportZip(temp.resolve("fallbacks.zip"));var reopened=PackWorkspace.open(temp.resolve("fallbacks"));var imported=PackWorkspace.importZip(temp.resolve("fallbacks.zip"));
        for (String path : pack.paths()) check(Arrays.equals(pack.read(path),reopened.read(path)) && Arrays.equals(pack.read(path),imported.read(path)));
        audit(temp);check(FieldCoverage.fields("dimension").stream().anyMatch(f -> f.name().equals("blockFallbacks") && f.implemented()));
        System.out.println("PASS: literal block fallback authoring/rename/collisions, root/type guards, native source normalization, pack-wide advisory overlaps/incomplete inputs, revision-aware cache, scoped drafts and byte-preserving Save/ZIP");
    }
    private static void audit(Path temp) throws Exception {
        var files=new LinkedHashMap<String,byte[]>();
        files.put("dimensions/a.json",document("{\"STONE\":\"minecraft:stone\",\"minecraft:ore[axis=X]\":\"mod:target[State=X]\",\" \":\"STONE\",\"minecraft:blank\":\"\\u2003\"}"));
        files.put("dimensions/b.json",document("{\"minecraft:stone\":\"minecraft:cobblestone\",\"minecraft:ore\":\"mod:other\",\"other:ore\":\"minecraft:quartz_block\"}"));
        files.put("dimensions/c.json",bytes("{}"));files.put("dimensions/mixed.json",document("{\"minecraft:stone\":\"DIRT\",\"future\":1}"));files.put("dimensions/null.json",document("null"));
        files.put("dimensions/invalid.json",new byte[]{(byte)255});files.put("dimensions/huge.json",bytes("{\"padding\":\""+"x".repeat(ReferenceDiagnostics.MAX_JSON_BYTES)+"\",\"blockFallbacks\":{\"minecraft:stone\":\"DIRT\"}}"));
        files.put("dimensions/nested/ignored.json",document("{\"minecraft:stone\":\"DIRT\"}"));files.put("dimensions/ignored.JSON",document("{\"minecraft:stone\":\"DIRT\"}"));files.put("biomes/ignored.json",document("{\"minecraft:stone\":\"DIRT\"}"));files.put("objects/opaque.iob",new byte[]{0,127,-1,42});
        var pack=PackWorkspace.newPack(files);var audit=BlockFallbacks.inspect(pack);check(audit.inspectedDimensions()==3 && audit.definitions()==5 && audit.overlaps().size()==2 && audit.notices().size()==6);
        check(audit.overlaps().getFirst().normalizedSource().equals("minecraft:ore") && audit.overlaps().getFirst().definitions().size()==2);check(audit.overlaps().get(1).normalizedSource().equals("minecraft:stone") && audit.overlaps().get(1).definitions().size()==2);
        check(audit.overlaps().getFirst().definitions().getFirst().source().equals("minecraft:ore[axis=X]") && audit.overlaps().getFirst().definitions().getFirst().target().equals("mod:target[State=X]"));
        check(audit.notices().stream().anyMatch(n -> n.path().equals("dimensions/mixed.json") && n.message().contains("incomplete")));check(audit.notices().stream().anyMatch(n -> n.path().equals("dimensions/huge.json") && n.message().contains("1 MB")));
        try { audit.overlaps().clear();throw new AssertionError(); } catch (UnsupportedOperationException expected) { }
        try { audit.overlaps().getFirst().definitions().clear();throw new AssertionError(); } catch (UnsupportedOperationException expected) { }
        for (String path : files.keySet()) check(Arrays.equals(files.get(path),pack.read(path)));
        var cache=new BlockFallbacks.AuditCache();var first=cache.inspect(pack);check(first==cache.inspect(pack));
        byte[] updated=BlockFallbacks.remove("dimensions/b.json",pack.read("dimensions/b.json"),"minecraft:stone");pack.replace("dimensions/b.json",updated);var second=cache.inspect(pack);check(second!=first && second.overlaps().size()==1);
        pack.replace("dimensions/b.json",updated);check(second==cache.inspect(pack));pack.duplicate("dimensions/b.json","copy");var third=cache.inspect(pack);check(third!=second && third.overlaps().size()==2);
        var replacement=PackWorkspace.newPack(Map.of("dimensions/one.json",document("{\"minecraft:ore\":\"STONE\"}")));check(cache.inspect(replacement).overlaps().isEmpty());
        // Advisory notices never gate saving malformed or too-large resources and opaque assets unchanged.
        pack.save(temp.resolve("fallback-audit"));pack.exportZip(temp.resolve("fallback-audit.zip"));var reopened=PackWorkspace.open(temp.resolve("fallback-audit"));var imported=PackWorkspace.importZip(temp.resolve("fallback-audit.zip"));
        for (String path : pack.paths()) check(Arrays.equals(pack.read(path),reopened.read(path)) && Arrays.equals(pack.read(path),imported.read(path)));
    }
}
