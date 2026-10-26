"""The enum extractor must fail closed instead of quietly omitting new enum syntax."""
from extract_interpolation_fields import extract

field = b'    @Description("Choose a method")\n    private InterpolationMethod function = InterpolationMethod.A;'
methods = b'public enum InterpolationMethod {\n @Description("First") A,\n @Description("Second\\nline") B,\n}'
data = extract(field, methods).decode('ascii')
assert 'choices=A,B\n' in data and 'B.description=Second\\nline\n' in data


def rejects(interpolator, enum):
    try:
        extract(interpolator, enum)
    except ValueError:
        return
    raise AssertionError('Changed enum syntax was silently accepted')


rejects(field, methods.replace(b' B,', b' A,'))
rejects(field, methods.replace(b' B,', b' B(1),'))
rejects(field, methods.replace(b'@Description("Second\\nline") ', b''))
rejects(field, methods.replace(b'\n}', b'\n C,\n}'))
rejects(field, methods.replace(b'\n}', b'\n void method() {}\n}'))
rejects(field.replace(b'InterpolationMethod.A;', b'InterpolationMethod.MISSING;'), methods)
rejects(field.replace(b'InterpolationMethod function', b'String function'), methods)
print('PASS: interpolation enum extraction rejects omitted/duplicate/unknown declarations and missing defaults')
