package dev.irisvisualizer.editor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class SeaCaveTests {
    private static final String PATH="dimensions/sea-caves.json";
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static String text(byte[] value) { return new String(value,StandardCharsets.UTF_8); }
    private static void check(boolean value) { if (!value) throw new AssertionError("Sea-cave contract failed"); }
    private static void rejects(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Expected rejected sea-cave operation");
    }
    private static LosslessJson coastal(byte[] value) { return GrottoFields.section(GrottoFields.grottos(HydrologyFields.rivers(HydrologyFields.hydrology(new LosslessJson(value)))),"coastal"); }
    private static LosslessJson caves(byte[] value) { return SeaCaveFields.object(coastal(value)); }
    static void run(Path temp) throws Exception {
        Map<String,String> defaults=Map.of("enabled","true","maximumPerTile","3","minimumSpacing","160","minimumCoastHeight","8","depth","12","sweepJitterDegrees","25");
        Map<String,int[]> bounds=Map.of("maximumPerTile",new int[]{0,64},"minimumSpacing",new int[]{16,8192},"minimumCoastHeight",new int[]{1,128},"depth",new int[]{0,128},"sweepJitterDegrees",new int[]{0,90});
        byte[] parent=bytes("{\"hydrology\":{\"rivers\":{\"enabled\":false,\"grottos\":{\"coastal\":{\"enabled\":false}}}}}");
        byte[] empty=SeaCaveFields.enableObject(PATH,parent,"160");
        check(caves(empty).fields().isEmpty() && coastal(empty).token("horizontalRadius")==null && coastal(empty).token("enabled").equals("false"));
        check(new LosslessJson(empty).tokenPath("hydrology.rivers.enabled").equals("false"));
        rejects(() -> SeaCaveFields.enableObject(PATH,empty,"160"));
        byte[] highRadius=GrottoFields.enableScalar(PATH,parent,"coastal","horizontalRadius");
        highRadius=GrottoFields.setText(PATH,highRadius,"coastal","horizontalRadius","128");
        byte[] large=highRadius;
        rejects(() -> SeaCaveFields.enableObject(PATH,large,"160"));
        rejects(() -> SeaCaveFields.enableObject(PATH,large,"255"));
        byte[] custom=SeaCaveFields.enableObject(PATH,large,"2.56e2");
        check(caves(custom).fields().equals(Set.of("minimumSpacing")) && caves(custom).token("minimumSpacing").equals("256"));
        check(coastal(custom).token("horizontalRadius").equals("128") && coastal(custom).token("enabled").equals("false"));
        for (String field : SeaCaveFields.EDITABLE) {
            check(SeaCaveFields.defaultToken(field).equals(defaults.get(field)) && !SeaCaveFields.help(field).isBlank());
            byte[] one=SeaCaveFields.enable(PATH,empty,field);
            check(caves(one).fields().equals(Set.of(field)) && caves(one).token(field).equals(defaults.get(field)));
            rejects(() -> SeaCaveFields.enable(PATH,one,field));
        }
        check(caves(SeaCaveFields.setEnabled(PATH,empty,true)).token("enabled").equals("true") && coastal(SeaCaveFields.setEnabled(PATH,empty,true)).token("enabled").equals("false"));
        String prefix="{\r\n \"hydrology\":{\"rivers\":{\"enabled\":false,\"grottos\":{\"coastal\":{\"enabled\":false,\"horizontalRadius\":8,\"seaCaves\":";
        String suffix="},\"inland\":{\"enabled\":true}},\"geometry\":{\"grottos\":{\"aspectRange\":0.2}},\"profiles\":[{\"id\":\"literal ID\"}]}},\"unknown\":9007199254740993\r\n}\r\n";
        String leaf="{\"enabled\":true,\"maximumPerTile\":3,\"minimumSpacing\":160,\"minimumCoastHeight\":8,\"depth\":12,\"sweepJitterDegrees\":25,\"future\":\"雪\\u0061\"}";
        byte[] original=bytes(prefix+leaf+suffix),copy=original.clone();
        check(text(SeaCaveFields.setEnabled(PATH,original,false)).equals(prefix+leaf.replace("\"enabled\":true","\"enabled\":false")+suffix));
        for (var entry : bounds.entrySet()) {
            String field=entry.getKey(),minimum=Integer.toString(entry.getValue()[0]);
            check(text(SeaCaveFields.setText(PATH,original,field,minimum)).equals(prefix+leaf.replace("\""+field+"\":"+defaults.get(field),"\""+field+"\":"+minimum)+suffix));
            SeaCaveFields.setText(PATH,original,field,Integer.toString(entry.getValue()[1]));
            for (String bad : List.of(Integer.toString(entry.getValue()[0]-1),Integer.toString(entry.getValue()[1]+1),"NaN","Infinity","1e999","")) rejects(() -> SeaCaveFields.setText(PATH,original,field,bad));
            if (!field.equals("sweepJitterDegrees")) {
                rejects(() -> SeaCaveFields.setText(PATH,original,field,minimum+".000000000000000001"));
                check(caves(SeaCaveFields.setText(PATH,original,field,minimum+"e0")).token(field).equals(minimum));
            }
            rejects(() -> SeaCaveFields.setText(PATH,empty,field,field.equals("minimumSpacing") ? "24" : minimum));
        }
        rejects(() -> SeaCaveFields.setText(PATH,original,"enabled","1"));
        for (String field : SeaCaveFields.EDITABLE) for (String bad : List.of("null","true","[]","{}","\"1\"")) {
            if (field.equals("enabled") && bad.equals("true")) continue;
            byte[] wrong=bytes(prefix+"{\""+field+"\":"+bad+"}"+suffix);
            if (field.equals("enabled")) rejects(() -> SeaCaveFields.setEnabled(PATH,wrong,false));
            else rejects(() -> SeaCaveFields.setText(PATH,wrong,field,"32"));
        }
        // Default radius 12 requires spacing 24, even if coastal or sea-cave enable flags are false.
        byte[] spacing=SeaCaveFields.enable(PATH,empty,"minimumSpacing");
        rejects(() -> SeaCaveFields.setText(PATH,spacing,"minimumSpacing","16"));
        check(caves(SeaCaveFields.setText(PATH,spacing,"minimumSpacing","2.4e1")).token("minimumSpacing").equals("24"));
        byte[] radius=GrottoFields.enableScalar(PATH,spacing,"coastal","horizontalRadius");
        GrottoFields.setText(PATH,radius,"coastal","horizontalRadius","80");
        rejects(() -> GrottoFields.setText(PATH,radius,"coastal","horizontalRadius","81"));
        byte[] tight=SeaCaveFields.setSpacingAndRadius(PATH,empty,"8","16");
        rejects(() -> GrottoFields.setText(PATH,tight,"coastal","horizontalRadius","9"));
        byte[] noRadius=bytes("{\"hydrology\":{\"rivers\":{\"grottos\":{\"coastal\":{\"seaCaves\":{\"minimumSpacing\":16}}}}}}");
        rejects(() -> GrottoFields.enableScalar(PATH,noRadius,"coastal","horizontalRadius"));
        check(coastal(SeaCaveFields.setSpacingAndRadius(PATH,noRadius,"8","16")).token("horizontalRadius").equals("8"));
        byte[] paired=SeaCaveFields.setSpacingAndRadius(PATH,original,"33","66");
        check(text(paired).equals((prefix+leaf+suffix).replace("\"horizontalRadius\":8","\"horizontalRadius\":33").replace("\"minimumSpacing\":160","\"minimumSpacing\":66")));
        check(caves(SeaCaveFields.setSpacingAndRadius(PATH,empty,"1.28e2","2.56e2")).token("minimumSpacing").equals("256"));
        for (String bad : List.of("0","129","8.000000000000000001","2147483648","NaN")) rejects(() -> SeaCaveFields.setSpacingAndRadius(PATH,original,bad,"256"));
        for (String bad : List.of("15","8193","16.000000000000000001","NaN")) rejects(() -> SeaCaveFields.setSpacingAndRadius(PATH,original,"8",bad));
        rejects(() -> SeaCaveFields.setSpacingAndRadius(PATH,original,"33","65"));
        byte[] wrongSpacing=bytes(prefix+"{\"minimumSpacing\":\"160\",\"depth\":12}"+suffix);
        byte[] wrongCopy=wrongSpacing.clone();
        rejects(() -> GrottoFields.setText(PATH,wrongSpacing,"coastal","horizontalRadius","12"));
        rejects(() -> SeaCaveFields.setSpacingAndRadius(PATH,wrongSpacing,"12","160"));
        check(Arrays.equals(wrongSpacing,wrongCopy));
        check(caves(SeaCaveFields.setText(PATH,wrongSpacing,"depth","13")).token("minimumSpacing").equals("\"160\""));
        byte[] wrongRadius=bytes((prefix+leaf+suffix).replace("\"horizontalRadius\":8","\"horizontalRadius\":\"8\""));
        rejects(() -> SeaCaveFields.setText(PATH,wrongRadius,"minimumSpacing","160"));
        rejects(() -> SeaCaveFields.setSpacingAndRadius(PATH,wrongRadius,"8","160"));
        check(caves(SeaCaveFields.setText(PATH,wrongRadius,"maximumPerTile","1")).token("maximumPerTile").equals("1"));
        check(Arrays.equals(original,copy));
        check(DimensionFields.readOnlyPaths(new LosslessJson(original)).contains("hydrology.rivers.grottos.coastal.seaCaves.future"));
        for (String bad : List.of("null","[]","1","true","\"snippet\"")) {
            byte[] wrong=bytes(prefix+bad+suffix);
            rejects(() -> SeaCaveFields.enableObject(PATH,wrong,"160"));
            rejects(() -> SeaCaveFields.enable(PATH,wrong,"depth"));
            rejects(() -> SeaCaveFields.setEnabled(PATH,wrong,false));
            rejects(() -> SeaCaveFields.setSpacingAndRadius(PATH,wrong,"8","160"));
            check(DimensionFields.readOnlyPaths(new LosslessJson(wrong)).contains("hydrology.rivers.grottos.coastal.seaCaves"));
        }
        for (String missing : List.of("{}","{\"hydrology\":{}}","{\"hydrology\":{\"rivers\":{}}}","{\"hydrology\":{\"rivers\":{\"grottos\":{}}}}","{\"hydrology\":{\"rivers\":{\"grottos\":{\"coastal\":null}}}}")) rejects(() -> SeaCaveFields.enableObject(PATH,bytes(missing),"160"));
        rejects(() -> SeaCaveFields.enable(PATH,parent,"depth"));
        rejects(() -> SeaCaveFields.setSpacingAndRadius(PATH,parent,"12","160"));
        for (String path : List.of("regions/a.json","biomes/a.json","generators/a.json","dimensions/nested/a.json","a.json")) {
            rejects(() -> SeaCaveFields.enableObject(path,parent,"160"));
            rejects(() -> SeaCaveFields.setEnabled(path,empty,false));
            rejects(() -> SeaCaveFields.setText(path,original,"depth","1"));
            rejects(() -> SeaCaveFields.setSpacingAndRadius(path,original,"8","160"));
        }
        rejects(() -> SeaCaveFields.enable(PATH,empty,"future"));
        var drafts=new InspectorDrafts();
        String radiusKey=GrottoFields.draftPrefix("coastal")+"horizontalRadius",spacingKey=SeaCaveFields.draftPrefix()+"minimumSpacing";
        for (String key : List.of(radiusKey,spacingKey,SeaCaveFields.draftPrefix()+"depth",GrottoFields.draftPrefix("coastal")+"cliffMinimumHeight",HydrologyFields.draftPrefix()+"tileSize")) drafts.update(key,"1","2");
        drafts.update(SeaCaveFields.creationDraftKey(),"160","256");
        rejects(() -> SeaCaveFields.enableObject(PATH,large,"160")); check(drafts.count()==6);
        SeaCaveFields.enableObject(PATH,large,"256"); drafts.applied(SeaCaveFields.creationDraftKey()); check(drafts.count()==5);
        SeaCaveFields.setSpacingAndRadius(PATH,original,"33","66"); drafts.applied(radiusKey); drafts.applied(spacingKey);
        check(drafts.count()==3 && drafts.contains(SeaCaveFields.draftPrefix()+"depth"));
        check(FieldCoverage.fields("seaCaves").size()==6 && FieldCoverage.fields("seaCaves").stream().allMatch(FieldCoverage.Field::implemented));
        check(FieldCoverage.fields("coastalGrotto").size()==9 && FieldCoverage.fields("coastalGrotto").stream().allMatch(FieldCoverage.Field::implemented));
        var workspace=PackWorkspace.newPack(Map.of(PATH,paired,"dimensions/defaults.json",empty,"dimensions/custom-spacing.json",custom));
        workspace.save(temp.resolve("sea-caves")); workspace.exportZip(temp.resolve("sea-caves.zip"));
        var reopened=PackWorkspace.open(temp.resolve("sea-caves")); var imported=PackWorkspace.importZip(temp.resolve("sea-caves.zip"));
        for (String path : workspace.paths()) {
            check(Arrays.equals(workspace.read(path),reopened.read(path)));
            check(Arrays.equals(workspace.read(path),imported.read(path)));
        }
        check(caves(reopened.read("dimensions/defaults.json")).fields().isEmpty());
        check(caves(reopened.read("dimensions/custom-spacing.json")).fields().equals(Set.of("minimumSpacing")));
        check(coastal(reopened.read(PATH)).token("enabled").equals("false") && new LosslessJson(reopened.read(PATH)).tokenPath("hydrology.rivers.enabled").equals("false"));
        System.out.println("PASS: sea-cave defaults/native bounds, spacing/radius guards and atomic pairs, custom creation, typed parents, draft isolation and lossless Save/ZIP contracts");
    }
}
