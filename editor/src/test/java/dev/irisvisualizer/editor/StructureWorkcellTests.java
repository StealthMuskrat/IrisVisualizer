package dev.irisvisualizer.editor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

final class StructureWorkcellTests {
    private static final String PATH="structures/nested/workcells.json";
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static String text(byte[] raw) { return new String(raw,StandardCharsets.UTF_8); }
    private static void check(boolean ok,String message) { if (!ok) throw new AssertionError("Planar workcell contract: "+message); }
    private static void rejects(Runnable action) { try { action.run(); } catch (IllegalArgumentException expected) { return; }throw new AssertionError("Expected rejected workcell input"); }
    private static LosslessJson root(byte[] raw) { return new LosslessJson(raw); }
    private static byte[] override(List<String> rows) { return bytes("{\"planarWorkcells\":["+String.join(",",rows)+"],\"futureRoot\":9007199254740993}"); }
    private static List<String> rows() { return StructureWorkcells.archetypes().stream().map(name -> "{\"archetype\":\""+name+"\"}").toList(); }
    private static byte[] changedRow(int index,String row) { var rows=new ArrayList<>(rows());rows.set(index,row);return override(rows); }
    private static void size(byte[] raw,int index,int width,int height,int depth) { check(StructureWorkcells.size(root(raw),index).equals(new StructureWorkcells.Size(width,height,depth)),"size "+index+" expected "+width+","+height+","+depth); }
    static void run(Path temp) throws Exception {
        byte[] empty=bytes("{}");size(empty,-1,15,15,15);size(bytes("{\"cellSize\":null}"),-1,16,16,16);size(bytes("{\"cellSize\":{}}"),-1,0,0,0);size(bytes("{\"cellSize\":{\"x\":3}}"),-1,3,0,0);
        check(StructureWorkcells.archetypes().equals(List.of("BLANK","END","STRAIGHT","CORNER","TEE","CROSS")),"canonical inventory");
        check(StructureWorkcells.minimum("width")==3 && StructureWorkcells.minimum("height")==1 && StructureWorkcells.maximum("height")==192 && StructureWorkcells.maximumVolume()==2_097_152,"native bounds");
        for (String token : List.of("absent","null","[]","[ ]")) {
            byte[] source=token.equals("absent") ? empty : bytes("{\"planarWorkcells\":"+token+"}");
            check(StructureWorkcells.entries(root(source)).isEmpty(),"fallback list state "+token);
            byte[] created=StructureWorkcells.create(PATH,source);var entries=StructureWorkcells.entries(root(created));check(entries.size()==6,"six explicit rows");
            for (int i=0;i<6;i++) { size(created,i,15,15,15);check(StructureWorkcells.enabled(root(created),i) && entries.get(i).token("enabled")==null && entries.get(i).token("displayName")==null && StructureWorkcells.archetype(entries.get(i)).equals(StructureWorkcells.archetypes().get(i)),"created identity/default "+i); }
            rejects(() -> StructureWorkcells.create(PATH,created));
        }
        byte[] fromNull=StructureWorkcells.create(PATH,bytes("{\"cellSize\":null,\"planarWorkcells\":null}"));size(fromNull,0,16,16,16);check(root(fromNull).token("cellSize").equals("null"),"null legacy preserved on map creation");
        String legacy="{\r\n \"cellSize\":{\"x\":7e0,\"y\":13,\"z\":5,\"future\":\"雪\\u0061\"},\"mode\":\"SPATIAL_JIGSAW\",\"opaque\":9007199254740993\r\n}";
        byte[] original=bytes(legacy),created=StructureWorkcells.create(PATH,original);
        check(root(created).token("cellSize").equals(root(original).token("cellSize")) && root(created).stringToken("mode").equals("SPATIAL_JIGSAW"),"creation preserves legacy spelling/mode");for (int i=0;i<6;i++) size(created,i,7,13,5);
        byte[] changedLegacy=StructureWorkcells.setBounds(PATH,original,-1,"7.000","14","5");check(text(changedLegacy).equals(legacy.replace("\"y\":13","\"y\":14")),"paired legacy writes preserve equal exponent and unknown data");
        check(Arrays.equals(StructureWorkcells.setBounds(PATH,original,-1,"7","13","5"),original),"exact legacy no-op");size(StructureWorkcells.setBounds(PATH,empty,-1,"3","1","3"),-1,3,1,3);size(StructureWorkcells.setBounds(PATH,bytes("{\"cellSize\":null}"),-1,"16","16","16"),-1,16,16,16);
        size(StructureWorkcells.setBounds(PATH,bytes("{\"cellSize\":{\"future\":true}}"),-1,"4","5","6"),-1,4,5,6);
        check(root(StructureWorkcells.setBounds(PATH,bytes("{\"cellSize\":{\"future\":true}}"),-1,"4","5","6")).token("cellSize").contains("\"future\":true"),"partial legacy repair retains unknown children");
        String row="{\"archetype\":\"\\u0042LANK\",\"width\":16e0,\"height\":16,\"depth\":16,\"enabled\":false,\"displayName\":{\"kept\":true},\"future\":9007199254740993}";
        byte[] stored=changedRow(0,row);check(StructureWorkcells.entries(root(stored)).size()==6,"escaped archetype accepted");
        check(Arrays.equals(StructureWorkcells.setBounds(PATH,stored,0,"16","16","16"),stored),"exact cell no-op");
        byte[] changed=StructureWorkcells.setBounds(PATH,stored,0,"16","17","16");check(text(changed).equals(text(stored).replace("\"height\":16","\"height\":17")),"single target change retains raw siblings and read-only metadata");
        check(StructureWorkcells.otherFields(root(stored),0).equals(List.of("displayName","future")) && StructureWorkcells.otherFields(root(original),-1).equals(List.of("future")),"read-only children reported");
        for (int index : List.of(-1,0)) {
            byte[] source=index<0 ? original : stored;
            for (String[] value : List.of(new String[]{"3","1","3"},new String[]{"128","128","128"},new String[]{"128","192","85"})) {
                byte[] next=StructureWorkcells.setBounds(PATH,source,index,value[0],value[1],value[2]);size(next,index,Integer.parseInt(value[0]),Integer.parseInt(value[1]),Integer.parseInt(value[2]));
            }
            for (String[] value : List.of(new String[]{"128","129","128"},new String[]{"128","192","86"},new String[]{"2","1","3"},new String[]{"3","0","3"},new String[]{"3","1","129"},new String[]{"129","1","3"},new String[]{"3","193","3"},new String[]{"3.0000000000001","16","16"},new String[]{"2147483648","16","16"},new String[]{"NaN","16","16"},new String[]{"16","Infinity","16"}))
                rejects(() -> StructureWorkcells.setBounds(PATH,source,index,value[0],value[1],value[2]));
        }
        check(StructureWorkcells.size(root(StructureWorkcells.setBounds(PATH,stored,0,"128","128","128")),0).volume()==StructureWorkcells.maximumVolume(),"inclusive volume boundary");
        // Every disabled workcell still requires bounded dimensions; toggles remain independent of malformed sizes.
        check(!StructureWorkcells.enabled(root(stored),0) && Arrays.equals(StructureWorkcells.setEnabled(PATH,stored,0,false),stored),"false flag no-op");
        byte[] implicit=override(rows());check(StructureWorkcells.enabled(root(implicit),2),"implicit true flag");byte[] toggled=StructureWorkcells.setEnabled(PATH,implicit,2,false);check(!StructureWorkcells.enabled(root(toggled),2) && StructureWorkcells.entries(root(toggled)).get(2).token("width")==null,"toggle preserves absent size defaults");
        for (String token : List.of("null","true","\"16\"","[]","{}","16.00000000001")) {
            byte[] bad=changedRow(0,"{\"archetype\":\"BLANK\",\"width\":"+token+"}");rejects(() -> StructureWorkcells.setBounds(PATH,bad,0,"16","16","16"));
            size(StructureWorkcells.setBounds(PATH,bad,1,"17","18","19"),1,17,18,19);check(!StructureWorkcells.enabled(root(StructureWorkcells.setEnabled(PATH,bad,0,false)),0),"flag independent of unsupported size");
        }
        for (String token : List.of("null","1","\"false\"","[]")) { byte[] bad=changedRow(0,"{\"archetype\":\"BLANK\",\"enabled\":"+token+"}");rejects(() -> StructureWorkcells.setEnabled(PATH,bad,0,true));size(StructureWorkcells.setBounds(PATH,bad,0,"17","18","19"),0,17,18,19); }
        var reverse=new ArrayList<>(rows());Collections.reverse(reverse);check(StructureWorkcells.archetype(StructureWorkcells.entries(root(override(reverse))).getFirst()).equals("CROSS"),"source order retained");
        var defaultIdentity=new ArrayList<>(rows());defaultIdentity.set(0,"{}");check(StructureWorkcells.archetype(StructureWorkcells.entries(root(override(defaultIdentity))).getFirst()).equals("BLANK"),"absent identity uses class default");
        var invalidMaps=new ArrayList<byte[]>();invalidMaps.add(override(rows().subList(0,5)));var seven=new ArrayList<>(rows());seven.add(rows().getFirst());invalidMaps.add(override(seven));
        for (String token : List.of("null","\"END\"","\"unknown\"","1")) invalidMaps.add(changedRow(0,"{\"archetype\":"+token+"}"));
        for (String token : List.of("\"snippet/map\"","{}","false","[null]","[\"snippet/row\"]")) invalidMaps.add(bytes("{\"planarWorkcells\":"+token+"}"));
        for (byte[] bad : invalidMaps) { rejects(() -> StructureWorkcells.entries(root(bad)));rejects(() -> StructureWorkcells.create(PATH,bad));rejects(() -> StructureWorkcells.setBounds(PATH,bad,0,"16","16","16"));rejects(() -> StructureWorkcells.setEnabled(PATH,bad,0,true));size(StructureWorkcells.setBounds(PATH,bad,-1,"4","5","6"),-1,4,5,6); }
        for (String token : List.of("\"snippet/size\"","[]","1","false","{\"x\":null}","{\"x\":\"3\"}")) { byte[] bad=bytes("{\"cellSize\":"+token+"}");rejects(() -> StructureWorkcells.size(root(bad),-1));rejects(() -> StructureWorkcells.create(PATH,bad));rejects(() -> StructureWorkcells.setBounds(PATH,bad,-1,"3","1","3")); }
        rejects(() -> StructureWorkcells.create(PATH,bytes("{\"cellSize\":{}}")));rejects(() -> StructureWorkcells.create(PATH,bytes("{\"cellSize\":{\"x\":128,\"y\":192,\"z\":128}}")));
        for (String path : List.of("biomes/x.json","dimensions/x.json","jigsaw-pools/x.json","structures/x.JSON","structures/../x.json")) { rejects(() -> StructureWorkcells.create(path,empty));rejects(() -> StructureWorkcells.setBounds(path,stored,0,"16","16","16"));rejects(() -> StructureWorkcells.setEnabled(path,stored,0,false)); }
        for (int index : List.of(-2,6,Integer.MAX_VALUE)) { rejects(() -> StructureWorkcells.prefix(index));rejects(() -> StructureWorkcells.setBounds(PATH,stored,index,"16","16","16"));rejects(() -> StructureWorkcells.setEnabled(PATH,stored,index,true)); }
        rejects(() -> StructureWorkcells.setEnabled(PATH,stored,-1,true));rejects(() -> StructureWorkcells.setBounds(PATH,empty,0,"16","16","16"));
        check(FieldCoverage.fields("structureWorkcell").size()==6 && FieldCoverage.fields("structureWorkcell").stream().filter(FieldCoverage.Field::implemented).count()==5,"bounded class coverage includes creation identities");
        // Applied paired edits are one journal entry; draft guards retain unrelated and legacy scopes.
        var pack=PackWorkspace.newPack(Map.of(PATH,original,"future/malformed.json",bytes("{bad"),"objects/opaque.iob",new byte[]{0,-1,7}));pack.save(temp.resolve("workcell-source"));
        var drafts=new InspectorDrafts();String legacyKey=StructureWorkcells.prefix(-1)+"bounds",entryKey=StructureWorkcells.prefix(0)+"bounds";drafts.update(legacyKey,"7|13|5","8|13|5");drafts.update(entryKey,"old","pending");drafts.update("unrelated","old","pending");
        check(!drafts.transitionUnder(StructureWorkcells.entriesPrefix(),() -> false,() -> pack.replace(PATH,StructureWorkcells.create(PATH,pack.read(PATH)))) && Arrays.equals(pack.read(PATH),original) && drafts.contains(entryKey),"cancel preserves source/drafts");
        check(drafts.transitionUnder(StructureWorkcells.entriesPrefix(),() -> true,() -> pack.replace(PATH,StructureWorkcells.create(PATH,pack.read(PATH)))) && !drafts.contains(entryKey) && drafts.contains(legacyKey) && drafts.contains("unrelated"),"create clears indexed drafts only");
        byte[] before=pack.read(PATH);int history=pack.undoCount();pack.replace(PATH,StructureWorkcells.setBounds(PATH,before,0,"128","128","128"));check(pack.undoCount()==history+1,"paired edit is one atomic journal entry");pack.undo();check(Arrays.equals(pack.read(PATH),before),"paired Undo exact");pack.redo();size(pack.read(PATH),0,128,128,128);
        before=pack.read(PATH);long revision=pack.revision();history=pack.undoCount();drafts.update(entryKey,"128|128|128","128|129|128");
        try { drafts.transitionUnder(StructureWorkcells.prefix(0),() -> true,() -> pack.replace(PATH,StructureWorkcells.setBounds(PATH,pack.read(PATH),0,"128","129","128")));throw new AssertionError(); } catch (IllegalArgumentException expected) { }
        check(Arrays.equals(pack.read(PATH),before) && pack.revision()==revision && pack.undoCount()==history && drafts.contains(legacyKey) && drafts.contains(entryKey),"failed bounds preserve bytes/history/drafts");
        pack.save(pack.directory());pack.saveCopy(temp.resolve("workcell-copy"));pack.saveAs(temp.resolve("workcell-as"));pack.exportZip(temp.resolve("workcells.zip"));
        for (var reopened : List.of(PackWorkspace.open(temp.resolve("workcell-source")),PackWorkspace.open(temp.resolve("workcell-copy")),PackWorkspace.open(temp.resolve("workcell-as")),PackWorkspace.importZip(temp.resolve("workcells.zip")))) for (String path : pack.paths()) check(Arrays.equals(pack.read(path),reopened.read(path)),"exact persistence "+path);
        check(drafts.contains(legacyKey) && drafts.contains("unrelated"),"unrelated drafts retained through saving");
        System.out.println("PASS: planar workcell constructor/null/partial defaults, explicit complete identity creation, exact paired bounds/disabled volume guards, unsupported-map/type preservation, independent entry repair, draft scope/cancellation/failure isolation, atomic Undo/Redo and byte-exact Save/Copy/As/ZIP without native assembly");
    }
}
