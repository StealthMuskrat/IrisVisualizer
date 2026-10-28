package dev.irisvisualizer.editor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.function.Function;

/** Placement-specific terrain/foundation data, reusing pinned shared scalar and palette contracts. */
public final class StructurePlacementIntegration {
    public static final List<String> OBJECT_FIELDS = List.of("terrain", "stilt");
    private static final Properties DATA=new Properties();
    static { try (var input=StructurePlacementIntegration.class.getResourceAsStream("/structure-placement-integration-fields.properties")) { if (input==null) throw new IOException("Missing pinned placement integration contracts");DATA.load(input);if (!"SOURCE,PRESERVE,BORE,FORCE_CARVE".equals(DATA.getProperty("iris.modes")) || !"defaultSource".equals(DATA.getProperty("terrain.absentOrNull"))) throw new IOException("Unsupported placement terrain policy"); } catch (IOException failure) { throw new ExceptionInInitializerError(failure); } }
    private static void authorize(String path) { String base=ResourceTypes.base(path);if (base==null || !StructurePlacements.CONTEXTS.contains(base)) throw new IllegalArgumentException("Placement integration requires a dimension, biome or region"); }
    private static void owner(String owner) { if (owner==null || !OBJECT_FIELDS.contains(owner)) throw new IllegalArgumentException("Select terrain or foundation settings"); }
    static LosslessJson object(LosslessJson entry,String owner) { owner(owner);return NativeStructureAdjustments.object(entry,owner); }
    static byte[] editOwner(byte[] original,int index,String owner,Function<LosslessJson,byte[]> operation) { owner(owner);return StructurePlacements.edit(original,index,e -> { if (object(e,owner)==null) throw new IllegalArgumentException("Enable the inline placement owner first");return e.objectEdit(owner,operation,false); }); }
    public static byte[] enable(String path,byte[] original,int index,String owner) { authorize(path);owner(owner);return StructurePlacements.edit(original,index,e -> { if (object(e,owner)!=null) throw new IllegalArgumentException("Placement owner already exists");return e.enableObject(owner); }); }
    public static byte[] reset(String path,byte[] original,int index,String owner) { authorize(path);owner(owner);return StructurePlacements.edit(original,index,e -> { object(e,owner);return e.token(owner)==null ? e.sourceBytes() : e.removeField(owner); }); }
    public static List<String> modes(LosslessJson entry) { return StructurePlacements.backend(entry).equals("NATIVE") ? NativeStructureAdjustments.choices("mode") : List.of(DATA.getProperty("iris.modes").split(",")); }
    public static byte[] setNumber(String path,byte[] original,int index,String owner,String field,String input) { authorize(path);owner(owner);var number=NativeStructureAdjustments.input(owner,field,input);return editOwner(original,index,owner,o -> NativeStructureAdjustments.putNumber(o,owner,field,number)); }
    public static byte[] setBoolean(String path,byte[] original,int index,String owner,String field,boolean input) { authorize(path);owner(owner);if (!NativeStructureAdjustments.booleanField(owner,field)) throw new IllegalArgumentException("Select a foundation toggle");return editOwner(original,index,owner,o -> { NativeStructureAdjustments.value(o,owner,field);return o.token(field)==null ? o.enable(field,Boolean.toString(input)) : o.bool(field,input); }); }
    public static byte[] setChoice(String path,byte[] original,int index,String field,String choice) { authorize(path);if (field==null || !List.of("mode","shape").contains(field) || choice==null || !NativeStructureAdjustments.choices(field).contains(choice)) throw new IllegalArgumentException("Choose a pinned terrain option");return StructurePlacements.edit(original,index,e -> { if (field.equals("mode") && !modes(e).contains(choice)) throw new IllegalArgumentException("Iris assembly placements support SOURCE, PRESERVE, BORE and FORCE_CARVE; other modes require native sources");if (object(e,"terrain")==null) throw new IllegalArgumentException("Enable inline terrain first");return e.objectEdit("terrain",o -> { String old=NativeStructureAdjustments.value(o,"terrain",field);return o.token(field)!=null && old.equals(choice) ? o.sourceBytes() : o.token(field)==null ? o.enableStringPath(field,choice) : o.stringValue(field,choice); },false); }); }
    public static byte[] resetLeaf(String path,byte[] original,int index,String owner,String field) { authorize(path);owner(owner);return editOwner(original,index,owner,o -> { NativeStructureAdjustments.value(o,owner,field);return o.token(field)==null ? o.sourceBytes() : o.removeField(field); }); }
    static List<String> readOnlyPaths(LosslessJson entry,int index,String base,String owner) {
        owner(owner);var paths=new ArrayList<String>();
        try { var object=object(entry,owner);if (object==null) return List.of();var fields=owner.equals("stilt") ? NativeStructureAdjustments.STILT_FIELDS : NativeStructureAdjustments.TERRAIN_FIELDS;var context=NativeStructurePalettes.Context.placement(base,index,owner);
            for (String field : object.fields()) { if (field.equals(context.member())) { for (String path : NativeStructurePalettes.readOnlyPaths(entry,context)) paths.add(owner+"."+path); }else if (!fields.contains(field)) paths.add(owner+"."+field);else try { NativeStructureAdjustments.value(object,owner,field); } catch (IllegalArgumentException invalid) { paths.add(owner+"."+field); } }
        } catch (IllegalArgumentException invalid) { paths.add(owner); }return List.copyOf(paths);
    }
}
