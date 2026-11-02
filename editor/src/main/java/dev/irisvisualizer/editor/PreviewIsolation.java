package dev.irisvisualizer.editor;

import java.util.Map;
import java.util.Objects;

/** Describes a future native request only. No pack mutation, model loading or generation. */
record PreviewIsolation(Placement placement,String dimension,String resourceType,String resourceKey,Map<String,String> nativeFields) {
    enum Placement {ISOLATED,DIMENSION_SETTINGS}

    PreviewIsolation {
        Objects.requireNonNull(placement);Objects.requireNonNull(dimension);Objects.requireNonNull(resourceKey);
        if(!"biomes".equals(resourceType) && !"regions".equals(resourceType))throw new IllegalArgumentException("Only biome and region previews have native isolation controls.");
        var expected=fields(placement,resourceType,resourceKey);
        if(!expected.equals(nativeFields))throw new IllegalArgumentException("Unexpected native focus request.");
        nativeFields=Map.copyOf(nativeFields);
    }
    static PreviewIsolation request(Placement placement,String dimension,PreviewSession.Identity resource){
        Objects.requireNonNull(resource);return new PreviewIsolation(placement,dimension,resource.resourceType(),resource.resourceKey(),fields(placement,resource.resourceType(),resource.resourceKey()));
    }
    private static Map<String,String> fields(Placement placement,String family,String key){
        ResourceSelectors.requirePreviewIsolationPolicy();
        if(placement==Placement.DIMENSION_SETTINGS)return Map.of();
        // Clear the competing native focus explicitly; do not inherit a stored biome focus for a region.
        return "biomes".equals(family)?Map.of("focus",key,"focusRegion",""):Map.of("focus","","focusRegion",key);
    }
    static String help(boolean biome,Placement placement){
        if(placement==Placement.DIMENSION_SETTINGS)return "Uses the dimension's stored settings, including any stored focus. The selected resource is not forced and may not occur at these coordinates.";
        return (biome?"Native biome focus evaluates the selected biome as LAND, even if it normally appears as sea, shore or cave. Iris resolves its region or creates an in-memory fallback region.":"Native region focus keeps that region's native biome distribution; it does not force an individual biome.")
                +" Version-excluded or unloadable resources cannot be guaranteed to remain focused. An active native studio buffet takes precedence over focus. No preview has been generated.";
    }
}
