package org.abstruck.ortho_sight.skill;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Server side config for the blink skill.
 *
 * <p>A {@link ModConfig.Type#SERVER} config on purpose: NeoForge syncs it to the client at login, so the
 * client preview and the server validation always use the same numbers.
 */
public final class SkillConfig {

    public static final ModConfigSpec.BooleanValue ENABLED;
    public static final ModConfigSpec.BooleanValue REQUIRE_SNEAK;
    public static final ModConfigSpec.DoubleValue RANGE;
    public static final ModConfigSpec.DoubleValue MAX_RISE;
    public static final ModConfigSpec.DoubleValue MAX_DROP;
    public static final ModConfigSpec.IntValue COOLDOWN_TICKS;
    public static final ModConfigSpec SPEC;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        builder.push("legacyBlink");
        ENABLED = builder
                .comment("Enable the old blink skill (teleport). Off by default: the screen-space illusion",
                        "replaced it with smooth, picture-invariant traversal.")
                .define("enabled", false);
        REQUIRE_SNEAK = builder
                .comment("Only while sneaking.")
                .define("requireSneak", true);
        RANGE = builder
                .comment("How far along the view axis (in blocks) the target surface is searched for.")
                .defineInRange("range", 8.0D, 1.0D, 24.0D);
        MAX_RISE = builder
                .comment("How far above your feet the target surface may be.")
                .defineInRange("maxRise", 1.0D, 0.0D, 4.0D);
        MAX_DROP = builder
                .comment("How far below your feet the target surface may be.")
                .defineInRange("maxDrop", 4.0D, 0.0D, 16.0D);
        COOLDOWN_TICKS = builder
                .comment("Ticks between two blinks.")
                .defineInRange("cooldownTicks", 6, 0, 100);
        builder.pop();
        SPEC = builder.build();
    }

    private SkillConfig() {
    }

    public static boolean enabled() {
        try {
            return ENABLED.get();
        } catch (Exception notLoadedYet) {
            return true;
        }
    }

    public static boolean requireSneak() {
        try {
            return REQUIRE_SNEAK.get();
        } catch (Exception notLoadedYet) {
            return true;
        }
    }

    public static double range() {
        try {
            return RANGE.get();
        } catch (Exception notLoadedYet) {
            return 8.0D;
        }
    }

    public static double maxRise() {
        try {
            return MAX_RISE.get();
        } catch (Exception notLoadedYet) {
            return 1.0D;
        }
    }

    public static double maxDrop() {
        try {
            return MAX_DROP.get();
        } catch (Exception notLoadedYet) {
            return 4.0D;
        }
    }

    public static int cooldownTicks() {
        try {
            return COOLDOWN_TICKS.get();
        } catch (Exception notLoadedYet) {
            return 6;
        }
    }

    /**
     * Called reflectively from the mod constructor so the client-only build (which does not contain this
     * package at all) still compiles and loads.
     */
    public static void register(net.neoforged.fml.ModContainer container) {
        container.registerConfig(net.neoforged.fml.config.ModConfig.Type.SERVER, SPEC);
    }
}
