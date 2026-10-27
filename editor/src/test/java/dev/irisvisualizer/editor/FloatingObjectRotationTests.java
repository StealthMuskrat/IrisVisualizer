package dev.irisvisualizer.editor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class FloatingObjectRotationTests {
    private static final String PATH="biomes/nested/rotation.json";
    private static final String LIST="floatingObjects";
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static String text(byte[] value) { return new String(value,StandardCharsets.UTF_8); }
    private static void check(boolean condition) { if (!condition) throw new AssertionError("Floating rotation contract failed"); }
    private static void rejects(Runnable action) { try { action.run(); } catch (IllegalArgumentException expected) { return; }throw new AssertionError("Expected rejected floating rotation operation"); }
    private static byte[] document(String placement) { return bytes("{\r\n \"floatingChildBiomes\":[{\"floatingObjects\":["+placement+",{}],\"extraObjects\":[{\"rotation\":{\"enabled\":false}}],\"objectShrinkFactor\":0.5},{}],\"future\":9007199254740993,\"escaped\":\"雪\\u0061\"\r\n}\r\n"); }
    private static LosslessJson placement(byte[] value) { return FloatingObjectPlacements.placements(FloatingBiomes.entries(new LosslessJson(value)).getFirst(),LIST).getFirst(); }
    private static LosslessJson rotation(byte[] value) { return FloatingObjectRotations.object(placement(value)); }
    private static LosslessJson axis(byte[] value,String name) { return FloatingObjectRotations.axisObject(rotation(value),name); }
    static void run(Path temp) throws Exception {
        var context=new FloatingObjectRotations.Context(0,LIST,0);
        check(context.prefix().equals(FloatingObjectPlacements.placementPrefix(0,LIST,0)+"rotation."));
        check(FloatingObjectRotations.rotationDefault().equals("true") && FloatingObjectRotations.axisDefault("enabled").equals("false") && FloatingObjectRotations.axisDefault("interval").equals("0"));
        check(FloatingObjectRotations.creationToken("xAxis").equals("{}") && FloatingObjectRotations.creationToken("zAxis").equals("{}"));
        byte[] absent=document("{\"place\":[\"legacy\"],\"mode\":\"FLOATING\",\"rotateTowardsSlope\":true,\"futurePlacement\":9007199254740993}");
        check(rotation(absent)==null);byte[] enabled=FloatingObjectRotations.create(PATH,absent,context);
        check(rotation(enabled).fields().isEmpty() && placement(enabled).token("rotateTowardsSlope").equals("true"));
        rejects(() -> FloatingObjectRotations.create(PATH,enabled,context));
        rejects(() -> FloatingObjectRotations.setRotationFlag(PATH,enabled,context,false));
        byte[] flag=FloatingObjectRotations.enableRotationFlag(PATH,enabled,context);check(rotation(flag).token("enabled").equals("true"));
        check(rotation(FloatingObjectRotations.setRotationFlag(PATH,flag,context,false)).token("enabled").equals("false"));
        rejects(() -> FloatingObjectRotations.enableRotationFlag(PATH,flag,context));
        for (String bad : List.of("null","[]","true","1","\"snippet/rotation\"")) {
            byte[] invalid=document("{\"rotation\":"+bad+"}");
            rejects(() -> rotation(invalid));rejects(() -> FloatingObjectRotations.create(PATH,invalid,context));rejects(() -> FloatingObjectRotations.enableRotationFlag(PATH,invalid,context));
            rejects(() -> FloatingObjectRotations.createAxis(PATH,invalid,context,"yAxis"));
        }
        for (String bad : List.of("null","1","\"true\"")) rejects(() -> FloatingObjectRotations.setRotationFlag(PATH,document("{\"rotation\":{\"enabled\":"+bad+"}}"),context,true));
        for (String name : FloatingObjectRotations.AXES) {
            check(!FloatingObjectRotations.axisHelp(name).isBlank() && axis(enabled,name)==null);
            byte[] created=FloatingObjectRotations.createAxis(PATH,enabled,context,name);
            check(rotation(created).fields().equals(Set.of(name)));
            if (name.equals("yAxis")) check(axis(created,name).fields().equals(Set.of("enabled","interval")) && axis(created,name).token("enabled").equals("true") && axis(created,name).token("interval").equals("90"));
            else check(axis(created,name).fields().isEmpty());
            rejects(() -> FloatingObjectRotations.createAxis(PATH,created,context,name));
            // Existing {} always uses the axis class defaults, even on Y.
            byte[] empty=document("{\"rotation\":{\""+name+"\":{}}}");
            byte[] axisFlag=FloatingObjectRotations.enableAxisField(PATH,empty,context,name,"enabled");
            check(axis(axisFlag,name).token("enabled").equals("false"));
            check(axis(FloatingObjectRotations.setAxisFlag(PATH,axisFlag,context,name,true),name).token("enabled").equals("true"));
            rejects(() -> FloatingObjectRotations.setAxisFlag(PATH,empty,context,name,true));
            rejects(() -> FloatingObjectRotations.enableAxisField(PATH,axisFlag,context,name,"enabled"));
            byte[] interval=FloatingObjectRotations.enableAxisField(PATH,empty,context,name,"interval");check(axis(interval,name).token("interval").equals("0"));
            for (String valid : List.of("0","0.125","1","90","360")) check(axis(FloatingObjectRotations.setInterval(PATH,interval,context,name,valid),name).token("interval").equals(valid));
            for (String bad : List.of("-0.01","360.01","NaN","Infinity","-Infinity")) rejects(() -> FloatingObjectRotations.setInterval(PATH,interval,context,name,bad));
            rejects(() -> FloatingObjectRotations.setInterval(PATH,empty,context,name,"90"));
            for (String[] pair : List.of(new String[]{"-360","360"},new String[]{"0","0"},new String[]{"180","180"},new String[]{"-90.5","45.25"})) {
                byte[] angles=FloatingObjectRotations.setAngles(PATH,empty,context,name,pair[0],pair[1]);check(axis(angles,name).token("min").equals(pair[0]) && axis(angles,name).token("max").equals(pair[1]));
            }
            for (String[] pair : List.of(new String[]{"-360.01","0"},new String[]{"0","360.01"},new String[]{"1","0"},new String[]{"NaN","90"},new String[]{"0","Infinity"})) rejects(() -> FloatingObjectRotations.setAngles(PATH,empty,context,name,pair[0],pair[1]));
            rejects(() -> FloatingObjectRotations.enableAxisField(PATH,empty,context,name,"min"));
            for (String bad : List.of("null","[]","true","1","\"snippet/axis\"")) {
                byte[] invalid=document("{\"rotation\":{\""+name+"\":"+bad+"}}");
                rejects(() -> axis(invalid,name));rejects(() -> FloatingObjectRotations.createAxis(PATH,invalid,context,name));rejects(() -> FloatingObjectRotations.enableAxisField(PATH,invalid,context,name,"enabled"));rejects(() -> FloatingObjectRotations.setAngles(PATH,invalid,context,name,"0","90"));
                check(FloatingObjectRotations.readOnlyPaths(placement(invalid)).equals(List.of("rotation."+name)));
                // A bad child does not prevent an independent parent flag edit.
                check(rotation(FloatingObjectRotations.enableRotationFlag(PATH,invalid,context)).token(name).equals(bad));
            }
            for (String member : List.of("min","max")) for (String bad : List.of("null","[]","true","\"0\"")) rejects(() -> FloatingObjectRotations.setAngles(PATH,document("{\"rotation\":{\""+name+"\":{\""+member+"\":"+bad+"}}}"),context,name,"0","90"));
            for (String bad : List.of("null","1","\"false\"")) rejects(() -> FloatingObjectRotations.setAxisFlag(PATH,document("{\"rotation\":{\""+name+"\":{\"enabled\":"+bad+"}}}"),context,name,true));
            for (String bad : List.of("null","[]","\"90\"")) rejects(() -> FloatingObjectRotations.setInterval(PATH,document("{\"rotation\":{\""+name+"\":{\"interval\":"+bad+"}}}"),context,name,"90"));
            for (byte[] missing : List.of(absent,enabled)) {
                rejects(() -> FloatingObjectRotations.setAngles(PATH,missing,context,name,"0","90"));
                rejects(() -> FloatingObjectRotations.enableAxisField(PATH,missing,context,name,"enabled"));
            }
            for (String path : List.of("dimensions/a.json","regions/a.json","generators/a.json","biomes/a.txt","a.json")) {
                rejects(() -> FloatingObjectRotations.create(path,absent,context));rejects(() -> FloatingObjectRotations.createAxis(path,enabled,context,name));
                rejects(() -> FloatingObjectRotations.enableRotationFlag(path,enabled,context));rejects(() -> FloatingObjectRotations.setRotationFlag(path,flag,context,false));
                rejects(() -> FloatingObjectRotations.enableAxisField(path,empty,context,name,"interval"));rejects(() -> FloatingObjectRotations.setAxisFlag(path,axisFlag,context,name,true));
                rejects(() -> FloatingObjectRotations.setAngles(path,empty,context,name,"0","90"));rejects(() -> FloatingObjectRotations.setInterval(path,interval,context,name,"90"));
            }
        }
        for (String bad : List.of("{}","{\"floatingChildBiomes\":null}","{\"floatingChildBiomes\":[]}","{\"floatingChildBiomes\":[null]}","{\"floatingChildBiomes\":[{\"floatingObjects\":null}]}","{\"floatingChildBiomes\":[{\"floatingObjects\":[\"snippet/placement\"]}]}")) {
            rejects(() -> FloatingObjectRotations.create(PATH,bytes(bad),context));rejects(() -> FloatingObjectRotations.createAxis(PATH,bytes(bad),context,"xAxis"));rejects(() -> FloatingObjectRotations.setAngles(PATH,bytes(bad),context,"xAxis","0","90"));
        }
        for (String list : List.of("extraObjects","topObjectOverrides","bottomObjectOverrides","objects")) rejects(() -> new FloatingObjectRotations.Context(0,list,0));
        rejects(() -> new FloatingObjectRotations.Context(-1,LIST,0));rejects(() -> new FloatingObjectRotations.Context(0,LIST,-1));
        rejects(() -> FloatingObjectRotations.createAxis(PATH,enabled,context,"wAxis"));
        byte[] original=document("{\"rotation\":{\"enabled\":true,\"xAxis\":{\"enabled\":true,\"min\":-9e1,\"max\":9e1,\"interval\":4.5e1,\"futureAxis\":\"雪\\u0061\",\"forceLock\":true},\"yAxis\":{\"enabled\":false,\"futureY\":9007199254740993},\"futureRotation\":true},\"mode\":\"UNKNOWN\",\"translate\":{\"y\":1},\"force\":true,\"densityStyle\":\"snippet/range\"}");
        byte[] copy=original.clone();
        check(text(FloatingObjectRotations.setInterval(PATH,original,context,"xAxis","90")).equals(text(original).replace("\"interval\":4.5e1","\"interval\":90")));
        check(text(FloatingObjectRotations.setAngles(PATH,original,context,"xAxis","-180","180")).equals(text(original).replace("\"min\":-9e1","\"min\":-180").replace("\"max\":9e1","\"max\":180")));
        check(text(FloatingObjectRotations.setRotationFlag(PATH,original,context,false)).equals(text(original).replace("\"rotation\":{\"enabled\":true","\"rotation\":{\"enabled\":false")));
        check(FloatingObjectRotations.readOnlyPaths(placement(original)).containsAll(List.of("rotation.futureRotation","rotation.xAxis.futureAxis","rotation.xAxis.forceLock","rotation.yAxis.futureY")));
        check(!FloatingObjectPlacements.readOnlyFields(placement(original),LIST).contains("rotation"));
        for (String list : List.of("extraObjects","topObjectOverrides","bottomObjectOverrides")) check(FloatingObjectPlacements.readOnlyFields(placement(original),list).contains("rotation"));
        var drafts=new InspectorDrafts();
        String x=context.axisPrefix("xAxis")+"min",y=context.axisPrefix("yAxis")+"interval",translation=FloatingObjectPlacements.placementPrefix(0,LIST,0)+"translate.y",density=FloatingObjectPlacements.placementPrefix(0,LIST,0)+"densityStyle.min",other=new FloatingObjectRotations.Context(1,LIST,0).prefix()+"enabled";
        for (String key : List.of(x,y,translation,density,other)) drafts.update(key,"0","1");
        check(!drafts.transitionUnder(context.axisPrefix("xAxis"),() -> false,() -> { throw new AssertionError(); }) && drafts.count()==5);
        try { drafts.transitionUnder(context.axisPrefix("xAxis"),() -> true,() -> { throw new IOException("failed write"); });throw new AssertionError(); } catch (IOException expected) { check(drafts.count()==5); }
        check(drafts.transitionUnder(context.axisPrefix("xAxis"),() -> true,() -> {}) && drafts.count()==4 && drafts.contains(y) && drafts.contains(translation));
        check(drafts.transitionUnder(context.prefix(),() -> true,() -> {}) && drafts.count()==3 && drafts.contains(density));
        check(drafts.transitionUnder(FloatingObjectPlacements.listPrefix(0,LIST),() -> true,() -> {}) && drafts.count()==1 && drafts.contains(other));
        check(Arrays.equals(original,copy));
        var saved=PackWorkspace.newPack(Map.of(PATH,original,"objects/opaque.iob",new byte[]{0,127,-1,42}));
        saved.replace(PATH,FloatingObjectRotations.setAngles(PATH,saved.read(PATH),context,"xAxis","-180","180"));
        saved.replace(PATH,FloatingObjectRotations.setInterval(PATH,saved.read(PATH),context,"xAxis","90"));
        saved.replace(PATH,FloatingObjectRotations.createAxis(PATH,saved.read(PATH),context,"zAxis"));
        saved.replace(PATH,FloatingObjectRotations.enableAxisField(PATH,saved.read(PATH),context,"zAxis","enabled"));
        saved.replace(PATH,FloatingObjectRotations.setAxisFlag(PATH,saved.read(PATH),context,"zAxis",true));
        saved.save(temp.resolve("floating-rotation"));saved.exportZip(temp.resolve("floating-rotation.zip"));var reopened=PackWorkspace.open(temp.resolve("floating-rotation"));var imported=PackWorkspace.importZip(temp.resolve("floating-rotation.zip"));
        for (String path : saved.paths()) check(Arrays.equals(saved.read(path),reopened.read(path)) && Arrays.equals(saved.read(path),imported.read(path)));
        check(FieldCoverage.fields("objectRotation").size()==4 && FieldCoverage.fields("objectRotation").stream().allMatch(FieldCoverage.Field::implemented));
        check(FieldCoverage.fields("axisRotation").size()==4 && FieldCoverage.fields("axisRotation").stream().allMatch(FieldCoverage.Field::implemented));
        System.out.println("PASS: floating-only rotation, contextual Y versus inline defaults, rotation/axis flags, every angle/interval bound, malformed owners/leaves, anchored guards, scoped drafts, raw preservation and Save/ZIP contracts");
    }
}
