package dev.irisvisualizer.editor;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

final class ProceduralShapeTests {
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static String sibling(String kind) { return kind.equals("trees") ? "crystals" : "trees"; }
    private static byte[] root(String kind,String row) { return bytes("{\r\n \"proceduralObjects\":{\""+kind+"\":["+row+",{\"name\":\"untouched\",\"seed\":9223372036854775807}],\""+sibling(kind)+"\":[{\"name\":\"sibling\",\"seed\":-9007199254740993}],\"futureOwner\":9223372036854775807},\"objects\":[{\"place\":[\"opaque\"],\"chance\":7e-1}],\"futureRoot\":\"雪\"\r\n}"); }
    private static LosslessJson row(byte[] raw,String kind,int index) { return ProceduralObjects.entries(new LosslessJson(raw),kind).get(index); }
    private static void check(boolean valid) { if (!valid) throw new AssertionError("Procedural shape contract failed"); }
    private static void rejects(Runnable action) { try { action.run(); } catch (IllegalArgumentException expected) { return; }throw new AssertionError("Expected rejected procedural shape operation"); }
    private static void preserved(byte[] before,byte[] after,String kind) { var a=new LosslessJson(before);var b=new LosslessJson(after);check(a.token("objects").equals(b.token("objects")) && a.token("futureRoot").equals(b.token("futureRoot")) && ProceduralObjects.object(a).token(sibling(kind)).equals(ProceduralObjects.object(b).token(sibling(kind))) && row(before,kind,1).sourceBytes().length==row(after,kind,1).sourceBytes().length && Arrays.equals(row(before,kind,1).sourceBytes(),row(after,kind,1).sourceBytes()) && row(after,kind,0).token("future").equals("9007199254740993") && row(after,kind,0).token("palette").equals(row(before,kind,0).token("palette"))); }
    static void run(Path temp) throws Exception {
        check(ProceduralObjects.LIST_FIELDS.stream().mapToInt(k -> ProceduralShapes.fields(k).size()).sum()==131 && ProceduralObjects.LIST_FIELDS.stream().mapToInt(k -> ProceduralShapes.pairs(k).size()).sum()==11);
        check(ProceduralObjects.choices("trees","profile").contains("MEGA_SPRUCE") && ProceduralObjects.choices("trees","trunkShape").contains("EASE_IN_OUT") && ProceduralObjects.choices("crystals","distribution").contains("GOLDEN_ANGLE") && !ProceduralObjects.choices("formations","profile").contains("MEGA_SPRUCE"));
        for (String base : List.of("biomes","regions")) for (String kind : ProceduralObjects.LIST_FIELDS) {
            String path=base+"/nested/world.json";var c=new ProceduralObjects.Context(base,kind,0);byte[] raw=root(kind,"{\"seed\":9007199254740993,\"future\":9007199254740993,\"palette\":{\"n\":9223372036854775807},\"canopy\":{\"future\":true},\"trunk\":\"mod:preserved\"}");
            for (String field : ProceduralShapes.fields(kind)) {
                String type=ProceduralObjects.type(kind,field),value=ProceduralObjects.defaultValue(kind,field);check(!ProceduralObjects.help(kind,field).isBlank() && !ProceduralShapes.property(kind,field,"description").isBlank());
                byte[] changed=ProceduralObjects.set(path,raw,c,field,value);preserved(raw,changed,kind);check(Arrays.equals(changed,ProceduralObjects.set(path,changed,c,field,value)) && Arrays.equals(raw,ProceduralObjects.resetLeaf(path,changed,c,field)));
                check(!ProceduralObjects.readOnlyPaths(new LosslessJson(changed)).contains(kind+"[0]."+field));
                for (String bad : List.of("null","{}","[]","\"opaque\"")) { if (bad.equals("\"opaque\"") && !ProceduralObjects.choices(kind,field).isEmpty()) continue;byte[] malformed=root(kind,"{\""+field+"\":"+bad+",\"future\":9007199254740993,\"palette\":{}}");rejects(() -> ProceduralObjects.set(path,malformed,c,field,value));rejects(() -> ProceduralObjects.resetLeaf(path,malformed,c,field));check(ProceduralObjects.readOnlyPaths(new LosslessJson(malformed)).contains(kind+"[0]."+field)); }
                if (type.equals("boolean")) { check(row(ProceduralObjects.set(path,raw,c,field,"true"),kind,0).token(field).equals("true") && row(ProceduralObjects.set(path,raw,c,field,"false"),kind,0).token(field).equals("false"));rejects(() -> ProceduralObjects.set(path,raw,c,field,"null")); }
                else if (!ProceduralObjects.choices(kind,field).isEmpty()) { for (String choice : ProceduralObjects.choices(kind,field)) { check(!ProceduralObjects.choiceHelp(kind,field,choice).isBlank());check(row(ProceduralObjects.set(path,raw,c,field,choice),kind,0).stringToken(field).equals(choice)); }rejects(() -> ProceduralObjects.set(path,raw,c,field,"UNKNOWN"));byte[] escaped=ProceduralObjects.set(path,raw,c,field,value);String stringToken=row(escaped,kind,0).token(field),replacement="\"\\u"+String.format("%04x",(int)value.charAt(0))+value.substring(1)+"\"";byte[] encoded=bytes(new String(escaped,StandardCharsets.UTF_8).replace(stringToken,replacement));check(Arrays.equals(encoded,ProceduralObjects.set(path,encoded,c,field,value))); }
                else {
                    String min=ProceduralObjects.property(kind,field,"minimum"),max=ProceduralObjects.property(kind,field,"maximum");
                    for (String boundary : new String[]{min,max}) if (boundary!=null) { byte[] limit=ProceduralObjects.set(path,raw,c,field,boundary);check(new BigDecimal(row(limit,kind,0).token(field)).compareTo(new BigDecimal(boundary))==0); }
                    if (min!=null) { String below=new BigDecimal(min).subtract(BigDecimal.ONE).toString();rejects(() -> ProceduralObjects.set(path,raw,c,field,below)); }if (max!=null) { String above=new BigDecimal(max).add(BigDecimal.ONE).toString();rejects(() -> ProceduralObjects.set(path,raw,c,field,above)); }
                    for (String bad : List.of("NaN","Infinity","1e1000","1e-1000",""," 1")) rejects(() -> ProceduralObjects.set(path,raw,c,field,bad));
                    if (type.equals("int")) { for (String bad : List.of("1.5","2147483648","-2147483649")) rejects(() -> ProceduralObjects.set(path,raw,c,field,bad)); }
                    String number=new BigDecimal(value).stripTrailingZeros().toPlainString();byte[] exponent=root(kind,"{\""+field+"\":"+number+"e0,\"future\":9007199254740993,\"palette\":{}}");check(Arrays.equals(exponent,ProceduralObjects.set(path,exponent,c,field,value)));
                    if (min==null && max==null) check(new BigDecimal(row(ProceduralObjects.set(path,raw,c,field,"-2.5"),kind,0).token(field)).compareTo(new BigDecimal("-2.5"))==0);
                }
                String other=base.equals("biomes") ? "regions" : "biomes";rejects(() -> ProceduralObjects.set(other+"/world.json",raw,c,field,value));rejects(() -> ProceduralObjects.set("dimensions/world.json",raw,c,field,value));
            }
            for (var pair : ProceduralShapes.pairs(kind)) {
                String first=pair.get(0),second=pair.get(1);byte[] reversed=ProceduralShapes.setPair(path,raw,c,first,"9","2");preserved(raw,reversed,kind);check(row(reversed,kind,0).token(first).equals("9") && row(reversed,kind,0).token(second).equals("2") && Arrays.equals(reversed,ProceduralShapes.setPair(path,reversed,c,first,"9e0","2.0")) && Arrays.equals(raw,ProceduralShapes.resetPair(path,reversed,c,first)));
                byte[] identical=ProceduralShapes.setPair(path,raw,c,first,"3","3");check(row(identical,kind,0).token(first).equals("3") && row(identical,kind,0).token(second).equals("3"));
                byte[] snapshot=raw.clone();rejects(() -> ProceduralShapes.setPair(path,raw,c,first,"7","0"));check(Arrays.equals(raw,snapshot));
                String scope=c.prefix()+"shapeRange#"+first+".",draft=scope+"tuple",outside=c.prefix()+"seed";var drafts=new InspectorDrafts();drafts.update(draft,"1|2","3|4");drafts.update(outside,"1","2");check(drafts.anyOtherUnder(scope,null) && !drafts.transitionUnder(scope,() -> false,() -> {}) && drafts.count()==2);try { drafts.transitionUnder(scope,() -> true,() -> { throw new IOException("failed"); });throw new AssertionError(); } catch (IOException expected) { check(drafts.count()==2); }check(drafts.transitionUnder(scope,() -> true,() -> {}) && drafts.count()==1 && drafts.contains(outside));
            }
            check(ProceduralObjects.readOnlyPaths(new LosslessJson(raw)).containsAll(List.of(kind+"[0].palette",kind+"[0].canopy")));
            rejects(() -> ProceduralObjects.set(path,raw,c,"trunk","minecraft:oak_log"));rejects(() -> ProceduralObjects.set(path,raw,c,"canopy","{}"));rejects(() -> ProceduralShapes.setPair(path,raw,c,"seed","1","2"));
            byte[] oversized=bytes("{\"proceduralObjects\":{\""+kind+"\":["+String.join(",",Collections.nCopies(65,"{}"))+"]}}");String field=ProceduralShapes.fields(kind).get(0),value=ProceduralObjects.defaultValue(kind,field);rejects(() -> ProceduralObjects.set(path,oversized,c,field,value));rejects(() -> ProceduralObjects.set(path,raw,new ProceduralObjects.Context(base,kind,2),field,value));
            byte[] changed=ProceduralObjects.set(path,raw,c,field,value);var pack=PackWorkspace.newPack(Map.of(path,raw,"objects/opaque.iob",new byte[]{0,1,2}));pack.replace(path,changed);String tag=base+"-"+kind;pack.save(temp.resolve("procedural-shapes-"+tag));pack.exportZip(temp.resolve("procedural-shapes-"+tag+".zip"));var saved=PackWorkspace.open(temp.resolve("procedural-shapes-"+tag));var imported=PackWorkspace.importZip(temp.resolve("procedural-shapes-"+tag+".zip"));for (String key : pack.paths()) check(Arrays.equals(pack.read(key),saved.read(key)) && Arrays.equals(pack.read(key),imported.read(key)));
        }
        rejects(() -> ProceduralShapes.fields(null));rejects(() -> ProceduralShapes.fields("unknown"));rejects(() -> ProceduralShapes.setPair("biomes/world.json",bytes("{}"),null,"heightMin","1","2"));
        check(FieldCoverage.fields("proceduralTree").stream().filter(FieldCoverage.Field::implemented).count()==58 && FieldCoverage.fields("proceduralFormation").stream().filter(FieldCoverage.Field::implemented).count()==50);
        System.out.println("PASS: 131 procedural shape controls across six types and two hosts, twelve complete/scoped enums, native defaults/bounds and exact/raw no-ops, eleven atomic equal/reversed range pairs, null/type/width/path/index/size guards, preserved materials/palettes/canopies/siblings, scoped range drafts and lossless Save/ZIP without baking or geometry");
    }
}
