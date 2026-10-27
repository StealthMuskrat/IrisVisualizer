package dev.irisvisualizer.editor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class SurfacePondTests {
    private static final String PATH="dimensions/ponds.json";
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static String text(byte[] bytes) { return new String(bytes,StandardCharsets.UTF_8); }
    private static void check(boolean value) { if (!value) throw new AssertionError("Surface pond contract failed"); }
    private static void rejects(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Expected rejected surface pond operation");
    }
    private static LosslessJson ponds(byte[] value) { return SurfacePondFields.ponds(SurfaceRiverFields.surface(HydrologyFields.rivers(HydrologyFields.hydrology(new LosslessJson(value))))); }
    private static LosslessJson pond(byte[] value,String context) { return SurfacePondFields.pond(ponds(value),context); }
    static void run(Path temp) throws Exception {
        Map<String,String> defaults=Map.of("enabled","true","minimumRadius","6","maximumRadius","12","depth","3");
        Map<String,int[]> bounds=Map.of("minimumRadius",new int[]{1,32},"maximumRadius",new int[]{1,32},"depth",new int[]{1,8});
        byte[] parent=bytes("{\"hydrology\":{\"rivers\":{\"enabled\":false,\"surface\":{}}}}");
        byte[] container=SurfacePondFields.enableContainer(PATH,parent);
        check(ponds(container).fields().isEmpty());
        check(new LosslessJson(container).tokenPath("hydrology.rivers.surface.enabled")==null);
        check(new LosslessJson(container).tokenPath("hydrology.rivers.enabled").equals("false"));
        rejects(() -> SurfacePondFields.enableContainer(PATH,container));
        byte[] source=SurfacePondFields.enablePond(PATH,container,"source");
        byte[] terminal=SurfacePondFields.enablePond(PATH,container,"terminal");
        check(pond(source,"source").fields().isEmpty() && ponds(source).fields().equals(Set.of("source")));
        check(pond(terminal,"terminal").fields().equals(Set.of("minimumRadius","maximumRadius")));
        check(pond(terminal,"terminal").token("minimumRadius").equals("4") && pond(terminal,"terminal").token("maximumRadius").equals("7"));
        check(pond(terminal,"terminal").token("depth")==null && pond(terminal,"terminal").token("enabled")==null && ponds(terminal).token("source")==null);
        String prefix="{\r\n \"hydrology\":{\"rivers\":{\"enabled\":false,\"surface\":{\"ponds\":{\"source\":";
        String middle=",\"terminal\":";
        String suffix=",\"future\":[\"\\u0061\"]},\"channel\":{\"roughness\":0.25},\"enabled\":false},\"geometry\":{\"surface\":{\"bedRoughness\":null}},\"profiles\":[{\"id\":\"literal ID\"}]}},\"unknown\":9007199254740993\r\n}\r\n";
        String sourceLeaf="{\"enabled\":true,\"minimumRadius\":6e0,\"maximumRadius\":12,\"depth\":3,\"future\":\"雪\\u0061\"}";
        String terminalLeaf="{\"enabled\":true,\"minimumRadius\":4e0,\"maximumRadius\":7,\"depth\":3,\"future\":\"雪\\u0062\"}";
        byte[] original=bytes(prefix+sourceLeaf+middle+terminalLeaf+suffix), copy=original.clone();
        for (String context : SurfacePondFields.CONTEXTS) {
            boolean first=context.equals("source"); String leaf=first ? sourceLeaf : terminalLeaf;
            String before=first ? prefix : prefix+sourceLeaf+middle;
            String after=first ? middle+terminalLeaf+suffix : suffix;
            byte[] existingEmpty=bytes(before+"{}"+after);
            check(!SurfacePondFields.description(context).isBlank());
            check(SurfacePondFields.contextDefault(context,"minimumRadius").equals(first ? "6" : "4"));
            check(SurfacePondFields.contextDefault(context,"maximumRadius").equals(first ? "12" : "7"));
            check(SurfacePondFields.contextDefault(context,"depth").equals("3") && SurfacePondFields.contextDefault(context,"enabled").equals("true"));
            rejects(() -> SurfacePondFields.enablePond(PATH,original,context));
            rejects(() -> SurfacePondFields.enablePond(PATH,existingEmpty,context));
            for (String field : SurfacePondFields.EDITABLE) {
                check(SurfacePondFields.defaultToken(field).equals(defaults.get(field)) && !SurfacePondFields.help(field).isBlank());
                byte[] enabled=SurfacePondFields.enableScalar(PATH,existingEmpty,context,field);
                check(pond(enabled,context).fields().equals(Set.of(field)) && pond(enabled,context).token(field).equals(defaults.get(field)));
                rejects(() -> SurfacePondFields.enableScalar(PATH,enabled,context,field));
                if (field.equals("enabled")) {
                    check(text(SurfacePondFields.setEnabled(PATH,original,context,false)).equals(before+leaf.replace("\"enabled\":true","\"enabled\":false")+after));
                    check(pond(SurfacePondFields.setEnabled(PATH,existingEmpty,context,false),context).fields().equals(Set.of("enabled")));
                    rejects(() -> SurfacePondFields.setText(PATH,original,context,field,"1"));
                } else {
                    String old=pond(original,context).token(field), minimum=Integer.toString(bounds.get(field)[0]);
                    check(text(SurfacePondFields.setText(PATH,original,context,field,minimum)).equals(before+leaf.replace("\""+field+"\":"+old,"\""+field+"\":"+minimum)+after));
                    SurfacePondFields.setText(PATH,original,context,field,Integer.toString(bounds.get(field)[1]));
                    check(pond(SurfacePondFields.setText(PATH,original,context,field,"1e0"),context).token(field).equals("1"));
                    for (String bad : List.of("0",Integer.toString(bounds.get(field)[1]+1),"-1","1.000000000000000001","2147483648","NaN","Infinity","1e999","")) rejects(() -> SurfacePondFields.setText(PATH,original,context,field,bad));
                    rejects(() -> SurfacePondFields.setText(PATH,existingEmpty,context,field,"1"));
                }
                for (String bad : List.of("null","true","[]","{}","\"1\"")) {
                    if (field.equals("enabled") && bad.equals("true")) continue;
                    byte[] wrong=bytes(before+"{\""+field+"\":"+bad+"}"+after);
                    if (field.equals("enabled")) rejects(() -> SurfacePondFields.setEnabled(PATH,wrong,context,false));
                    else rejects(() -> SurfacePondFields.setText(PATH,wrong,context,field,"1"));
                    rejects(() -> SurfacePondFields.enableScalar(PATH,wrong,context,field));
                }
            }
            for (String bad : List.of("null","[]","1","true","\"snippet\"")) {
                byte[] wrong=bytes(before+bad+after);
                rejects(() -> SurfacePondFields.enablePond(PATH,wrong,context));
                rejects(() -> SurfacePondFields.enableScalar(PATH,wrong,context,"depth"));
                rejects(() -> SurfacePondFields.setEnabled(PATH,wrong,context,false));
                check(DimensionFields.readOnlyPaths(new LosslessJson(wrong)).contains("hydrology.rivers.surface.ponds."+context));
                String other=first ? "terminal" : "source";
                check(pond(SurfacePondFields.setEnabled(PATH,wrong,other,false),other).token("enabled").equals("false"));
            }
            rejects(() -> SurfacePondFields.enableScalar(PATH,original,context,"future"));
            rejects(() -> SurfacePondFields.setText(PATH,original,context,"future","1"));
        }
        check(Arrays.equals(original,copy));
        check(DimensionFields.readOnlyPaths(new LosslessJson(original)).containsAll(List.of("hydrology.rivers.surface.ponds.source.future","hydrology.rivers.surface.ponds.terminal.future","hydrology.rivers.surface.ponds.future")));
        for (String missing : List.of("{}","{\"hydrology\":null}","{\"hydrology\":{}}","{\"hydrology\":{\"rivers\":{}}}","{\"hydrology\":{\"rivers\":{\"surface\":null}}}")) {
            rejects(() -> SurfacePondFields.enableContainer(PATH,bytes(missing)));
            rejects(() -> SurfacePondFields.enablePond(PATH,bytes(missing),"source"));
        }
        rejects(() -> SurfacePondFields.enablePond(PATH,parent,"terminal"));
        rejects(() -> SurfacePondFields.enableScalar(PATH,container,"source","minimumRadius"));
        rejects(() -> SurfacePondFields.setEnabled(PATH,container,"terminal",false));
        for (String bad : List.of("null","[]","1","true","\"snippet\"")) {
            byte[] wrong=bytes("{\"hydrology\":{\"rivers\":{\"surface\":{\"ponds\":"+bad+"}}}}");
            rejects(() -> SurfacePondFields.enableContainer(PATH,wrong));
            rejects(() -> SurfacePondFields.enablePond(PATH,wrong,"source"));
            check(DimensionFields.readOnlyPaths(new LosslessJson(wrong)).contains("hydrology.rivers.surface.ponds"));
        }
        for (String path : List.of("regions/a.json","biomes/a.json","generators/a.json","dimensions/nested/a.json","a.json")) {
            rejects(() -> SurfacePondFields.enableContainer(path,parent));
            rejects(() -> SurfacePondFields.enablePond(path,container,"terminal"));
            rejects(() -> SurfacePondFields.setEnabled(path,original,"source",false));
            rejects(() -> SurfacePondFields.setText(path,original,"terminal","depth","1"));
        }
        rejects(() -> SurfacePondFields.enablePond(PATH,container,"source.nested"));
        rejects(() -> SurfacePondFields.setText(PATH,original,"unknown","depth","1"));
        byte[] independent=SurfacePondFields.setText(PATH,original,"terminal","minimumRadius","32");
        check(pond(independent,"terminal").token("maximumRadius").equals("7"));
        var drafts=new InspectorDrafts();
        for (String key : List.of(SurfacePondFields.draftPrefix("source")+"minimumRadius",SurfacePondFields.draftPrefix("terminal")+"minimumRadius",SurfaceRiverFields.draftPrefix("erosion")+"smoothingRadius",HydrologyFields.draftPrefix()+"tileSize")) drafts.update(key,"1","2");
        SurfacePondFields.setEnabled(PATH,original,"source",false); check(drafts.count()==4);
        SurfacePondFields.setText(PATH,original,"terminal","minimumRadius","2"); drafts.applied(SurfacePondFields.draftPrefix("terminal")+"minimumRadius");
        check(drafts.count()==3 && drafts.contains(SurfacePondFields.draftPrefix("source")+"minimumRadius"));
        check(FieldCoverage.fields("surfaceRiver").size()==9 && FieldCoverage.fields("surfaceRiver").stream().allMatch(FieldCoverage.Field::implemented));
        check(FieldCoverage.fields("surfaceRiverPonds").size()==2 && FieldCoverage.fields("surfaceRiverPonds").stream().allMatch(FieldCoverage.Field::implemented));
        check(FieldCoverage.fields("surfaceRiverPond").size()==4 && FieldCoverage.fields("surfaceRiverPond").stream().allMatch(FieldCoverage.Field::implemented));
        var workspace=PackWorkspace.newPack(Map.of(PATH,independent,"dimensions/container.json",container,"dimensions/source.json",source,"dimensions/terminal.json",terminal));
        workspace.save(temp.resolve("surface-ponds")); workspace.exportZip(temp.resolve("surface-ponds.zip"));
        var reopened=PackWorkspace.open(temp.resolve("surface-ponds")); var imported=PackWorkspace.importZip(temp.resolve("surface-ponds.zip"));
        for (String path : workspace.paths()) {
            check(Arrays.equals(workspace.read(path),reopened.read(path)));
            check(Arrays.equals(workspace.read(path),imported.read(path)));
        }
        check(ponds(reopened.read("dimensions/container.json")).fields().isEmpty());
        check(pond(reopened.read("dimensions/source.json"),"source").fields().isEmpty());
        check(pond(reopened.read("dimensions/terminal.json"),"terminal").fields().equals(Set.of("minimumRadius","maximumRadius")));
        check(new LosslessJson(reopened.read(PATH)).tokenPath("hydrology.rivers.enabled").equals("false"));
        System.out.println("PASS: pond endpoint/class defaults and minimal contextual creation, exact bounds/types, independent repair/drafts, lossless unknown data and Save/ZIP contracts");
    }
}
