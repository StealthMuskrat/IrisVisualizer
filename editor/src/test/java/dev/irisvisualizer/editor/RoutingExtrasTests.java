package dev.irisvisualizer.editor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class RoutingExtrasTests {
    private static final String PATH="dimensions/regional.json";
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static String text(byte[] source) { return new String(source,StandardCharsets.UTF_8); }
    private static void check(boolean condition) { if (!condition) throw new AssertionError("Regional routing/outlet contract failed"); }
    private static void rejects(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Expected rejected regional routing/outlet operation");
    }
    private static LosslessJson route(byte[] source) { return HydrologyFields.routing(HydrologyFields.rivers(HydrologyFields.hydrology(new LosslessJson(source)))); }
    private static LosslessJson region(byte[] source) { return RoutingExtras.regional(route(source)); }
    static void run(Path temp) throws Exception {
        Map<String,String> defaults=Map.of("enabled","true","sampleSpacing","256","minimumLength","2048","maximumTrunks","2","maximumCachedBasins","8","maximumCachedStations","131072","coastalChannels","true","coastalChannelChance","0.25","maximumCoastalIncision","8");
        Map<String,double[]> bounds=Map.of("sampleSpacing",new double[]{128,1024},"minimumLength",new double[]{256,32768},"maximumTrunks",new double[]{1,8},"maximumCachedBasins",new double[]{1,64},"maximumCachedStations",new double[]{16384,1048576},"coastalChannelChance",new double[]{0,1},"maximumCoastalIncision",new double[]{1,32});
        check(RoutingExtras.REGIONAL_FIELDS.size()==9 && RoutingExtras.outletChoices().equals(List.of("SINKHOLE_GROTTO")));
        check(!RoutingExtras.regionalDescription().isBlank() && !RoutingExtras.outletsDescription().isBlank());
        check(RoutingExtras.options("sampleSpacing").equals(List.of("128","256","512","1024")));
        check(RoutingExtras.optionMatches("2.56e2","256") && !RoutingExtras.optionMatches("257","256"));
        byte[] parent=bytes("{\"hydrology\":{\"rivers\":{\"routing\":{}}}}");
        byte[] enabled=RoutingExtras.enableRegional(PATH,parent);
        check(route(enabled).fields().equals(Set.of("regional")) && region(enabled).fields().isEmpty());
        check(new LosslessJson(enabled).tokenPath("hydrology.rivers.enabled")==null);
        rejects(() -> RoutingExtras.enableRegional(PATH,enabled));
        byte[] emptyOutlets=RoutingExtras.enableOutlets(PATH,enabled);
        check(route(emptyOutlets).token("inlandOutlets").equals("[]") && region(emptyOutlets).fields().isEmpty());
        rejects(() -> RoutingExtras.enableOutlets(PATH,emptyOutlets));
        String prefix="{\r\n \"hydrology\":{\"rivers\":{\"enabled\":false,\"routing\":{\"sampleSpacing\":64,\"regional\":";
        String leaf="{\"enabled\":true,\"sampleSpacing\":256e0,\"minimumLength\":2048,\"maximumTrunks\":2,\"maximumCachedBasins\":8,\"maximumCachedStations\":131072,\"coastalChannels\":true,\"coastalChannelChance\":0.25,\"maximumCoastalIncision\":8,\"future\":\"雪\\u0061\"}";
        String middle=",\"inlandOutlets\":";
        String first="\"FUTURE\\u005fOUTLET\"", second="\"SINKHOLE_GROTTO\"", gap=",\r\n  ";
        String list="[\r\n  "+first+gap+second+"\r\n ]";
        String suffix=",\"future\":true},\"profiles\":[{\"id\":\"native\"}]},\"surfacePools\":[{\"id\":\"hot\"}]},\"riverPolicy\":{\"widthMultiplier\":1e0},\"unknown\":9007199254740993\r\n}\r\n";
        String original=prefix+leaf+middle+list+suffix;
        byte[] source=bytes(original), copy=source.clone();
        for (String field : RoutingExtras.REGIONAL_FIELDS) {
            check(RoutingExtras.defaultToken(field).equals(defaults.get(field)) && !RoutingExtras.help(field).isBlank());
            byte[] withDefault=RoutingExtras.enableScalar(PATH,enabled,field);
            check(region(withDefault).fields().equals(Set.of(field)) && region(withDefault).token(field).equals(defaults.get(field)));
            check(new LosslessJson(withDefault).tokenPath("hydrology.rivers.enabled")==null);
            rejects(() -> RoutingExtras.enableScalar(PATH,withDefault,field));
            String old=region(source).token(field);
            if (RoutingExtras.booleanField(field)) {
                String expected=leaf.replace("\""+field+"\":"+old,"\""+field+"\":false");
                check(text(RoutingExtras.setBoolean(PATH,source,field,false)).equals(prefix+expected+middle+list+suffix));
                check(region(RoutingExtras.setBoolean(PATH,enabled,field,false)).fields().equals(Set.of(field)));
                rejects(() -> RoutingExtras.setText(PATH,source,field,"1"));
            } else {
                double min=bounds.get(field)[0], max=bounds.get(field)[1];
                String minimum=java.math.BigDecimal.valueOf(min).stripTrailingZeros().toPlainString();
                check(text(RoutingExtras.setText(PATH,source,field,minimum)).equals(prefix+leaf.replace("\""+field+"\":"+old,"\""+field+"\":"+minimum)+middle+list+suffix));
                RoutingExtras.setText(PATH,source,field,Double.toString(max));
                rejects(() -> RoutingExtras.setText(PATH,source,field,Double.toString(min-1)));
                rejects(() -> RoutingExtras.setText(PATH,source,field,Double.toString(max+1)));
                rejects(() -> RoutingExtras.setText(PATH,enabled,field,minimum));
                rejects(() -> RoutingExtras.setBoolean(PATH,source,field,true));
                for (String invalid : List.of("NaN","Infinity","1e999","\"1\"","")) rejects(() -> RoutingExtras.setText(PATH,source,field,invalid));
                if (!field.equals("coastalChannelChance")) rejects(() -> RoutingExtras.setText(PATH,source,field,"256.00000000000000000001"));
                for (String bad : List.of("null","true","[]","{}","\"1\"")) {
                    byte[] wrong=bytes(prefix+"{\""+field+"\":"+bad+"}"+middle+list+suffix);
                    rejects(() -> RoutingExtras.setText(PATH,wrong,field,minimum));
                }
            }
        }
        for (String spacing : RoutingExtras.options("sampleSpacing")) RoutingExtras.setText(PATH,source,"sampleSpacing",spacing);
        rejects(() -> RoutingExtras.setText(PATH,source,"sampleSpacing","129"));
        rejects(() -> RoutingExtras.setText(PATH,source,"sampleSpacing","257"));
        check(region(RoutingExtras.setText(PATH,source,"sampleSpacing","2.56e2")).token("sampleSpacing").equals("256"));
        check(route(HydrologyFields.setRoutingText(PATH,source,"sampleSpacing","9")).token("sampleSpacing").equals("9"));
        check(text(RoutingExtras.editOutlet(PATH,source,0,"SINKHOLE_GROTTO")).equals(prefix+leaf+middle+"[\r\n  "+second+gap+second+"\r\n ]"+suffix));
        byte[] duplicated=RoutingExtras.changeOutlet(PATH,source,0,RoutingExtras.Action.DUPLICATE);
        check(text(duplicated).equals(prefix+leaf+middle+"[\r\n  "+first+gap+first+", "+second+"\r\n ]"+suffix));
        check(Arrays.equals(RoutingExtras.editOutlet(PATH,duplicated,1,null),source));
        byte[] moved=RoutingExtras.changeOutlet(PATH,source,0,RoutingExtras.Action.DOWN);
        check(text(moved).equals(prefix+leaf+middle+"[\r\n  "+second+gap+first+"\r\n ]"+suffix));
        check(Arrays.equals(RoutingExtras.changeOutlet(PATH,moved,1,RoutingExtras.Action.UP),source));
        check(route(RoutingExtras.editOutlet(PATH,source,2,"SINKHOLE_GROTTO")).stringArrayPath("inlandOutlets").equals(List.of("FUTURE_OUTLET","SINKHOLE_GROTTO","SINKHOLE_GROTTO")));
        byte[] one=RoutingExtras.editOutlet(PATH,emptyOutlets,0,"SINKHOLE_GROTTO");
        check(route(RoutingExtras.editOutlet(PATH,one,0,null)).token("inlandOutlets").equals("[]"));
        rejects(() -> RoutingExtras.editOutlet(PATH,source,0,"sinkhole_grotto"));
        rejects(() -> RoutingExtras.editOutlet(PATH,source,0,"FUTURE"));
        rejects(() -> RoutingExtras.changeOutlet(PATH,source,0,RoutingExtras.Action.UP));
        rejects(() -> RoutingExtras.changeOutlet(PATH,source,1,RoutingExtras.Action.DOWN));
        rejects(() -> RoutingExtras.changeOutlet(PATH,source,2,RoutingExtras.Action.DUPLICATE));
        rejects(() -> RoutingExtras.editOutlet(PATH,source,-1,null));
        rejects(() -> RoutingExtras.changeOutlet(PATH,source,0,null));
        for (String bad : List.of("null","1","true","\"unsupported\"","[]")) {
            byte[] wrongRegional=bytes(prefix+bad+middle+list+suffix);
            rejects(() -> RoutingExtras.enableRegional(PATH,wrongRegional));
            rejects(() -> RoutingExtras.enableScalar(PATH,wrongRegional,"minimumLength"));
            rejects(() -> RoutingExtras.setBoolean(PATH,wrongRegional,"enabled",false));
            check(route(RoutingExtras.editOutlet(PATH,wrongRegional,0,"SINKHOLE_GROTTO")).token("regional").equals(bad));
        }
        for (String bad : List.of("null","{}","1","\"unsupported\"","[null]","[\"SINKHOLE_GROTTO\",{}]")) {
            byte[] wrongOutlets=bytes(prefix+leaf+middle+bad+suffix);
            rejects(() -> RoutingExtras.enableOutlets(PATH,wrongOutlets));
            rejects(() -> RoutingExtras.editOutlet(PATH,wrongOutlets,0,"SINKHOLE_GROTTO"));
            rejects(() -> RoutingExtras.changeOutlet(PATH,wrongOutlets,0,RoutingExtras.Action.DUPLICATE));
            check(route(RoutingExtras.setBoolean(PATH,wrongOutlets,"enabled",false)).token("inlandOutlets").equals(bad));
        }
        for (String bad : List.of("null","1","\"true\"")) rejects(() -> RoutingExtras.setBoolean(PATH,bytes(prefix+"{\"enabled\":"+bad+"}"+middle+list+suffix),"enabled",false));
        for (String missing : List.of("{}","{\"hydrology\":{}}","{\"hydrology\":{\"rivers\":{}}}","{\"hydrology\":{\"rivers\":{\"routing\":null}}}")) {
            rejects(() -> RoutingExtras.enableRegional(PATH,bytes(missing)));
            rejects(() -> RoutingExtras.enableOutlets(PATH,bytes(missing)));
            rejects(() -> RoutingExtras.setBoolean(PATH,bytes(missing),"enabled",false));
        }
        rejects(() -> RoutingExtras.enableScalar(PATH,parent,"minimumLength"));
        rejects(() -> RoutingExtras.editOutlet(PATH,parent,0,"SINKHOLE_GROTTO"));
        for (String path : List.of("regions/test.json","biomes/test.json","generators/test.json","dimensions/nested/test.json","test.json")) {
            rejects(() -> RoutingExtras.enableRegional(path,parent));
            rejects(() -> RoutingExtras.setText(path,source,"minimumLength","4096"));
            rejects(() -> RoutingExtras.enableOutlets(path,parent));
            rejects(() -> RoutingExtras.editOutlet(path,source,0,"SINKHOLE_GROTTO"));
        }
        rejects(() -> RoutingExtras.enableScalar(PATH,source,"inlandOutlets"));
        check(Arrays.equals(source,copy));
        check(DimensionFields.readOnlyPaths(new LosslessJson(source)).equals(List.of("hydrology.rivers.routing.regional.future","hydrology.rivers.routing.future","hydrology.rivers.profiles","hydrology.surfacePools","unknown")));
        check(DimensionFields.readOnlyPaths(new LosslessJson(bytes(prefix+"null"+middle+"[null]"+suffix))).containsAll(List.of("hydrology.rivers.routing.regional","hydrology.rivers.routing.inlandOutlets")));
        var drafts=new InspectorDrafts();
        drafts.update(RoutingExtras.regionalPrefix()+"minimumLength","2048","4096");
        drafts.update(RoutingExtras.regionalPrefix()+"maximumCachedStations","131072","262144");
        drafts.update(HydrologyFields.draftPrefix()+"tileSize","2048","4096");
        drafts.update(RiverPolicyFields.draftPrefix("dimensions")+"widthMultiplier","1","2");
        byte[] changed=RoutingExtras.changeOutlet(PATH,source,0,RoutingExtras.Action.DUPLICATE);
        check(drafts.count()==4);
        changed=RoutingExtras.setText(PATH,changed,"minimumLength","4096"); drafts.applied(RoutingExtras.regionalPrefix()+"minimumLength");
        check(drafts.count()==3 && drafts.contains(HydrologyFields.draftPrefix()+"tileSize") && drafts.contains(RoutingExtras.regionalPrefix()+"maximumCachedStations"));
        check(new LosslessJson(changed).tokenPath("hydrology.rivers.enabled").equals("false"));
        var workspace=PackWorkspace.newPack(Map.of(PATH,changed,"dimensions/defaults.json",emptyOutlets));
        check(FieldCoverage.fields("riverRouting").size()==16 && FieldCoverage.fields("riverRouting").stream().allMatch(FieldCoverage.Field::implemented));
        check(FieldCoverage.fields("riverRegional").size()==9 && FieldCoverage.fields("riverRegional").stream().allMatch(FieldCoverage.Field::implemented));
        workspace.save(temp.resolve("regional-routing")); workspace.exportZip(temp.resolve("regional-routing.zip"));
        var reopened=PackWorkspace.open(temp.resolve("regional-routing")); var imported=PackWorkspace.importZip(temp.resolve("regional-routing.zip"));
        for (String path : workspace.paths()) {
            check(Arrays.equals(workspace.read(path),reopened.read(path)));
            check(Arrays.equals(workspace.read(path),imported.read(path)));
        }
        check(region(reopened.read("dimensions/defaults.json")).fields().isEmpty() && route(reopened.read("dimensions/defaults.json")).token("inlandOutlets").equals("[]"));
        check(new LosslessJson(reopened.read("dimensions/defaults.json")).tokenPath("hydrology.rivers.enabled")==null);
        System.out.println("PASS: regional routing defaults/types/bounds and power-of-two rule, lossless outlet enum lists, parent/owner/draft guards and Save/ZIP contracts");
    }
}
