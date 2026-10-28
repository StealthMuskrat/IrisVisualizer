package dev.irisvisualizer.editor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class RiverPaletteTests {
    private static final String PATH="dimensions/palette-noise.json";
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static String text(byte[] value) { return new String(value,StandardCharsets.UTF_8); }
    private static void check(boolean condition) { if (!condition) throw new AssertionError("River palette noise contract failed"); }
    private static void rejects(Runnable action) { try { action.run(); } catch (IllegalArgumentException expected) { return; } throw new AssertionError("Expected rejected river palette operation"); }
    private static byte[] document(String target,String rawPalette) {
        String nested=target.equals(RiverMaterialFields.PADDING) ? rawPalette : "{\"enabled\":false,\"depth\":2,\"futureMaterial\":true,\"palette\":"+rawPalette+"}";
        String[] parts=target.split("\\.");
        for (int i=parts.length-1;i>=0;i--) nested="{\""+parts[i]+"\":"+nested+"}";
        return bytes("{\r\n \"hydrology\":{\"rivers\":"+nested+"},\"futureRoot\":9007199254740993,\"escaped\":\"雪\\u0061\"\r\n}\r\n");
    }
    private static LosslessJson palette(byte[] document,String target) {
        var rivers=HydrologyFields.rivers(HydrologyFields.hydrology(new LosslessJson(document)));
        return RiverMaterialFields.palette(RiverMaterialFields.object(rivers,target),target);
    }
    private static LosslessJson style(byte[] document,String target) { return NoiseStyleFields.object(palette(document,target),NoiseStyleFields.Target.palette(target)); }
    static void run(Path temp) throws Exception {
        check(RiverPaletteFields.targets().size()==5 && RiverPaletteFields.zoomDefault().equals("5") && RiverPaletteFields.zoomMinimum().equals("0.0001"));
        check(RiverPaletteFields.styleDefault().equals("STATIC") && RiverPaletteFields.styleObjectDefault().equals("FLAT"));
        for (String target : RiverPaletteFields.targets()) {
            var styleTarget=NoiseStyleFields.Target.palette(target);
            check(styleTarget.palette() && styleTarget.member().equals("style") && styleTarget.paletteTarget().equals(target));
            check(NoiseStyleFields.contextDefault(styleTarget).equals("STATIC") && !NoiseStyleFields.contextDescription(styleTarget).isBlank());
            byte[] empty=document(target,"{}");
            byte[] zoom=RiverPaletteFields.enableZoom(PATH,empty,target);
            check(palette(zoom,target).fields().equals(Set.of("zoom")) && palette(zoom,target).token("zoom").equals("5"));
            rejects(() -> RiverPaletteFields.enableZoom(PATH,zoom,target));
            rejects(() -> RiverPaletteFields.setZoom(PATH,empty,target,"10"));
            for (String valid : List.of("0.0001","5","1e2","1.7976931348623157e308")) RiverPaletteFields.setZoom(PATH,zoom,target,valid);
            for (String invalid : List.of("0","-1","0.000099999999","1e-999","1e999","NaN","Infinity","")) rejects(() -> RiverPaletteFields.setZoom(PATH,zoom,target,invalid));
            rejects(() -> RiverPaletteFields.setZoom(PATH,zoom,target,null));
            byte[] contextual=NoiseStyleFields.enable(PATH,empty,styleTarget,"style");
            check(palette(contextual,target).fields().equals(Set.of("style")) && style(contextual,target).fields().equals(Set.of("style")) && style(contextual,target).stringToken("style").equals("STATIC"));
            check(palette(contextual,target).token("zoom")==null && palette(contextual,target).token("palette")==null);
            byte[] nestedZoom=NoiseStyleFields.enable(PATH,empty,styleTarget,"zoom");
            check(style(nestedZoom,target).token("zoom").equals("1") && style(nestedZoom,target).stringToken("style").equals("STATIC") && palette(nestedZoom,target).token("zoom")==null);
            byte[] classDefault=document(target,"{\"style\":{}}");
            check(style(NoiseStyleFields.enable(PATH,classDefault,styleTarget,"style"),target).stringToken("style").equals("FLAT"));
            for (String field : List.of("zoom","exponent","cellularFrequency","cellularZoom")) {
                byte[] enabled=NoiseStyleFields.enable(PATH,classDefault,styleTarget,field);
                check(style(enabled,target).fields().equals(Set.of(field)) && style(enabled,target).token(field).equals(NoiseStyleFields.defaultToken(field)));
                rejects(() -> NoiseStyleFields.enable(PATH,enabled,styleTarget,field));
            }
            String raw="{\"style\":{\"style\":\"FUTURE_STYLE\",\"zoom\":1e0,\"exponent\":1,\"cellularFrequency\":0,\"cellularZoom\":1,\"expression\":\"unknown + x\",\"imageMap\":{\"image\":\"literal\"},\"fracture\":{\"zoom\":2},\"cacheSize\":9007199254740993,\"multiplier\":4,\"futureStyle\":\"雪\\u0061\"},\"zoom\":5e0,\"palette\":[ {\"block\":\"unknown:stored\",\"weight\":999,\"data\":{\"future\":true}} ],\"futurePalette\":true}";
            byte[] original=document(target,raw),copy=original.clone();
            check(text(RiverPaletteFields.setZoom(PATH,original,target,"10")).equals(text(original).replace("\"zoom\":5e0","\"zoom\":10")));
            for (String choice : NoiseStyleFields.choices()) {
                check(text(NoiseStyleFields.editStyle(PATH,original,styleTarget,choice)).equals(text(original).replace("\"FUTURE_STYLE\"","\""+choice+"\"")));
            }
            check(text(NoiseStyleFields.editText(PATH,original,styleTarget,"zoom","2")).equals(text(original).replace("\"zoom\":1e0","\"zoom\":2")));
            NoiseStyleFields.editText(PATH,original,styleTarget,"zoom","0.00001");
            NoiseStyleFields.editText(PATH,original,styleTarget,"exponent","0.01562");
            NoiseStyleFields.editText(PATH,original,styleTarget,"exponent","64");
            for (String field : List.of("zoom","exponent","cellularFrequency","cellularZoom")) {
                for (String invalid : List.of("NaN","Infinity","1e999","")) rejects(() -> NoiseStyleFields.editText(PATH,original,styleTarget,field,invalid));
            }
            rejects(() -> NoiseStyleFields.editText(PATH,original,styleTarget,"zoom","0.000009"));
            rejects(() -> NoiseStyleFields.editText(PATH,original,styleTarget,"exponent","0.01561"));
            rejects(() -> NoiseStyleFields.editText(PATH,original,styleTarget,"exponent","65"));
            rejects(() -> NoiseStyleFields.editText(PATH,original,styleTarget,"cellularFrequency","-1"));
            byte[] inactive=NoiseStyleFields.editText(PATH,original,styleTarget,"cellularZoom","0");
            rejects(() -> NoiseStyleFields.editText(PATH,inactive,styleTarget,"cellularFrequency","1"));
            byte[] active=NoiseStyleFields.editText(PATH,original,styleTarget,"cellularFrequency","1");
            rejects(() -> NoiseStyleFields.editText(PATH,active,styleTarget,"cellularZoom","0"));
            rejects(() -> NoiseStyleFields.editText(PATH,active,styleTarget,"cellularZoom","1e-320"));
            check(style(NoiseStyleFields.editText(PATH,active,styleTarget,"cellularFrequency","0"),target).token("cellularFrequency").equals("0"));
            byte[] negativeInactive=NoiseStyleFields.editText(PATH,original,styleTarget,"cellularZoom","-1");
            rejects(() -> NoiseStyleFields.editText(PATH,negativeInactive,styleTarget,"cellularFrequency","1"));
            String readOnlyPrefix="hydrology.rivers."+target+(target.equals(RiverMaterialFields.PADDING) ? "" : ".palette");
            var readOnly=DimensionFields.readOnlyPaths(new LosslessJson(original));
            for (String field : List.of("expression","imageMap","fracture","cacheSize","multiplier","futureStyle")) {
                check(readOnly.contains(readOnlyPrefix+".style."+field) == List.of("imageMap","cacheSize","multiplier","futureStyle").contains(field));
                rejects(() -> NoiseStyleFields.editText(PATH,original,styleTarget,field,"1"));
                rejects(() -> NoiseStyleFields.enable(PATH,original,styleTarget,field));
            }
            check(readOnly.contains(readOnlyPrefix+".futurePalette") && !readOnly.contains(readOnlyPrefix+".palette[0].data") && !readOnly.contains(readOnlyPrefix+".style") && !readOnly.contains(readOnlyPrefix+".zoom"));
            rejects(() -> NoiseStyleFields.editStyle(PATH,original,styleTarget,"FUTURE_STYLE"));
            for (String wrong : List.of("null","[]","true","1","\"snippet\"")) {
                byte[] wrongPalette=document(target,wrong);
                rejects(() -> RiverPaletteFields.enableZoom(PATH,wrongPalette,target));
                rejects(() -> RiverPaletteFields.setZoom(PATH,wrongPalette,target,"10"));
                rejects(() -> NoiseStyleFields.enable(PATH,wrongPalette,styleTarget,"style"));
                byte[] wrongStyle=document(target,"{\"zoom\":5,\"style\":"+wrong+"}");
                rejects(() -> NoiseStyleFields.enable(PATH,wrongStyle,styleTarget,"style"));
                rejects(() -> NoiseStyleFields.editStyle(PATH,wrongStyle,styleTarget,"STATIC"));
                check(palette(RiverPaletteFields.setZoom(PATH,wrongStyle,target,"10"),target).token("style").equals(wrong));
                check(DimensionFields.readOnlyPaths(new LosslessJson(wrongStyle)).contains(readOnlyPrefix+".style"));
                byte[] wrongZoom=document(target,"{\"zoom\":"+wrong+",\"style\":{}}");
                if (!wrong.equals("1")) rejects(() -> RiverPaletteFields.setZoom(PATH,wrongZoom,target,"10"));
                rejects(() -> RiverPaletteFields.enableZoom(PATH,wrongZoom,target));
                check(style(NoiseStyleFields.enable(PATH,wrongZoom,styleTarget,"style"),target).stringToken("style").equals("FLAT"));
                byte[] wrongStyleScalar=document(target,"{\"style\":{\"zoom\":"+wrong+",\"style\":"+wrong+"}}");
                if (!wrong.equals("1")) rejects(() -> NoiseStyleFields.editText(PATH,wrongStyleScalar,styleTarget,"zoom","2"));
                if (!wrong.startsWith("\"")) rejects(() -> NoiseStyleFields.editStyle(PATH,wrongStyleScalar,styleTarget,"STATIC"));
            }
            for (String missing : List.of("{}","{\"hydrology\":{}}","{\"hydrology\":{\"rivers\":{}}}","{\"hydrology\":{\"rivers\":{\"surface\":{\"banks\":{},\"bed\":{}},\"underground\":{}}}}")) {
                rejects(() -> RiverPaletteFields.enableZoom(PATH,bytes(missing),target));
                rejects(() -> NoiseStyleFields.enable(PATH,bytes(missing),styleTarget,"style"));
            }
            for (String path : List.of("biomes/a.json","regions/a.json","generators/a.json","dimensions/nested/a.json","a.json")) {
                rejects(() -> RiverPaletteFields.enableZoom(path,empty,target));
                rejects(() -> RiverPaletteFields.setZoom(path,original,target,"10"));
                rejects(() -> NoiseStyleFields.enable(path,empty,styleTarget,"style"));
                rejects(() -> NoiseStyleFields.editStyle(path,original,styleTarget,"STATIC"));
            }
            rejects(() -> new NoiseStyleFields.Target("biomes",styleTarget.objectField(),null));
            rejects(() -> new NoiseStyleFields.Target("dimensions",styleTarget.objectField(),null,0));
            check(Arrays.equals(original,copy));
            var drafts=new InspectorDrafts();
            String listPrefix=RiverMaterialFields.listDraftPrefix(target),zoomKey=RiverPaletteFields.draftPrefix(target)+"zoom",styleKey=styleTarget.draftPrefix()+"zoom",materialKey=RiverMaterialFields.draftPrefix(target)+"depth";
            for (String key : List.of(listPrefix+"0.block",zoomKey,styleKey,materialKey)) drafts.update(key,"1","2");
            check(drafts.transitionUnder(listPrefix,() -> true,() -> {}) && drafts.count()==3 && drafts.contains(zoomKey) && drafts.contains(styleKey));
            RiverPaletteFields.setZoom(PATH,original,target,"10");drafts.applied(zoomKey);
            check(drafts.count()==2 && drafts.contains(styleKey) && drafts.contains(materialKey));
            var workspace=PackWorkspace.newPack(Map.of(PATH,original,"dimensions/static.json",contextual,"dimensions/zoom-only.json",zoom,"dimensions/empty.json",empty));
            workspace.save(temp.resolve("river-palette-"+target));workspace.exportZip(temp.resolve("river-palette-"+target+".zip"));
            var reopened=PackWorkspace.open(temp.resolve("river-palette-"+target));var imported=PackWorkspace.importZip(temp.resolve("river-palette-"+target+".zip"));
            for (String path : workspace.paths()) check(Arrays.equals(workspace.read(path),reopened.read(path)) && Arrays.equals(workspace.read(path),imported.read(path)));
            check(palette(reopened.read("dimensions/empty.json"),target).fields().isEmpty());
        }
        rejects(() -> NoiseStyleFields.Target.palette("surface.banks.other"));
        rejects(() -> RiverPaletteFields.enableZoom(PATH,bytes("{}"),"surface.bed.style"));
        check(RiverPaletteFields.targets().stream().map(RiverPaletteFields::draftPrefix).distinct().count()==5);
        check(FieldCoverage.fields("materialPalette").size()==3 && FieldCoverage.fields("materialPalette").stream().allMatch(FieldCoverage.Field::implemented));
        System.out.println("PASS: five river palette noise targets, STATIC/FLAT contextual defaults, palette/style zoom separation, all style choices and cellular bounds, lossless edits, parent/type/draft guards and Save/ZIP contracts");
    }
}
