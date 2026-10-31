package dev.irisvisualizer.editor;

import java.util.EnumMap;
import java.util.List;
import java.util.Objects;

/** Shared path-only authoring metadata. Existing typed setters remain the authority for every edit. */
public final class AuthoringReferences {
    public enum Kind {
        BIOMES, REGIONS, GENERATORS, OBJECTS, DIMENSIONS, STRUCTURES,
        SUBTERRAIN_BIOMES, SUBTERRAIN_REGIONS, PNG_SOURCES, DIMENSION_PATHS, EXPRESSIONS, IMAGE_MAPS
    }

    private static List<String> keys(PackWorkspace workspace,Kind kind) {
        return switch(kind) {
            case BIOMES -> RegionFields.biomeKeys(workspace);
            case REGIONS -> DimensionFields.regionKeys(workspace);
            case GENERATORS -> BiomeGeneratorLinks.choices(workspace);
            case OBJECTS -> FloatingObjectPlacements.objectChoices(workspace);
            case DIMENSIONS -> DimensionStacks.choices(workspace);
            case STRUCTURES -> StructurePlacements.references(workspace,"structures");
            case SUBTERRAIN_BIOMES -> SubterrainFeatures.choices(workspace,"biomes");
            case SUBTERRAIN_REGIONS -> SubterrainFeatures.choices(workspace,"regions");
            case PNG_SOURCES -> ImageMapDefinitions.sources(workspace);
            case DIMENSION_PATHS -> RiverIdLists.dimensions(workspace);
            case EXPRESSIONS -> NoiseStyleSources.choices(workspace,"expression");
            case IMAGE_MAPS -> NoiseStyleSources.choices(workspace,"imageMap");
        };
    }

    public static final class Cache {
        private PackWorkspace workspace;
        private long revision=Long.MIN_VALUE;
        private final EnumMap<Kind,ReferenceChoices.Catalog> catalogs=new EnumMap<>(Kind.class);

        public ReferenceChoices.Catalog catalog(PackWorkspace current,Kind kind) {
            Objects.requireNonNull(current);Objects.requireNonNull(kind);
            if(workspace!=current || revision!=current.resourceRevision()) {
                clear();workspace=current;revision=current.resourceRevision();
            }
            return catalogs.computeIfAbsent(kind,ignored->new ReferenceChoices.Catalog(keys(current,kind)));
        }

        public void clear(){catalogs.clear();workspace=null;revision=Long.MIN_VALUE;}
    }

    private AuthoringReferences(){}
}
