import sys
from extract_block_definition_fields import read_sources,extract
sources=read_sources(sys.argv[1]);result=extract(sources)
for value in [b'block.default=air\n',b'folder=blocks\n',b'weight.policy=consumingPaletteOnly\n',b'data.policy=callerOverridesAliasProperties\n',b'backup.default=null\n',b'invalidMergedState.policy=keepAliasState\n']:assert value in result
for name,old,new in [
 ('IrisBlockData','private String block = "air";','private String block = "stone";'),
 ('IrisBlockData','private IrisBlockData backup = null;','private IrisBlockData backup = new IrisBlockData();'),
 ('IrisBlockData','private KMap<String, Object> data = new KMap<>();','private KMap<String, String> data = new KMap<>();'),
 ('IrisBlockData','private KMap<String, Object> tileData = new KMap<>();','private KMap<String, Object> tileData = null;'),
 ('IrisBlockData','private boolean debug = false;','private boolean debug = true;'),
 ('IrisBlockData','private int weight = 1;','private int weight = 2;'),
 ('IrisBlockData','merged.getData().putAll(getData());','getData().putAll(merged.getData());'),
 ('IrisBlockData','return customState;\n                }','return B.getAirState();\n                }'),
 ('IrisBlockData','return backup.getBlockData(data);','return B.getAirState();'),
 ('IrisBlockData','entry.getValue().toString()','entry.getValue()'),
 ('IrisBlockData','return "blocks";','return "block";'),
 ('IrisBlockData','this.data.containsKey("entitySpawn")','this.data.containsKey("entity")'),
 ('IrisMaterialPalette','i < ix.getWeight()','i < 1'),
 ('IrisMaterialPalette','NativeBlockState bx = ix.getBlockData(rdata);','NativeBlockState bx = null;'),
 ('IrisBiomePaletteLayer','NativeBlockState bx = ix.getBlockData(data);','NativeBlockState bx = null;'),
 ('IrisDepositGenerator','blockData.add(bx);','for (int w=0; w<ix.getWeight(); w++) { blockData.add(bx); }'),
 ('IrisObjectReplace','i.getBlockDataOrPlaceholder(rdata)','i.getBlockData(rdata)'),
 ('IrisObjectReplace',"declared.indexOf('[') < 0","declared.indexOf('[') > 0"),
 ('IrisDecorator','private IrisBlockData forceBlock;','private String forceBlock;'),
 ('IrisDecorator','n < i.getWeight()','n < 1'),
 ('IrisDecorator','list.get(i).getBlockData(data)','list.get(0).getBlockData(data)'),
 ('DecoratorCore','decorator.getForceBlock().getBlockData(irisData)','decorator.getForceBlock()')]:
 raw=sources[name];assert old.encode() in raw;changed=dict(sources);changed[name]=raw.replace(old.encode(),new.encode(),1)
 try:extract(changed)
 except ValueError:pass
 else:raise AssertionError('Accepted changed block contract: '+old)
print('PASS: block alias defaults/types, caller state overrides, native alias/state/backup/air precedence, primitive coercion, folder and consuming-palette-only weights reject drift; tile/gameplay data remains read-only')
