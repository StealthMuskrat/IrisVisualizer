"""Deferred lexical coverage contracts; field indentation is not serialization authority."""
from field_coverage import declared_fields
source = '''class Example {
    private double zoom = 1;
    private final transient Object cache = null;
    private static final int LIMIT = 1;
    private volatile String pending;
    private java.util.List<String> values = null;
    private long calculate() { return 1; }
    /* private boolean commented = false; */
        protected int indented = 1;
    boolean packageField;
    public String visible;
    class Nested { private int invisible = 1; }
}'''
assert declared_fields(source) == {'zoom':'double','pending':'String','values':'java.util.List<String>','indented':'int','packageField':'boolean','visible':'String'}
assert declared_fields('interface Empty {}') == {}
assert declared_fields('class Empty {}') == {}
print('PASS: shared lexical field inventory includes all access levels independent of indentation and excludes comments, methods, true nested declarations and static/transient state')
