package dev.irisvisualizer.editor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;

/** Structural editing of the pinned generator composite list, without resolving or generating noise. */
public final class CompositeNoise {
    public static final List<String> EDITABLE = List.of("composite");
    private static final Properties DEFINITIONS = new Properties();
    static {
        try (var input = CompositeNoise.class.getResourceAsStream("/generator-fields.properties")) {
            if (input == null) throw new IOException("Missing pinned generator definitions");
            DEFINITIONS.load(input);
        } catch (IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
    public enum Action { ADD, DUPLICATE, REMOVE, UP, DOWN }
    public static int minimumEntries() { return Integer.parseInt(DEFINITIONS.getProperty("composite.minimumEntries")); }
    public static byte[] change(String path, byte[] original, Action action, int index) {
        if (!GeneratorFields.generator(path)) throw new IllegalArgumentException("Composite list is read-only");
        var document = new LosslessJson(original);
        if (document.token("composite") == null) {
            if (action != Action.ADD) throw new IllegalArgumentException("Composite list is absent");
            document = new LosslessJson(document.enable("composite", "[]"));
        }
        var entries = document.objectArray("composite");
        if (action == Action.ADD) return document.objectArrayInsert("composite", entries.size(), "{}".getBytes(StandardCharsets.UTF_8));
        if (index < 0 || index >= entries.size()) throw new IllegalArgumentException("Noise entry index out of range");
        return switch (action) {
            case DUPLICATE -> document.objectArrayInsert("composite", index+1, entries.get(index).sourceBytes());
            case REMOVE -> {
                if (entries.size() <= minimumEntries()) throw new IllegalArgumentException("Pinned Iris composite list requires at least " + minimumEntries() + " entry");
                yield document.objectArrayRemove("composite", index);
            }
            case UP -> document.objectArrayMove("composite", index, index-1);
            case DOWN -> document.objectArrayMove("composite", index, index+1);
            default -> throw new IllegalArgumentException("Unsupported noise list action");
        };
    }
}
