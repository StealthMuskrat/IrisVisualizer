package dev.irisvisualizer.editor;

import java.util.Set;

/** Read-only resource access. Implementations return defensive byte copies and stable path ordering. */
public interface PackResourceView {
    Set<String> paths();
    int size(String path);
    byte[] read(String path);
}
