package dev.irisvisualizer.editor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

final class TerrainTransformTests {
    private static final String PATH="dimensions/transform.json";
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static String text(byte[] value) { return new String(value,StandardCharsets.UTF_8); }
    private static byte[] document(String value) { return bytes("{\"terrainTransform\":"+value+",\"future\":9007199254740993}"); }
    private static LosslessJson descriptor(byte[] value) { return TerrainTransforms.object(new LosslessJson(value)); }
    private static void check(boolean value) { if (!value) throw new AssertionError("Terrain transform contract failed"); }
    private static void rejects(Runnable action) { try { action.run(); } catch (IllegalArgumentException expected) { return; }throw new AssertionError("Expected rejected transform operation"); }
    static void run(Path temp) throws Exception {
        byte[] absent=bytes("{\r\n \"future\":\"雪\\u0061\",\"large\":9223372036854775807\r\n}\r\n"),created=TerrainTransforms.create(PATH,absent,"Exact.Provider","v1+Build");
        check(descriptor(created).fields().equals(Set.of("id","version")) && descriptor(created).stringToken("id").equals("Exact.Provider") && TerrainTransforms.settings(descriptor(created))==null);check(Arrays.equals(absent,TerrainTransforms.remove(PATH,created)) && Arrays.equals(absent,TerrainTransforms.remove(PATH,absent)));
        check(descriptor(TerrainTransforms.create(PATH,document("null")," Provider "," Exact Version ")).stringToken("id").equals(" Provider "));
        for (String wrong : List.of(""," ","\n","\t")) { rejects(() -> TerrainTransforms.create(PATH,absent,wrong,"v1"));rejects(() -> TerrainTransforms.create(PATH,absent,"id",wrong));rejects(() -> TerrainTransforms.setIdentity(PATH,created,wrong,"v1"));rejects(() -> TerrainTransforms.setIdentity(PATH,created,"id",wrong)); }
        rejects(() -> TerrainTransforms.create(PATH,created,"id","v1"));rejects(() -> TerrainTransforms.setIdentity(PATH,absent,"id","v1"));rejects(() -> TerrainTransforms.enableSettings(PATH,absent));
        byte[] incomplete=TerrainTransforms.setIdentity(PATH,document("{}"),"id","any string version");check(descriptor(incomplete).stringToken("version").equals("any string version"));
        for (String wrong : List.of("true","[]","1","\"snippet\"")) {
            byte[] invalid=document(wrong);rejects(() -> TerrainTransforms.create(PATH,invalid,"id","v1"));rejects(() -> TerrainTransforms.setIdentity(PATH,invalid,"id","v1"));rejects(() -> TerrainTransforms.remove(PATH,invalid));rejects(() -> TerrainTransforms.enableSettings(PATH,invalid));
        }
        for (String field : List.of("id","version")) for (String wrong : List.of("null","true","[]","{}","1")) rejects(() -> TerrainTransforms.setIdentity(PATH,document("{\""+field+"\":"+wrong+"}"),"id","v1"));
        String raw="{\r\n \"terrainTransform\":{\"id\":\"Exact.Prov\\u0069der\",\"version\":\"v1+Build\",\"settings\":{\"dot.key\":\"snow\\u0061\",\"other\":\"雪\"},\"futureDescriptor\":9223372036854775807},\"futureRoot\":9007199254740993\r\n}\r\n";
        byte[] source=bytes(raw),copy=source.clone();check(Arrays.equals(source,TerrainTransforms.setIdentity(PATH,source,"Exact.Provider","v1+Build")));check(text(TerrainTransforms.setIdentity(PATH,source,"Exact.Provider","v2")).equals(raw.replace("\"version\":\"v1+Build\"","\"version\":\"v2\"")));
        check(text(TerrainTransforms.set(PATH,source,"dot.key","dot.key","snowa")).equals(raw));check(text(TerrainTransforms.set(PATH,source,"dot.key","new.key","snowa")).equals(raw.replace("\"dot.key\"","\"new.key\"")));
        rejects(() -> TerrainTransforms.set(PATH,source,"dot.key","other","value"));rejects(() -> TerrainTransforms.add(PATH,source,"other","value"));rejects(() -> TerrainTransforms.set(PATH,source,"missing","new","value"));rejects(() -> TerrainTransforms.removeSetting(PATH,source,"missing"));
        byte[] enabled=TerrainTransforms.enableSettings(PATH,created);check(TerrainTransforms.settings(descriptor(enabled)).isEmpty());rejects(() -> TerrainTransforms.enableSettings(PATH,enabled));
        byte[] setting=TerrainTransforms.add(PATH,enabled,"","");check(TerrainTransforms.settings(descriptor(setting)).getFirst().key().isEmpty());check(Arrays.equals(created,TerrainTransforms.removeSetting(PATH,setting,"")));
        setting=TerrainTransforms.add(PATH,enabled,"literal.dot:key/\\name","line\nquote\"雪\u0000");check(TerrainTransforms.settings(descriptor(setting)).getFirst().value().equals("line\nquote\"雪\u0000"));
        byte[] renamed=TerrainTransforms.set(PATH,setting,"literal.dot:key/\\name"," renamed ","changed");check(TerrainTransforms.settings(descriptor(renamed)).getFirst().key().equals(" renamed "));
        for (String wrong : List.of("null","[]","1","true","\"snippet\"","{\"key\":null}","{\"key\":1}","{\"key\":true}","{\"key\":{}}")) {
            byte[] invalid=document("{\"id\":\"id\",\"version\":\"v1\",\"settings\":"+wrong+"}");rejects(() -> TerrainTransforms.enableSettings(PATH,invalid));rejects(() -> TerrainTransforms.add(PATH,invalid,"key","value"));rejects(() -> TerrainTransforms.set(PATH,invalid,"key","new","value"));rejects(() -> TerrainTransforms.removeSetting(PATH,invalid,"key"));
            check(descriptor(TerrainTransforms.setIdentity(PATH,invalid,"other","v2")).token("settings").equals(wrong));
        }
        var rows=new ArrayList<String>();for (int index=0;index<64;index++) rows.add("\"k"+index+"\":\"v\"");byte[] maximum=document("{\"settings\":{"+String.join(",",rows)+"}}");rejects(() -> TerrainTransforms.add(PATH,maximum,"overflow","v"));check(TerrainTransforms.settings(descriptor(TerrainTransforms.removeSetting(PATH,maximum,"k0"))).size()==63);
        rows.add("\"overflow\":\"v\"");byte[] oversized=document("{\"settings\":{"+String.join(",",rows)+"}}");rejects(() -> TerrainTransforms.add(PATH,oversized,"new","v"));rejects(() -> TerrainTransforms.set(PATH,oversized,"k0","k0","changed"));rejects(() -> TerrainTransforms.removeSetting(PATH,oversized,"k0"));check(descriptor(TerrainTransforms.setIdentity(PATH,oversized,"id","version")).token("settings").equals(descriptor(oversized).token("settings")));
        for (String path : List.of("biomes/x.json","regions/x.json","generators/x.json","dimensions/nested/x.json","x.json")) { rejects(() -> TerrainTransforms.create(path,absent,"id","v1"));rejects(() -> TerrainTransforms.setIdentity(path,created,"id","v1"));rejects(() -> TerrainTransforms.enableSettings(path,created));rejects(() -> TerrainTransforms.remove(path,created)); }
        check(!TerrainTransforms.draftPair("a\nb","c").equals(TerrainTransforms.draftPair("a","b\nc")) && !TerrainTransforms.draftPair("a:b","c").equals(TerrainTransforms.draftPair("a","b:c")));
        var drafts=new InspectorDrafts();String identity=TerrainTransforms.prefix()+"identity.pair",map=TerrainTransforms.prefix()+"settings#0.pair",outside="other#zoom";for (String key : List.of(identity,map,outside)) drafts.update(key,"1","2");
        check(!drafts.transitionUnder(TerrainTransforms.prefix(),() -> false,() -> {}) && drafts.count()==3);
        try { drafts.transitionUnder(TerrainTransforms.prefix(),() -> true,() -> { throw new IOException("write failed"); });throw new AssertionError(); } catch (IOException expected) { check(drafts.count()==3); }
        check(drafts.transitionUnder(TerrainTransforms.prefix()+"settings#",() -> true,() -> {}) && drafts.count()==2 && drafts.contains(identity));check(drafts.transitionUnder(TerrainTransforms.prefix(),() -> true,() -> {}) && drafts.count()==1 && drafts.contains(outside));
        check(Arrays.equals(source,copy));var pack=PackWorkspace.newPack(Map.of(PATH,source,"objects/opaque.iob",new byte[]{1,2,3},"expressions/opaque.json",bytes("not parsed")));pack.save(temp.resolve("terrain-transform"));pack.exportZip(temp.resolve("terrain-transform.zip"));var reopened=PackWorkspace.open(temp.resolve("terrain-transform"));var imported=PackWorkspace.importZip(temp.resolve("terrain-transform.zip"));for (String path : pack.paths()) check(Arrays.equals(pack.read(path),reopened.read(path)) && Arrays.equals(pack.read(path),imported.read(path)));
        check(FieldCoverage.fields("terrainTransform").size()==3 && FieldCoverage.fields("terrainTransform").stream().allMatch(FieldCoverage.Field::implemented));
        System.out.println("PASS: optional external terrain descriptor, exact nonnormalized identity pairs, literal string settings/rename/collisions/null guards, map limits, raw no-ops/preservation, unambiguous scoped drafts and lossless Save/ZIP");
    }
}
