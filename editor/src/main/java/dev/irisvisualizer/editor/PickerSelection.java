package dev.irisvisualizer.editor;

import java.util.List;
import java.util.Objects;

/** Bounded, clamped keyboard selection over the currently visible immutable result identities. */
public final class PickerSelection {
    public static <T> T move(List<T> visible,T current,int direction) {
        Objects.requireNonNull(visible);
        if (direction < -1 || direction > 1) throw new IllegalArgumentException("Use one selection step");
        if (visible.isEmpty()) return null;
        int index=current==null ? -1 : visible.indexOf(current);
        if (index<0) return visible.getFirst();
        return visible.get(Math.clamp(index+direction,0,visible.size()-1));
    }
    private PickerSelection() {}
}
