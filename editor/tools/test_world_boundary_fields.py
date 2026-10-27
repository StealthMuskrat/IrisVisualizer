"""Reject drift in optional-border semantics, exact defaults, bounds and consumers."""
import sys
from pathlib import Path
from extract_world_boundary_fields import extract

sources=[Path(path).read_bytes() for path in sys.argv[1:]]
result=extract(*sources).decode()
for token in ['size.default=16384\n','center.x.minimum=-29999984\n','warningDistance.maximum=2147483647\n','damageAmount.default=0.2\n','null.policy=leaveNativeBorderUnchanged\n','size.policy=fullDiameter\n']:assert token in result,token
mutations=[(0,b'private IrisWorldBoundary worldBoundary = null;',b'private IrisWorldBoundary worldBoundary = new IrisWorldBoundary();'),
           (1,b'private double size = 16_384D;',b'private double size = 32_768D;'),(1,b'public static final double MAXIMUM_SIZE = 59_999_968D;',b'public static final double MAXIMUM_SIZE = 30_000_000D;'),
           (1,b'source.validate();',b''),(1,b'size < 1D',b'size < 0D'),(1,b'!Double.isFinite(damageBuffer)',b'Double.isNaN(damageBuffer)'),(1,b'warningDistance < 0',b'warningDistance < -1'),
           (1,b'center.getX() - size / 2D',b'center.getX() - size'),(1,b'private double damageAmount = DEFAULT_DAMAGE_AMOUNT;',b'private double damageAmount = 0D;'),
           (2,b'@MinNumber(-29_999_984)',b'@MinNumber(-30_000_000)'),(2,b'private double z = 0D;',b'private double z = 1D;'),
           (3,b'if (configuredBoundary == null)',b'if (configuredBoundary != null)'),(3,b'worldBorder.setSize(boundary.getSize());',b'worldBorder.setSize(boundary.getSize() * 2);'),
           (3,b'worldBorder.setDamageAmount(boundary.getDamageAmount());',b''),(4,b'boundary = IrisWorldBoundary.snapshot(configuredBoundary);',b'boundary = configuredBoundary;'),
           (4,b'boundary.getWarningDistance(), boundary.getDamageBuffer(), boundary.getDamageAmount()',b'0, 0, 0')]
for index,old,new in mutations:
    assert old in sources[index],old
    changed=sources.copy();changed[index]=changed[index].replace(old,new)
    try:extract(*changed)
    except ValueError:continue
    raise AssertionError('Accepted border source drift: '+str(old))
print('PASS: nullable world border, native bounds/defaults, validated snapshot, full diameter and Bukkit/modded consumers reject drift')
