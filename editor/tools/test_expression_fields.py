import sys
from extract_expression_fields import read_sources,extract
sources=read_sources(sys.argv[1]);data=extract(sources)
for value in [b'variables.staticValue.default=-1\n',b'functions.args.default=2\n',b'functions.args.minimum=2\n',b'coordinates.2d=x,z,-1\n',b'engineStreamValue.RIVER_WATER_SURFACE.description=',b'engineValue.FLUID_HEIGHT.description=']:assert value in data
for name,old,new in [
 ('IrisExpression','private String expression;','private String expression = "0";'),
 ('IrisExpression','new KList<>()','null'),
 ('IrisExpression','arguments[index++] = z;','arguments[index++] = y;'),
 ('IrisExpression','if (!f.isValid()) continue;','if (f.isValid()) continue;'),
 ('IrisExpressionLoad','private double staticValue = -1;','private double staticValue = 0;'),
 ('IrisExpressionLoad','private IrisGeneratorStyle styleValue = null;','private String styleValue = null;'),
 ('IrisExpressionLoad','if (engineValue != null)','if (styleValue != null)'),
 ('IrisExpressionFunction','private int args = 2;','private int args = 3;'),
 ('IrisExpressionFunction','@MinNumber(2)','@MinNumber(1)'),
 ('IrisExpressionFunction','if (engineStreamValue != null) return 2;','if (engineStreamValue != null) return args;'),
 ('IrisExpressionFunction','return Math.max(args, 2);','return args;'),
 ('IrisEngineStreamType','    RIVER_WATER_SURFACE(','    REPLACED('),
 ('IrisEngineValueType','@Description("The fluid height defined in the dimension file")',''),
 ('ExpressionNoise','return expression.evaluate(rng, x, -1);','return expression.evaluate(rng, x, 0);')]:
 raw=sources[name];assert old.encode() in raw;changed=dict(sources);changed[name]=raw.replace(old.encode(),new.encode(),1)
 try:extract(changed)
 except (ValueError,KeyError):pass
 else:raise AssertionError('Accepted changed expression contract: '+old)
print('PASS: expression field defaults/types/bounds, complete engine enums, native variable/function precedence, inactive functions, stream arity and 1D/2D/3D coordinate bindings reject drift without evaluating expressions')
