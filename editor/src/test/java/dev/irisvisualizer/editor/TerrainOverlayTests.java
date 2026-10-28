package dev.irisvisualizer.editor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

final class TerrainOverlayTests {
    private static final String PATH="dimensions/overlay.json";
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static String text(byte[] value) { return new String(value,StandardCharsets.UTF_8); }
    private static byte[] document(String list) { return bytes("{\"overlayNoise\":"+list+",\"futureRoot\":9007199254740993}"); }
    private static byte[] single(String value) { return document("["+value+"]"); }
    private static LosslessJson entry(byte[] value,int index) { return TerrainOverlays.entries(new LosslessJson(value)).get(index); }
    private static void check(boolean value) { if (!value) throw new AssertionError("Terrain overlay contract failed"); }
    private static void rejects(Runnable action) { try { action.run(); } catch (IllegalArgumentException expected) { return; }throw new AssertionError("Expected rejected overlay operation"); }
    static void run(Path temp) throws Exception {
        byte[] absent=bytes("{\r\n \"futureRoot\":\"雪\\u0061\",\"large\":9223372036854775807\r\n}\r\n"),created=TerrainOverlays.change(PATH,absent,RiverProfiles.Action.ADD,-1);
        check(entry(created,0).fields().equals(Set.of("generator","min","max")) && entry(created,0).token("min").equals("0") && entry(created,0).token("max").equals("0"));
        var target=TerrainOverlays.styleTarget(0);check(target.overlay() && target.overlayIndex()==0 && target.member().equals("generator") && target.draftPrefix().equals("terrainOverlay#0.generator.") && NoiseStyleFields.contextDefaultLabel(target).equals("IRIS"));
        check(NoiseStyleFields.object(entry(created,0),target).stringToken("style").equals("IRIS"));check(Arrays.equals(absent,TerrainOverlays.change(PATH,created,RiverProfiles.Action.REMOVE,0)));
        check(TerrainOverlays.entries(new LosslessJson(document("[]"))).isEmpty());
        for (String wrong : List.of("null","true","1","{}","\"snippet\"","[null]","[{},1]")) {
            byte[] invalid=document(wrong);rejects(() -> TerrainOverlays.change(PATH,invalid,RiverProfiles.Action.ADD,-1));rejects(() -> TerrainOverlays.setBounds(PATH,invalid,0,"0","1"));rejects(() -> NoiseStyleFields.enable(PATH,invalid,target,"style"));
        }
        for (String[] pair : List.of(new String[]{"-2032","2032"},new String[]{"2032","-2032"},new String[]{"0","0"},new String[]{"1e2","-1e2"})) {
            byte[] next=TerrainOverlays.setBounds(PATH,created,0,pair[0],pair[1]);check(new java.math.BigDecimal(entry(next,0).token("min")).intValueExact()==new java.math.BigDecimal(pair[0]).intValueExact());
        }
        for (String wrong : List.of("-2033","2033","1.000000000000000001","2147483648","9007199254740993","NaN","Infinity","","1e9999999999")) {
            rejects(() -> TerrainOverlays.setBounds(PATH,created,0,wrong,"0"));rejects(() -> TerrainOverlays.setBounds(PATH,created,0,"0",wrong));
        }
        for (String field : List.of("min","max")) for (String wrong : List.of("null","[]","{}","true","\"1\"","1.5","2147483648","1e9999999999")) {
            byte[] invalid=single("{\""+field+"\":"+wrong+"}");rejects(() -> TerrainOverlays.setBounds(PATH,invalid,0,"0","1"));
        }
        TerrainOverlays.setBounds(PATH,single("{\"min\":-3000,\"max\":3000}"),0,"-2032","2032");
        byte[] enabled=TerrainOverlays.setBounds(PATH,single("{}"),0,"-1","1");check(entry(enabled,0).token("min").equals("-1") && entry(enabled,0).token("max").equals("1"));
        String raw="{\r\n \"overlayNoise\":[{\"min\":-1e2,\"max\":1e2,\"generator\":{\"style\":\"UNKNOWN\",\"zoom\":1e0,\"futureStyle\":\"雪\\u0061\"},\"futureEntry\":9223372036854775807}],\"futureRoot\":9007199254740993\r\n}\r\n";
        byte[] source=bytes(raw),copy=source.clone();check(Arrays.equals(source,TerrainOverlays.setBounds(PATH,source,0,"-100","100")));check(text(TerrainOverlays.setBounds(PATH,source,0,"-100","200")).equals(raw.replace("\"max\":1e2","\"max\":200")));
        byte[] duplicated=TerrainOverlays.change(PATH,source,RiverProfiles.Action.DUPLICATE,0);check(Arrays.equals(entry(duplicated,0).sourceBytes(),entry(duplicated,1).sourceBytes()));
        byte[] altered=TerrainOverlays.setBounds(PATH,duplicated,1,"2","1");byte[] moved=TerrainOverlays.change(PATH,altered,RiverProfiles.Action.UP,1);check(entry(moved,0).token("min").equals("2") && entry(moved,1).token("min").equals("-1e2"));check(entry(TerrainOverlays.change(PATH,moved,RiverProfiles.Action.DOWN,0),1).token("min").equals("2"));
        rejects(() -> TerrainOverlays.change(PATH,created,RiverProfiles.Action.UP,0));rejects(() -> TerrainOverlays.change(PATH,created,RiverProfiles.Action.DOWN,0));rejects(() -> TerrainOverlays.change(PATH,created,RiverProfiles.Action.REMOVE,-1));
        byte[] absentGenerator=NoiseStyleFields.enable(PATH,single("{}"),target,"style");check(NoiseStyleFields.object(entry(absentGenerator,0),target).stringToken("style").equals("IRIS"));
        byte[] inline=NoiseStyleFields.enable(PATH,single("{\"generator\":{}}"),target,"style");check(NoiseStyleFields.object(entry(inline,0),target).stringToken("style").equals("FLAT"));
        for (String choice : NoiseStyleFields.choices()) check(NoiseStyleFields.object(entry(NoiseStyleFields.editStyle(PATH,created,target,choice),0),target).stringToken("style").equals(choice));
        for (String wrong : List.of("null","[]","true","1","\"snippet\"")) rejects(() -> NoiseStyleFields.enable(PATH,single("{\"generator\":"+wrong+"}"),target,"style"));
        for (String owner : List.of("biomes","regions","generators")) rejects(() -> new NoiseStyleFields.Target(owner,"overlayNoise.0.generator",null));rejects(() -> TerrainOverlays.styleTarget(-1));rejects(() -> new NoiseStyleFields.Target("dimensions","overlayNoise.01.generator",null));
        var pack=PackWorkspace.newPack(Map.of(PATH,absent,"expressions/formula.json",bytes("opaque expression"),"image-maps/height.json",bytes("opaque image map"),"objects/opaque.iob",new byte[]{1,2,3}));
        byte[] styled=NoiseStyleSources.setReference(PATH,created,target,"expression","formula",pack);styled=NoiseStyleSources.setReference(PATH,styled,target,"imageMap","height",pack);styled=NoiseStyleSources.enableCache(PATH,styled,target);styled=NoiseStyleSources.setCache(PATH,styled,target,"8192");styled=StyleFractures.enable(PATH,styled,target.child());styled=StyleFractures.enableMultiplier(PATH,styled,target.child());styled=StyleFractures.setMultiplier(PATH,styled,target.child(),"2");check(NoiseStyleFields.object(entry(styled,0),target).token("fracture")!=null);
        byte[] maximum=document("["+String.join(",",Collections.nCopies(64,"{\"generator\":{}}"))+"]"),oversized=document("["+String.join(",",Collections.nCopies(65,"{\"generator\":{}}"))+"]");
        rejects(() -> TerrainOverlays.change(PATH,maximum,RiverProfiles.Action.ADD,-1));rejects(() -> TerrainOverlays.change(PATH,maximum,RiverProfiles.Action.DUPLICATE,0));check(TerrainOverlays.entries(new LosslessJson(TerrainOverlays.change(PATH,maximum,RiverProfiles.Action.REMOVE,0))).size()==63);
        rejects(() -> TerrainOverlays.change(PATH,oversized,RiverProfiles.Action.REMOVE,0));rejects(() -> TerrainOverlays.setBounds(PATH,oversized,0,"0","1"));rejects(() -> NoiseStyleFields.enable(PATH,oversized,target,"style"));rejects(() -> NoiseStyleSources.enableCache(PATH,oversized,target));rejects(() -> StyleFractures.enable(PATH,oversized,target.child()));
        for (String path : List.of("biomes/x.json","regions/x.json","generators/x.json","dimensions/nested/x.json","x.json")) { rejects(() -> TerrainOverlays.change(path,created,RiverProfiles.Action.REMOVE,0));rejects(() -> TerrainOverlays.setBounds(path,created,0,"0","1")); }
        var drafts=new InspectorDrafts();String pair=TerrainOverlays.prefix(0)+"bounds.pair",style=target.child().draftPrefix()+"zoom",outside="other#zoom";for (String key : List.of(pair,style,outside)) drafts.update(key,"1","2");
        check(!drafts.transitionUnder(TerrainOverlays.prefix(),() -> false,() -> {}) && drafts.count()==3);
        try { drafts.transitionUnder(TerrainOverlays.prefix(),() -> true,() -> { throw new IOException("write failed"); });throw new AssertionError(); } catch (IOException expected) { check(drafts.count()==3); }
        check(drafts.transitionUnder(TerrainOverlays.prefix(0)+"bounds.",() -> true,() -> {}) && drafts.count()==2 && drafts.contains(style));check(drafts.transitionUnder(TerrainOverlays.prefix(),() -> true,() -> {}) && drafts.count()==1 && drafts.contains(outside));
        check(Arrays.equals(source,copy));pack.replace(PATH,styled);pack.save(temp.resolve("terrain-overlays"));pack.exportZip(temp.resolve("terrain-overlays.zip"));var reopened=PackWorkspace.open(temp.resolve("terrain-overlays"));var imported=PackWorkspace.importZip(temp.resolve("terrain-overlays.zip"));for (String path : pack.paths()) check(Arrays.equals(pack.read(path),reopened.read(path)) && Arrays.equals(pack.read(path),imported.read(path)));
        check(FieldCoverage.fields("shapedStyle").size()==3 && FieldCoverage.fields("shapedStyle").stream().allMatch(FieldCoverage.Field::implemented));
        System.out.println("PASS: ordered terrain overlays, explicit zero/IRIS defaults, exact signed/equal/reversed bound pairs and raw no-ops, full generator/style/source/cache/fracture routing, type/owner/size guards, scoped drafts and lossless Save/ZIP");
    }
}
