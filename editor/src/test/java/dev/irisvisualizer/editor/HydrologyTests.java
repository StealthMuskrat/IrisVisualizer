package dev.irisvisualizer.editor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class HydrologyTests {
    private static final String PATH="dimensions/routing.json";
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static String text(byte[] source) { return new String(source,StandardCharsets.UTF_8); }
    private static void check(boolean condition) { if (!condition) throw new AssertionError("Hydrology routing contract failed"); }
    private static void rejects(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Expected rejected physical hydrology operation");
    }
    private static LosslessJson hydro(byte[] source) { return HydrologyFields.hydrology(new LosslessJson(source)); }
    private static LosslessJson rivers(byte[] source) { return HydrologyFields.rivers(hydro(source)); }
    private static LosslessJson route(byte[] source) { return HydrologyFields.routing(rivers(source)); }
    static void run(Path temp) throws Exception {
        Map<String,String> defaults=Map.ofEntries(
                Map.entry("tileSize","2048"),Map.entry("sampleSpacing","64"),Map.entry("maximumRouteLength","16384"),
                Map.entry("minimumSurfaceCourseLength","384"),Map.entry("minimumUndergroundCourseLength","192"),Map.entry("maximumOutletsPerTile","4"),
                Map.entry("maximumCoastalOutletsPerTile","2"),Map.entry("oceanOutlets","true"),Map.entry("valleyPreference","1.5"),
                Map.entry("uphillPenalty","24"),Map.entry("slopePenalty","2"),Map.entry("confluenceAttraction","0.2"),Map.entry("lengthPreference","1"),Map.entry("tributaries","1"));
        Map<String,double[]> bounds=Map.ofEntries(
                Map.entry("tileSize",new double[]{256,8192}),Map.entry("sampleSpacing",new double[]{8,64}),Map.entry("maximumRouteLength",new double[]{256,32768}),
                Map.entry("minimumSurfaceCourseLength",new double[]{0,32768}),Map.entry("minimumUndergroundCourseLength",new double[]{0,32768}),
                Map.entry("maximumOutletsPerTile",new double[]{1,256}),Map.entry("maximumCoastalOutletsPerTile",new double[]{0,64}),
                Map.entry("valleyPreference",new double[]{0,8}),Map.entry("uphillPenalty",new double[]{0,128}),Map.entry("slopePenalty",new double[]{0,16}),
                Map.entry("confluenceAttraction",new double[]{0,1}),Map.entry("lengthPreference",new double[]{0,8}),Map.entry("tributaries",new double[]{0,4}));
        Set<String> integers=Set.of("tileSize","sampleSpacing","maximumRouteLength","minimumSurfaceCourseLength","minimumUndergroundCourseLength","maximumOutletsPerTile","maximumCoastalOutletsPerTile","tributaries");
        check(HydrologyFields.ROUTING_FIELDS.size()==14 && defaults.size()==14 && bounds.size()==13);
        for (String object : List.of("hydrology","rivers","routing")) check(!HydrologyFields.description(object).isBlank());
        byte[] root=HydrologyFields.enableHydrology(PATH,bytes("{\"unknown\":9007199254740993}"));
        check(hydro(root).fields().isEmpty() && new LosslessJson(root).token("unknown").equals("9007199254740993"));
        byte[] river=HydrologyFields.enableRivers(PATH,root);
        check(hydro(river).fields().equals(Set.of("rivers")) && rivers(river).fields().isEmpty());
        byte[] routing=HydrologyFields.enableRouting(PATH,river);
        check(rivers(routing).fields().equals(Set.of("routing")) && route(routing).fields().isEmpty());
        check(rivers(routing).token("enabled")==null && rivers(routing).token("profiles")==null);
        rejects(() -> HydrologyFields.enableHydrology(PATH,root));
        rejects(() -> HydrologyFields.enableRivers(PATH,river));
        rejects(() -> HydrologyFields.enableRouting(PATH,routing));
        rejects(() -> HydrologyFields.enableRivers(PATH,bytes("{}")));
        rejects(() -> HydrologyFields.enableRouting(PATH,root));
        rejects(() -> HydrologyFields.enableScalar(PATH,river,"tileSize"));
        rejects(() -> HydrologyFields.setEnabled(PATH,root,true));
        check(rivers(HydrologyFields.setEnabled(PATH,river,true)).fields().equals(Set.of("enabled")));
        check(rivers(HydrologyFields.setEnabled(PATH,river,true)).token("enabled").equals("true"));
        String original="{\r\n \"hydrology\":{\"rivers\":{\"enabled\":false,\"routing\":{\"tileSize\":2048e0,\"sampleSpacing\":64,\"maximumRouteLength\":16384,\"minimumSurfaceCourseLength\":384,\"minimumUndergroundCourseLength\":192,\"maximumOutletsPerTile\":4,\"maximumCoastalOutletsPerTile\":2,\"oceanOutlets\":true,\"valleyPreference\":1.5e0,\"uphillPenalty\":24,\"slopePenalty\":2,\"confluenceAttraction\":0.2,\"lengthPreference\":1,\"tributaries\":1,\"inlandOutlets\":[\"future-outlet\"],\"regional\":{\"future\":true},\"future\":\"雪\\u0061\"},\"profiles\":[{\"id\":\"native\"}],\"geometry\":{\"future\":true},\"future\":9007199254740993},\"surfacePools\":[{\"id\":\"hot\"}],\"deepFluids\":[\"future\"],\"future\":true},\"riverPolicy\":{\"widthMultiplier\":1e0,\"profiles\":[\"native\"]},\"unknown\":9007199254740993\r\n}\r\n";
        byte[] source=bytes(original), copy=source.clone();
        check(text(HydrologyFields.setEnabled(PATH,source,true)).equals(original.replace("\"enabled\":false","\"enabled\":true")));
        for (String field : HydrologyFields.ROUTING_FIELDS) {
            check(HydrologyFields.routingDefault(field).equals(defaults.get(field)) && !HydrologyFields.routingHelp(field).isBlank());
            byte[] enabled=HydrologyFields.enableScalar(PATH,routing,field);
            check(route(enabled).fields().equals(Set.of(field)) && route(enabled).token(field).equals(defaults.get(field)));
            check(rivers(enabled).token("enabled")==null && hydro(enabled).fields().equals(Set.of("rivers")));
            rejects(() -> HydrologyFields.enableScalar(PATH,enabled,field));
            if (HydrologyFields.routingBoolean(field)) {
                check(field.equals("oceanOutlets"));
                check(text(HydrologyFields.setRoutingBoolean(PATH,source,field,false)).equals(original.replace("\"oceanOutlets\":true","\"oceanOutlets\":false")));
                check(route(HydrologyFields.setRoutingBoolean(PATH,routing,field,false)).fields().equals(Set.of(field)));
                rejects(() -> HydrologyFields.setRoutingText(PATH,source,field,"0"));
            } else {
                double min=bounds.get(field)[0], max=bounds.get(field)[1];
                String minToken=java.math.BigDecimal.valueOf(min).stripTrailingZeros().toPlainString();
                String old=route(source).token(field);
                check(text(HydrologyFields.setRoutingText(PATH,source,field,minToken)).equals(original.replace("\""+field+"\":"+old,"\""+field+"\":"+minToken)));
                HydrologyFields.setRoutingText(PATH,source,field,Double.toString(max));
                rejects(() -> HydrologyFields.setRoutingText(PATH,source,field,Double.toString(min-1)));
                rejects(() -> HydrologyFields.setRoutingText(PATH,source,field,Double.toString(max+1)));
                rejects(() -> HydrologyFields.setRoutingText(PATH,routing,field,minToken));
                rejects(() -> HydrologyFields.setRoutingBoolean(PATH,source,field,true));
                for (String bad : List.of("NaN","Infinity","1e999","\"1\"","")) rejects(() -> HydrologyFields.setRoutingText(PATH,source,field,bad));
                if (integers.contains(field)) rejects(() -> HydrologyFields.setRoutingText(PATH,source,field,"256.00000000000000000001"));
                for (String bad : List.of("null","true","[]","{}","\"1\"")) {
                    byte[] wrong=bytes("{\"hydrology\":{\"rivers\":{\"routing\":{\""+field+"\":"+bad+"}}}}");
                    rejects(() -> HydrologyFields.setRoutingText(PATH,wrong,field,minToken));
                }
            }
        }
        // Individual field edits do not invent power-of-two or cross-field rules.
        HydrologyFields.setRoutingText(PATH,source,"tileSize","257");
        HydrologyFields.setRoutingText(PATH,source,"sampleSpacing","9");
        HydrologyFields.setRoutingText(PATH,source,"maximumRouteLength","256");
        byte[] invalidSibling=bytes(original.replace("\"tileSize\":2048e0","\"tileSize\":\"wrong-type\""));
        check(route(HydrologyFields.setRoutingBoolean(PATH,invalidSibling,"oceanOutlets",false)).token("tileSize").equals("\"wrong-type\""));
        check(Arrays.equals(source,copy));
        for (String bad : List.of("null","[]","1","true","\"unsupported\"")) {
            byte[] wrongHydro=bytes("{\"hydrology\":"+bad+"}");
            rejects(() -> HydrologyFields.enableHydrology(PATH,wrongHydro));
            rejects(() -> HydrologyFields.enableRivers(PATH,wrongHydro));
            rejects(() -> HydrologyFields.setEnabled(PATH,wrongHydro,true));
            byte[] wrongRivers=bytes("{\"hydrology\":{\"rivers\":"+bad+"}}");
            rejects(() -> HydrologyFields.enableRivers(PATH,wrongRivers));
            rejects(() -> HydrologyFields.enableRouting(PATH,wrongRivers));
            rejects(() -> HydrologyFields.setEnabled(PATH,wrongRivers,true));
            byte[] wrongRouting=bytes("{\"hydrology\":{\"rivers\":{\"routing\":"+bad+"}}}");
            rejects(() -> HydrologyFields.enableRouting(PATH,wrongRouting));
            rejects(() -> HydrologyFields.enableScalar(PATH,wrongRouting,"tileSize"));
            check(rivers(HydrologyFields.setEnabled(PATH,wrongRouting,true)).token("routing").equals(bad));
            if (!bad.equals("true")) {
                byte[] wrongEnabled=bytes("{\"hydrology\":{\"rivers\":{\"enabled\":"+bad+"}}}");
                rejects(() -> HydrologyFields.setEnabled(PATH,wrongEnabled,true));
                byte[] wrongBoolean=bytes("{\"hydrology\":{\"rivers\":{\"routing\":{\"oceanOutlets\":"+bad+"}}}}");
                rejects(() -> HydrologyFields.setRoutingBoolean(PATH,wrongBoolean,"oceanOutlets",false));
            }
        }
        for (String path : List.of("regions/test.json","biomes/test.json","generators/test.json","dimensions/nested/test.json","test.json")) {
            rejects(() -> HydrologyFields.enableHydrology(path,bytes("{}")));
            rejects(() -> HydrologyFields.enableRivers(path,root));
            rejects(() -> HydrologyFields.enableRouting(path,river));
            rejects(() -> HydrologyFields.setEnabled(path,source,true));
            rejects(() -> HydrologyFields.setRoutingText(path,source,"tileSize","512"));
        }
        rejects(() -> HydrologyFields.enableScalar(PATH,source,"inlandOutlets"));
        rejects(() -> HydrologyFields.setRoutingText(PATH,source,"regional","1"));
        check(HydrologyFields.readOnlyPaths(new LosslessJson(bytes("{}"))).isEmpty());
        check(DimensionFields.readOnlyPaths(new LosslessJson(source)).equals(List.of("hydrology.rivers.routing.inlandOutlets","hydrology.rivers.routing.regional","hydrology.rivers.routing.future","hydrology.rivers.profiles","hydrology.rivers.geometry","hydrology.rivers.future","hydrology.surfacePools","hydrology.deepFluids","hydrology.future","unknown")));
        check(DimensionFields.readOnlyPaths(new LosslessJson(bytes("{\"hydrology\":null}"))).equals(List.of("hydrology")));
        check(DimensionFields.readOnlyPaths(new LosslessJson(bytes("{\"hydrology\":{\"rivers\":null}}"))).equals(List.of("hydrology.rivers")));
        check(DimensionFields.readOnlyPaths(new LosslessJson(bytes("{\"hydrology\":{\"rivers\":{\"routing\":null}}}"))).equals(List.of("hydrology.rivers.routing")));
        var drafts=new InspectorDrafts();
        drafts.update(HydrologyFields.draftPrefix()+"tileSize","2048","4096");
        drafts.update(HydrologyFields.draftPrefix()+"valleyPreference","1.5","2");
        drafts.update(RiverPolicyFields.draftPrefix("dimensions")+"widthMultiplier","1","2");
        drafts.applied(HydrologyFields.draftPrefix()+"tileSize");
        check(drafts.count()==2 && drafts.contains(HydrologyFields.draftPrefix()+"valleyPreference") && drafts.contains(RiverPolicyFields.draftPrefix("dimensions")+"widthMultiplier"));
        var workspace=PackWorkspace.newPack(Map.of(PATH,source,"dimensions/defaults.json",routing));
        var cache=new RiverIdLists.CatalogCache();
        var before=cache.inspect(workspace,PATH,null,"profiles");
        byte[] changed=HydrologyFields.setRoutingText(PATH,source,"valleyPreference","2");
        changed=HydrologyFields.setEnabled(PATH,changed,true); workspace.replace(PATH,changed);
        var after=cache.inspect(workspace,PATH,null,"profiles");
        check(before!=after && before.ids().equals(List.of("native")) && after.ids().equals(before.ids()));
        check(RiverIdLists.catalog(workspace,"dimensions/defaults.json",null,"profiles").ids().equals(List.of("default")));
        check(FieldCoverage.fields("hydrology").size()==3 && FieldCoverage.fields("riverHydrology").size()==7 && FieldCoverage.fields("riverRouting").size()==16);
        check(FieldCoverage.fields("riverRouting").stream().filter(FieldCoverage.Field::implemented).count()==14);
        workspace.save(temp.resolve("physical-hydrology")); workspace.exportZip(temp.resolve("physical-hydrology.zip"));
        var reopened=PackWorkspace.open(temp.resolve("physical-hydrology")); var imported=PackWorkspace.importZip(temp.resolve("physical-hydrology.zip"));
        for (String path : workspace.paths()) {
            check(Arrays.equals(workspace.read(path),reopened.read(path)));
            check(Arrays.equals(workspace.read(path),imported.read(path)));
        }
        check(rivers(reopened.read(PATH)).token("enabled").equals("true") && route(reopened.read(PATH)).token("valleyPreference").equals("2"));
        check(route(reopened.read("dimensions/defaults.json")).fields().isEmpty() && rivers(reopened.read("dimensions/defaults.json")).token("enabled")==null);
        workspace.replace(PATH,bytes("{\"hydrology\":\"unsupported\"}")); workspace.save(temp.resolve("physical-hydrology"));
        check(new LosslessJson(PackWorkspace.open(temp.resolve("physical-hydrology")).read(PATH)).token("hydrology").equals("\"unsupported\""));
        System.out.println("PASS: hydrology explicit disabled-default parents, routing defaults/types/bounds, lossless edits, draft scopes, catalog consistency and Save/ZIP contracts");
    }
}
