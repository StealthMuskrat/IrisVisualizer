"""Bind fixed placements to pinned schema, validation, transform and ordering consumers."""
import hashlib
import json
import re
import sys
from pathlib import Path
from extract_river_material_fields import compact

def extract(entry,rotation,position,dimension,scale,layer,interpolation):
    sources=[entry,rotation,position,dimension,scale,layer,interpolation]
    e,r,p,d,s,l,i=[raw.decode('utf-8') for raw in sources]
    def require(text,*contracts):
        for contract in contracts:
            if compact(contract) not in compact(text):raise ValueError('Changed static object contract: '+contract)
    values={'irisRevision':'c3359e0a16438b1bb8468901c6171dcefdf63f7f'}
    for name,raw in zip(['entry','rotation','position','dimension','scale','layer','interpolation'],sources):values[name+'SourceSha256']=hashlib.sha256(raw).hexdigest()
    declarations={'object':'String object;', 'position':'IrisPosition position;', 'rotation':'IrisStaticObjectRotation rotation = new IrisStaticObjectRotation();',
                  'scale':'Double scale;', 'scaleInterpolation':'IrisObjectPlacementScaleInterpolator scaleInterpolation = IrisObjectPlacementScaleInterpolator.NONE;',
                  'edit':'KList<IrisObjectReplace> edit = new KList<>();', 'bore':'boolean bore = false;', 'smartBore':'boolean smartBore = false;', 'seed':'long seed = 0L;'}
    for field,declaration in declarations.items():
        match=re.search(r'@Description\(("(?:[^"\\]|\\.)*")\)\s+private '+re.escape(declaration),e)
        if not match:raise ValueError('Changed static field '+field)
        values[field+'.description']=json.loads(match.group(1))
    for axis in ['x','y','z']:
        require(p,'private int '+axis+' = 0;')
        require(r,'@MinNumber(-360) @MaxNumber(360) @Description("Fixed '+axis.upper()+'-axis rotation in degrees, including negative and fractional angles") private double '+axis+' = 0;')
    require(e,'public static final int MAXIMUM_HORIZONTAL_POSITION = 29_999_984;', 'public static final double MINIMUM_SCALE = 0.01D;', 'public static final double MAXIMUM_SCALE = 50D;',
            '@Required @RegistryListResource(IrisObject.class)', '@Required @Description("Absolute world X, Y and Z for the object\'s saved origin. Rotation and scaling keep this origin fixed.")',
            '@MinNumber(MINIMUM_SCALE) @MaxNumber(MAXIMUM_SCALE)',
            'if (position.getX() < -MAXIMUM_HORIZONTAL_POSITION || position.getX() > MAXIMUM_HORIZONTAL_POSITION || position.getZ() < -MAXIMUM_HORIZONTAL_POSITION || position.getZ() > MAXIMUM_HORIZONTAL_POSITION)',
            'if (position.getY() < minY || position.getY() >= maxY)', 'if (rotation == null)', 'rotation.validate();',
            'return scale == null ? dimension.getAllObjectScaleFactor() : IrisObjectScale.requireValidFactor(scale, "scale");',
            '.setMode(ObjectPlaceMode.STRUCTURE_PIECE) .setForcePlace(true) .setRotation(rotation.toRotation())', '.setBore(bore) .setSmartBore(smartBore);')
    require(r,'if (!Double.isFinite(angle) || angle < -360 || angle > 360)', 'return new IrisAxisRotationClamp(angle != 0, false, angle, angle, 0);')
    require(s,'public static final double MINIMUM_FACTOR = 0.01D;', 'public static final double MAXIMUM_FACTOR = 50D;')
    match=re.search(r'@MinNumber\(IrisObjectScale.MINIMUM_FACTOR\)\s+@MaxNumber\(IrisObjectScale.MAXIMUM_FACTOR\)\s+@Description\(("(?:[^"\\]|\\.)*")\)\s+private double allObjectScaleFactor = 1D;',d)
    if not match:raise ValueError('Changed dimension scale')
    values['allObjectScaleFactor.description']=json.loads(match.group(1))
    require(d,'private KList<IrisStaticObject> staticObjects = new KList<>();','return IrisObjectScale.requireValidFactor(allObjectScaleFactor, "allObjectScaleFactor");')
    require(l,'Objects.requireNonNull(dimension.getStaticObjects(), "staticObjects must be a list");', 'entry.validate(dimension.getMinHeight(), dimension.getMaxHeight());',
            'if (entry.evaluateCompat(data, dimension.getLoadKey(), index).excluded())',
            'object = object.scaledAroundOrigin(scale, entry.getScaleInterpolation());',
            'object.place(position.getX(), position.getY() - dimension.getMinHeight(), position.getZ(), compilation, entry.toPlacement(), new RNG(entry.getSeed()), data);',
            'for (Placement placement : placements)', 'combined.computeIfAbsent(chunk.getKey(), key -> new HashMap<>()).putAll(chunk.getValue().blocks());')
    choices=re.findall(r'^    (NONE|TRILINEAR|TRICUBIC|TRIHERMITE)(?:,|;|\s*\{|\s*$)',i,re.MULTILINE)
    if choices!=['NONE','TRILINEAR','TRICUBIC','TRIHERMITE']:raise ValueError('Changed interpolation choices')
    values.update({'position.horizontalMaximum':'29999984','rotation.minimum':'-360','rotation.maximum':'360','scale.minimum':'0.01','scale.maximum':'50',
                   'interpolation.choices':','.join(choices),'position.policy':'absoluteWorldYExclusiveMaximum','order.policy':'laterWins','scale.policy':'dimensionFallback'})
    return ''.join(key+'='+json.dumps(value,ensure_ascii=True)[1:-1]+'\n' for key,value in values.items()).encode('ascii')

if __name__=='__main__':Path(sys.argv[8]).write_bytes(extract(*(Path(path).read_bytes() for path in sys.argv[1:8])))
