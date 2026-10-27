package dev.irisvisualizer.editor;

import java.io.IOException;
import java.util.List;
import java.util.Properties;

/** Native region surface/cave collections reuse the audited object placement type. */
public final class RegionObjectPlacements {
    public static final List<String> LIST_FIELDS = List.of("objects");
    private static final Properties DATA=new Properties();
    static {
        try (var input=RegionObjectPlacements.class.getResourceAsStream("/region-object-fields.properties")) {
            if (input==null) throw new IOException("Missing pinned region object definitions");DATA.load(input);
            if (!"regionThenCaveBiomeThenDimension".equals(DATA.getProperty("caveProfile.policy")) || !"none".equals(DATA.getProperty("anchor.biomeConstraint")) || !"beforeDensityLoop".equals(DATA.getProperty("chance.policy")) || !"absentOnly".equals(DATA.getProperty("objects.enablePolicy"))
                    || !String.join(",",BiomeObjectPlacements.anchors()).equals(DATA.getProperty("caveAnchorMode.choices"))
                    || !BiomeObjectPlacements.defaultAnchor().equals(DATA.getProperty("caveAnchorMode.default")) || !"false".equals(DATA.getProperty("bottom.default"))) throw new IOException("Unsupported region object policy");
        } catch (IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
    public static String description() { return DATA.getProperty("objects.description"); }
    public static String caveHelp() { return "Region cave placements choose an enabled region cave profile, then an enabled cave-biome profile, then the dimension/default profile. A region placement does not constrain its anchor search to a single cave-biome key. Native cave containment, burial and transition checks can still reject placement."; }
}
