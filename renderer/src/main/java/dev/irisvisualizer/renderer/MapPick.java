package dev.irisvisualizer.renderer;

import java.util.*;

/** Constant-time inspection of the original sample behind a displayed map pixel. */
public final class MapPick {
    private MapPick() {}
    public record Hit(MapSamples.Context context,MapSamples.Kind kind,MapView.Pixel pixel,double worldX,double worldZ,boolean present,OptionalInt terrainHeight,OptionalDouble noise,Optional<WorldSnapshot.Biome> biome,int rgba) {
        public Hit {
            Objects.requireNonNull(context);Objects.requireNonNull(kind);Objects.requireNonNull(pixel);Objects.requireNonNull(terrainHeight);Objects.requireNonNull(noise);Objects.requireNonNull(biome);
            if(pixel.x()<0||pixel.z()<0||pixel.index()<0||!Double.isFinite(worldX)||!Double.isFinite(worldZ)||terrainHeight.isPresent()!=(present&&kind==MapSamples.Kind.TERRAIN)||noise.isPresent()!=(present&&kind==MapSamples.Kind.NOISE)||biome.isPresent()!=(present&&kind==MapSamples.Kind.BIOME)||noise.isPresent()&&!Double.isFinite(noise.getAsDouble()))throw new IllegalArgumentException("Invalid original map sample hit");
        }
    }
    public static Optional<Hit> at(MapRaster raster,MapView view,double screenX,double screenY) {
        Objects.requireNonNull(raster);Objects.requireNonNull(view);if(raster.requiresPlaceholder())throw new IllegalArgumentException("Map inspection requires a complete raster");
        var samples=raster.samples();var grid=samples.grid();if(view.width()!=grid.width()||view.height()!=grid.height())throw new IllegalArgumentException("Map transform dimensions differ from the inspected raster");
        var pixel=view.pixelAt(screenX,screenY);if(pixel.isEmpty())return Optional.empty();var at=pixel.orElseThrow();boolean present=samples.present(at.index());
        return Optional.of(new Hit(samples.context(),samples.kind(),at,grid.worldX(at.x()),grid.worldZ(at.z()),present,
            present&&samples.kind()==MapSamples.Kind.TERRAIN?OptionalInt.of(samples.height(at.index())):OptionalInt.empty(),
            present&&samples.kind()==MapSamples.Kind.NOISE?OptionalDouble.of(samples.noise(at.index())):OptionalDouble.empty(),
            present&&samples.kind()==MapSamples.Kind.BIOME?Optional.of(samples.biome(at.index())):Optional.empty(),raster.image().orElseThrow().rgba(at.x(),at.z())));
    }
}
