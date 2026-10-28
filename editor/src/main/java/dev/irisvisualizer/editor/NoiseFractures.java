package dev.irisvisualizer.editor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/** Bounded authoring of recursive IrisNoiseGenerator lists without invoking native sampling. */
public final class NoiseFractures {
    public static final List<String> EDITABLE = List.of("fracture");
    public static final int MAXIMUM_DEPTH=8;
    public static final int MAXIMUM_CHILDREN=64;
    private static final Properties DATA=new Properties();
    static {
        try (var input=NoiseFractures.class.getResourceAsStream("/noise-fracture-fields.properties")) {
            if (input==null) throw new IOException("Missing pinned noise-fracture definitions");DATA.load(input);
            if (!"absentEmpty".equals(DATA.getProperty("default.policy")) || !"1".equals(DATA.getProperty("minimumEntries")) || !"orderedEnabledChildren".equals(DATA.getProperty("sampling.policy"))) throw new IOException("Unsupported noise-fracture policy");
        } catch (IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
    public static String help() { return DATA.getProperty("description",""); }
    public static String listPrefix(NoiseFields.Target target) { return target.draftPrefix()+"fracture#"; }
    static List<LosslessJson> entries(LosslessJson noise) { return noise.token("fracture")==null ? null : noise.objectArray("fracture"); }
    private static void authorize(String path,NoiseFields.Target target) {
        if (target==null || !target.resourceBase().equals(ResourceTypes.base(path))) throw new IllegalArgumentException("Wrong noise-fracture resource owner");
        if (target.fracturePath().size()>=MAXIMUM_DEPTH) throw new IllegalArgumentException("Fracture lists at the editor depth limit remain read-only");
    }
    public static byte[] change(String path,byte[] original,NoiseFields.Target target,CompositeNoise.Action action,int index) {
        authorize(path,target);if (action==null) throw new IllegalArgumentException("Select a fracture-list action");
        return NoiseFields.editObject(original,target,noise -> {
            var children=entries(noise);
            if (children==null) {
                if (action!=CompositeNoise.Action.ADD) throw new IllegalArgumentException("Fracture list is absent");
                noise=new LosslessJson(noise.enable("fracture","[]"));children=entries(noise);
            }
            if (children.size()>MAXIMUM_CHILDREN) throw new IllegalArgumentException("Oversized fracture lists remain read-only in this editor");
            if (action==CompositeNoise.Action.ADD) {
                if (children.size()>=MAXIMUM_CHILDREN) throw new IllegalArgumentException("Editor fracture-list size limit reached");
                return noise.objectArrayInsert("fracture",children.size(),new byte[]{'{','}'});
            }
            if (index<0 || index>=children.size()) throw new IllegalArgumentException("Select an existing fracture child");
            return switch (action) {
                case DUPLICATE -> {
                    if (children.size()>=MAXIMUM_CHILDREN) throw new IllegalArgumentException("Editor fracture-list size limit reached");
                    yield noise.objectArrayInsert("fracture",index+1,children.get(index).sourceBytes());
                }
                case REMOVE -> children.size()==1 ? noise.removeField("fracture") : noise.objectArrayRemove("fracture",index);
                case UP -> noise.objectArrayMove("fracture",index,index-1);
                case DOWN -> noise.objectArrayMove("fracture",index,index+1);
                default -> throw new IllegalArgumentException("Unsupported fracture action");
            };
        },action==CompositeNoise.Action.ADD);
    }
    static List<String> readOnlyPaths(LosslessJson noise) { return readOnlyPaths(noise,0); }
    static List<String> readOnlyPaths(LosslessJson noise,int depth) { var paths=new ArrayList<String>();readOnly(noise,"",depth,paths);return List.copyOf(paths); }
    private static void readOnly(LosslessJson noise,String prefix,int depth,List<String> paths) {
        for (String field : noise.fields()) {
            if (field.equals("fracture")) {
                try {
                    var children=entries(noise);
                    if (depth>=MAXIMUM_DEPTH || children.size()>MAXIMUM_CHILDREN) { paths.add(prefix+field);continue; }
                    for (int index=0;index<children.size();index++) readOnly(children.get(index),prefix+field+"["+index+"].",depth+1,paths);
                } catch (IllegalArgumentException unsupported) { paths.add(prefix+field); }
            } else if (field.equals("style")) {
                try { var style=NoiseStyleFields.object(noise);for (String member : style.fields()) if (NoiseStyleFields.readOnly(style,member)) paths.add(prefix+"style."+member); }
                catch (IllegalArgumentException unsupported) { paths.add(prefix+field); }
            } else if (!NoiseFields.EDITABLE.contains(field)) paths.add(prefix+field);
        }
    }
}
