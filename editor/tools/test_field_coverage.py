"""Check inventory boundaries: direct declarations, modifiers, comments and methods."""
from field_coverage import declared_fields
source = '''class Example {
    private double zoom = 1;
    private final transient Object cache = null;
    private static final int LIMIT = 1;
    private volatile String pending;
    private java.util.List<String> values = null;
    private long calculate() { return 1; }
    /*
    private boolean commented = false;
    */
        private int nested = 1;
}'''
assert declared_fields(source) == {'zoom': 'double', 'pending': 'String', 'values': 'java.util.List<String>'}
print('PASS: direct-field inventory excludes static/transient fields, comments, methods and nested declarations')
