package org.abstruck.ortho_sight;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Client config, written to {@code config/ortho_sight-client.toml}.
 */
public final class OrthoConfig {

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    /**
     * Radius of the hidden cylinder's cross-section, in blocks. The cross-section lies in the plane
     * perpendicular to the view axis and is centred on the player, so this is "how much of the area around
     * the player is projected towards the camera and cleared".
     *
     * <p>It is a <b>world</b> radius, so on screen the window is {@code 2 * radius} out of the visible
     * height - it shrinks when you zoom out. The default 1.5 gives a window just wide enough to see the
     * player (the body is ~0.7 off the axis): about a quarter of the screen at the default 12 block view.
     * Values around the camera distance clear most of the screen.
     */
    public static final ModConfigSpec.DoubleValue BOX_RADIUS;

    /**
     * Only blocks above this height (relative to the player's feet) get hidden. The default is
     * one block above the player's feet.
     */
    public static final ModConfigSpec.DoubleValue MIN_HEIGHT_ABOVE_FEET;

    /** Loud outline + fill on the block under the cursor. Off = nothing is drawn for it. */
    public static final ModConfigSpec.BooleanValue HIGHLIGHT_BLOCKS;

    /** Loud outline + fill on the entity under the cursor. */
    public static final ModConfigSpec.BooleanValue HIGHLIGHT_ENTITIES;

    /** The marker on the block directly below the player, so their position stays readable while flying. */
    public static final ModConfigSpec.BooleanValue GROUND_MARKER;

    /**
     * How far along the view axis (from the player towards the camera) the hidden tube starts. The tube has a
     * perpendicular end cap, so raising this keeps the blocks right next to the player - at their body height
     * and directly above their head - intact and interactive.
     */
    public static final ModConfigSpec.DoubleValue NEAR_OFFSET;

    /**
     * Only allow interacting with a block whose face towards the camera is actually drawn. The chunk mesher
     * culls the faces between two solid blocks, so a block buried in a wall has no geometry on the side the
     * camera looks from: it cannot be seen, and with this on it cannot be mined or clicked either.
     */
    public static final ModConfigSpec.BooleanValue REQUIRE_VISIBLE_FACE;

    /**
     * Move the camera along the view axis until it is no longer inside solid rock. In an orthographic view
     * this does not change the picture at all (only the axis perpendicular components matter), it only moves
     * the clipping planes - and it is what makes an underground view possible at all, because rock interiors
     * have no faces to render.
     */
    public static final ModConfigSpec.BooleanValue PULL_CAMERA_OUT_OF_ROCK;

    /**
     * Limit the view height so the bottom edge of the near plane stays inside the air pocket around the
     * camera. Without it a wide view in a tunnel puts that edge inside the floor, which renders as a black
     * band along the bottom of the screen.
     */
    public static final ModConfigSpec.BooleanValue CLAMP_VIEW_TO_AIR_POCKET;

    /** Paints the hidden volume magenta instead of hiding it, so the exact culled region can be inspected. */
    public static final ModConfigSpec.BooleanValue DEBUG_SHOW_HIDDEN;

    /**
     * Draw hidden blocks as a dark screen-door ghost instead of deleting them. This is the classic x-ray look:
     * the world behind stays visible through the blocks, but nothing opens into a black hole, so walking around
     * no longer makes whole faces pop in and out.
     */
    public static final ModConfigSpec.BooleanValue ILLUSION_ENABLED;
    public static final ModConfigSpec.BooleanValue ILLUSION_REQUIRE_SNEAK;
    public static final ModConfigSpec.DoubleValue ILLUSION_MAX_RISE;
    public static final ModConfigSpec.DoubleValue ILLUSION_MAX_STEP;
    public static final ModConfigSpec.BooleanValue ILLUSION_FOLLOW_ZOOM;

    public static final ModConfigSpec.BooleanValue GHOST_HIDDEN_BLOCKS;

    public static final ModConfigSpec SPEC;

