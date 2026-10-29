package dev.irisvisualizer.editor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

final class StructureThemeTests {
    private static final String PATH="structures/nested/themes.json";
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static String text(byte[] raw) { return new String(raw,StandardCharsets.UTF_8); }
    private static LosslessJson root(byte[] raw) { return new LosslessJson(raw); }
    private static void check(boolean ok,String message) { if (!ok) throw new AssertionError("Structure theme contract: "+message); }
    private static void rejects(Runnable action) { try { action.run(); } catch (IllegalArgumentException expected) { return; }throw new AssertionError("Expected rejected theme operation"); }
    static void run(Path temp) throws Exception {
        byte[] empty=bytes("{}"),explicitEmpty=bytes("{\"themeSets\":[]}");
        check(StructureThemes.entries(root(empty)).isEmpty() && StructureThemes.entries(root(explicitEmpty)).isEmpty(),"implicit/explicit empty read without mutation");
        check(StructureThemes.weight(root(empty))==1 && StructureThemes.key(root(empty)).isEmpty(),"row class defaults");
        check(StructureThemes.minimumWeight()==1 && StructureThemes.maximumWeight()==Integer.MAX_VALUE,"native int limits");
        byte[] first=StructureThemes.append(PATH,empty,"Warm","1");var row=StructureThemes.entries(root(first)).getFirst();check(StructureThemes.key(row).equals("Warm") && row.token("weight").equals("1") && row.fields().size()==2,"first declared theme only");
        check(Arrays.equals(StructureThemes.resetEmpty(PATH,empty),empty) && root(StructureThemes.resetEmpty(PATH,explicitEmpty)).token("themeSets")==null && root(StructureThemes.resetEmpty(PATH,bytes("{\"themeSets\":null,\"future\":1}"))).token("future").equals("1"),"safe empty/null removal");
        rejects(() -> StructureThemes.entries(root(bytes("{\"themeSets\":null}"))));rejects(() -> StructureThemes.append(PATH,bytes("{\"themeSets\":null}"),"Warm","1"));rejects(() -> StructureThemes.resetEmpty(PATH,first));
        String original="{\r\n \"themeSets\":[{\"key\":\"H\\u006ft\",\"weight\":1e0,\"future\":9007199254740993},{\"key\":\"Cold\",\"future\":\"雪\\u0061\"}],\"themeOther\":{\"kept\":true},\"mode\":\"PLANAR_JIGSAW\"\r\n}";
        byte[] raw=bytes(original);check(Arrays.equals(StructureThemes.setWeight(PATH,raw,0,"1.000"),raw),"exact numeric no-op");
        check(text(StructureThemes.setWeight(PATH,raw,0,"2")).equals(original.replace("\"weight\":1e0","\"weight\":2")),"weight-only raw span edit");
        check(StructureThemes.otherFields(StructureThemes.entries(root(raw)).getFirst()).equals(List.of("future")),"unknown row data visible");
        byte[] defaultStored=StructureThemes.setWeight(PATH,raw,1,"1");check(StructureThemes.entries(root(defaultStored)).get(1).token("weight").equals("1") && StructureThemes.entries(root(raw)).get(1).token("weight")==null,"explicit default storage only");
        for (String input : List.of("0","-1","2147483648","1.00000000000001","NaN","Infinity","","1e99999")) { rejects(() -> StructureThemes.setWeight(PATH,raw,0,input));rejects(() -> StructureThemes.append(PATH,raw,"new",input)); }
        rejects(() -> StructureThemes.setWeight(PATH,raw,0,null));
        byte[] maximum=StructureThemes.setWeight(PATH,StructureThemes.setWeight(PATH,raw,0,"2147483647"),1,"2147483647");check(StructureThemes.totalWeight(StructureThemes.entries(root(maximum)))==4_294_967_294L,"64-bit total above int range");
        for (String token : List.of("null","true","\"1\"","[]","{}","1.000000001")) {
            byte[] malformed=bytes("{\"themeSets\":[{\"key\":\"bad\",\"weight\":"+token+"},{\"key\":\"good\",\"weight\":1}]}" );
            rejects(() -> StructureThemes.setWeight(PATH,malformed,0,"2"));byte[] repaired=StructureThemes.setWeight(PATH,malformed,1,"3");check(StructureThemes.entries(root(repaired)).getFirst().token("weight").equals(token),"unsupported sibling retained "+token);
            rejects(() -> StructureThemes.copy(PATH,malformed,0,"copy"));
        }
        byte[] zero=bytes("{\"themeSets\":[{\"key\":\"bad\",\"weight\":0}]}");check(StructureThemes.weight(StructureThemes.entries(root(StructureThemes.setWeight(PATH,zero,0,"1"))).getFirst())==1,"repair stored nonpositive numeric weight");
        rejects(() -> StructureThemes.totalWeight(StructureThemes.entries(root(zero))));
        for (String key : List.of(""," "," Hot","Hot ","Hot\t","A\u0000B","\ud800","\udc00","x".repeat(257))) rejects(() -> StructureThemes.append(PATH,raw,key,"1"));
        rejects(() -> StructureThemes.append(PATH,raw,null,"1"));rejects(() -> StructureThemes.append(PATH,raw,"Hot","1"));
        rejects(() -> StructureThemes.append(PATH,bytes("{\"themeSets\":[{\"key\":\" Hot \"}]}"),"Hot","1"));
        for (String key : List.of("hot","雪😀 / mixed # Key","x".repeat(256),"\u2003Key")) check(StructureThemes.key(StructureThemes.entries(root(StructureThemes.append(PATH,raw,key,"1"))).getLast()).equals(key),"literal case/Unicode/new key "+key.length());
        byte[] malformedKey=bytes("{\"themeSets\":[{\"key\":null,\"weight\":1}]}" );rejects(() -> StructureThemes.append(PATH,malformedKey,"new","1"));check(StructureThemes.weight(StructureThemes.entries(root(StructureThemes.setWeight(PATH,malformedKey,0,"2"))).getFirst())==2,"weight repair independent of unsupported key");
        byte[] copied=StructureThemes.copy(PATH,raw,0,"Spring");var rows=StructureThemes.entries(root(copied));check(rows.size()==3 && rows.getLast().stringToken("key").equals("Spring") && rows.getLast().token("weight").equals("1e0") && rows.getLast().token("future").equals("9007199254740993") && rows.getFirst().token("key").equals("\"H\\u006ft\""),"copy preserves source/unknowns and changes new identity only");
        check(Arrays.equals(StructureThemes.move(PATH,StructureThemes.move(PATH,raw,0,StructureThemes.Direction.DOWN),1,StructureThemes.Direction.UP),raw),"raw move and reverse exact");
        rejects(() -> StructureThemes.move(PATH,raw,0,StructureThemes.Direction.UP));rejects(() -> StructureThemes.move(PATH,raw,1,StructureThemes.Direction.DOWN));rejects(() -> StructureThemes.move(PATH,raw,0,null));
        for (String token : List.of("true","{}","\"snippet/themes\"","[null]","[\"snippet/theme\"]")) { byte[] malformed=bytes("{\"themeSets\":"+token+"}");rejects(() -> StructureThemes.entries(root(malformed)));rejects(() -> StructureThemes.append(PATH,malformed,"new","1"));rejects(() -> StructureThemes.resetEmpty(PATH,malformed)); }
        for (String path : List.of("biomes/a.json","dimensions/a.json","jigsaw-pieces/a.json","structures/a.JSON","structures/../a.json")) { rejects(() -> StructureThemes.append(path,raw,"new","1"));rejects(() -> StructureThemes.copy(path,raw,0,"new"));rejects(() -> StructureThemes.setWeight(path,raw,0,"2"));rejects(() -> StructureThemes.move(path,raw,0,StructureThemes.Direction.DOWN));rejects(() -> StructureThemes.resetEmpty(path,explicitEmpty)); }
        for (int index : List.of(-1,2,Integer.MAX_VALUE)) { rejects(() -> StructureThemes.setWeight(PATH,raw,index,"2"));rejects(() -> StructureThemes.copy(PATH,raw,index,"new"));rejects(() -> StructureThemes.move(PATH,raw,index,StructureThemes.Direction.UP)); }
        byte[] full=empty;for (int i=0;i<64;i++) full=StructureThemes.append(PATH,full,"k"+i,"2147483647");final byte[] capped=full;check(StructureThemes.totalWeight(StructureThemes.entries(root(full)))==137_438_953_408L,"all 64 max weights use long total");rejects(() -> StructureThemes.append(PATH,capped,"overflow","1"));rejects(() -> StructureThemes.copy(PATH,capped,0,"overflow"));
        byte[] over=root(full).objectArrayInsert("themeSets",64,bytes("{\"key\":\"overflow\"}"));rejects(() -> StructureThemes.entries(root(over)));
        check(FieldCoverage.fields("structureTheme").size()==2 && FieldCoverage.fields("structureTheme").stream().allMatch(FieldCoverage.Field::implemented),"bounded source coverage");
        // Appends/copies retain existing row indices; moves guard only indexed theme drafts.
        var pack=PackWorkspace.newPack(Map.of(PATH,raw,"jigsaw-pieces/piece.json",bytes("{\"themes\":[\"Hot\"]}"),"future/malformed.json",bytes("bad JSON"),"objects/raw.iob",new byte[]{0,-1,9}));pack.save(temp.resolve("theme-source"));byte[] piece=pack.read("jigsaw-pieces/piece.json");
        var drafts=new InspectorDrafts();String weightDraft=StructureThemes.prefix(0)+"weight",addDraft=StructureThemes.newPrefix()+"pair",workcellDraft=StructureWorkcells.prefix(-1)+"bounds";drafts.update(weightDraft,"1e0","2");drafts.update(addDraft,"|1","New|3");drafts.update(workcellDraft,"15|15|15","16|15|15");
        drafts.transitionUnder(StructureThemes.newPrefix(),() -> true,() -> pack.replace(PATH,StructureThemes.append(PATH,pack.read(PATH),"New","3")));check(drafts.contains(weightDraft) && !drafts.contains(addDraft) && drafts.contains(workcellDraft),"append clears new form only");
        byte[] before=pack.read(PATH);int history=pack.undoCount();long revision=pack.revision();
        check(!drafts.transitionUnder(StructureThemes.entriesPrefix(),() -> false,() -> pack.replace(PATH,StructureThemes.move(PATH,pack.read(PATH),0,StructureThemes.Direction.DOWN))) && Arrays.equals(pack.read(PATH),before) && drafts.contains(weightDraft),"move cancellation retains indexed drafts");
        try { drafts.transitionUnder(StructureThemes.entriesPrefix(),() -> true,() -> pack.replace(PATH,StructureThemes.setWeight(PATH,pack.read(PATH),0,"0")));throw new AssertionError(); } catch (IllegalArgumentException expected) { }
        check(pack.revision()==revision && pack.undoCount()==history && drafts.contains(weightDraft),"failed mutation retains source/history/drafts");
        drafts.transitionUnder(StructureThemes.entriesPrefix(),() -> true,() -> pack.replace(PATH,StructureThemes.move(PATH,pack.read(PATH),0,StructureThemes.Direction.DOWN)));check(!drafts.contains(weightDraft) && drafts.contains(workcellDraft) && pack.undoCount()==history+1,"move guards only indexed themes");
        pack.undo();check(Arrays.equals(pack.read(PATH),before),"exact Undo");pack.redo();check(Arrays.equals(pack.read("jigsaw-pieces/piece.json"),piece),"piece tag identities never rewritten");
        byte[] portable=StructureDefinitions.setChoice(PATH,pack.read(PATH),"compatibility","VANILLA_PORTABLE");pack.replace(PATH,portable);check(!StructureDefinitions.advisories(root(portable)).isEmpty(),"portable finding does not gate storage");
        pack.save(pack.directory());pack.saveCopy(temp.resolve("theme-copy"));pack.saveAs(temp.resolve("theme-as"));pack.exportZip(temp.resolve("themes.zip"));
        for (var reopened : List.of(PackWorkspace.open(temp.resolve("theme-source")),PackWorkspace.open(temp.resolve("theme-copy")),PackWorkspace.open(temp.resolve("theme-as")),PackWorkspace.importZip(temp.resolve("themes.zip")))) for (String path : pack.paths()) check(Arrays.equals(pack.read(path),reopened.read(path)),"exact persistence "+path);
        System.out.println("PASS: exact theme identities, positive int/long weights, Unicode/duplicate/type/list guards, source-preserving copies/moves, empty/null repair, fixed piece-tag keys, scoped draft cancellation/failure, Undo/Redo and byte-exact Save/Copy/As/ZIP without sampling or native graph evaluation");
    }
}
