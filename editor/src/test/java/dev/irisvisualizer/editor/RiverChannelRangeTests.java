package dev.irisvisualizer.editor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class RiverChannelRangeTests {
    private static final String PATH="dimensions/channel-ranges.json";
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static String text(byte[] bytes) { return new String(bytes,StandardCharsets.UTF_8); }
    private static void check(boolean value) { if (!value) throw new AssertionError("Channel range contract failed"); }
    private static void rejects(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Expected rejected channel range operation");
    }
    private static LosslessJson channel(byte[] source) { return SurfaceRiverFields.section(SurfaceRiverFields.surface(HydrologyFields.rivers(HydrologyFields.hydrology(new LosslessJson(source)))),"channel"); }
    private static LosslessJson range(byte[] source,String field) { return RiverChannelRanges.object(channel(source),field); }
    private static LosslessJson style(byte[] source,String field) { return NoiseStyleFields.object(range(source,field),NoiseStyleFields.Target.channelRange(field)); }
    static void run(Path temp) throws Exception {
        byte[] parent=bytes("{\"hydrology\":{\"rivers\":{\"enabled\":false,\"surface\":{\"channel\":{}}}}}");
        check(RiverChannelRanges.classBound("min").equals("16") && RiverChannelRanges.classBound("max").equals("32"));
        check(RiverChannelRanges.classStyle().equals("STATIC"));
        String styleLeaf="{\"style\":\"FUTURE\\u005fSTYLE\",\"zoom\":1e0,\"exponent\":1,\"cellularFrequency\":0,\"cellularZoom\":1,\"expression\":\"unresolved\",\"imageMap\":\"unknown\",\"fracture\":{\"style\":\"FUTURE\"},\"cacheSize\":17,\"multiplier\":2e0,\"future\":\"雪\"}";
        String leaf="{\"min\":4e0,\"max\":8e0,\"style\":"+styleLeaf+",\"future\":\"雪\\u0061\"}";
        String prefix="{\r\n \"hydrology\":{\"rivers\":{\"enabled\":false,\"surface\":{\"channel\":{\"width\":";
        String gap=",\"depth\":";
        String suffix=",\"roughness\":0.25,\"future\":true},\"enabled\":false},\"geometry\":{\"surface\":{\"bedRoughness\":null}},\"profiles\":[{\"id\":\"literal ID\"}]}},\"unknown\":9007199254740993\r\n}\r\n";
        byte[] original=bytes(prefix+leaf+gap+leaf+suffix), copy=original.clone();
        byte[] createdBoth=parent;
        for (String field : RiverChannelRanges.OBJECT_FIELDS) {
            boolean width=field.equals("width");
            String before=width ? prefix : prefix+leaf+gap, after=width ? gap+leaf+suffix : suffix;
            var target=NoiseStyleFields.Target.channelRange(field);
            check(target.channelRange() && target.rangeField().equals(field) && target.member().equals("style") && !target.strictBounds());
            check(!RiverChannelRanges.description(field).isBlank() && !NoiseStyleFields.contextDescription(target).isBlank());
            check(RiverChannelRanges.contextBound(field,"min").equals(width ? "4" : "2"));
            check(RiverChannelRanges.contextBound(field,"max").equals(width ? "8" : "4"));
            check(RiverChannelRanges.contextStyle(field).equals("IRIS") && RiverChannelRanges.contextZoom(field).equals(width ? "1024" : "768"));
            byte[] created=RiverChannelRanges.enable(PATH,parent,field);
            createdBoth=RiverChannelRanges.enable(PATH,createdBoth,field);
            check(channel(created).fields().equals(Set.of(field)) && range(created,field).fields().equals(Set.of("min","max","style")));
            check(range(created,field).token("min").equals(width ? "4" : "2") && range(created,field).token("max").equals(width ? "8" : "4"));
            check(style(created,field).stringToken("style").equals("IRIS") && style(created,field).token("zoom").equals(width ? "1024" : "768"));
            check(style(created,field).fields().equals(Set.of("style","zoom")));
            check(new LosslessJson(created).tokenPath("hydrology.rivers.enabled").equals("false"));
            check(new LosslessJson(created).tokenPath("hydrology.rivers.surface.enabled")==null);
            rejects(() -> RiverChannelRanges.enable(PATH,created,field));
            check(text(RiverChannelRanges.editBounds(PATH,original,field,"-2","10.5")).equals(before+leaf.replace("\"min\":4e0","\"min\":-2").replace("\"max\":8e0","\"max\":10.5")+after));
            check(range(RiverChannelRanges.editBounds(PATH,original,field,"0","0"),field).token("max").equals("0"));
            RiverChannelRanges.editBounds(PATH,original,field,"-20","-1");
            RiverChannelRanges.editBounds(PATH,original,field,Double.toString(-Double.MAX_VALUE),Double.toString(Double.MAX_VALUE));
            rejects(() -> RiverChannelRanges.editBounds(PATH,original,field,"9","8"));
            for (String invalid : List.of("NaN","Infinity","1e999","\"1\"","")) {
                rejects(() -> RiverChannelRanges.editBounds(PATH,original,field,invalid,"8"));
                rejects(() -> RiverChannelRanges.editBounds(PATH,original,field,"4",invalid));
            }
            rejects(() -> RiverChannelRanges.editBounds(PATH,original,field,null,"8"));
            byte[] empty=bytes(before+"{}"+after);
            check(range(RiverChannelRanges.editBounds(PATH,empty,field,"16","32"),field).fields().equals(Set.of("min","max")));
            byte[] partial=bytes(before+"{\"min\":1e0,\"future\":true}"+after);
            check(text(RiverChannelRanges.editBounds(PATH,partial,field,"2","3")).contains("\"future\":true"));
            check(range(RiverChannelRanges.editBounds(PATH,partial,field,"2","3"),field).token("max").equals("3"));
            check(NoiseStyleFields.contextDefault(target).equals("STATIC"));
            for (String child : NoiseStyleFields.EDITABLE) {
                byte[] enabled=NoiseStyleFields.enable(PATH,empty,target,child);
                check(style(enabled,field).stringToken("style").equals("STATIC"));
                check(style(enabled,field).fields().equals(child.equals("style") ? Set.of("style") : Set.of("style",child)));
                check(range(enabled,field).fields().equals(Set.of("style")));
                if (child.equals("zoom")) check(style(enabled,field).token("zoom").equals("1"));
                rejects(() -> NoiseStyleFields.enable(PATH,enabled,target,child));
            }
            byte[] emptyStyle=bytes(before+"{\"style\":{}}"+after);
            check(style(NoiseStyleFields.enable(PATH,emptyStyle,target,"style"),field).stringToken("style").equals("FLAT"));
            for (String choice : NoiseStyleFields.choices()) check(text(NoiseStyleFields.editStyle(PATH,original,target,choice)).equals(before+leaf.replace("FUTURE\\u005fSTYLE",choice)+after));
            check(text(NoiseStyleFields.editText(PATH,original,target,"zoom","2.5")).equals(before+leaf.replace("\"zoom\":1e0","\"zoom\":2.5")+after));
            NoiseStyleFields.editText(PATH,original,target,"zoom","0.00001");
            NoiseStyleFields.editText(PATH,original,target,"exponent","0.01562");
            NoiseStyleFields.editText(PATH,original,target,"exponent","64");
            rejects(() -> NoiseStyleFields.editText(PATH,original,target,"zoom","0"));
            rejects(() -> NoiseStyleFields.editText(PATH,original,target,"exponent","64.01"));
            rejects(() -> NoiseStyleFields.editText(PATH,original,target,"cellularFrequency","-1"));
            // General style rules apply here: inactive cellular zoom may be nonpositive.
            byte[] inactive=NoiseStyleFields.editText(PATH,original,target,"cellularZoom","0");
            rejects(() -> NoiseStyleFields.editText(PATH,inactive,target,"cellularFrequency","1"));
            byte[] repaired=NoiseStyleFields.editText(PATH,inactive,target,"cellularZoom","0.5");
            check(style(NoiseStyleFields.editText(PATH,repaired,target,"cellularFrequency","1"),field).token("cellularFrequency").equals("1"));
            rejects(() -> NoiseStyleFields.editStyle(PATH,original,target,"UNKNOWN"));
            for (String member : List.of("min","max")) for (String bad : List.of("null","true","[]","{}","\"1\"")) {
                byte[] wrong=bytes(before+"{\""+member+"\":"+bad+",\"style\":{\"style\":\"IRIS\"}}"+after);
                rejects(() -> RiverChannelRanges.editBounds(PATH,wrong,field,"1","2"));
                check(style(NoiseStyleFields.editStyle(PATH,wrong,target,"SIMPLEX"),field).stringToken("style").equals("SIMPLEX"));
                check(range(wrong,field).token(member).equals(bad));
            }
            for (String bad : List.of("null","true","[]","1","\"snippet\"")) {
                byte[] wrongRange=bytes(before+bad+after), wrongStyle=bytes(before+"{\"min\":1,\"max\":2,\"style\":"+bad+"}"+after);
                rejects(() -> RiverChannelRanges.enable(PATH,wrongRange,field));
                rejects(() -> RiverChannelRanges.editBounds(PATH,wrongRange,field,"1","2"));
                rejects(() -> NoiseStyleFields.enable(PATH,wrongRange,target,"style"));
                rejects(() -> NoiseStyleFields.enable(PATH,wrongStyle,target,"style"));
                check(range(RiverChannelRanges.editBounds(PATH,wrongStyle,field,"3","4"),field).token("style").equals(bad));
                check(DimensionFields.readOnlyPaths(new LosslessJson(wrongRange)).contains("hydrology.rivers.surface.channel."+field));
                check(DimensionFields.readOnlyPaths(new LosslessJson(wrongStyle)).contains("hydrology.rivers.surface.channel."+field+".style"));
            }
            for (String unsupported : List.of("expression","imageMap","fracture","cacheSize","multiplier")) {
                rejects(() -> NoiseStyleFields.editText(PATH,original,target,unsupported,"1"));
                check(DimensionFields.readOnlyPaths(new LosslessJson(original)).contains("hydrology.rivers.surface.channel."+field+".style."+unsupported) == List.of("fracture","multiplier").contains(unsupported));
            }
            check(DimensionFields.readOnlyPaths(new LosslessJson(original)).contains("hydrology.rivers.surface.channel."+field+".future"));
            for (String missing : List.of("{}","{\"hydrology\":{}}","{\"hydrology\":{\"rivers\":{}}}","{\"hydrology\":{\"rivers\":{\"surface\":{}}}}")) rejects(() -> RiverChannelRanges.enable(PATH,bytes(missing),field));
            rejects(() -> NoiseStyleFields.enable(PATH,parent,target,"style"));
            rejects(() -> RiverChannelRanges.editBounds(PATH,parent,field,"1","2"));
            for (String path : List.of("regions/a.json","biomes/a.json","generators/a.json","dimensions/nested/a.json","a.json")) {
                rejects(() -> RiverChannelRanges.enable(path,parent,field));
                rejects(() -> RiverChannelRanges.editBounds(path,original,field,"1","2"));
                rejects(() -> NoiseStyleFields.editStyle(path,original,target,"IRIS"));
            }
            rejects(() -> new NoiseStyleFields.Target("biomes",target.objectField(),null));
            rejects(() -> new NoiseStyleFields.Target("dimensions",target.objectField(),null,0));
        }
        check(Arrays.equals(original,copy));
        rejects(() -> NoiseStyleFields.Target.channelRange("width.style"));
        rejects(() -> NoiseStyleFields.Target.channelRange("height"));
        rejects(() -> RiverChannelRanges.enable(PATH,parent,"height"));
        var drafts=new InspectorDrafts();
        for (String field : RiverChannelRanges.OBJECT_FIELDS) {
            for (String member : List.of("min","max")) drafts.update(RiverChannelRanges.draftPrefix(field)+member,"1","2");
            drafts.update(NoiseStyleFields.Target.channelRange(field).draftPrefix()+"zoom","1","2");
        }
        drafts.update(SurfaceRiverFields.draftPrefix("channel")+"roughness","0.25","0");
        RiverChannelRanges.editBounds(PATH,original,"width","1","2");
        drafts.applied(RiverChannelRanges.draftPrefix("width")+"min"); drafts.applied(RiverChannelRanges.draftPrefix("width")+"max");
        check(drafts.count()==5 && drafts.contains(NoiseStyleFields.Target.channelRange("width").draftPrefix()+"zoom") && drafts.contains(RiverChannelRanges.draftPrefix("depth")+"min"));
        check(FieldCoverage.fields("surfaceRiverChannel").size()==12 && FieldCoverage.fields("surfaceRiverChannel").stream().allMatch(FieldCoverage.Field::implemented));
        var workspace=PackWorkspace.newPack(Map.of(PATH,original,"dimensions/context.json",createdBoth,"dimensions/absent.json",parent));
        workspace.save(temp.resolve("channel-ranges")); workspace.exportZip(temp.resolve("channel-ranges.zip"));
        var reopened=PackWorkspace.open(temp.resolve("channel-ranges")); var imported=PackWorkspace.importZip(temp.resolve("channel-ranges.zip"));
        for (String path : workspace.paths()) {
            check(Arrays.equals(workspace.read(path),reopened.read(path)));
            check(Arrays.equals(workspace.read(path),imported.read(path)));
        }
        check(channel(reopened.read("dimensions/absent.json")).fields().isEmpty());
        check(new LosslessJson(reopened.read("dimensions/context.json")).tokenPath("hydrology.rivers.enabled").equals("false"));
        System.out.println("PASS: channel range contextual/class/style defaults, paired bounds, all styles/cellular guards, lossless edits, parent/draft guards and Save/ZIP contracts");
    }
}
