package dev.irisvisualizer.editor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

final class DimensionGenerationControlTests {
    private static final String PATH="dimensions/world.json";
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static String text(byte[] value) { return new String(value,StandardCharsets.UTF_8); }
    private static void check(boolean value) { if (!value) throw new AssertionError("Dimension generation control contract failed"); }
    private static void rejects(Runnable action) { try { action.run(); } catch (IllegalArgumentException expected) { return; }throw new AssertionError("Expected rejected generation control operation"); }
    static void run(Path temp) throws Exception {
        byte[] original=bytes("{\r\n \"future\":\"雪\\u0061\", \"large\":9007199254740993\r\n}\r\n");var empty=new LosslessJson(original);
        check(DimensionGenerationControls.ROOT_FIELDS.size()==10 && DimensionGenerationControls.modes().equals(List.of("OVERWORLD","SUPERFLAT","ENCLOSURE","ISLANDS")) && DimensionGenerationControls.studioModes().size()==9);
        check(DimensionGenerationControls.modeType(empty).equals("OVERWORLD") && DimensionGenerationControls.studioMode(empty).equals("NORMAL"));
        for (String field : DimensionGenerationControls.BOOLEANS) {
            boolean expected=field.equals("useMantle");check(DimensionGenerationControls.booleanValue(empty,field)==expected);
            byte[] changed=DimensionGenerationControls.setBoolean(PATH,original,field,!expected);check(DimensionGenerationControls.booleanValue(new LosslessJson(changed),field)!=expected);
            check(Arrays.equals(original,DimensionGenerationControls.resetRoot(PATH,changed,field)) && Arrays.equals(original,DimensionGenerationControls.resetRoot(PATH,original,field)));
        }
        byte[] enabled=DimensionGenerationControls.enableMode(PATH,original);check(DimensionGenerationControls.mode(new LosslessJson(enabled)).fields().isEmpty());rejects(() -> DimensionGenerationControls.enableMode(PATH,enabled));rejects(() -> DimensionGenerationControls.setMode(PATH,original,"OVERWORLD"));
        for (String mode : DimensionGenerationControls.modes()) { byte[] changed=DimensionGenerationControls.setMode(PATH,enabled,mode);check(DimensionGenerationControls.modeType(new LosslessJson(changed)).equals(mode) && !DimensionGenerationControls.choiceHelp(false,mode).isEmpty());check(DimensionGenerationControls.mode(new LosslessJson(DimensionGenerationControls.resetModeType(PATH,changed))).fields().isEmpty()); }
        byte[] precise=bytes("{\"mode\": { \"type\" : \"OVER\\u0057ORLD\",\"future\":{\"n\":9007199254740993}},\"studioMode\":\"NOR\\u004dAL\",\"debugCrossSectionsMod\":3e0,\"explodeBiomePaletteSize\":3.000,\"future\":\"雪\"}\r\n");
        check(Arrays.equals(precise,DimensionGenerationControls.setMode(PATH,precise,"OVERWORLD")) && Arrays.equals(precise,DimensionGenerationControls.setStudioMode(PATH,precise,"NORMAL")) && Arrays.equals(precise,DimensionGenerationControls.setNumber(PATH,precise,"debugCrossSectionsMod","3.0")) && Arrays.equals(precise,DimensionGenerationControls.setNumber(PATH,precise,"explodeBiomePaletteSize","3e0")));
        byte[] updated=DimensionGenerationControls.setMode(PATH,precise,"ISLANDS");check(text(updated).contains("\"future\":{\"n\":9007199254740993}") && text(updated).contains("\"debugCrossSectionsMod\":3e0"));
        check(DimensionGenerationControls.mode(new LosslessJson(DimensionGenerationControls.resetModeType(PATH,updated))).token("future").equals("{\"n\":9007199254740993}"));
        for (String studio : DimensionGenerationControls.studioModes()) { byte[] changed=DimensionGenerationControls.setStudioMode(PATH,original,studio);check(DimensionGenerationControls.studioMode(new LosslessJson(changed)).equals(studio) && !DimensionGenerationControls.choiceHelp(true,studio).isEmpty());check(Arrays.equals(original,DimensionGenerationControls.resetRoot(PATH,changed,"studioMode"))); }
        byte[] unknown=bytes("{\"mode\":{\"type\":\"FUTURE\",\"advanced\":true},\"studioMode\":\"FUTURE\"}");check(DimensionGenerationControls.readOnlyPaths(new LosslessJson(unknown)).equals(List.of("mode.advanced")));check(DimensionGenerationControls.modeType(new LosslessJson(DimensionGenerationControls.setMode(PATH,unknown,"ENCLOSURE"))).equals("ENCLOSURE"));
        rejects(() -> DimensionGenerationControls.setMode(PATH,enabled,"overworld"));rejects(() -> DimensionGenerationControls.setMode(PATH,enabled,null));rejects(() -> DimensionGenerationControls.setStudioMode(PATH,original,"BIOME_BUFFET_2x2"));
        for (String field : DimensionGenerationControls.NUMBERS) {
            byte[] number=DimensionGenerationControls.enableNumber(PATH,original,field);check(DimensionGenerationControls.numberValue(new LosslessJson(number),field).equals("3"));rejects(() -> DimensionGenerationControls.enableNumber(PATH,number,field));rejects(() -> DimensionGenerationControls.setNumber(PATH,original,field,"3"));
            String minimum=field.equals("debugCrossSectionsMod") ? "2" : "1";
            for (String valid : List.of(minimum,"16","3.000","3e0")) check(new LosslessJson(DimensionGenerationControls.setNumber(PATH,number,field,valid)).token(field)!=null);
            for (String invalid : List.of("0","-1","17","3.5","2147483648","1e1000","NaN","Infinity","null","")) rejects(() -> DimensionGenerationControls.setNumber(PATH,number,field,invalid));
            rejects(() -> DimensionGenerationControls.setNumber(PATH,number,field,null));check(Arrays.equals(original,DimensionGenerationControls.resetRoot(PATH,number,field)));
            byte[] outOfBounds=bytes("{\""+field+"\":0}");check(new LosslessJson(DimensionGenerationControls.setNumber(PATH,outOfBounds,field,"3")).token(field).equals("3"));
        }
        for (String token : List.of("null","\"snippet\"","[]","true","3")) {
            byte[] wrong=bytes("{\"mode\":"+token+"}");rejects(() -> DimensionGenerationControls.enableMode(PATH,wrong));rejects(() -> DimensionGenerationControls.setMode(PATH,wrong,"OVERWORLD"));rejects(() -> DimensionGenerationControls.resetModeType(PATH,wrong));check(DimensionFields.readOnlyPaths(new LosslessJson(wrong)).contains("mode"));
        }
        for (String token : List.of("null","3","{}","[]","true")) {
            byte[] wrong=bytes("{\"mode\":{\"type\":"+token+",\"future\":1},\"studioMode\":"+token+"}");rejects(() -> DimensionGenerationControls.setMode(PATH,wrong,"OVERWORLD"));rejects(() -> DimensionGenerationControls.resetModeType(PATH,wrong));rejects(() -> DimensionGenerationControls.setStudioMode(PATH,wrong,"NORMAL"));rejects(() -> DimensionGenerationControls.resetRoot(PATH,wrong,"studioMode"));check(DimensionFields.readOnlyPaths(new LosslessJson(wrong)).containsAll(List.of("mode.type","mode.future","studioMode")));
        }
        for (String field : DimensionGenerationControls.BOOLEANS) for (String token : List.of("null","\"false\"","0","{}")) { byte[] wrong=bytes("{\""+field+"\":"+token+"}");rejects(() -> DimensionGenerationControls.setBoolean(PATH,wrong,field,true));rejects(() -> DimensionGenerationControls.resetRoot(PATH,wrong,field));check(DimensionFields.readOnlyPaths(new LosslessJson(wrong)).contains(field)); }
        for (String field : DimensionGenerationControls.NUMBERS) for (String token : List.of("null","\"3\"","3.5","2147483648","[]")) { byte[] wrong=bytes("{\""+field+"\":"+token+"}");rejects(() -> DimensionGenerationControls.setNumber(PATH,wrong,field,"3"));rejects(() -> DimensionGenerationControls.resetRoot(PATH,wrong,field));check(DimensionFields.readOnlyPaths(new LosslessJson(wrong)).contains(field)); }
        rejects(() -> DimensionGenerationControls.resetRoot(PATH,enabled,"mode"));rejects(() -> DimensionGenerationControls.setBoolean(PATH,original,"future",true));rejects(() -> DimensionGenerationControls.enableNumber(PATH,original,"future"));rejects(() -> DimensionGenerationControls.setNumber(PATH,original,"useMantle","1"));
        for (String path : List.of("biomes/world.json","regions/world.json","generators/world.json","dimensions/nested/world.json","world.json")) { rejects(() -> DimensionGenerationControls.enableMode(path,original));rejects(() -> DimensionGenerationControls.setMode(path,enabled,"OVERWORLD"));rejects(() -> DimensionGenerationControls.setStudioMode(path,original,"NORMAL"));rejects(() -> DimensionGenerationControls.setBoolean(path,original,"useMantle",false));rejects(() -> DimensionGenerationControls.enableNumber(path,original,"debugCrossSectionsMod"));rejects(() -> DimensionGenerationControls.resetRoot(path,original,"studioMode")); }
        check(DimensionFields.readOnlyPaths(new LosslessJson(precise)).equals(List.of("mode.future","future")) && FieldCoverage.groups(PATH).contains("dimensionMode") && FieldCoverage.fields("dimensionMode").size()==1 && FieldCoverage.fields("dimensionMode").get(0).implemented());
        var drafts=new InspectorDrafts();String key=DimensionGenerationControls.prefix()+"debugCrossSectionsMod",other=DimensionGenerationControls.prefix()+"explodeBiomePaletteSize",outside="elsewhere";for (String draft : List.of(key,other,outside)) drafts.update(draft,"3","4");
        check(!drafts.transitionUnder(key,() -> false,() -> {}) && drafts.count()==3);try { drafts.transitionUnder(key,() -> true,() -> { throw new IOException("write failure"); });throw new AssertionError(); } catch (IOException expected) { check(drafts.count()==3); }
        check(drafts.transitionUnder(key,() -> true,() -> {}) && drafts.count()==2 && drafts.contains(other) && drafts.contains(outside));
        var workspace=PackWorkspace.newPack(Map.of(PATH,precise,"objects/opaque.iob",new byte[]{0,1,2},"biomes/opaque.json",bytes("opaque")));workspace.replace(PATH,updated);workspace.save(temp.resolve("dimension-generation"));workspace.exportZip(temp.resolve("dimension-generation.zip"));var reopened=PackWorkspace.open(temp.resolve("dimension-generation"));var imported=PackWorkspace.importZip(temp.resolve("dimension-generation.zip"));for (String path : workspace.paths()) check(Arrays.equals(workspace.read(path),reopened.read(path)) && Arrays.equals(workspace.read(path),imported.read(path)));
        System.out.println("PASS: four dimension modes, nine server Studio modes, six native toggles, exact bounded integers, absent/default/reset semantics, raw no-ops/unknown data, malformed/snippet/path guards, scoped drafts, coverage and lossless Save/ZIP without enabling previews");
    }
}
