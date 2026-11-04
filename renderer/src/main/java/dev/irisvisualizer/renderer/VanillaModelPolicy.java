package dev.irisvisualizer.renderer;

import java.util.Objects;
import java.util.Set;

/** Reviewed vanilla JSON-only block-entity visuals; never infers unsupported special geometry or entity data. */
public final class VanillaModelPolicy {
    private VanillaModelPolicy() {}
    public static final String PROFILE="26.3";
    private static final Set<AssetId> MODEL_ONLY=Set.of(
        AssetId.minecraft("furnace"),AssetId.minecraft("blast_furnace"),AssetId.minecraft("smoker"),
        AssetId.minecraft("dispenser"),AssetId.minecraft("dropper"),AssetId.minecraft("hopper"),
        AssetId.minecraft("brewing_stand"),AssetId.minecraft("jukebox"),AssetId.minecraft("barrel"),
        AssetId.minecraft("comparator"),AssetId.minecraft("daylight_detector"),
        AssetId.minecraft("command_block"),AssetId.minecraft("repeating_command_block"),AssetId.minecraft("chain_command_block"),
        AssetId.minecraft("jigsaw"),AssetId.minecraft("bee_nest"),AssetId.minecraft("beehive"),
        AssetId.minecraft("sculk_sensor"),AssetId.minecraft("calibrated_sculk_sensor"),
        AssetId.minecraft("sculk_catalyst"),AssetId.minecraft("sculk_shrieker"),
        AssetId.minecraft("chiseled_bookshelf"),AssetId.minecraft("crafter")
    );
    /** Caller must separately verify exact native registry identity and native MODEL render shape. */
    public static boolean modelOnlyBlockEntity(String minecraft,AssetId block) {
        Objects.requireNonNull(minecraft);Objects.requireNonNull(block);return PROFILE.equals(minecraft)&&MODEL_ONLY.contains(block);
    }
    /** Immutable reviewed identifiers, not a claim that selected model assets or native sampling succeeded. */
    public static Set<AssetId> modelOnlyBlockEntities() { return MODEL_ONLY; }
}
