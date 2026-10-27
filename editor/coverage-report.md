# Editor field coverage

Direct nonstatic/nontransient private fields in nineteen pinned source classes only. This is not full schema coverage. Unclassified or unimplemented fields remain read-only; this audit never gates Save/Export.

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
| riverHydrology | 7 | 2 | 2 | 5 |
| riverRouting | 16 | 14 | 14 | 2 |

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

Unclassified: `geometry`, `grottos`, `profiles`, `surface`, `underground`.

## riverRouting

Unclassified: `inlandOutlets`, `regional`.
