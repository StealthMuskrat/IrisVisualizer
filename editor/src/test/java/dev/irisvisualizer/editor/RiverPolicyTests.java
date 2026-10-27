package dev.irisvisualizer.editor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class RiverPolicyTests {
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static String text(byte[] source) { return new String(source,StandardCharsets.UTF_8); }
    private static void check(boolean condition) { if (!condition) throw new AssertionError("River policy contract failed"); }
    private static void rejects(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Expected rejected river policy operation");
    }
    private static LosslessJson policy(byte[] source) { return new LosslessJson(new LosslessJson(source).token("riverPolicy").getBytes(StandardCharsets.UTF_8)); }
    private static String replaced(String source,String field,String old,String next) { return source.replace("\""+field+"\":"+old,"\""+field+"\":"+next); }
    static void run(Path temp) throws Exception {
        Map<String,double[]> bounds = Map.ofEntries(
                Map.entry("surfaceSourceDensity",new double[]{0,64}),Map.entry("surfaceSourceSpacing",new double[]{0,8192}),
                Map.entry("surfaceTributaries",new double[]{0,4}),Map.entry("surfaceInlandOutlets",new double[]{0,256}),Map.entry("surfaceCoastalOutlets",new double[]{0,64}),
                Map.entry("surfaceMinimumCourseLength",new double[]{16,4096}),Map.entry("surfaceMaximumIncision",new double[]{1,32}),
                Map.entry("widthMultiplier",new double[]{0.0001,16}),Map.entry("depthMultiplier",new double[]{0.0001,16}),
                Map.entry("incisionMultiplier",new double[]{0,16}),Map.entry("routingMultiplier",new double[]{0,64}),Map.entry("bankMultiplier",new double[]{0,4}),
                Map.entry("shoreBiomeWidth",new double[]{0,32}),Map.entry("shoreWidth",new double[]{0,16}));
        Set<String> integers = Set.of("surfaceSourceSpacing","surfaceTributaries","surfaceInlandOutlets","surfaceCoastalOutlets","surfaceMinimumCourseLength","surfaceMaximumIncision");
        check(RiverPolicyFields.EDITABLE.size()==19 && bounds.size()==14);
        check(RiverPolicyFields.choices("placement").equals(List.of("DISABLED","TRANSIT_ONLY","NATURAL","PREFERRED_HEADWATER","REQUIRED_HEADWATER")));
        check(RiverPolicyFields.choices("routing").equals(List.of("BLOCK","AVOID","ALLOW","PREFER")));
        String original = "{\r\n \"riverPolicy\":{\"surfaceSourceDensity\":1e0,\"surfaceSourceSpacing\":128,\"surfaceTributaries\":1,\"surfaceInlandOutlets\":2,\"surfaceCoastalOutlets\":1,\"surfaceMinimumCourseLength\":16,\"surfaceMaximumIncision\":1,\"placement\":\"FUTURE\\u005fPLACE\",\"routing\":\"FUTURE\\u005fROUTE\",\"outletAdmission\":false,\"widthMultiplier\":1e0,\"depthMultiplier\":1e0,\"incisionMultiplier\":1e0,\"routingMultiplier\":1e0,\"bankMultiplier\":1e0,\"shoreBiomeWidth\":1e0,\"shoreWidth\":1e0,\"erosion\":true,\"confined\":false,\"profiles\":[\"unresolved-profile\"],\"surfaceBiomes\":[\"unresolved-biome\"],\"surfacePools\":[],\"future\":\"雪\\u0061\"},\"caveProfile\":{\"enabled\":false},\"unknown\":9007199254740993\r\n}\r\n";
        byte[] source = bytes(original), copy = source.clone();
        var prefixes = new HashSet<String>();
        for (String context : CaveProfileFields.CONTEXTS) {
            String path = context+"/test.json";
            check(prefixes.add(RiverPolicyFields.draftPrefix(context)) && !RiverPolicyFields.description(path).isBlank());
            byte[] enabled = RiverPolicyFields.enablePolicy(path,bytes("{\"future\":true}"));
            check(policy(enabled).fields().isEmpty() && new LosslessJson(enabled).token("future").equals("true"));
            check(RiverPolicyFields.object(path,new LosslessJson(bytes("{}")))==null);
            rejects(() -> RiverPolicyFields.enablePolicy(path,enabled));
            if (context.equals("dimensions")) {
                rejects(() -> RiverPolicyFields.enablePolicy(path,bytes("{\"riverPolicy\":null}")));
                rejects(() -> RiverPolicyFields.object(path,new LosslessJson(bytes("{\"riverPolicy\":null}"))));
            } else {
                check(RiverPolicyFields.object(path,new LosslessJson(bytes("{\"riverPolicy\":null}")))==null);
                check(policy(RiverPolicyFields.enablePolicy(path,bytes("{\"riverPolicy\":null,\"unknown\":1e0}"))).fields().isEmpty());
            }
            for (String field : RiverPolicyFields.EDITABLE) {
                check(!RiverPolicyFields.help(field).isBlank());
                check(Arrays.equals(RiverPolicyFields.reset(path,enabled,field),enabled)); // Reset preserves an absent null default.
                byte[] nullField = bytes("{\"riverPolicy\":{\""+field+"\":null,\"future\":true}}");
                check(Arrays.equals(RiverPolicyFields.reset(path,nullField,field),nullField));
                String old = policy(source).token(field);
                check(text(RiverPolicyFields.reset(path,source,field)).equals(replaced(original,field,old,"null")));
                if (RiverPolicyFields.booleanField(field)) {
                    check(Set.of("outletAdmission","erosion","confined").contains(field));
                    for (boolean value : List.of(false,true)) {
                        String token = Boolean.toString(value);
                        check(text(RiverPolicyFields.setBoolean(path,source,field,value)).equals(replaced(original,field,old,token)));
                        check(policy(RiverPolicyFields.setBoolean(path,enabled,field,value)).fields().equals(Set.of(field)));
                        check(policy(RiverPolicyFields.setBoolean(path,nullField,field,value)).token(field).equals(token));
                    }
                    rejects(() -> RiverPolicyFields.setText(path,source,field,"1"));
                    rejects(() -> RiverPolicyFields.setMode(path,source,field,"ALLOW"));
                    for (String bad : List.of("1","[]","{}","\"false\"")) {
                        byte[] wrong = bytes("{\"riverPolicy\":{\""+field+"\":"+bad+"}}");
                        rejects(() -> RiverPolicyFields.setBoolean(path,wrong,field,false));
                        rejects(() -> RiverPolicyFields.reset(path,wrong,field));
                    }
                } else if (RiverPolicyFields.enumField(field)) {
                    for (String choice : RiverPolicyFields.choices(field)) {
                        check(!RiverPolicyFields.choiceDescription(field,choice).isBlank());
                        check(text(RiverPolicyFields.setMode(path,source,field,choice)).equals(replaced(original,field,old,"\""+choice+"\"")));
                        check(policy(RiverPolicyFields.setMode(path,enabled,field,choice)).fields().equals(Set.of(field)));
                        check(policy(RiverPolicyFields.setMode(path,nullField,field,choice)).stringToken(field).equals(choice));
                    }
                    rejects(() -> RiverPolicyFields.setMode(path,source,field,"FUTURE"));
                    rejects(() -> RiverPolicyFields.setText(path,source,field,"1"));
                    rejects(() -> RiverPolicyFields.setBoolean(path,source,field,false));
                    for (String bad : List.of("1","true","[]","{}")) {
                        byte[] wrong = bytes("{\"riverPolicy\":{\""+field+"\":"+bad+"}}");
                        rejects(() -> RiverPolicyFields.setMode(path,wrong,field,RiverPolicyFields.choices(field).getFirst()));
                        rejects(() -> RiverPolicyFields.reset(path,wrong,field));
                    }
                } else {
                    double min = bounds.get(field)[0], max = bounds.get(field)[1];
                    check(RiverPolicyFields.integerField(field)==integers.contains(field));
                    String minimum = new java.math.BigDecimal(Double.toString(min)).stripTrailingZeros().toPlainString();
                    check(text(RiverPolicyFields.setText(path,source,field,minimum)).equals(replaced(original,field,old,minimum)));
                    check(policy(RiverPolicyFields.setText(path,enabled,field,minimum)).fields().equals(Set.of(field)));
                    check(policy(RiverPolicyFields.setText(path,nullField,field,minimum)).token(field).equals(minimum));
                    RiverPolicyFields.setText(path,source,field,Double.toString(max));
                    rejects(() -> RiverPolicyFields.setText(path,source,field,Double.toString(min-1)));
                    rejects(() -> RiverPolicyFields.setText(path,source,field,Double.toString(max+1)));
                    for (String invalid : List.of("NaN","Infinity","1e999","\"1\"","","null")) rejects(() -> RiverPolicyFields.setText(path,source,field,invalid));
                    rejects(() -> RiverPolicyFields.setBoolean(path,source,field,false));
                    rejects(() -> RiverPolicyFields.setMode(path,source,field,"ALLOW"));
                    for (String bad : List.of("true","false","[]","{}","\"1\"")) {
                        byte[] wrong = bytes("{\"riverPolicy\":{\""+field+"\":"+bad+"}}");
                        rejects(() -> RiverPolicyFields.setText(path,wrong,field,minimum));
                        rejects(() -> RiverPolicyFields.reset(path,wrong,field));
                    }
                }
            }
            rejects(() -> RiverPolicyFields.setText(path,source,"surfaceSourceSpacing","1.00000000000000000001"));
            rejects(() -> RiverPolicyFields.setText(path,source,"surfaceSourceSpacing","1.5"));
            check(policy(RiverPolicyFields.setText(path,source,"surfaceSourceSpacing","1e2")).token("surfaceSourceSpacing").equals("100"));
            byte[] invalidSibling = bytes("{\"riverPolicy\":{\"widthMultiplier\":-999,\"surfaceSourceDensity\":1e9999}}");
            check(policy(RiverPolicyFields.setBoolean(path,invalidSibling,"erosion",false)).token("widthMultiplier").equals("-999"));
            check(policy(RiverPolicyFields.reset(path,invalidSibling,"widthMultiplier")).token("widthMultiplier").equals("null"));
            check(policy(RiverPolicyFields.setText(path,invalidSibling,"surfaceSourceDensity","0")).token("surfaceSourceDensity").equals("0"));
            for (String bad : List.of("[]","1","true","\"unsupported-policy\"")) {
                byte[] wrong = bytes("{\"riverPolicy\":"+bad+"}");
                rejects(() -> RiverPolicyFields.enablePolicy(path,wrong));
                rejects(() -> RiverPolicyFields.setText(path,wrong,"widthMultiplier","1"));
                rejects(() -> RiverPolicyFields.setBoolean(path,wrong,"erosion",false));
                rejects(() -> RiverPolicyFields.setMode(path,wrong,"routing","ALLOW"));
            }
            for (String missing : List.of("{}","{\"riverPolicy\":null}")) {
                rejects(() -> RiverPolicyFields.setText(path,bytes(missing),"widthMultiplier","1"));
                rejects(() -> RiverPolicyFields.setBoolean(path,bytes(missing),"erosion",false));
                rejects(() -> RiverPolicyFields.setMode(path,bytes(missing),"routing","ALLOW"));
                rejects(() -> RiverPolicyFields.reset(path,bytes(missing),"widthMultiplier"));
            }
        }
        check(prefixes.size()==3 && Arrays.equals(source,copy));
        for (String path : List.of("generators/test.json","dimensions/nested/test.json","test.json")) {
            rejects(() -> RiverPolicyFields.enablePolicy(path,source));
            rejects(() -> RiverPolicyFields.setText(path,source,"widthMultiplier","1"));
            rejects(() -> RiverPolicyFields.setBoolean(path,source,"erosion",false));
            rejects(() -> RiverPolicyFields.setMode(path,source,"routing","ALLOW"));
            rejects(() -> RiverPolicyFields.reset(path,source,"widthMultiplier"));
        }
        rejects(() -> RiverPolicyFields.reset("biomes/test.json",source,"profiles"));
        rejects(() -> RiverPolicyFields.setText("biomes/test.json",source,"surfaceBiomes","1"));
        check(DimensionFields.readOnlyPaths(new LosslessJson(source)).equals(List.of("riverPolicy.future","unknown")));
        check(DimensionFields.readOnlyPaths(new LosslessJson(bytes("{\"riverPolicy\":null}"))).equals(List.of("riverPolicy")));
        check(FieldCoverage.fields("riverPolicy").size()==26 && FieldCoverage.fields("riverPolicy").stream().allMatch(FieldCoverage.Field::implemented));
        var drafts = new InspectorDrafts();
        for (String prefix : prefixes) {
            drafts.update(prefix+"widthMultiplier","","1.5");
            drafts.update(prefix+"surfaceSourceSpacing","128","256");
        }
        drafts.update(CaveThresholdFields.draftPrefix("biomes")+"min","0","1");
        drafts.applied(RiverPolicyFields.draftPrefix("biomes")+"widthMultiplier");
        check(drafts.count()==6 && drafts.contains(RiverPolicyFields.draftPrefix("biomes")+"surfaceSourceSpacing") && drafts.contains(CaveThresholdFields.draftPrefix("biomes")+"min"));
        var workspace = StarterPack.create("river_policy");
        for (String path : List.of("dimensions/river_policy.json","regions/starter.json","biomes/starter.json")) {
            byte[] changed = RiverPolicyFields.setText(path,source,"surfaceSourceDensity","0");
            changed = RiverPolicyFields.setText(path,changed,"widthMultiplier","1.5");
            changed = RiverPolicyFields.setBoolean(path,changed,"erosion",false);
            changed = RiverPolicyFields.setMode(path,changed,"routing","PREFER");
            changed = RiverPolicyFields.reset(path,changed,"confined");
            workspace.replace(path,changed);
        }
        workspace.save(temp.resolve("river-policy")); workspace.exportZip(temp.resolve("river-policy.zip"));
        var reopened = PackWorkspace.open(temp.resolve("river-policy")); var imported = PackWorkspace.importZip(temp.resolve("river-policy.zip"));
        for (String path : workspace.paths()) {
            check(Arrays.equals(workspace.read(path),reopened.read(path)));
            check(Arrays.equals(workspace.read(path),imported.read(path)));
        }
        var saved = policy(reopened.read("biomes/starter.json"));
        check(saved.token("surfaceSourceDensity").equals("0") && saved.token("erosion").equals("false") && saved.token("confined").equals("null") && saved.token("profiles").equals("[\"unresolved-profile\"]"));
        System.out.println("PASS: river nullable overrides/owner defaults, exact integers, all modes/bounds, lossless reset/type guards and Save/ZIP contracts");
    }
}
