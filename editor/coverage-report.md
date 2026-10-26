# Editor field coverage

Direct nonstatic/nontransient private fields in ten pinned source classes only. This is not full schema coverage. Unclassified or unimplemented fields remain read-only; this audit never gates Save/Export.

| Class | Declared | Classified | Implemented editable | Unclassified |
| --- | ---: | ---: | ---: | ---: |
| dimension | 82 | 39 | 33 | 43 |
| importedStructures | 6 | 4 | 4 | 2 |
| importedFeatures | 6 | 3 | 3 | 3 |
| biome | 42 | 17 | 11 | 25 |
| biomeGeneratorLink | 3 | 3 | 3 | 0 |
| generator | 16 | 16 | 16 | 0 |
| interpolator | 2 | 2 | 2 | 0 |
| region | 27 | 19 | 13 | 8 |
| noise | 15 | 14 | 14 | 1 |
| noiseStyle | 10 | 3 | 3 | 7 |

## dimension

Unclassified: `allObjectScaleFactor`, `blockFallbacks`, `borrowedDimensionType`, `carving`, `caveProfile`, `datapackImports`, `debugChunkCrossSections`, `debugCrossSectionsMod`, `debugSmartBore`, `depositVariants`, `deposits`, `dimensionHeight`, `dimensionOptions`, `dimensionStack`, `disabledComponents`, `environment`, `explodeBiomePaletteSize`, `explodeBiomePalettes`, `fluidPalette`, `focus`, `focusRegion`, `fullbright`, `hideOresForHiddenOre`, `hydrology`, `imageMaps`, `importedFeatures`, `importedStructures`, `mode`, `ores`, `overlayNoise`, `preventLeafDecay`, `riverPolicy`, `rockPalette`, `staticObjects`, `structures`, `studioMode`, `subterrainFeatures`, `surfaceLayerFallback`, `terrainTransform`, `treeSettings`, `upperDimension`, `useMantle`, `worldBoundary`.

## importedStructures

Unclassified: `adjustments`, `frequencyOverrides`.

## importedFeatures

Unclassified: `biomeRules`, `disabledSteps`, `steps`.

## biome

Unclassified: `biomeScatter`, `biomeSkyScatter`, `biomeStyle`, `carvingBiome`, `caveCeilingLayers`, `caveProfile`, `customDerivitives`, `decorators`, `depositVariants`, `deposits`, `derivative`, `floatingChildBiomes`, `layers`, `objects`, `ores`, `proceduralObjects`, `riverPolicy`, `seaLayers`, `slab`, `structures`, `surfaceLayerFallback`, `surfaceOreReplaceableBlocks`, `terrain3D`, `vanillaDerivative`, `wall`.

## biomeGeneratorLink

Unclassified: none.

## generator

Unclassified: none.

## interpolator

Unclassified: none.

## region

Unclassified: `caveProfile`, `depositVariants`, `deposits`, `objects`, `ores`, `proceduralObjects`, `riverPolicy`, `structures`.

## noise

Unclassified: `fracture`.

## noiseStyle

Unclassified: `cacheSize`, `cellularFrequency`, `cellularZoom`, `expression`, `fracture`, `imageMap`, `multiplier`.
