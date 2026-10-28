package dev.irisvisualizer.editor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

final class DimensionStackTests {
    private static final String PATH="dimensions/host.json";
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static String text(byte[] value) { return new String(value,StandardCharsets.UTF_8); }
    private static LosslessJson stack(byte[] source) { return DimensionStacks.object(new LosslessJson(source)); }
    private static byte[] document(String stack) { return bytes("{\"dimensionStack\":"+stack+",\"future\":9007199254740993}"); }
    private static void check(boolean value) { if (!value) throw new AssertionError("Dimension-stack contract failed"); }
    private static void rejects(Runnable action) { try { action.run(); } catch (IllegalArgumentException expected) { return; }throw new AssertionError("Expected rejected dimension-stack operation"); }
    static void run(Path temp) throws Exception {
        byte[] original=bytes("{\r\n \"future\":\"雪\\u0061\",\"seed\":9007199254740993\r\n}\r\n");
        var pack=PackWorkspace.newPack(Map.of(PATH,original,"dimensions/top.json",bytes("{}"),"dimensions/other.json",bytes("opaque target"),"dimensions/none.json",bytes("{}"),"dimensions/NONE.json",bytes("{}"),"dimensions/nested/ignored.json",bytes("{}"),"dimensions/ignored.JSON",bytes("{}"),"objects/opaque.iob",new byte[]{0,127,-1,42},"expressions/formula.json",bytes("opaque expression")));
        check(DimensionStacks.host(PATH).equals("host") && DimensionStacks.choices(pack).equals(List.of("NONE","host","none","other","top")) && DimensionStacks.MAXIMUM_LAYERS==64);
        byte[] created=DimensionStacks.create(PATH,original,"top",pack);check(DimensionStacks.layers(stack(created)).equals(List.of("top","host")) && stack(created).fields().size()==1 && stack(created).token("spacer")==null && stack(created).token("blend")==null);
        check(Arrays.equals(original,DimensionStacks.disable(PATH,created)) && Arrays.equals(original,DimensionStacks.disable(PATH,original)));rejects(() -> DimensionStacks.create(PATH,created,"top",pack));rejects(() -> DimensionStacks.create(PATH,original,"host",pack));rejects(() -> DimensionStacks.create(PATH,original,"missing",pack));
        for (String value : List.of("top","host","other")) check(new LosslessJson(DimensionStacks.setUpper(PATH,original,value,pack)).stringToken("upperDimension").equals(value));
        check(Arrays.equals(original,DimensionStacks.setUpper(PATH,original,null,pack)));
        for (String value : List.of("none","NONE","missing","top.json","alias:top")) rejects(() -> DimensionStacks.setUpper(PATH,original,value,pack));
        byte[] upper=DimensionStacks.setUpper(PATH,original,"top",pack);rejects(() -> DimensionStacks.create(PATH,upper,"other",pack));rejects(() -> DimensionStacks.setUpper(PATH,created,"top",pack));
        byte[] cleared=DimensionStacks.setUpper(PATH,upper,null,pack);check(new LosslessJson(cleared).stringToken("upperDimension").equals("none") && DimensionStacks.layers(stack(DimensionStacks.create(PATH,cleared,"other",pack))).equals(List.of("other","host")));
        for (String token : List.of("null","\"\"","\"NONE\"")) check(DimensionStacks.layers(stack(DimensionStacks.create(PATH,bytes("{\"upperDimension\":"+token+"}"),"top",pack))).equals(List.of("top","host")));
        for (String token : List.of("true","1","[]","{}")) {
            byte[] invalid=bytes("{\"upperDimension\":"+token+"}");rejects(() -> DimensionStacks.setUpper(PATH,invalid,null,pack));rejects(() -> DimensionStacks.setUpper(PATH,invalid,"top",pack));rejects(() -> DimensionStacks.create(PATH,invalid,"top",pack));check(DimensionFields.readOnlyPaths(new LosslessJson(invalid)).contains("upperDimension"));
        }
        byte[] conflict=bytes("{\"upperDimension\":\"top\",\"dimensionStack\":{\"dimensions\":[\"top\",\"host\"]}}");
        check(!DimensionStacks.findings(PATH,new LosslessJson(conflict),pack).isEmpty());rejects(() -> DimensionStacks.replaceLayers(PATH,conflict,List.of("other","host"),pack));check(DimensionStacks.findings(PATH,new LosslessJson(DimensionStacks.setUpper(PATH,conflict,null,pack)),pack).isEmpty());check(new LosslessJson(DimensionStacks.disable(PATH,conflict)).stringToken("upperDimension").equals("top"));
        for (List<String> keys : List.of(List.of("top","host"),List.of("top","top","host"),List.of("none","other","host"))) check(DimensionStacks.layers(stack(DimensionStacks.replaceLayers(PATH,created,keys,pack))).equals(keys));
        for (List<String> keys : List.of(List.<String>of(),List.of("host"),List.of("host","top"),List.of("host","host"),List.of("host","top","host"),List.of("top","other"),List.of("missing","host"),List.of("TOP","host"),List.of("top.json","host"))) rejects(() -> DimensionStacks.replaceLayers(PATH,created,keys,pack));
        rejects(() -> DimensionStacks.replaceLayers(PATH,created,Arrays.asList(null,"host"),pack));
        var maximum=new ArrayList<String>();for (int index=0;index<63;index++) maximum.add("top");maximum.add("host");check(DimensionStacks.layers(stack(DimensionStacks.replaceLayers(PATH,created,maximum,pack))).size()==64);maximum.add(0,"top");rejects(() -> DimensionStacks.replaceLayers(PATH,created,maximum,pack));
        for (String old : List.of("{}","{\"dimensions\":[]}","{\"dimensions\":[\"host\",\"host\",\"missing\"]}")) check(DimensionStacks.layers(stack(DimensionStacks.replaceLayers(PATH,document(old),List.of("top","host"),pack))).equals(List.of("top","host")));
        for (String raw : List.of("null","true","{}","\"snippet/layers\"","[1]","[\"top\",null]","[\"top\",{}]")) {
            byte[] wrong=document("{\"dimensions\":"+raw+"}");rejects(() -> DimensionStacks.replaceLayers(PATH,wrong,List.of("top","host"),pack));check(DimensionFields.readOnlyPaths(new LosslessJson(wrong)).contains("dimensionStack.dimensions"));
        }
        String raw="{\r\n \"dimensionStack\":{\"dimensions\":[ \"t\\u006fp\",\"other\", \"h\\u006fst\" ],\"spacer\":32,\"blend\":{\"amplitude\":8,\"style\":{\"style\":\"UNKNOWN\",\"zoom\":1e0,\"fracture\":{\"futureChild\":true}},\"futureBlend\":\"雪\\u0061\"},\"futureStack\":9007199254740993},\"upperDimension\":\"none\",\"futureRoot\":9223372036854775807\r\n}\r\n";
        byte[] source=bytes(raw),copy=source.clone();check(Arrays.equals(source,DimensionStacks.replaceLayers(PATH,source,List.of("top","other","host"),pack)));
        byte[] reordered=DimensionStacks.replaceLayers(PATH,source,List.of("other","top","top","host"),pack);check(DimensionStacks.layers(stack(reordered)).equals(List.of("other","top","top","host")) && stack(reordered).token("dimensions").contains("\"t\\u006fp\"") && stack(reordered).token("dimensions").contains("\"h\\u006fst\""));
        check(stack(reordered).token("blend").equals(stack(source).token("blend")) && new LosslessJson(reordered).token("futureRoot").equals("9223372036854775807"));
        for (String field : List.of("spacer","amplitude")) {
            byte[] base=field.equals("spacer") ? created : DimensionStacks.enableBlend(PATH,created);byte[] enabled=DimensionStacks.enableScalar(PATH,base,field);rejects(() -> DimensionStacks.enableScalar(PATH,enabled,field));
            for (String number : List.of("0","256","2.56e2")) DimensionStacks.setScalar(PATH,enabled,field,number);
            for (String number : List.of("-1","257","1.000000000000000001","NaN","Infinity","2147483648","")) rejects(() -> DimensionStacks.setScalar(PATH,enabled,field,number));
            rejects(() -> DimensionStacks.setScalar(PATH,base,field,"1"));
            for (String bad : List.of("null","[]","{}","true","\"1\"","1.5","9007199254740993")) {
                byte[] wrong=field.equals("spacer") ? document("{\"spacer\":"+bad+"}") : document("{\"blend\":{\"amplitude\":"+bad+"}}");rejects(() -> DimensionStacks.setScalar(PATH,wrong,field,"1"));check(DimensionFields.readOnlyPaths(new LosslessJson(wrong)).contains(field.equals("spacer") ? "dimensionStack.spacer" : "dimensionStack.blend.amplitude"));
            }
        }
        check(text(DimensionStacks.setScalar(PATH,source,"spacer","64")).equals(raw.replace("\"spacer\":32","\"spacer\":64")) && text(DimensionStacks.setScalar(PATH,source,"amplitude","16")).equals(raw.replace("\"amplitude\":8","\"amplitude\":16")));
        DimensionStacks.setScalar(PATH,document("{\"spacer\":-1}"),"spacer","0");
        byte[] blend=DimensionStacks.enableBlend(PATH,created);check(DimensionStacks.blend(stack(blend)).fields().isEmpty() && DimensionStacks.defaultToken("spacer").equals("32") && DimensionStacks.defaultToken("amplitude").equals("8"));rejects(() -> DimensionStacks.enableBlend(PATH,blend));check(Arrays.equals(created,DimensionStacks.disableBlend(PATH,blend)) && Arrays.equals(created,DimensionStacks.disableBlend(PATH,created)));
        var style=DimensionStacks.styleTarget();check(NoiseStyleFields.contextDefaultLabel(style).equals("SIMPLEX") && style.draftPrefix().equals(DimensionStacks.blendPrefix()+"style."));
        byte[] styled=NoiseStyleFields.enable(PATH,blend,style,"style");check(NoiseStyleFields.object(DimensionStacks.blend(stack(styled))).stringToken("style").equals("SIMPLEX"));
        byte[] inline=document("{\"blend\":{\"style\":{}}}");check(NoiseStyleFields.object(DimensionStacks.blend(stack(NoiseStyleFields.enable(PATH,inline,style,"style")))).stringToken("style").equals("FLAT"));
        rejects(() -> NoiseStyleFields.enable(PATH,created,style,"style"));rejects(() -> DimensionStacks.enableBlend(PATH,original));rejects(() -> DimensionStacks.enableScalar(PATH,created,"amplitude"));
        styled=NoiseStyleSources.enableCache(PATH,styled,style);styled=NoiseStyleSources.setCache(PATH,styled,style,"8192");styled=NoiseStyleSources.setReference(PATH,styled,style,"expression","formula",pack);styled=StyleFractures.enable(PATH,styled,style.child());styled=StyleFractures.enableMultiplier(PATH,styled,style.child());styled=StyleFractures.setMultiplier(PATH,styled,style.child(),"2");
        check(new LosslessJson(NoiseStyleFields.object(DimensionStacks.blend(stack(styled))).token("fracture").getBytes(StandardCharsets.UTF_8)).token("multiplier").equals("2"));
        for (String rawBlend : List.of("null","[]","true","1","\"snippet/blend\"")) {
            byte[] wrong=document("{\"blend\":"+rawBlend+"}");rejects(() -> DimensionStacks.enableBlend(PATH,wrong));rejects(() -> DimensionStacks.disableBlend(PATH,wrong));rejects(() -> NoiseStyleFields.enable(PATH,wrong,style,"style"));check(DimensionFields.readOnlyPaths(new LosslessJson(wrong)).contains("dimensionStack.blend"));
        }
        for (String rawStack : List.of("null","[]","true","1","\"snippet/stack\"")) {
            byte[] wrong=document(rawStack);rejects(() -> DimensionStacks.create(PATH,wrong,"top",pack));rejects(() -> DimensionStacks.disable(PATH,wrong));rejects(() -> DimensionStacks.replaceLayers(PATH,wrong,List.of("top","host"),pack));rejects(() -> DimensionStacks.enableBlend(PATH,wrong));rejects(() -> DimensionStacks.setUpper(PATH,wrong,"top",pack));check(DimensionFields.readOnlyPaths(new LosslessJson(wrong)).contains("dimensionStack"));
        }
        check(DimensionFields.readOnlyPaths(new LosslessJson(source)).containsAll(List.of("dimensionStack.futureStack","dimensionStack.blend.futureBlend","dimensionStack.blend.style.fracture.futureChild")) && !DimensionFields.readOnlyPaths(new LosslessJson(source)).contains("dimensionStack"));check(Arrays.equals(source,copy));
        // Atomic replacement preserves duplicate occurrence tokens and literal field names.
        byte[] dictionary=bytes("{\"dim.s\":[\"t\\u006fp\",\"top\"],\"future\":9007199254740993}");var replaced=new LosslessJson(new LosslessJson(dictionary).replaceStringArray("dim.s",List.of("top","top","top")));check(replaced.token("dim.s").equals("[\"t\\u006fp\", \"top\", \"top\"]") && replaced.token("future").equals("9007199254740993"));
        check(Arrays.equals(dictionary,new LosslessJson(dictionary).replaceStringArray("dim.s",List.of("top","top"))));rejects(() -> new LosslessJson(dictionary).replaceStringArray("dim.s",Arrays.asList("top",null)));
        for (String path : List.of("biomes/host.json","regions/host.json","generators/host.json","dimensions/nested/host.json","host.json")) {
            rejects(() -> DimensionStacks.host(path));rejects(() -> DimensionStacks.create(path,original,"top",pack));rejects(() -> DimensionStacks.disable(path,created));rejects(() -> DimensionStacks.replaceLayers(path,created,List.of("top","host"),pack));rejects(() -> DimensionStacks.setUpper(path,original,"top",pack));rejects(() -> DimensionStacks.enableScalar(path,created,"spacer"));rejects(() -> DimensionStacks.enableBlend(path,created));
        }
        var drafts=new InspectorDrafts();String order=DimensionStacks.prefix()+"dimensions",spacer=DimensionStacks.prefix()+"spacer",amplitude=DimensionStacks.blendPrefix()+"amplitude",styleDraft=style.child().draftPrefix()+"cacheSize",other="outside-stack#zoom";
        for (String key : List.of(order,spacer,amplitude,styleDraft,other)) drafts.update(key,"1","2");String staged=DimensionStacks.draftToken(List.of("missing","host"));check(DimensionStacks.draftLayers(staged).equals(List.of("missing","host")));
        rejects(() -> DimensionStacks.replaceLayers(PATH,created,DimensionStacks.draftLayers(staged),pack));check(drafts.count()==5 && Arrays.equals(pack.read(PATH),original));
        check(!drafts.transitionUnder(DimensionStacks.blendPrefix(),() -> false,() -> { throw new AssertionError(); }) && drafts.count()==5);
        try { drafts.transitionUnder(DimensionStacks.blendPrefix(),() -> true,() -> { throw new IOException("failed write"); });throw new AssertionError(); } catch (IOException expected) { check(drafts.count()==5); }
        check(drafts.transitionUnder(DimensionStacks.blendPrefix(),() -> true,() -> {}) && drafts.count()==3 && drafts.contains(order) && drafts.contains(spacer) && drafts.contains(other));check(drafts.transitionUnder(DimensionStacks.prefix(),() -> true,() -> {}) && drafts.count()==1);
        pack.replace(PATH,styled);pack.save(temp.resolve("dimension-stack"));pack.exportZip(temp.resolve("dimension-stack.zip"));var reopened=PackWorkspace.open(temp.resolve("dimension-stack"));var imported=PackWorkspace.importZip(temp.resolve("dimension-stack.zip"));for (String path : pack.paths()) check(Arrays.equals(pack.read(path),reopened.read(path)) && Arrays.equals(pack.read(path),imported.read(path)));
        check(FieldCoverage.fields("dimensionStack").size()==3 && FieldCoverage.fields("dimensionStack").stream().allMatch(FieldCoverage.Field::implemented) && FieldCoverage.fields("dimensionStackBlend").size()==2 && FieldCoverage.fields("dimensionStackBlend").stream().allMatch(FieldCoverage.Field::implemented));
        System.out.println("PASS: ordered dimension stacks/upper references, host and mutual-exclusion guards, atomic raw-token layer drafts, exact spacer/blend bounds, shared SIMPLEX/FLAT styles/fractures/sources, scoped removals and lossless Save/ZIP");
    }
}
