# Editor field coverage

Direct nonstatic/nontransient private fields in nine pinned source classes only. This is not full schema coverage. Unclassified or unimplemented fields remain read-only; this audit never gates Save/Export.

| Class | Declared | Classified | Implemented editable | Unclassified |
| --- | ---: | ---: | ---: | ---: |
| dimension | 82 | 33 | 27 | 49 |
| importedStructures | 6 | 4 | 4 | 2 |
| importedFeatures | 6 | 3 | 3 | 3 |
| biome | 42 | 14 | 8 | 28 |
| biomeGeneratorLink | 3 | 2 | 2 | 1 |
| generator | 16 | 16 | 16 | 0 |
| interpolator | 2 | 2 | 2 | 0 |
| region | 27 | 19 | 13 | 8 |
| noise | 15 | 13 | 13 | 2 |

## dimension

Unclassified: `allObjectScaleFactor`, `blockFallbacks`, `borrowedDimensionType`, `carving`, `caveBiomeStyle`, `caveProfile`, `continentalStyle`, `datapackImports`, `debugChunkCrossSections`, `debugCrossSectionsMod`, `debugSmartBore`, `depositVariants`, `deposits`, `dimensionHeight`, `dimensionOptions`, `dimensionStack`, `disabledComponents`, `environment`, `explodeBiomePaletteSize`, `explodeBiomePalettes`, `fluidPalette`, `focus`, `focusRegion`, `fullbright`, `hideOresForHiddenOre`, `hydrology`, `imageMaps`, `importedFeatures`, `importedStructures`, `landBiomeStyle`, `mode`, `ores`, `overlayNoise`, `preventLeafDecay`, `regionStyle`, `riverPolicy`, `rockPalette`, `seaBiomeStyle`, `shoreBiomeStyle`, `staticObjects`, `structures`, `studioMode`, `subterrainFeatures`, `surfaceLayerFallback`, `terrainTransform`, `treeSettings`, `upperDimension`, `useMantle`, `worldBoundary`.

## importedStructures

Unclassified: `adjustments`, `frequencyOverrides`.

## importedFeatures

Unclassified: `biomeRules`, `disabledSteps`, `steps`.

## biome

Unclassified: `biomeScatter`, `biomeSkyScatter`, `biomeStyle`, `carvingBiome`, `caveCeilingLayers`, `caveProfile`, `childStyle`, `children`, `customDerivitives`, `decorators`, `depositVariants`, `deposits`, `derivative`, `floatingChildBiomes`, `generators`, `layers`, `objects`, `ores`, `proceduralObjects`, `riverPolicy`, `seaLayers`, `slab`, `structures`, `surfaceLayerFallback`, `surfaceOreReplaceableBlocks`, `terrain3D`, `vanillaDerivative`, `wall`.

## biomeGeneratorLink

Unclassified: `generator`.

## generator

Unclassified: none.

## interpolator

Unclassified: none.

## region

Unclassified: `caveProfile`, `depositVariants`, `deposits`, `objects`, `ores`, `proceduralObjects`, `riverPolicy`, `structures`.

## noise

Unclassified: `fracture`, `style`.
