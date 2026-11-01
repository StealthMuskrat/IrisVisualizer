"""Deferred lexical-inventory regression contracts; no Java execution is involved."""
from java_model_inventory import inventory

source = r'''
package example;
@Description("class Wrong {;}")
class Owner extends Parent {
    @Description(value={"braces { }", "semi;colon"})
    protected final String retained = "text // literal /* literal */";
    boolean packageField = false;
    public @Required String interleaved = "ref";
    @Required private @Other String annotated = "value";
    private static final String CONSTANT = "excluded";
    final transient String cache = "excluded";
    private java.util.Map<String, Integer> map = new java.util.HashMap<>();
    private String[] array = {"a", "b"};
    static { register(); }
    public Owner(int count) { this.packageField = count > 0; }
    void method() { String invisible = "field"; if (true) { run(); } }
    abstract class Nested { private String invisible; }
    enum Choice { FIRST, SECOND; private String hidden; }
}
'''
model = inventory(source, 'Owner')
assert model.parent == 'Parent' and not model.custom_noarg
assert [f.name for f in model.fields] == ['retained','packageField','interleaved','annotated','map','array']
assert model.fields[0].default == '"text // literal /* literal */"'
assert '@Required' in model.fields[2].annotations
assert model.fields[3].annotations == '@Required@Other'
assert model.fields[4].type == 'java.util.Map<String,Integer>'
assert model.fields[5].type == 'String[]'
assert inventory(source, 'Choice').kind == 'enum'
assert inventory('class Empty {}', 'Empty').fields == ()
assert inventory('class Owner { public @Required Owner() { run(); } }', 'Owner').custom_noarg
for text, name in [
    ('class Owner { String one, two; }', 'Owner'),
    ('class Owner { String one = call(1, 2), two = \"value\"; }', 'Owner'),
    ('class Owner { { value = "implicit"; } String value; }', 'Owner'),
    ('record Owner(String value) {}', 'Owner'),
    ('class Owner<T extends Parent> { T value; }', 'Owner'),
    ('class Owner { String value;', 'Owner'),
]:
    try:
        inventory(text, name)
    except ValueError:
        pass
    else:
        raise AssertionError('Accepted unsupported/ambiguous declaration: ' + text)
print('PASS: lexical serialized-field inventory with comments/literals, package/protected/public fields, interleaved annotations and annotation arrays, static/transient exclusion, nested/method isolation, explicit constructors and fail-closed unsupported declarations')
