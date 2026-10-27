# Editor field coverage

Direct nonstatic/nontransient private fields in twelve pinned source classes only. This is not full schema coverage. Unclassified or unimplemented fields remain read-only; this audit never gates Save/Export.

| Class | Declared | Classified | Implemented editable | Unclassified |
| --- | ---: | ---: | ---: | ---: |
| dimension | 82 | 40 | 34 | 42 |
| importedStructures | 6 | 4 | 4 | 2 |
| importedFeatures | 6 | 3 | 3 | 3 |
| biome | 42 | 19 | 13 | 23 |
| biomeGeneratorLink | 3 | 3 | 3 | 0 |
| generator | 16 | 16 | 16 | 0 |
| interpolator | 2 | 2 | 2 | 0 |
| region | 27 | 20 | 14 | 7 |
| noise | 15 | 14 | 14 | 1 |
| noiseStyle | 10 | 5 | 5 | 5 |
| terrain3D | 14 | 14 | 14 | 0 |
| caveProfile | 32 | 30 | 30 | 2 |

## dimension

Unclassified: `allObjectScaleFactor`, `blockFallbacks`, `borrowedDimensionType`, `carving`, `datapackImports`, `debugChunkCrossSections`, `debugCrossSectionsMod`, `debugSmartBore`, `depositVariants`, `deposits`, `dimensionHeight`, `dimensionOptions`, `dimensionStack`, `disabledComponents`, `environment`, `explodeBiomePaletteSize`, `explodeBiomePalettes`, `fluidPalette`, `focus`, `focusRegion`, `fullbright`, `hideOresForHiddenOre`, `hydrology`, `imageMaps`, `importedFeatures`, `importedStructures`, `mode`, `ores`, `overlayNoise`, `preventLeafDecay`, `riverPolicy`, `rockPalette`, `staticObjects`, `structures`, `studioMode`, `subterrainFeatures`, `surfaceLayerFallback`, `terrainTransform`, `treeSettings`, `upperDimension`, `useMantle`, `worldBoundary`.

## importedStructures

Unclassified: `adjustments`, `frequencyOverrides`.

## importedFeatures

Unclassified: `biomeRules`, `disabledSteps`, `steps`.

## biome

Unclassified: `biomeScatter`, `biomeSkyScatter`, `biomeStyle`, `carvingBiome`, `caveCeilingLayers`, `customDerivitives`, `decorators`, `depositVariants`, `deposits`, `derivative`, `floatingChildBiomes`, `layers`, `objects`, `ores`, `proceduralObjects`, `riverPolicy`, `seaLayers`, `slab`, `structures`, `surfaceLayerFallback`, `surfaceOreReplaceableBlocks`, `vanillaDerivative`, `wall`.

## biomeGeneratorLink

Unclassified: none.

## generator

Unclassified: none.

## interpolator

Unclassified: none.

## region

Unclassified: `depositVariants`, `deposits`, `objects`, `ores`, `proceduralObjects`, `riverPolicy`, `structures`.

## noise

Unclassified: `fracture`.

## noiseStyle

Unclassified: `cacheSize`, `expression`, `fracture`, `imageMap`, `multiplier`.

## terrain3D

Unclassified: none.

## caveProfile

Unclassified: `densityThreshold`, `modules`.
