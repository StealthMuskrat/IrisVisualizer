package dev.irisvisualizer.editor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

final class DimensionCarvingTests {
    private static final String PATH="dimensions/carving.json";
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static String text(byte[] value) { return new String(value,StandardCharsets.UTF_8); }
    private static byte[] document(String list) { return bytes("{\"carving\":"+list+",\"future\":9007199254740993}"); }
    private static byte[] single(String entry) { return document("["+entry+"]"); }
    private static LosslessJson entry(byte[] raw,int index) { return DimensionCarvings.entries(new LosslessJson(raw)).get(index); }
    private static void check(boolean value) { if (!value) throw new AssertionError("Dimension carving contract failed"); }
    private static void rejects(Runnable action) { try { action.run(); } catch (IllegalArgumentException expected) { return; }throw new AssertionError("Expected rejected carving operation"); }
    static void run(Path temp) throws Exception {
        var pack=PackWorkspace.newPack(Map.of(PATH,bytes("{}"),"biomes/nested/cave.json",bytes("opaque target"),"biomes/other.json",bytes("{}"),"expressions/formula.json",bytes("opaque expression"),"objects/opaque.iob",new byte[]{1,2,3}));
        byte[] absent=bytes("{\r\n \"future\":\"雪\\u0061\",\"large\":9223372036854775807\r\n}\r\n");
        byte[] created=DimensionCarvings.change(PATH,absent,RiverProfiles.Action.ADD,-1,"root","nested/cave",pack);
        check(entry(created,0).fields().equals(Set.of("id","biome")) && entry(created,0).stringToken("id").equals("root"));check(Arrays.equals(absent,DimensionCarvings.change(PATH,created,RiverProfiles.Action.REMOVE,0,null,null,pack)));
        check(DimensionCarvings.rangeBound(entry(created,0),"min").equals("-64") && DimensionCarvings.rangeBound(entry(created,0),"max").equals("320"));
        check(DimensionCarvings.rangeBound(new LosslessJson(bytes("{\"worldYRange\":{}}")),"min").equals("16"));
        for (String disabled : List.of("null","[]")) check(entry(DimensionCarvings.change(PATH,document(disabled),RiverProfiles.Action.ADD,-1,"root","other",pack),0).stringToken("biome").equals("other"));
        for (String id : List.of(""," ","Upper","a/b","a:b","a.b")) rejects(() -> DimensionCarvings.change(PATH,absent,RiverProfiles.Action.ADD,-1,id,"other",pack));
        for (String biome : List.of("missing","nested/cave.json","NESTED/cave","alias:cave","../other","")) rejects(() -> DimensionCarvings.setBiome(PATH,created,0,biome,pack));
        for (String token : List.of("true","{}","1","\"snippet\"","[null]","[{},1]")) {
            byte[] wrong=document(token);rejects(() -> DimensionCarvings.change(PATH,wrong,RiverProfiles.Action.ADD,-1,"root","other",pack));rejects(() -> DimensionCarvings.setEnabled(PATH,wrong,0,false));
        }
        String raw="{\r\n \"carving\":[{\"id\":\"r\\u006fot\",\"biome\":\"nested/cave\",\"enabled\":true,\"worldYRange\":{\"min\":-64e0,\"max\":320,\"futureRange\":\"雪\\u0061\"},\"children\":[\"r\\u006fot\"],\"childStyle\":{\"style\":\"UNKNOWN\",\"futureStyle\":9007199254740993},\"futureEntry\":9223372036854775807}],\"futureRoot\":1e2\r\n}\r\n";
        byte[] source=bytes(raw),copy=source.clone();byte[] duplicated=DimensionCarvings.change(PATH,source,RiverProfiles.Action.DUPLICATE,0,"copy",null,pack);
        check(entry(duplicated,1).stringToken("id").equals("copy") && entry(duplicated,1).token("worldYRange").equals(entry(source,0).token("worldYRange")) && entry(duplicated,1).token("children").equals(entry(source,0).token("children")));
        byte[] moved=DimensionCarvings.change(PATH,duplicated,RiverProfiles.Action.UP,1,null,null,pack);check(entry(moved,0).stringToken("id").equals("copy") && entry(moved,1).token("id").equals("\"r\\u006fot\""));check(Arrays.equals(duplicated,DimensionCarvings.change(PATH,moved,RiverProfiles.Action.DOWN,0,null,null,pack)));
        check(text(DimensionCarvings.setId(PATH,source,0,"renamed")).equals(raw.replace("\"id\":\"r\\u006fot\"","\"id\":\"renamed\"")));
        check(text(DimensionCarvings.setBiome(PATH,source,0,"other",pack)).equals(raw.replace("\"biome\":\"nested/cave\"","\"biome\":\"other\"")));
        check(text(DimensionCarvings.setEnabled(PATH,source,0,false)).equals(raw.replace("\"enabled\":true","\"enabled\":false")));
        check(entry(DimensionCarvings.setEnabled(PATH,created,0,false),0).token("enabled").equals("false"));
        rejects(() -> DimensionCarvings.setId(PATH,duplicated,1,"root"));rejects(() -> DimensionCarvings.change(PATH,source,RiverProfiles.Action.DUPLICATE,0,"root",null,pack));
        byte[] legacy=document("[{\"id\":\" root \"},{\"id\":\"root\"},{\"id\":\"ROOT\"}]");check(DimensionCarvings.ids(new LosslessJson(legacy)).equals(List.of("ROOT","root")) && DimensionCarvings.findings(new LosslessJson(legacy)).stream().filter(f -> f.contains("duplicate")).count()==2);
        rejects(() -> DimensionCarvings.change(PATH,legacy,RiverProfiles.Action.ADD,-1,"root","other",pack));
        for (String field : List.of("id","biome","enabled")) for (String token : List.of("null","[]","{}","1")) {
            byte[] wrong=single("{\""+field+"\":"+token+"}");
            if (field.equals("id")) rejects(() -> DimensionCarvings.setId(PATH,wrong,0,"fixed"));else if (field.equals("biome")) rejects(() -> DimensionCarvings.setBiome(PATH,wrong,0,"other",pack));else rejects(() -> DimensionCarvings.setEnabled(PATH,wrong,0,false));
        }
        for (String field : DimensionCarvings.SCALARS) {
            byte[] enabled=DimensionCarvings.enableScalar(PATH,created,0,field);check(entry(enabled,0).token(field).equals(DimensionCarvings.defaultToken(field)));rejects(() -> DimensionCarvings.enableScalar(PATH,enabled,0,field));rejects(() -> DimensionCarvings.setScalar(PATH,created,0,field,"1"));
            for (String value : field.equals("childRecursionDepth") ? List.of("0","32","3.2e1") : List.of("0.0001","1.5","1000000")) DimensionCarvings.setScalar(PATH,enabled,0,field,value);
            for (String value : field.equals("childRecursionDepth") ? List.of("-1","33","1.000000000000000001","2147483648","NaN","") : List.of("-1","0","0.00001","1e999","NaN","Infinity","")) rejects(() -> DimensionCarvings.setScalar(PATH,enabled,0,field,value));
            for (String token : List.of("null","[]","{}","true","\"1\"")) rejects(() -> DimensionCarvings.setScalar(PATH,single("{\""+field+"\":"+token+"}"),0,field,"1"));
            DimensionCarvings.setScalar(PATH,single("{\""+field+"\":-5}"),0,field,"1");
        }
        for (String token : List.of("1.5","2147483648","9007199254740993")) rejects(() -> DimensionCarvings.setScalar(PATH,single("{\"childRecursionDepth\":"+token+"}"),0,"childRecursionDepth","1"));
        for (byte[] input : List.of(created,single("{\"worldYRange\":null}"),single("{\"worldYRange\":{}}"),single("{\"worldYRange\":{\"min\":10,\"max\":0}}"))) {
            byte[] ranged=DimensionCarvings.setRange(PATH,input,0,"-1000.5","1000.5");check(DimensionCarvings.range(entry(ranged,0)).token("min").equals("-1000.5"));
            check(entry(DimensionCarvings.unbounded(PATH,input,0),0).token("worldYRange").equals("null"));
        }
        byte[] ranged=DimensionCarvings.setRange(PATH,source,0,"-32.5","64.5");check(DimensionCarvings.range(entry(ranged,0)).token("futureRange").equals("\"雪\\u0061\"") && entry(ranged,0).token("childStyle").equals(entry(source,0).token("childStyle")));
        DimensionCarvings.setRange(PATH,created,0,"0","0");rejects(() -> DimensionCarvings.setRange(PATH,created,0,"1","0"));rejects(() -> DimensionCarvings.setRange(PATH,created,0,"NaN","0"));rejects(() -> DimensionCarvings.setRange(PATH,created,0,"0","Infinity"));
        for (String token : List.of("[]","1","true","\"snippet\"","{\"min\":null}","{\"max\":\"32\"}")) rejects(() -> DimensionCarvings.setRange(PATH,single("{\"worldYRange\":"+token+"}"),0,"-64","320"));
        byte[] child=DimensionCarvings.changeChild(PATH,created,0,RiverProfiles.Action.ADD,-1,"root");check(DimensionCarvings.children(entry(child,0)).equals(List.of("root")));
        child=DimensionCarvings.changeChild(PATH,child,0,RiverProfiles.Action.DUPLICATE,0,null);check(DimensionCarvings.children(entry(child,0)).equals(List.of("root","root")));child=DimensionCarvings.changeChild(PATH,child,0,RiverProfiles.Action.REMOVE,0,null);check(Arrays.equals(created,DimensionCarvings.changeChild(PATH,child,0,RiverProfiles.Action.REMOVE,0,null)));
        byte[] cycle=DimensionCarvings.changeChild(PATH,duplicated,0,RiverProfiles.Action.ADD,-1,"copy");cycle=DimensionCarvings.changeChild(PATH,cycle,1,RiverProfiles.Action.ADD,-1,"root");check(DimensionCarvings.findings(new LosslessJson(cycle)).isEmpty());
        rejects(() -> DimensionCarvings.changeChild(PATH,created,0,RiverProfiles.Action.ADD,-1,"missing"));
        for (String token : List.of("1","{}","\"snippet\"","[null]","[1]")) rejects(() -> DimensionCarvings.changeChild(PATH,single("{\"id\":\"root\",\"children\":"+token+"}"),0,RiverProfiles.Action.ADD,-1,"root"));
        check(DimensionCarvings.children(entry(DimensionCarvings.changeChild(PATH,single("{\"id\":\"root\",\"children\":null}"),0,RiverProfiles.Action.ADD,-1,"root"),0)).equals(List.of("root")));
        byte[] dangling=DimensionCarvings.setId(PATH,source,0,"renamed");check(DimensionCarvings.findings(new LosslessJson(dangling)).stream().anyMatch(f -> f.contains("no exact")));
        var target=DimensionCarvings.styleTarget(0);check(target.carving() && target.carvingIndex()==0 && target.member().equals("childStyle") && target.draftPrefix().equals(DimensionCarvings.prefix(0)+"childStyle.") && NoiseStyleFields.contextDefaultLabel(target).equals("CELLULAR_IRIS_DOUBLE"));
        byte[] styled=NoiseStyleFields.enable(PATH,created,target,"style");check(NoiseStyleFields.object(entry(styled,0),target).stringToken("style").equals("CELLULAR_IRIS_DOUBLE"));
        byte[] inline=NoiseStyleFields.enable(PATH,single("{\"childStyle\":{}}"),target,"style");check(NoiseStyleFields.object(entry(inline,0),target).stringToken("style").equals("FLAT"));
        for (String choice : NoiseStyleFields.choices()) check(NoiseStyleFields.object(entry(NoiseStyleFields.editStyle(PATH,styled,target,choice),0),target).stringToken("style").equals(choice));
        for (String token : List.of("null","[]","1","true","\"snippet\"")) rejects(() -> NoiseStyleFields.enable(PATH,single("{\"childStyle\":"+token+"}"),target,"style"));
        rejects(() -> new NoiseStyleFields.Target("biomes","carving.0.childStyle",null));rejects(() -> new NoiseStyleFields.Target("dimensions","carving.01.childStyle",null));rejects(() -> DimensionCarvings.styleTarget(-1));
        styled=NoiseStyleSources.setReference(PATH,styled,target,"expression","formula",pack);styled=NoiseStyleSources.enableCache(PATH,styled,target);styled=NoiseStyleSources.setCache(PATH,styled,target,"8192");styled=StyleFractures.enable(PATH,styled,target.child());check(NoiseStyleFields.object(entry(styled,0),target).token("fracture")!=null);
        String many="["+String.join(",",Collections.nCopies(65,"{\"id\":\"root\",\"childStyle\":{}}"))+"]";byte[] oversized=document(many);
        rejects(() -> DimensionCarvings.setEnabled(PATH,oversized,0,false));rejects(() -> DimensionCarvings.change(PATH,oversized,RiverProfiles.Action.REMOVE,0,null,null,pack));rejects(() -> NoiseStyleFields.enable(PATH,oversized,target,"zoom"));rejects(() -> NoiseStyleSources.enableCache(PATH,oversized,target));rejects(() -> StyleFractures.enable(PATH,oversized,target.child()));
        byte[] childLimit=single("{\"id\":\"root\",\"children\":["+String.join(",",Collections.nCopies(64,"\"root\""))+"]}");rejects(() -> DimensionCarvings.changeChild(PATH,childLimit,0,RiverProfiles.Action.ADD,-1,"root"));check(DimensionCarvings.children(entry(DimensionCarvings.changeChild(PATH,childLimit,0,RiverProfiles.Action.REMOVE,0,null),0)).size()==63);
        for (String path : List.of("biomes/x.json","regions/x.json","generators/x.json","dimensions/nested/x.json","x.json")) { rejects(() -> DimensionCarvings.setEnabled(path,created,0,false));rejects(() -> DimensionCarvings.setRange(path,created,0,"0","1"));rejects(() -> DimensionCarvings.change(path,created,RiverProfiles.Action.REMOVE,0,null,null,pack)); }
        pack.replace(PATH,created);var links=ReferenceDiagnostics.inspect(pack,PATH);check(links.stream().anyMatch(l -> l.field().equals("carving[0].biome") && l.state()==ReferenceDiagnostics.State.EXACT_FILE));
        pack.replace(PATH,document("[{\"biome\":1}]"));check(ReferenceDiagnostics.inspect(pack,PATH).stream().anyMatch(l -> l.field().equals("carving[0].biome") && l.state()==ReferenceDiagnostics.State.UNSUPPORTED));
        var drafts=new InspectorDrafts();String own=target.child().draftPrefix()+"zoom",other="outside#zoom";drafts.update(own,"1","2");drafts.update(other,"1","2");check(!drafts.transitionUnder(DimensionCarvings.prefix(),() -> false,() -> {}) && drafts.count()==2);
        try { drafts.transitionUnder(DimensionCarvings.prefix(),() -> true,() -> { throw new IOException("write failed"); });throw new AssertionError(); } catch (IOException expected) { check(drafts.count()==2); }
        check(drafts.transitionUnder(DimensionCarvings.prefix(),() -> true,() -> {}) && drafts.count()==1 && drafts.contains(other));
        check(Arrays.equals(source,copy));pack.replace(PATH,styled);pack.save(temp.resolve("dimension-carvings"));pack.exportZip(temp.resolve("dimension-carvings.zip"));var reopened=PackWorkspace.open(temp.resolve("dimension-carvings"));var imported=PackWorkspace.importZip(temp.resolve("dimension-carvings.zip"));for (String path : pack.paths()) check(Arrays.equals(pack.read(path),reopened.read(path)) && Arrays.equals(pack.read(path),imported.read(path)));
        check(FieldCoverage.fields("dimensionCarving").size()==8 && FieldCoverage.fields("dimensionCarving").stream().allMatch(FieldCoverage.Field::implemented));
        System.out.println("PASS: dimension carving entries, trimmed local IDs/cycles, inclusive absolute-Y defaults/null ranges, native clamp editor guards, shared child styles/fractures/sources, list/type/owner/size guards, scoped drafts and lossless Save/ZIP");
    }
}
