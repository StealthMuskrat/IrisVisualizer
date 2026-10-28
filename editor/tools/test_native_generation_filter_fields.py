"""Reject native feature and frequency filter defaults, precedence and matching drift."""
import sys
from pathlib import Path
from extract_native_generation_filter_fields import extract
sources=[Path(path).read_bytes() for path in sys.argv[1:]];result=extract(*sources).decode()
for token in ['step.policy=denyAfterAllow\n','frequency.policy=lastNormalizedKeyWins\n','multiplier.minimum=0.01\n','multiplier.maximum=16\n','multiplier.default=1\n','registry.policy=syntaxOnly\n']:assert token in result,token
for index,old,new in [(0,b'private IrisImportedFeatureControl importedFeatures = new IrisImportedFeatureControl();',b'private IrisImportedFeatureControl importedFeatures;'),
                      (1,b'private boolean enabled = false;',b'private boolean enabled = true;'),
                      (1,b'return !denied.contains(step);',b'return true;'),(1,b'return allowed.isEmpty();',b'return false;'),
                      (2,b'normalizedEntry.endsWith(":")',b'normalizedEntry.startsWith(":")'),
                      (2,b'biomeTags.contains(normalizedEntry.substring(TAG_PREFIX.length()))',b'biomeTags.contains(normalizedEntry)'),
                      (3,b'LAKES("lakes")',b'LAKES("lake")'),(3,b'values[ordinal]',b'values[0]'),
                      (4,b'multiplier = override.getMultiplier();',b'multiplier *= override.getMultiplier();'),
                      (4,b"return boundary == '/' || boundary == '_';",b"return boundary == '/';"),
                      (5,b'@MinNumber(0.01)',b'@MinNumber(0)'),(5,b'private double multiplier = 1D;',b'private double multiplier = 2D;'),
                      (6,b'"multiplier", 0.01D, 16D',b'"multiplier", 0D, 16D'),
                      (7,b'[a-z0-9_.-]+:[a-z0-9/._-]+',b'[a-z0-9_.-]+:[a-z0-9/._-]*')]:
    assert old in sources[index],old
    changed=sources.copy();changed[index]=changed[index].replace(old,new)
    try:extract(*changed)
    except ValueError:continue
    raise AssertionError('Accepted native generation filter drift: '+str(old))
print('PASS: native feature default/gates, decoration step enum/ordinal, biome tags/exact/namespace/family matching and last-key frequency precedence/default/bounds reject drift')
