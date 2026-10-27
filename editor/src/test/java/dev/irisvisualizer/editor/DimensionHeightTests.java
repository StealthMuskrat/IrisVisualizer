package dev.irisvisualizer.editor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class DimensionHeightTests {
    private static final String PATH="dimensions/heights.json";
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static String text(byte[] value) { return new String(value,StandardCharsets.UTF_8); }
    private static void check(boolean value) { if (!value) throw new AssertionError("Dimension height/envelope contract failed"); }
    private static void rejects(Runnable action) { try { action.run(); } catch (IllegalArgumentException expected) { return; }throw new AssertionError("Expected rejected dimension-height operation"); }
    private static byte[] envelope(String dimension,String systems) { return bytes("{"+(dimension==null ? "" : "\"dimensionHeight\":"+dimension+",")+"\"hydrology\":{\"deepFluids\":"+systems+"}}"); }
    private static long count(HydrologyEnvelopes.Report report,HydrologyEnvelopes.Kind kind) { return report.findings().stream().filter(f -> f.kind()==kind).count(); }
    static void run(Path temp) throws Exception {
        check(DimensionHeights.defaultBound("min",true).equals("-64") && DimensionHeights.defaultBound("max",true).equals("320") && DimensionHeights.defaultBound("min",false).equals("16") && DimensionHeights.defaultBound("max",false).equals("32"));
        byte[] parent=bytes("{\"logicalHeight\":256,\"future\":9007199254740993}");
        byte[] enabled=DimensionHeights.enable(PATH,parent);var range=DimensionHeights.object(new LosslessJson(enabled));
        check(range.fields().equals(Set.of("min","max")) && range.token("min").equals("-64") && range.token("max").equals("320"));
        check(new LosslessJson(enabled).token("future").equals("9007199254740993") && new LosslessJson(enabled).token("hydrology")==null);
        rejects(() -> DimensionHeights.enable(PATH,enabled));rejects(() -> DimensionHeights.set(PATH,parent,"-64","320"));
        for (String[] good : new String[][]{{"-64","320"},{"-2032","2032"},{"2016","6080"},{"-208","320"},{"-6.4e1","3.2e2"}}) DimensionHeights.set(PATH,enabled,good[0],good[1]);
        for (String[] bad : new String[][]{{"-2048","320"},{"2032","2304"},{"-65","319"},{"0","255"},{"0","257"},{"0","4080"},{"320","-64"},{"0","16"},{"-64","2147483647"},{"-64","320.000000000000001"},{"NaN","320"},{"Infinity","320"},{"","320"}}) rejects(() -> DimensionHeights.set(PATH,enabled,bad[0],bad[1]));
        byte[] tall=bytes("{\"logicalHeight\":512}");rejects(() -> DimensionHeights.enable(PATH,tall));
        check(new LosslessJson(DimensionHeights.create(PATH,tall,"-64","448")).tokenPath("dimensionHeight.max").equals("448"));
        rejects(() -> DimensionHeights.create(PATH,enabled,"-64","320"));
        byte[] small=bytes("{\"logicalHeight\":0,\"dimensionHeight\":{}}");
        check(new LosslessJson(DimensionHeights.set(PATH,small,"16","32")).tokenPath("dimensionHeight.min").equals("16"));
        rejects(() -> DimensionHeights.set(PATH,bytes("{\"dimensionHeight\":{}}"),"16","32"));
        check(new LosslessJson(DimensionHeights.set(PATH,bytes("{\"logicalHeight\":null,\"dimensionHeight\":{}}"),"-64","320")).token("logicalHeight").equals("null"));
        for (String logical : List.of("-1","385","\"256\"","true","{}","256.000000000000000001")) rejects(() -> DimensionHeights.set(PATH,bytes("{\"logicalHeight\":"+logical+",\"dimensionHeight\":{}}"),"-64","320"));
        String original="{\r\n \"dimensionHeight\":{\"min\":-64,\"max\":320,\"future\":\"雪\\u0061\"},\"logicalHeight\":256,\"hydrology\":{\"deepFluids\":[{\"id\":\"hot\"}]},\"large\":9007199254740993\r\n}\r\n";
        byte[] source=bytes(original),copy=source.clone();
        byte[] widened=DimensionHeights.set(PATH,source,"-208","320");check(text(widened).equals(original.replace("\"min\":-64","\"min\":-208")));
        check(DimensionFields.readOnlyPaths(new LosslessJson(source)).contains("dimensionHeight.future") && !DimensionFields.readOnlyPaths(new LosslessJson(source)).contains("dimensionHeight"));
        for (String bad : List.of("null","[]","true","1","\"snippet/range/world\"")) {
            byte[] wrong=bytes("{\"dimensionHeight\":"+bad+"}");rejects(() -> DimensionHeights.enable(PATH,wrong));rejects(() -> DimensionHeights.set(PATH,wrong,"-64","320"));
            check(DimensionFields.readOnlyPaths(new LosslessJson(wrong)).contains("dimensionHeight"));
            check(count(HydrologyEnvelopes.inspect(envelope(bad,"[{}]")),HydrologyEnvelopes.Kind.UNAVAILABLE)==1);
        }
        for (String bad : List.of("null","[]","{}","true","\"16\"")) rejects(() -> DimensionHeights.set(PATH,bytes("{\"dimensionHeight\":{\"min\":"+bad+",\"max\":320}}"),"-64","320"));
        for (String path : List.of("biomes/a.json","regions/a.json","generators/a.json","dimensions/nested/a.json","a.json")) {
            rejects(() -> DimensionHeights.enable(path,parent));rejects(() -> DimensionHeights.set(path,source,"-64","320"));
        }
        var defaultReport=HydrologyEnvelopes.inspect(envelope(null,"[{}]"));check(defaultReport.checked()==1 && count(defaultReport,HydrologyEnvelopes.Kind.ERROR)==1 && defaultReport.findings().get(0).path().endsWith("height.min"));
        check(HydrologyEnvelopes.inspect(widened).findings().isEmpty() && HydrologyEnvelopes.inspect(widened).checked()==1);
        String equal="[{\"height\":{\"min\":-58,\"max\":314}}]";
        var equality=HydrologyEnvelopes.inspect(envelope(null,equal));check(equality.checked()==1 && count(equality,HydrologyEnvelopes.Kind.ERROR)==2);
        check(HydrologyEnvelopes.inspect(envelope(null,"[{\"height\":{\"min\":-57.999,\"max\":313.999}}]")).findings().isEmpty());
        check(HydrologyEnvelopes.inspect(envelope(null,"[{\"containedPools\":false,\"height\":{\"min\":-62,\"max\":32}}]")).findings().isEmpty());
        check(count(HydrologyEnvelopes.inspect(envelope(null,"[{\"containedPools\":true,\"height\":{\"min\":-62,\"max\":32}}]")),HydrologyEnvelopes.Kind.ERROR)==1);
        check(count(HydrologyEnvelopes.inspect(envelope(null,"[{\"depth\":8,\"height\":{\"min\":-56,\"max\":32}}]")),HydrologyEnvelopes.Kind.ERROR)==1);
        check(count(HydrologyEnvelopes.inspect(envelope(null,"[{\"density\":0,\"containedPools\":false,\"shortChannels\":false,\"height\":{\"min\":-63,\"max\":32}}]")),HydrologyEnvelopes.Kind.ERROR)==1);
        var partialDimension=HydrologyEnvelopes.inspect(envelope("{\"min\":-208}","[{}]"));check(partialDimension.checked()==1 && count(partialDimension,HydrologyEnvelopes.Kind.NOTE)==1 && count(partialDimension,HydrologyEnvelopes.Kind.ERROR)==0);
        check(count(HydrologyEnvelopes.inspect(envelope("{\"min\":-208.5,\"max\":320}","[{}]")),HydrologyEnvelopes.Kind.NOTE)==1);
        var partialHeight=HydrologyEnvelopes.inspect(envelope(null,"[{\"height\":{}}]"));check(partialHeight.checked()==1 && count(partialHeight,HydrologyEnvelopes.Kind.NOTE)==1 && count(partialHeight,HydrologyEnvelopes.Kind.ERROR)==1);
        var emptyDimension=HydrologyEnvelopes.inspect(envelope("{}","[{}]"));check(emptyDimension.checked()==1 && count(emptyDimension,HydrologyEnvelopes.Kind.NOTE)==1 && count(emptyDimension,HydrologyEnvelopes.Kind.ERROR)==1);
        for (String bad : List.of("null","true","[]","\"snippet/style-range/x\"")) {
            var report=HydrologyEnvelopes.inspect(envelope(null,"[{\"height\":"+bad+"}]")).findings();check(report.stream().anyMatch(f -> f.kind()==HydrologyEnvelopes.Kind.UNAVAILABLE));
        }
        for (String field : List.of("depth","headroom","verticalRadius")) for (String bad : List.of("null","true","\"1\"","1.000000000000000001","2147483648")) {
            var report=HydrologyEnvelopes.inspect(envelope(null,"[{\""+field+"\":"+bad+"},{\"height\":{\"min\":0,\"max\":32}}]"));
            check(report.checked()==1 && count(report,HydrologyEnvelopes.Kind.UNAVAILABLE)==1);
        }
        for (String bad : List.of("null","0","\"true\"")) check(count(HydrologyEnvelopes.inspect(envelope(null,"[{\"containedPools\":"+bad+"}]")),HydrologyEnvelopes.Kind.UNAVAILABLE)==1);
        for (String raw : List.of("null","{}","[null]","[{},1]","\"snippet\"")) check(count(HydrologyEnvelopes.inspect(envelope(null,raw)),HydrologyEnvelopes.Kind.UNAVAILABLE)==1);
        check(count(HydrologyEnvelopes.inspect(envelope(null,"[{\"height\":{\"min\":1e999,\"max\":2}}]")),HydrologyEnvelopes.Kind.UNAVAILABLE)==1);
        check(count(HydrologyEnvelopes.inspect(envelope(null,"[{\"height\":{\"min\":3,\"max\":2}}]")),HydrologyEnvelopes.Kind.UNAVAILABLE)==1);
        check(HydrologyEnvelopes.inspect(bytes("{}")).checked()==0 && HydrologyEnvelopes.inspect(envelope(null,"[]")).findings().isEmpty());
        check(count(HydrologyEnvelopes.inspect(bytes("{\"hydrology\":null}")),HydrologyEnvelopes.Kind.UNAVAILABLE)==1);
        check(count(HydrologyEnvelopes.inspect(bytes("invalid JSON")),HydrologyEnvelopes.Kind.UNAVAILABLE)==1);
        check(Arrays.equals(source,copy));
        var drafts=new InspectorDrafts();String minKey=DimensionHeights.draftPrefix()+"min",maxKey=DimensionHeights.draftPrefix()+"max",otherKey=DeepFluids.systemPrefix(0)+"height.min";
        for (String key : List.of(minKey,maxKey,otherKey)) drafts.update(key,"1","2");
        rejects(() -> DimensionHeights.set(PATH,source,"-65","320"));check(drafts.count()==3);
        DimensionHeights.set(PATH,source,"-208","320");drafts.applied(minKey);drafts.applied(maxKey);check(drafts.count()==1 && drafts.contains(otherKey));
        var workspace=PackWorkspace.newPack(Map.of(PATH,source,"dimensions/widened.json",widened,"dimensions/enabled.json",enabled,"dimensions/unsupported.json",envelope("\"snippet/range/world\"","[{}]")));
        // Envelope errors/unavailable data remain saveable and byte-identical.
        workspace.save(temp.resolve("dimension-heights"));workspace.exportZip(temp.resolve("dimension-heights.zip"));var reopened=PackWorkspace.open(temp.resolve("dimension-heights"));var imported=PackWorkspace.importZip(temp.resolve("dimension-heights.zip"));
        for (String path : workspace.paths()) check(Arrays.equals(workspace.read(path),reopened.read(path)) && Arrays.equals(workspace.read(path),imported.read(path)));
        check(FieldCoverage.fields("dimension").stream().filter(f -> f.name().equals("dimensionHeight")).allMatch(FieldCoverage.Field::implemented));
        System.out.println("PASS: contextual/class dimension height defaults, native span/alignment/logical guards, paired lossless edits, strict native deep-fluid margins and fallback notes, unavailable inputs, draft isolation and Save/ZIP contracts");
    }
}
