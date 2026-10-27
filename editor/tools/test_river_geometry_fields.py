"""Reject geometry parent/type/default drift and changes to nullable or inactive field meanings."""
from extract_river_geometry_fields import MEANDERS, SURFACE, MEANDER_INTS, NULLABLE, extract

rivers=b'@NoArgsConstructor\n@Description("Geometry")\nprivate IrisRiverGeometryConfig geometry = new IrisRiverGeometryConfig();'
geometry=b'''@NoArgsConstructor
@Description("Meanders")
private IrisRiverMeanderConfig meanders = new IrisRiverMeanderConfig();
@Description("Surface")
private IrisSurfaceRiverShapeConfig surface = new IrisSurfaceRiverShapeConfig();'''
meander_lines=['@NoArgsConstructor','public class IrisRiverMeanderConfig {']
for name in MEANDERS:
    kind='int' if name in MEANDER_INTS else 'double'
    meander_lines+=['    @MinNumber(0)','    @MaxNumber(512)','    @Description("Meander")','    private '+kind+' '+name+' = '+('64' if kind=='int' else '0.34D')+';']
meander_lines+=['}']; meanders='\n'.join(meander_lines).encode()
shape_lines=['@NoArgsConstructor','public class IrisSurfaceRiverShapeConfig {']
for name in SURFACE+['aspectMinimum','aspectRange']:
    kind=NULLABLE.get(name,'double')
    help_text='Null uses surface.channel.roughness.' if name in NULLABLE else 'Shared field; only grotto chambers read it.' if name.startswith('aspect') else 'Surface setting'
    shape_lines+=['    @MinNumber(0)','    @MaxNumber(128)','    @Description("'+help_text+'")','    private '+kind+' '+name+' = '+('null' if name in NULLABLE else '0.86D')+';']
shape_lines+=['}']; surface='\n'.join(shape_lines).encode()
data=extract(rivers,geometry,meanders,surface).decode('ascii')
for item in ['geometry.default={}\n','meanders.default={}\n','surface.default={}\n','meanders.primaryWavelength.type=int\n',
             'surface.bedRoughness.default=null\n','surface.roughnessWavelength.type=Integer\n','surface.radialBase.default=0.86\n','surface.aspectRange.readOnlyReason=']:
    assert item in data


def rejects(r=rivers,g=geometry,m=meanders,s=surface):
    try:
        extract(r,g,m,s)
    except ValueError:
        return
    raise AssertionError('Changed river geometry contract silently accepted')


rejects(r=rivers.replace(b'new IrisRiverGeometryConfig()',b'null'))
rejects(g=geometry.replace(b'new IrisRiverMeanderConfig()',b'null'))
rejects(m=meanders.replace(b'@NoArgsConstructor',b''))
rejects(m=meanders.replace(b'int primaryWavelength',b'double primaryWavelength'))
rejects(s=surface.replace(b'Double bedRoughness',b'double bedRoughness'))
rejects(s=surface.replace(b'bedRoughness = null',b'bedRoughness = 0.0'))
rejects(s=surface.replace(b'Integer roughnessWavelength',b'Double roughnessWavelength'))
rejects(s=surface.replace(b'Null uses surface.channel.',b'Null means zero.'))
rejects(s=surface.replace(b'only grotto chambers read it.',b'surface channels read it.'))
print('PASS: geometry parent/scalar defaults, boxed channel fallbacks and inactive surface field classifications reject drift')