    static {
        BUILDER.push("occlusion");
        BOX_RADIUS = BUILDER
                .comment("Radius (blocks) of the circular area around the player that is projected towards",
                        "the camera and hidden. Note: at a 45 degree view a radius r sweeps 1.41*r blocks",
                        "straight up (4.0 -> 5.6 blocks), so raise minHeightAboveFeet to keep more ceiling.")
                .defineInRange("boxRadius", 4.0D, 0.5D, 32.0D);
        MIN_HEIGHT_ABOVE_FEET = BUILDER
                .comment("Only blocks above this height above the player's feet are hidden.",
                        "Default 1.8 = the player height: only blocks above the head are hidden.",
                        "Raise it to keep more of the space right above the player.")
                .defineInRange("minHeightAboveFeet", 1.8D, -1.0D, 16.0D);
        NEAR_OFFSET = BUILDER
                .comment("Distance (blocks, along the view axis) before the hidden tube starts.",
                        "0 = the tube starts at the plane through the player.")
                .defineInRange("nearOffset", 0.0D, 0.0D, 16.0D);
        REQUIRE_VISIBLE_FACE = BUILDER
                .comment("Only interact with blocks whose camera facing surface is actually rendered.",
                        "Turn off if you want to be able to mine blocks you cannot see.")
                .define("requireVisibleFace", true);
        PULL_CAMERA_OUT_OF_ROCK = BUILDER
                .comment("Pull the camera out of solid rock along the view axis (picture is unchanged).")
                .define("pullCameraOutOfRock", true);
        CLAMP_VIEW_TO_AIR_POCKET = BUILDER
                .comment("Limit the view height so the near plane stays inside the air pocket around the camera.")
                .define("clampViewToAirPocket", false);
        BUILDER.pop();
        BUILDER.push("illusion");
        ILLUSION_ENABLED = BUILDER
                .comment("Impossible-geometry traversal: stand on the surface the orthographic picture shows",
                        "under you, even when it is a different block at another depth (Monument Valley / FEZ).",
                        "The picture is unchanged, because the step is along the view axis.")
                .define("enabled", true);
        ILLUSION_REQUIRE_SNEAK = BUILDER
                .comment("Only cross while sneaking. The step itself is a translation along the view axis, so",
                        "the vanilla sneak edge guard (hover at a ledge) is never disabled: it simply cannot see",
                        "that your screen position moved. Set false to have the illusion always on.")
                .define("requireSneak", true);
        ILLUSION_MAX_RISE = BUILDER
                .comment("How far (in height) the illusion may reach along the view axis. 5.5 keeps the",
                        "depth step under 8 blocks, which vanilla's movement check accepts in one tick.")
                .defineInRange("maxRise", 5.5D, 0.5D, 12.0D);
        ILLUSION_MAX_STEP = BUILDER
                .comment("Largest height step applied in a single tick (blocks). Vanilla rejects moves it",
                        "cannot replay, so keep this modest.")
                .defineInRange("maxStep", 6.0D, 0.5D, 8.0D);
        ILLUSION_FOLLOW_ZOOM = BUILDER
                .comment("Let the reach grow with the zoom level. The block shaders discard anything nearer than",
                        "the near plane, so the usable depth is cameraDistance - 1 - 0.854 (~12.2 at zoom 12,",
                        "~26.2 at zoom 48); this derives maxRise from that instead of using a fixed value.")
                .define("followZoom", true);
        BUILDER.pop();
        BUILDER.push("visuals");
        HIGHLIGHT_BLOCKS = BUILDER
                .comment("Draw the highlight on the block under the cursor.")
                .define("highlightBlocks", true);
        HIGHLIGHT_ENTITIES = BUILDER
                .comment("Draw the highlight on the entity under the cursor.")
                .define("highlightEntities", true);
        GROUND_MARKER = BUILDER
                .comment("Draw the marker on the block directly below the player.")
                .define("groundMarker", true);
        GHOST_HIDDEN_BLOCKS = BUILDER
                .comment("Ghost hidden blocks (x-ray look) instead of deleting them.",
                        "Turn off for the old hard cut where hidden blocks leave holes.")
                .define("ghostHiddenBlocks", false);
        DEBUG_SHOW_HIDDEN = BUILDER
                .comment("Paint the hidden volume magenta instead of hiding it (debug aid).")
                .define("debugShowHiddenRegion", false);
        BUILDER.pop();
        SPEC = BUILDER.build();
    }

    private OrthoConfig() {
    }

    public static boolean illusionEnabled() {
        try {
            return ILLUSION_ENABLED.get();
        } catch (Exception notLoadedYet) {
            return true;
        }
    }

    public static boolean illusionRequireSneak() {
        try {
            return ILLUSION_REQUIRE_SNEAK.get();
        } catch (Exception notLoadedYet) {
            return true;
        }
    }

    public static double illusionMaxRise() {
        try {
            return ILLUSION_MAX_RISE.get();
        } catch (Exception notLoadedYet) {
            return 5.5D;
        }
    }

    public static double illusionMaxStep() {
        try {
            return ILLUSION_MAX_STEP.get();
        } catch (Exception notLoadedYet) {
            return 6.0D;
        }
    }

    public static boolean illusionFollowZoom() {
        try {
            return ILLUSION_FOLLOW_ZOOM.get();
        } catch (Exception notLoadedYet) {
            return true;
        }
    }

    public static boolean highlightBlocks() {
        try {
            return HIGHLIGHT_BLOCKS.get();
        } catch (Exception notLoadedYet) {
            return true;
        }
    }

    public static boolean highlightEntities() {
        try {
            return HIGHLIGHT_ENTITIES.get();
        } catch (Exception notLoadedYet) {
            return true;
        }
    }

    public static boolean groundMarker() {
        try {
            return GROUND_MARKER.get();
        } catch (Exception notLoadedYet) {
            return true;
        }
    }

    public static double nearOffset() {
        try {
            return NEAR_OFFSET.get();
        } catch (Exception notLoadedYet) {
            return 0.0D;
        }
    }

    public static boolean requireVisibleFace() {
        try {
            return REQUIRE_VISIBLE_FACE.get();
        } catch (Exception notLoadedYet) {
            return true;
        }
    }

    public static boolean pullCameraOutOfRock() {
        try {
            return PULL_CAMERA_OUT_OF_ROCK.get();
        } catch (Exception notLoadedYet) {
            return true;
        }
    }

    public static boolean clampViewToAirPocket() {
        try {
            return CLAMP_VIEW_TO_AIR_POCKET.get();
        } catch (Exception notLoadedYet) {
            return false;
        }
    }

    public static boolean ghostHiddenBlocks() {
        try {
            return GHOST_HIDDEN_BLOCKS.get();
        } catch (Exception notLoadedYet) {
            return true;
        }
    }

    public static boolean debugShowHiddenRegion() {
        try {
            return DEBUG_SHOW_HIDDEN.get();
        } catch (Exception notLoadedYet) {
            return false;
        }
    }

    public static double boxRadius() {
        try {
            return BOX_RADIUS.get();
        } catch (Exception notLoadedYet) {
            return 4.0D;
        }
    }

    public static double minHeightAboveFeet() {
        try {
            return MIN_HEIGHT_ABOVE_FEET.get();
        } catch (Exception notLoadedYet) {
            return 1.8D;
        }
    }
}
