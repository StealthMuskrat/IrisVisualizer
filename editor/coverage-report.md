# Editor field coverage

Direct nonstatic/nontransient private fields in thirty-two pinned source classes only. This is not full schema coverage. Unclassified or unimplemented fields remain read-only; this audit never gates Save/Export.

| Class | Declared | Classified | Implemented editable | Unclassified |
| --- | ---: | ---: | ---: | ---: |
| dimension | 82 | 42 | 36 | 40 |
| importedStructures | 6 | 4 | 4 | 2 |
| importedFeatures | 6 | 3 | 3 | 3 |
| biome | 42 | 20 | 14 | 22 |
| biomeGeneratorLink | 3 | 3 | 3 | 0 |
| generator | 16 | 16 | 16 | 0 |
| interpolator | 2 | 2 | 2 | 0 |
| region | 27 | 21 | 15 | 6 |
| noise | 15 | 14 | 14 | 1 |
| noiseStyle | 10 | 5 | 5 | 5 |
| terrain3D | 14 | 14 | 14 | 0 |
| caveProfile | 32 | 32 | 32 | 0 |
| styledRange | 3 | 3 | 3 | 0 |
| caveModule | 5 | 5 | 5 | 0 |
| range | 2 | 2 | 2 | 0 |
| riverPolicy | 26 | 26 | 26 | 0 |
| hydrology | 3 | 1 | 1 | 2 |
| riverHydrology | 7 | 4 | 4 | 3 |
| riverRouting | 16 | 16 | 16 | 0 |
| riverRegional | 9 | 9 | 9 | 0 |
| riverGeometry | 6 | 6 | 6 | 0 |
| riverMeanders | 7 | 7 | 7 | 0 |
| riverSurfaceShape | 12 | 12 | 10 | 0 |
| riverChannelShape | 12 | 12 | 12 | 0 |
| riverDropShape | 8 | 8 | 8 | 0 |
| riverBank3D | 7 | 7 | 7 | 0 |
| surfaceRiver | 9 | 6 | 6 | 3 |
| surfaceRiverSource | 4 | 4 | 4 | 0 |
| surfaceRiverChannel | 12 | 10 | 10 | 2 |
| surfaceRiverBank | 10 | 7 | 7 | 3 |
| surfaceRiverFlow | 6 | 6 | 6 | 0 |
| surfaceRiverMouth | 7 | 7 | 7 | 0 |

## dimension

Unclassified: `allObjectScaleFactor`, `blockFallbacks`, `borrowedDimensionType`, `carving`, `datapackImports`, `debugChunkCrossSections`, `debugCrossSectionsMod`, `debugSmartBore`, `depositVariants`, `deposits`, `dimensionHeight`, `dimensionOptions`, `dimensionStack`, `disabledComponents`, `environment`, `explodeBiomePaletteSize`, `explodeBiomePalettes`, `fluidPalette`, `focus`, `focusRegion`, `fullbright`, `hideOresForHiddenOre`, `imageMaps`, `importedFeatures`, `importedStructures`, `mode`, `ores`, `overlayNoise`, `preventLeafDecay`, `rockPalette`, `staticObjects`, `structures`, `studioMode`, `subterrainFeatures`, `surfaceLayerFallback`, `terrainTransform`, `treeSettings`, `upperDimension`, `useMantle`, `worldBoundary`.

## importedStructures

Unclassified: `adjustments`, `frequencyOverrides`.

## importedFeatures

Unclassified: `biomeRules`, `disabledSteps`, `steps`.

## biome

Unclassified: `biomeScatter`, `biomeSkyScatter`, `biomeStyle`, `carvingBiome`, `caveCeilingLayers`, `customDerivitives`, `decorators`, `depositVariants`, `deposits`, `derivative`, `floatingChildBiomes`, `layers`, `objects`, `ores`, `proceduralObjects`, `seaLayers`, `slab`, `structures`, `surfaceLayerFallback`, `surfaceOreReplaceableBlocks`, `vanillaDerivative`, `wall`.

## biomeGeneratorLink

Unclassified: none.

## generator

Unclassified: none.

## interpolator

Unclassified: none.

## region

Unclassified: `depositVariants`, `deposits`, `objects`, `ores`, `proceduralObjects`, `structures`.

## noise

Unclassified: `fracture`.

## noiseStyle

Unclassified: `cacheSize`, `expression`, `fracture`, `imageMap`, `multiplier`.

## terrain3D

Unclassified: none.

## caveProfile

Unclassified: none.

## styledRange

Unclassified: none.

## caveModule

Unclassified: none.

## range

Unclassified: none.

## riverPolicy

Unclassified: none.

## hydrology

Unclassified: `deepFluids`, `surfacePools`.

## riverHydrology

Unclassified: `grottos`, `profiles`, `underground`.

## riverRouting

Unclassified: none.

## riverRegional

Unclassified: none.

## riverGeometry

Unclassified: none.

## riverMeanders

Unclassified: none.

## riverSurfaceShape

Unclassified: none.

## riverChannelShape

Unclassified: none.

## riverDropShape

Unclassified: none.

## riverBank3D

Unclassified: none.

## surfaceRiver

Unclassified: `bed`, `erosion`, `ponds`.

## surfaceRiverSource

Unclassified: none.

## surfaceRiverChannel

Unclassified: `depth`, `width`.

## surfaceRiverBank

Unclassified: `bankMaterial`, `excavation`, `shoreMaterial`.

## surfaceRiverFlow

Unclassified: none.

## surfaceRiverMouth

Unclassified: none.
