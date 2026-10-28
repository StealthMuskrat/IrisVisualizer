package dev.irisvisualizer.editor;

import java.io.IOException;
import java.util.List;
import java.util.Properties;

/** Pinned procedural shape metadata; native geometry is never evaluated here. */
public final class ProceduralShapes {
    public static final List<String> TREE_FIELDS = List.of("profile", "heightMin", "heightMax", "trunkWidth", "trunkShape", "shapeStart", "shapeEnd", "shapeSteepness", "shapeBase", "shapePeriod", "shapeAmplitude", "shapePeakOffset", "shapeFloor", "leanAzimuth", "leanAngle", "trunkCurve", "curveSteepness", "leanAzimuthMode", "azimuthStart", "azimuthEnd", "azimuthTurns", "azimuthAmplitude", "azimuthPeriod", "azimuthOffset", "azimuthScale", "azimuthWhorlCount", "secondaryLeafFraction", "secondaryTrunkStart", "secondaryTrunkEnd", "roots", "rootStyle", "rootDepth", "rootFlare", "trunkForks", "forkHeight", "forkAngle");
    public static final List<String> RUIN_FIELDS = List.of("form", "heightMin", "heightMax", "widthMin", "widthMax", "lengthMin", "lengthMax", "mossiness", "weatheringScale", "erosion", "erosionScale", "buriedFraction");
    public static final List<String> FORMATION_FIELDS = List.of("form", "strataThickness", "heightMin", "heightMax", "baseWidthMin", "baseWidthMax", "topWidth", "profile", "profileWaist", "profileWaistFloor", "lean", "leanAzimuth", "roughness", "jitter", "hoodooCapRadius", "hoodooCapHeight", "archSpan", "archThickness", "archAsymmetry", "basaltColumns", "basaltColumnRadius", "basaltHeightVariance", "icebergPeaks", "fractureCount", "fractureSeparation", "spiralTurns", "spiralRadius", "spiralThickness", "overhangReach", "overhangDrop", "roomHeightFraction");
    public static final List<String> CORAL_FIELDS = List.of("waterlogged", "form", "tipChance", "heightMin", "heightMax", "spread", "sway", "branchCount", "branchLength", "branchElevation", "branchAzimuth", "subBranches", "subBranchCount", "subBranchScale", "tipClusterRadius", "brainRadius", "brainRoughness", "pillarRadius", "fanWidth", "tendrilCount");
    public static final List<String> FUNGUS_FIELDS = List.of("stemHeightMin", "stemHeightMax", "stemWidth", "stemCurve", "stemLeanAzimuth", "stemWaveAmplitude", "stemWavePeriods", "capShape", "capRadiusMin", "capRadiusMax", "capThickness", "capSquish", "capDroop", "capOverhang", "gillChance", "spotChance", "shelf", "shelfRadius");
    public static final List<String> CRYSTAL_FIELDS = List.of("growthSurface", "tipChance", "glow", "baseRadius", "baseNoise", "shardCountMin", "shardCountMax", "shardLengthMin", "shardLengthMax", "shardBaseRadius", "shardTaper", "spreadAngle", "distribution", "jitter");
    private static final Properties DATA=new Properties();
    static { try (var input=ProceduralShapes.class.getResourceAsStream("/procedural-shape-fields.properties")) { if (input==null) throw new IOException("Missing pinned procedural shape contracts");DATA.load(input);for (String kind : ProceduralObjects.LIST_FIELDS) if (!String.join(",",fields(kind)).equals(DATA.getProperty(kind+".fields"))) throw new IOException("Unsupported procedural shape policy"); } catch (IOException failure) { throw new ExceptionInInitializerError(failure); } }
    public static List<String> fields(String kind) { if (kind==null) throw new IllegalArgumentException("Select a procedural kind");return switch(kind) {
        case "trees" -> TREE_FIELDS;
        case "ruins" -> RUIN_FIELDS;
        case "formations" -> FORMATION_FIELDS;
        case "coral" -> CORAL_FIELDS;
        case "fungi" -> FUNGUS_FIELDS;
        case "crystals" -> CRYSTAL_FIELDS;
        default -> throw new IllegalArgumentException("Select a procedural kind");
    }; }
    public static boolean handles(String kind,String field) { return field!=null && fields(kind).contains(field); }
    static String property(String kind,String field,String key) { if (!handles(kind,field)) throw new IllegalArgumentException("Select a supported shape control");return DATA.getProperty(kind+"."+field+"."+key); }
    public static List<String> choices(String kind,String field) { String items=property(kind,field,"choices");return items==null ? List.of() : List.of(items.split(",")); }
    public static String choiceHelp(String kind,String field,String choice) { return property(kind,field,choice+".description"); }
    public static List<List<String>> pairs(String kind) { fields(kind);return switch(kind) {
        case "trees" -> List.of(List.of("heightMin", "heightMax"));
        case "ruins" -> List.of(List.of("heightMin", "heightMax"), List.of("widthMin", "widthMax"), List.of("lengthMin", "lengthMax"));
        case "formations" -> List.of(List.of("heightMin", "heightMax"), List.of("baseWidthMin", "baseWidthMax"));
        case "coral" -> List.of(List.of("heightMin", "heightMax"));
        case "fungi" -> List.of(List.of("stemHeightMin", "stemHeightMax"), List.of("capRadiusMin", "capRadiusMax"));
        case "crystals" -> List.of(List.of("shardCountMin", "shardCountMax"), List.of("shardLengthMin", "shardLengthMax"));
        default -> List.of();
    }; }
    public static boolean paired(String kind,String field) { return pairs(kind).stream().anyMatch(pair -> pair.contains(field)); }
    private static List<String> pair(String kind,String first) { return pairs(kind).stream().filter(pair -> pair.get(0).equals(first)).findFirst().orElseThrow(() -> new IllegalArgumentException("Select a native shape range")); }
    public static byte[] setPair(String path,byte[] raw,ProceduralObjects.Context c,String first,String left,String right) { if (c==null) throw new IllegalArgumentException("Select a shape context");var pair=pair(c.kind(),first);byte[] next=ProceduralObjects.set(path,raw,c,pair.get(0),left);return ProceduralObjects.set(path,next,c,pair.get(1),right); }
    public static byte[] resetPair(String path,byte[] raw,ProceduralObjects.Context c,String first) { if (c==null) throw new IllegalArgumentException("Select a shape context");var pair=pair(c.kind(),first);return ProceduralObjects.resetLeaf(path,ProceduralObjects.resetLeaf(path,raw,c,pair.get(0)),c,pair.get(1)); }
}
