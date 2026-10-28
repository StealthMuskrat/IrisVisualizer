package dev.irisvisualizer.editor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

final class NativeJigsawOverridesTests {
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static byte[] single(String jigsaw) { return bytes("{\r\n \"structures\":[{\"placementId\":\"stable\\u005fID\",\"nativeStructures\":[{\"structure\":\"mod:s\\u006furce\",\"weight\":2e0"+(jigsaw==null ? "" : ",\"jigsaw\":"+jigsaw)+",\"futureSource\":9007199254740993},{\"structure\":\"mod:other\",\"jigsaw\":{\"maxDepth\":7}}],\"terrain\":{\"mode\":\"SOURCE\"},\"futurePlacement\":9223372036854775807},{\"structures\":[\"untouched\"]}],\"future\":\"雪\"\r\n}"); }
    private static LosslessJson source(byte[] raw,int source) { return StructurePlacements.natives(StructurePlacements.entries(new LosslessJson(raw)).get(0)).get(source); }
    private static LosslessJson object(byte[] raw) { return NativeJigsawOverrides.object(source(raw,0)); }
    private static void check(boolean valid) { if (!valid) throw new AssertionError("Native jigsaw contract failed"); }
    private static void rejects(Runnable action) { try { action.run(); } catch (IllegalArgumentException expected) { return; }throw new AssertionError("Expected rejected native jigsaw operation"); }
    static void run(Path temp) throws Exception {
        check(NativeJigsawOverrides.EDITABLE.size()==10 && NativeJigsawOverrides.NUMBERS.size()==5 && NativeJigsawOverrides.choices("projectStartToHeightmap").size()==8 && NativeJigsawOverrides.choices("liquidSettings").size()==3);
        for (String base : StructurePlacements.CONTEXTS) {
            String path=base+"/nested/world.json";var c=new NativeJigsawOverrides.Context(base,0,0);byte[] original=single(null),empty=NativeJigsawOverrides.enable(path,original,c);
            check(object(empty).fields().isEmpty() && Arrays.equals(original,NativeJigsawOverrides.reset(path,empty,c)));rejects(() -> NativeJigsawOverrides.enable(path,empty,c));
            check(Arrays.equals(original,NativeJigsawOverrides.reset(path,original,c)));
            byte[] nullable=single("null");check(object(nullable)==null && object(NativeJigsawOverrides.enable(path,nullable,c)).fields().isEmpty() && source(NativeJigsawOverrides.reset(path,nullable,c),0).token("jigsaw")==null);
            byte[] precise=single("{\"maxDepth\":2e0,\"startPool\":\"mod:p\\u006fol\",\"startJigsawName\":\"NONE\",\"projectStartToHeightmap\":\"SOUR\\u0043E\",\"useExpansionHack\":false,\"future\":9007199254740993}");
            byte[] cleared=NativeJigsawOverrides.reset(path,precise,c);check(source(cleared,0).token("jigsaw")==null && source(cleared,1).token("jigsaw").equals(source(precise,1).token("jigsaw")) && source(cleared,0).token("futureSource").equals("9007199254740993"));
            for (String[] pair : List.of(new String[]{"maxDepth","2.00"},new String[]{"startPool","mod:pool"},new String[]{"startJigsawName","NONE"},new String[]{"projectStartToHeightmap","SOURCE"},new String[]{"useExpansionHack","false"})) check(Arrays.equals(precise,NativeJigsawOverrides.set(path,precise,c,pair[0],pair[1])));
            List<String[]> bounds=List.of(new String[]{"maxDepth","0","20","21"},new String[]{"maxDistanceHorizontal","1","128","129"},new String[]{"maxDistanceVertical","1","4064","4065"},new String[]{"dimensionPaddingBottom","0","2147483647","2147483648"},new String[]{"dimensionPaddingTop","0","2147483647","2147483648"});
            for (String[] limit : bounds) { String field=limit[0];for (String valid : List.of(limit[1],limit[2])) check(object(NativeJigsawOverrides.set(path,empty,c,field,valid)).token(field).equals(valid));for (String invalid : List.of("-1",limit[3],"NaN","Infinity","1e1000","1.5",""," 1")) rejects(() -> NativeJigsawOverrides.set(path,empty,c,field,invalid));byte[] nullNumber=single("{\""+field+"\":null}");check(NativeJigsawOverrides.value(object(nullNumber),field).isEmpty() && object(NativeJigsawOverrides.set(path,nullNumber,c,field,limit[1])).token(field).equals(limit[1])); }
            for (String field : NativeJigsawOverrides.EDITABLE) {
                check(NativeJigsawOverrides.help(field)!=null && !NativeJigsawOverrides.help(field).isBlank());
                String input=NativeJigsawOverrides.NUMBERS.contains(field) ? "1" : field.equals("useExpansionHack") ? "true" : NativeJigsawOverrides.STRINGS.contains(field) ? "mod:key" : "SOURCE";
                byte[] changed=NativeJigsawOverrides.set(path,precise,c,field,input);check(object(changed).token("future").equals("9007199254740993") && source(changed,1).token("jigsaw").equals(source(precise,1).token("jigsaw")) && source(changed,0).token("weight").equals("2e0") && source(changed,0).token("structure").equals(source(precise,0).token("structure")));
                check(object(NativeJigsawOverrides.resetLeaf(path,changed,c,field)).token(field)==null);
                byte[] nullLeaf=single("{\""+field+"\":null}");check(object(NativeJigsawOverrides.set(path,nullLeaf,c,field,input)).token(field)!=null && object(NativeJigsawOverrides.resetLeaf(path,nullLeaf,c,field)).fields().isEmpty());
                for (String invalid : List.of("{}","[]")) { byte[] malformed=single("{\""+field+"\":"+invalid+"}");rejects(() -> NativeJigsawOverrides.set(path,malformed,c,field,input));rejects(() -> NativeJigsawOverrides.resetLeaf(path,malformed,c,field));check(StructurePlacements.readOnlyPaths(new LosslessJson(malformed),base).contains("structures[0].nativeStructures[0].jigsaw."+field)); }
                rejects(() -> NativeJigsawOverrides.set(path,original,c,field,input));
                if (!NativeJigsawOverrides.choices(field).isEmpty()) for (String choice : NativeJigsawOverrides.choices(field)) check(object(NativeJigsawOverrides.set(path,empty,c,field,choice)).stringToken(field).equals(choice));
            }
            for (String field : NativeJigsawOverrides.STRINGS) { check(object(NativeJigsawOverrides.set(path,empty,c,field,"")).stringToken(field).isEmpty());for (String invalid : List.of("Mod:pool","missingnamespace","mod:"," mod:pool","mod:key?","mod:key\u0000")) rejects(() -> NativeJigsawOverrides.set(path,empty,c,field,invalid)); }
            check(object(NativeJigsawOverrides.set(path,empty,c,"startJigsawName","NONE")).stringToken("startJigsawName").equals("NONE"));rejects(() -> NativeJigsawOverrides.set(path,empty,c,"startPool","NONE"));
            rejects(() -> NativeJigsawOverrides.set(path,empty,c,"useExpansionHack","null"));rejects(() -> NativeJigsawOverrides.set(path,empty,c,"liquidSettings","DRY"));rejects(() -> NativeJigsawOverrides.set(path,empty,c,"future","1"));
            for (String invalid : List.of("\"snippet\"","[]","3","false")) { byte[] malformed=single(invalid);rejects(() -> NativeJigsawOverrides.enable(path,malformed,c));rejects(() -> NativeJigsawOverrides.reset(path,malformed,c));check(StructurePlacements.readOnlyPaths(new LosslessJson(malformed),base).contains("structures[0].nativeStructures[0].jigsaw")); }
            for (String invalid : List.of("1.5","2147483648","\"1\"","false")) { byte[] malformed=single("{\"maxDepth\":"+invalid+"}");rejects(() -> NativeJigsawOverrides.set(path,malformed,c,"maxDepth","1")); }
            check(StructurePlacements.readOnlyPaths(new LosslessJson(precise),base).contains("structures[0].nativeStructures[0].jigsaw.future"));
            rejects(() -> NativeJigsawOverrides.enable("generators/test.json",original,c));String other=base.equals("biomes") ? "regions" : "biomes";rejects(() -> NativeJigsawOverrides.set(other+"/test.json",empty,c,"maxDepth","1"));
            rejects(() -> NativeJigsawOverrides.enable(path,original,new NativeJigsawOverrides.Context(base,1,0)));rejects(() -> NativeJigsawOverrides.enable(path,original,new NativeJigsawOverrides.Context(base,0,2)));
            for (String backend : List.of("\"structures\":[\"assembly\"]","\"structures\":[\"assembly\"],\"nativeStructures\":[{}]","\"nativeStructures\":[]","\"nativeStructures\":[\"snippet\"]")) { byte[] malformed=bytes("{\"structures\":[{"+backend+"}]}");rejects(() -> NativeJigsawOverrides.enable(path,malformed,c)); }
            byte[] manyPlacements=bytes("{\"structures\":["+String.join(",",Collections.nCopies(65,"{}"))+"]}"),manySources=bytes("{\"structures\":[{\"nativeStructures\":["+String.join(",",Collections.nCopies(65,"{}"))+"]}]}");rejects(() -> NativeJigsawOverrides.enable(path,manyPlacements,c));rejects(() -> NativeJigsawOverrides.enable(path,manySources,c));
            var drafts=new InspectorDrafts();String inside=c.prefix()+"startPool",outside=new NativeJigsawOverrides.Context(base,0,1).prefix()+"startPool";drafts.update(inside,"a","b");drafts.update(outside,"a","b");check(!drafts.transitionUnder(c.prefix(),() -> false,() -> {}) && drafts.count()==2);try { drafts.transitionUnder(c.prefix(),() -> true,() -> { throw new IOException("failure"); });throw new AssertionError(); } catch (IOException expected) { check(drafts.count()==2); }check(drafts.transitionUnder(c.prefix(),() -> true,() -> {}) && drafts.count()==1 && drafts.contains(outside));
            byte[] changed=NativeJigsawOverrides.set(path,precise,c,"maxDepth","20");var pack=PackWorkspace.newPack(Map.of(path,precise,"objects/opaque.iob",new byte[]{0,1,2}));pack.replace(path,changed);pack.save(temp.resolve("native-jigsaw-"+base));pack.exportZip(temp.resolve("native-jigsaw-"+base+".zip"));var saved=PackWorkspace.open(temp.resolve("native-jigsaw-"+base));var imported=PackWorkspace.importZip(temp.resolve("native-jigsaw-"+base+".zip"));for (String key : pack.paths()) check(Arrays.equals(pack.read(key),saved.read(key)) && Arrays.equals(pack.read(key),imported.read(key)));
            check(FieldCoverage.groups(path).contains("jigsawConfiguration"));
        }
        rejects(() -> new NativeJigsawOverrides.Context(null,0,0));rejects(() -> new NativeJigsawOverrides.Context("generators",0,0));rejects(() -> new NativeJigsawOverrides.Context("dimensions",-1,0));rejects(() -> new NativeJigsawOverrides.Context("dimensions",0,-1));check(FieldCoverage.fields("jigsawConfiguration").size()==10 && FieldCoverage.fields("jigsawConfiguration").stream().allMatch(FieldCoverage.Field::implemented));
        System.out.println("PASS: native jigsaw overrides across three hosts, inherited/null/reset distinctions, exact integer bounds and raw no-ops, pool/connector syntax and NONE, all projection/liquid choices, host/backend/index/size/type guards, sibling/unknown preservation, scoped drafts and lossless Save/ZIP without assembly");
    }
}
