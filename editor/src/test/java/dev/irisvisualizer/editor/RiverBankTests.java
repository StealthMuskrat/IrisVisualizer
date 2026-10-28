package dev.irisvisualizer.editor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class RiverBankTests {
    private static final String PATH="dimensions/banks.json";
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static String text(byte[] bytes) { return new String(bytes,StandardCharsets.UTF_8); }
    private static void check(boolean value) { if (!value) throw new AssertionError("River bank contract failed"); }
    private static void rejects(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Expected rejected bank operation");
    }
    private static LosslessJson bank(byte[] source) { return RiverBankFields.object(RiverGeometryFields.geometry(HydrologyFields.rivers(HydrologyFields.hydrology(new LosslessJson(source))))); }
    private static LosslessJson style(byte[] source) { return NoiseStyleFields.object(bank(source),NoiseStyleFields.Target.bank()); }
    static void run(Path temp) throws Exception {
        var target=NoiseStyleFields.Target.bank();
        Map<String,String> defaults=Map.of("enabled","true","seed","0","amplitude","8","horizontalScale","48","verticalScale","8","maximumOverhang","8");
        Map<String,double[]> bounds=Map.of("amplitude",new double[]{0,32},"horizontalScale",new double[]{4,4096},"verticalScale",new double[]{4,256},"maximumOverhang",new double[]{1,16});
        byte[] parent=bytes("{\"hydrology\":{\"rivers\":{\"enabled\":false,\"geometry\":{\"surface\":{\"bedRoughness\":null}}}}}");
        byte[] empty=RiverBankFields.enableObject(PATH,parent);
        check(bank(empty).fields().isEmpty() && new LosslessJson(empty).tokenPath("hydrology.rivers.enabled").equals("false"));
        check(new LosslessJson(empty).tokenPath("hydrology.rivers.geometry.surface.bedRoughness").equals("null"));
        rejects(() -> RiverBankFields.enableObject(PATH,empty));
        for (String field : RiverBankFields.EDITABLE) {
            check(RiverBankFields.defaultToken(field).equals(defaults.get(field)) && !RiverBankFields.description(field).isBlank());
            byte[] enabled=RiverBankFields.enable(PATH,empty,field);
            check(bank(enabled).fields().equals(Set.of(field)) && bank(enabled).token(field).equals(defaults.get(field)));
            rejects(() -> RiverBankFields.enable(PATH,enabled,field));
        }
        check(bank(RiverBankFields.setEnabled(PATH,empty,false)).token("enabled").equals("false"));
        check(bank(empty).token("enabled")==null);
        String prefix="{\r\n \"hydrology\":{\"rivers\":{\"enabled\":false,\"geometry\":{\"banks3D\":";
        String suffix=",\"grottos\":{\"aspectRange\":0.2},\"future\":true},\"routing\":{\"sampleSpacing\":64}}},\"unknown\":9007199254740993\r\n}\r\n";
        String styleLeaf="{\"style\":\"FUTURE\\u005fSTYLE\",\"zoom\":1e0,\"exponent\":1,\"cellularFrequency\":0,\"cellularZoom\":1,\"multiplier\":2e0,\"cacheSize\":17,\"expression\":\"unresolved\",\"imageMap\":{\"future\":true},\"fracture\":{\"style\":\"UNKNOWN\",\"zoom\":9e0},\"unknown\":\"雪\"}";
        String leaf="{\"enabled\":true,\"seed\":9007199254740993,\"amplitude\":8,\"horizontalScale\":48,\"verticalScale\":8,\"maximumOverhang\":8,\"densityStyle\":"+styleLeaf+",\"future\":\"雪\\u0061\"}";
        byte[] original=bytes(prefix+leaf+suffix), copy=original.clone();
        check(text(RiverBankFields.setEnabled(PATH,original,false)).equals(prefix+leaf.replace("\"enabled\":true","\"enabled\":false")+suffix));
        byte[] exact=RiverBankFields.setText(PATH,original,"seed","-9223372036854775808");
        check(text(exact).equals(prefix+leaf.replace("9007199254740993","-9223372036854775808")+suffix));
        check(bank(RiverBankFields.setText(PATH,exact,"seed","9223372036854775807")).token("seed").equals("9223372036854775807"));
        check(bank(RiverBankFields.setText(PATH,exact,"seed","9007199254740993e0")).token("seed").equals("9007199254740993"));
        for (String input : List.of("9223372036854775808","-9223372036854775809","1.000000000000000001","NaN","Infinity","")) rejects(() -> RiverBankFields.setText(PATH,original,"seed",input));
        for (var entry : bounds.entrySet()) {
            String field=entry.getKey(), old=bank(original).token(field);
            double min=entry.getValue()[0], max=entry.getValue()[1];
            String minimum=java.math.BigDecimal.valueOf(min).stripTrailingZeros().toPlainString();
            check(text(RiverBankFields.setText(PATH,original,field,minimum)).equals(prefix+leaf.replace("\""+field+"\":"+old,"\""+field+"\":"+minimum)+suffix));
            RiverBankFields.setText(PATH,original,field,Double.toString(max));
            for (String invalid : List.of(Double.toString(min-1),Double.toString(max+1),"NaN","Infinity","1e999")) rejects(() -> RiverBankFields.setText(PATH,original,field,invalid));
            rejects(() -> RiverBankFields.setText(PATH,empty,field,minimum));
        }
        rejects(() -> RiverBankFields.setText(PATH,original,"maximumOverhang","8.000000000000000001"));
        check(bank(RiverBankFields.setText(PATH,original,"maximumOverhang","1.6e1")).token("maximumOverhang").equals("16"));
        for (String field : RiverBankFields.EDITABLE) for (String bad : List.of("null","true","[]","{}","\"1\"")) {
            if (field.equals("enabled") && bad.equals("true")) continue;
            byte[] wrong=bytes(prefix+"{\""+field+"\":"+bad+"}"+suffix);
            if (field.equals("enabled")) rejects(() -> RiverBankFields.setEnabled(PATH,wrong,false));
            else rejects(() -> RiverBankFields.setText(PATH,wrong,field,"8"));
        }
        rejects(() -> RiverBankFields.setText(PATH,original,"enabled","1"));
        rejects(() -> RiverBankFields.enable(PATH,original,"densityStyle"));
        check(target.banks() && target.strictBounds() && target.member().equals("densityStyle"));
        check(NoiseStyleFields.contextDefault(target).equals("SIMPLEX") && !NoiseStyleFields.contextDescription(target).isBlank());
        for (String choice : NoiseStyleFields.choices()) check(text(NoiseStyleFields.editStyle(PATH,original,target,choice)).equals(prefix+leaf.replace("FUTURE\\u005fSTYLE",choice)+suffix));
        check(text(NoiseStyleFields.editText(PATH,original,target,"zoom","2.5")).equals(prefix+leaf.replace("\"zoom\":1e0","\"zoom\":2.5")+suffix));
        Map<String,double[]> styleBounds=Map.of("zoom",new double[]{0.00001,Double.MAX_VALUE},"cellularFrequency",new double[]{0,Double.MAX_VALUE},"cellularZoom",new double[]{0.00001,Double.MAX_VALUE},"exponent",new double[]{0.01562,64});
        for (var entry : styleBounds.entrySet()) {
            String field=entry.getKey();
            NoiseStyleFields.editText(PATH,original,target,field,Double.toString(entry.getValue()[0]));
            NoiseStyleFields.editText(PATH,original,target,field,Double.toString(entry.getValue()[1]));
            for (String bad : List.of(Double.toString(entry.getValue()[0]-1),"NaN","Infinity","1e999")) rejects(() -> NoiseStyleFields.editText(PATH,original,target,field,bad));
        }
        for (String bad : List.of("0","0.000001","4.9e-324")) rejects(() -> NoiseStyleFields.editText(PATH,original,target,"cellularZoom",bad));
        rejects(() -> NoiseStyleFields.editText(PATH,original,target,"exponent","64.01"));
        byte[] invalidZoom=bytes(prefix+"{\"densityStyle\":{\"cellularFrequency\":0,\"cellularZoom\":0}}"+suffix);
        rejects(() -> NoiseStyleFields.editText(PATH,invalidZoom,target,"cellularFrequency","1"));
        byte[] repaired=NoiseStyleFields.editText(PATH,invalidZoom,target,"cellularZoom","0.00001");
        check(style(NoiseStyleFields.editText(PATH,repaired,target,"cellularFrequency","1")).token("cellularFrequency").equals("1"));
        for (String field : NoiseStyleFields.EDITABLE) {
            byte[] enabled=NoiseStyleFields.enable(PATH,empty,target,field);
            check(style(enabled).stringToken("style").equals("SIMPLEX"));
            check(style(enabled).fields().equals(field.equals("style") ? Set.of("style") : Set.of("style",field)));
            check(bank(enabled).fields().equals(Set.of("densityStyle")));
            rejects(() -> NoiseStyleFields.enable(PATH,enabled,target,field));
        }
        byte[] emptyStyle=bytes(prefix+"{\"densityStyle\":{}}"+suffix);
        check(style(NoiseStyleFields.enable(PATH,emptyStyle,target,"style")).stringToken("style").equals("FLAT"));
        check(style(NoiseStyleFields.enable(PATH,emptyStyle,target,"zoom")).fields().equals(Set.of("zoom")));
        for (String field : List.of("fracture","multiplier","cacheSize","expression","imageMap")) {
            rejects(() -> NoiseStyleFields.enable(PATH,original,target,field));
            rejects(() -> NoiseStyleFields.editText(PATH,original,target,field,"1"));
            check(DimensionFields.readOnlyPaths(new LosslessJson(original)).contains("hydrology.rivers.geometry.banks3D.densityStyle."+field) == List.of("multiplier","imageMap").contains(field));
        }
        for (String bad : List.of("null","[]","true","1","\"snippet\"")) {
            byte[] wrongBank=bytes(prefix+bad+suffix), wrongStyle=bytes(prefix+"{\"amplitude\":8,\"densityStyle\":"+bad+"}"+suffix);
            rejects(() -> RiverBankFields.enableObject(PATH,wrongBank));
            rejects(() -> RiverBankFields.enable(PATH,wrongBank,"seed"));
            rejects(() -> NoiseStyleFields.enable(PATH,wrongBank,target,"style"));
            rejects(() -> NoiseStyleFields.enable(PATH,wrongStyle,target,"style"));
            rejects(() -> NoiseStyleFields.editStyle(PATH,wrongStyle,target,"SIMPLEX"));
            check(bank(RiverBankFields.setText(PATH,wrongStyle,"amplitude","16")).token("densityStyle").equals(bad));
            check(DimensionFields.readOnlyPaths(new LosslessJson(wrongStyle)).contains("hydrology.rivers.geometry.banks3D.densityStyle"));
        }
        for (String missing : List.of("{}","{\"hydrology\":{}}","{\"hydrology\":{\"rivers\":{}}}","{\"hydrology\":{\"rivers\":{\"geometry\":null}}}")) {
            rejects(() -> RiverBankFields.enableObject(PATH,bytes(missing)));
            rejects(() -> NoiseStyleFields.enable(PATH,bytes(missing),target,"style"));
        }
        rejects(() -> NoiseStyleFields.enable(PATH,parent,target,"style"));
        for (String path : List.of("biomes/a.json","regions/a.json","generators/a.json","dimensions/nested/a.json","a.json")) {
            rejects(() -> RiverBankFields.enableObject(path,parent));
            rejects(() -> NoiseStyleFields.editStyle(path,original,target,"SIMPLEX"));
        }
        rejects(() -> new NoiseStyleFields.Target("biomes","hydrology.rivers.geometry.banks3D.densityStyle",null));
        rejects(() -> new NoiseStyleFields.Target("dimensions","hydrology.rivers.geometry.banks3D.densityStyle.fracture",null));
        rejects(() -> new NoiseStyleFields.Target("dimensions","hydrology.rivers.geometry.banks3D.densityStyle",null,0));
        rejects(() -> NoiseStyleFields.editStyle(PATH,original,target,"UNKNOWN"));
        check(Arrays.equals(original,copy));
        var drafts=new InspectorDrafts();
        drafts.update(RiverBankFields.draftPrefix()+"seed","0","1"); drafts.update(target.draftPrefix()+"zoom","1","2");
        drafts.update(RiverGeometryFields.draftPrefix("surface")+"bedRoundness","2","3");
        drafts.update(HydrologyFields.draftPrefix()+"tileSize","2048","4096");
        byte[] changed=NoiseStyleFields.editText(PATH,exact,target,"zoom","2"); drafts.applied(target.draftPrefix()+"zoom");
        check(drafts.count()==3 && drafts.contains(RiverBankFields.draftPrefix()+"seed"));
        check(FieldCoverage.fields("riverGeometry").size()==6 && FieldCoverage.fields("riverGeometry").stream().allMatch(FieldCoverage.Field::implemented));
        check(FieldCoverage.fields("riverBank3D").size()==7 && FieldCoverage.fields("riverBank3D").stream().allMatch(FieldCoverage.Field::implemented));
        var workspace=PackWorkspace.newPack(Map.of(PATH,changed,"dimensions/defaults.json",empty,"dimensions/absent.json",parent));
        workspace.save(temp.resolve("river-banks")); workspace.exportZip(temp.resolve("river-banks.zip"));
        var reopened=PackWorkspace.open(temp.resolve("river-banks")); var imported=PackWorkspace.importZip(temp.resolve("river-banks.zip"));
        for (String path : workspace.paths()) {
            check(Arrays.equals(workspace.read(path),reopened.read(path)));
            check(Arrays.equals(workspace.read(path),imported.read(path)));
        }
        check(bank(reopened.read("dimensions/defaults.json")).fields().isEmpty());
        check(new LosslessJson(reopened.read(PATH)).tokenPath("hydrology.rivers.enabled").equals("false"));
        System.out.println("PASS: bank defaults/native bounds, exact seeds/overhangs, contextual density styles/strict limits, lossless edits, parent/draft guards and Save/ZIP contracts");
    }
}
