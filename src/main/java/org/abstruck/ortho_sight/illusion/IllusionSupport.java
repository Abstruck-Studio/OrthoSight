package org.abstruck.ortho_sight.illusion;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.abstruck.ortho_sight.OrthoConfig;
import org.abstruck.ortho_sight.client.OrthoClient;

/**
 * The impossible-geometry support resolver: which real surface the player is standing on, judged by the screen
 * rather than by world space.
 *
 * <p>The player sits at the centre of the orthographic picture, so "the block you are standing on" means "the
 * block whose top face is drawn under you". Flat ground has exactly one such block - the one under your feet -
 * so nothing happens. A block at a different height, offset along the view axis, projects onto the same screen
 * cell; stepping onto it translates the player along that axis, which the camera cannot see.
 *
 * <p>Offsets follow {@link ScreenGrid#liftTo}: a step of {@code d = Y - feetY} moves the player by
 * {@code (sin(theta)*d, d, -cos(theta)*d)}, so at orbit 45 an ascent goes (+x, -z) and a descent (-x, +z).
 *
 * <p>Reference: the HSR dream ticker's projection-matrix cells, FEZ's "collision ignores the axis pointing at
 * the camera", Monument Valley's screen-adjacent nodes.
 */
public final class IllusionSupport {

    /** The surface that should carry the player, with the block providing it. */
    public record Support(double top, BlockPos pos) {
    }

    /** Vanilla's step height. Within this the player simply walks; the skill must never move them. */
    private static final double STEP_HEIGHT = 0.6D;
    /** How far ahead the "is the next cell still normal ground" test looks. */
    private static final double LOOK_AHEAD = 0.75D;
    /** A full-depth step is spread over this many ticks. */
    private static final int EASE_STEPS = 4;
    private static final double EPS = 1.0E-4D;

    private static final Logger LOGGER = LoggerFactory.getLogger("ortho_sight/illusion");
    /** Diagnostics: only logged when the decision changes, so the file stays readable. */
    private static String lastDecision = "";

    private static Support lastSupport;
    private static Double pendingTop;
    private static int armedTicks;

    private IllusionSupport() {
    }

    /** True while an illusion step is pending; the sneak edge guard is suspended then. */
    public static boolean armed() {
        return armedTicks > 0;
    }

    /** Called once per client tick, after vanilla movement. */
    public static void tick(Player player) {
        if (!OrthoClient.active || !OrthoConfig.illusionEnabled() || player == null || player.level() == null
                || !allowed(player)) {
            reset();
            return;
        }
        if (pendingTop != null) {
            if (stepTowards(player, pendingTop)) {
                pendingTop = null;
            }
            armedTicks = 6;
            return;
        }
        double reference = referenceHeight(player);
        Vec3 position = player.position();
        double a = ScreenGrid.a(position.x, position.y);
        double b = ScreenGrid.b(position.z, position.y);
        Support support = findSupport(player, a, b, reference);

        // Flush against the edge of ordinary ground, walking outwards, with a second surface drawn right under
        // you: the illusion takes over from there.
        if (support != null && Math.abs(support.top() - reference) <= STEP_HEIGHT + EPS
                && steppingOffEdge(player, support, reference)) {
            Support under = illusionUnderfoot(player, a, b, reference);
            if (under != null) {
                support = under;
            }
        }

        double delta = 0.0D;
        if (support != null) {
            // Compare against the anchor, not the (possibly falling) feet: while airborne the anchor is the
            // surface you left, and comparing against getY() would turn "the ledge I just stepped off" into an
            // ascent and snap the player back up.
            delta = support.top() - reference;
            if (Math.abs(delta) > STEP_HEIGHT + EPS) {
                pendingTop = support.top();
                if (stepTowards(player, pendingTop)) {
                    pendingTop = null;
                }
            } else if (player.onGround()) {
                player.resetFallDistance();
            }
            // Only commit the anchor when the player really is standing on it; committing a surface whose step
            // was blocked poisons the anchor for the following ticks.
            if (Math.abs(support.top() - player.getY()) <= 0.01D) {
                lastSupport = support;
            }
        } else {
            lastSupport = null;
        }
        boolean illusionHere = support != null && Math.abs(support.top() - reference) > STEP_HEIGHT + EPS;
        boolean stepAhead = illusionStepAhead(player, reference);
        armedTicks = (pendingTop != null || illusionHere || stepAhead) ? 6 : 0;
        String decision = (support == null ? "none"
                : support.top() + "@" + support.pos().toShortString() + " d=" + Math.round(delta * 100.0D) / 100.0D)
                + (pendingTop != null ? " pending=" + pendingTop : "")
                + (armedTicks > 0 ? " armed" : "");
        if (!decision.equals(lastDecision)) {
            lastDecision = decision;
            LOGGER.info("[OrthoSight/illusion] y={} ref={} support={} sneaking={} onGround={}",
                    Math.round(player.getY() * 100.0D) / 100.0D,
                    Math.round(reference * 100.0D) / 100.0D, decision,
                    player.isShiftKeyDown(), player.onGround());
        }
    }

    private static void reset() {
        lastSupport = null;
        pendingTop = null;
        armedTicks = 0;
    }

    private static boolean allowed(Player player) {
        if (OrthoConfig.illusionRequireSneak() && !player.isShiftKeyDown()) {
            return false;
        }
        if (player.isSpectator() || player.isPassenger() || player.getAbilities().flying || player.noPhysics) {
            return false;
        }
        if (player.isFallFlying() || player.isInWater() || player.isInLava() || !player.isAlive()) {
            return false;
        }
        BlockPos feet = player.blockPosition();
        Level level = player.level();
        return level.hasChunkAt(feet) && !level.isOutsideBuildHeight(feet);
    }

    /** While standing the anchor is the feet; while airborne keep the last surface so a jump is not hijacked. */
    private static double referenceHeight(Player player) {
        if (player.onGround()) {
            return player.getY();
        }
        return lastSupport != null ? lastSupport.top() : player.getY();
    }

    /**
     * How far (in height) the illusion may reach right now. With {@code followZoom} it is derived from the zoom
     * level: the block shaders discard anything nearer than the near plane, so the usable depth along the view
     * axis is {@code cameraDistance - 1 - 0.854} and the height difference is that over {@code sqrt(2)}.
     */
    private static double maxRise() {
        double usableDepth = OrthoClient.cameraDistance() - 1.0D - 0.8536D;
        double ceiling = usableDepth / Math.sqrt(2.0D);
        double value = OrthoConfig.illusionFollowZoom()
                ? ceiling
                : Math.min(OrthoConfig.illusionMaxRise(), ceiling);
        return Mth.clamp(value, 1.0D, 20.0D);
    }

    /**
     * The surface drawn under the player.
     *
     * <p>Ground contact needs special handling: an ascent target is by definition co-cell'd with the block you
     * are standing on, so a "same height first, then offsets" search always short-circuits on the floor and an
     * ascent can never be found. Accordingly the rule is asymmetric on purpose and mirrors how a player actually
     * uses it:
     *
     * <ul>
     *   <li><b>standing on something</b> - look <b>up</b> for a surface drawn above the one you stand on; if
     *       there is none, you simply walk.</li>
     *   <li><b>airborne</b> - look <b>both</b> ways. This is the descent path: the reference stays frozen at the
     *       surface you left, so the ladder of offsets is exact and a lower surface is found over its whole
     *       footprint (drift tolerant), which is why descending never depended on the edge hatch.</li>
     * </ul>
     *
     * <p>Searching down while grounded is deliberately not done: it would let the player be pulled through the
     * floor they are standing on.
     */
    public static Support findSupport(Player player, double a, double b, double reference) {
        Level level = player.level();
        Support same = probe(level, player, a, b, reference, STEP_HEIGHT, reference);
        double limit = maxRise();
        if (same != null) {
            for (double offset = 0.5D; offset <= limit + EPS; offset += 0.5D) {
                Support up = probe(level, player, a, b, reference + offset, 0.25D, reference);
                if (up != null) {
                    return up;
                }
            }
            return same;
        }
        for (double offset = 0.5D; offset <= limit + EPS; offset += 0.5D) {
            Support up = probe(level, player, a, b, reference + offset, 0.25D, reference);
            if (up != null) {
                return up;
            }
            Support down = probe(level, player, a, b, reference - offset, 0.25D, reference);
            if (down != null) {
                return down;
            }
        }
        return null;
    }

    /** The first illusion surface (either sign) drawn under the given cell. */
    private static Support illusionUnderfoot(Player player, double a, double b, double reference) {
        Level level = player.level();
        double limit = maxRise();
        for (double offset = 0.5D; offset <= limit + EPS; offset += 0.5D) {
            Support up = probe(level, player, a, b, reference + offset, 0.25D, reference);
            if (up != null && Math.abs(up.top() - reference) > STEP_HEIGHT + EPS) {
                return up;
            }
            Support down = probe(level, player, a, b, reference - offset, 0.25D, reference);
            if (down != null && Math.abs(down.top() - reference) > STEP_HEIGHT + EPS) {
                return down;
            }
        }
        return null;
    }

    /**
     * Is there an illusion surface at the cell the player is about to walk into, with no ordinary ground there?
     * That is the precise precondition for lifting the sneak edge guard.
     */
    private static boolean illusionStepAhead(Player player, double reference) {
        Vec3 motion = player.getDeltaMovement();
        double speed = Math.sqrt(motion.x * motion.x + motion.z * motion.z);
        if (speed < 1.0E-3D) {
            return false;
        }
        // Must look PAST the block boundary: the sneak guard stops the player about 0.05 blocks short of the
        // edge, so a shorter look-ahead still lands inside the block they stand on, the "normal ground"
        // probe succeeds, the guard is never lifted and the player can never take that last step.
        double aheadX = player.getX() + motion.x / speed * LOOK_AHEAD;
        double aheadZ = player.getZ() + motion.z / speed * LOOK_AHEAD;
        double a = ScreenGrid.a(aheadX, player.getY());
        double b = ScreenGrid.b(aheadZ, player.getY());
        if (probe(player.level(), player, a, b, reference, STEP_HEIGHT, reference) != null) {
            return false; // ordinary ground continues
        }
        return illusionUnderfoot(player, a, b, reference) != null;
    }

    /** Within ~0.16 blocks of the horizontal boundary of the block the player is standing on. */
    private static boolean nearBoundary(Player player, BlockPos pos) {
        double dx = Math.min(Math.abs(player.getX() - pos.getX()), Math.abs(pos.getX() + 1.0D - player.getX()));
        double dz = Math.min(Math.abs(player.getZ() - pos.getZ()), Math.abs(pos.getZ() + 1.0D - player.getZ()));
        return Math.min(dx, dz) <= 0.16D;
    }

    /**
     * Flush against the boundary of the current surface, walking towards it, and the ground does not simply
     * continue in that direction. The motion and neighbour tests matter: a continuous walkway has internal block
     * seams every block, and treating those as edges would hijack the player for no reason.
     */
    private static boolean steppingOffEdge(Player player, Support support, double reference) {
        Vec3 motion = player.getDeltaMovement();
        if (Math.abs(motion.x) < 1.0E-3D && Math.abs(motion.z) < 1.0E-3D) {
            return false;
        }
        if (!nearBoundary(player, support.pos())) {
            return false;
        }
        double outwardX = player.getX() - (support.pos().getX() + 0.5D);
        double outwardZ = player.getZ() - (support.pos().getZ() + 0.5D);
        if (motion.x * outwardX + motion.z * outwardZ <= 0.0D) {
            return false;
        }
        int stepX = Math.abs(motion.x) >= Math.abs(motion.z) ? (int) Math.signum(motion.x) : 0;
        int stepZ = Math.abs(motion.z) > Math.abs(motion.x) ? (int) Math.signum(motion.z) : 0;
        BlockPos neighbour = new BlockPos(support.pos().getX() + stepX, support.pos().getY(),
                support.pos().getZ() + stepZ);
        Double neighbourTop = topAt(player.level(), player, neighbour);
        return neighbourTop == null || Math.abs(neighbourTop - reference) > 0.05D;
    }

    /** Top of whatever stands in this block position, or null. */
    private static Double topAt(Level level, Player player, BlockPos pos) {
        if (!level.hasChunkAt(pos)) {
            return null;
        }
        BlockState state = level.getBlockState(pos);
        if (!state.getFluidState().isEmpty()) {
            return null;
        }
        VoxelShape shape = state.getCollisionShape(level, pos, CollisionContext.of(player));
        if (shape.isEmpty()) {
            return null;
        }
        return pos.getY() + shape.max(Direction.Axis.Y);
    }

    /**
     * Is there a standable surface whose top is within tolerance of targetHeight, with a top face that actually
     * covers the screen cell (a, b)?
     */
    private static Support probe(Level level, Player player, double a, double b, double targetHeight,
            double tolerance, double reference) {
        if (Math.abs(targetHeight - reference) > maxRise() + EPS) {
            return null;
        }
        double pointX = ScreenGrid.xAt(a, targetHeight);
        double pointZ = ScreenGrid.zAt(b, targetHeight);
        int columnX = Mth.floor(pointX);
        int columnZ = Mth.floor(pointZ);
        int baseY = Mth.floor(targetHeight);
        for (int y = baseY + 1; y >= baseY - 1; y--) {
            BlockPos pos = new BlockPos(columnX, y, columnZ);
            if (!level.hasChunkAt(pos)) {
                continue;
            }
            BlockState state = level.getBlockState(pos);
            if (!state.getFluidState().isEmpty()) {
                continue;
            }
            VoxelShape shape = state.getCollisionShape(level, pos, CollisionContext.of(player));
            if (shape.isEmpty()) {
                continue;
            }
            double top = pos.getY() + shape.max(Direction.Axis.Y);
            if (Math.abs(top - targetHeight) > tolerance + EPS) {
                continue;
            }
            double surfaceX = ScreenGrid.xAt(a, top);
            double surfaceZ = ScreenGrid.zAt(b, top);
            if (!coveredByTopFace(pos, shape, top, surfaceX, surfaceZ)) {
                continue;
            }
            if (!hasRoomAbove(level, player, pos, top)) {
                continue;
            }
            return new Support(top, pos);
        }
        return null;
    }

    /**
     * The point must lie inside the part of the shape that defines the top face. Using the shape's overall bounds
     * would accept a point over the low half of a stair and drop the player half a block.
     */
    private static boolean coveredByTopFace(BlockPos pos, VoxelShape shape, double top, double x, double z) {
        double localX = x - pos.getX();
        double localZ = z - pos.getZ();
        for (AABB part : shape.toAabbs()) {
            if (Math.abs(pos.getY() + part.maxY - top) > EPS) {
                continue;
            }
            if (localX >= part.minX - EPS && localX <= part.maxX + EPS
                    && localZ >= part.minZ - EPS && localZ <= part.maxZ + EPS) {
                return true;
            }
        }
        return false;
    }

    /** Two blocks of headroom above the surface, so the player fits. */
    private static boolean hasRoomAbove(Level level, Player player, BlockPos pos, double top) {
        // Start above the surface itself: for non-integer tops (slab 0.5, snow 0.125, dirt path 0.9375) the
        // old floor(top + 0.05) landed on the support block and rejected it.
        double base = Math.floor(top);
        int firstY = (int) base + (top > base + 0.05D ? 1 : 0);
        for (int i = 0; i < 2; i++) {
            BlockPos above = new BlockPos(pos.getX(), firstY + i, pos.getZ());
            if (!level.hasChunkAt(above)) {
                return false;
            }
            BlockState state = level.getBlockState(above);
            if (!state.getFluidState().isEmpty()) {
                return false;
            }
            if (!state.getCollisionShape(level, above, CollisionContext.of(player)).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /** One eased step towards the target height. Returns true when the transition is over. */
    private static boolean stepTowards(Player player, double targetTop) {
        double remaining = targetTop - player.getY();
        if (Math.abs(remaining) < 1.0E-3D) {
            land(player);
            return true;
        }
        double step = Math.min(Math.abs(remaining),
                Math.max(0.05D, OrthoConfig.illusionMaxStep() / (double) EASE_STEPS));
        Vec3 next = ScreenGrid.liftTo(player.position(), player.getY() + Math.copySign(step, remaining));
        if (canMove(player, next)) {
            moveTo(player, next);
            return Math.abs(targetTop - next.y) < 1.0E-3D;
        }
        Vec3 end = ScreenGrid.liftTo(player.position(), targetTop);
        if (canMove(player, end)) {
            moveTo(player, end);
            return true;
        }
        LOGGER.info("[OrthoSight/illusion] step blocked: y={} target={} (nothing was moved)",
                Math.round(player.getY() * 100.0D) / 100.0D, targetTop);
        return true; // give up on this transition and let vanilla decide
    }

    /**
     * May the player be moved to {@code target}?
     *
     * <p>The criterion mirrors what a vanilla server will accept, because a move it cannot replay is answered
     * with a rubber-band. The destination box must be free, and if the vertical part is obstructed the server
     * resolves X/Z at the original height - so in that case only the horizontal destination matters. A swept box
     * over the whole path is far stricter than the server and rejected every descent (the box always touches the
     * block being stood on), which is why descending used to be impossible.
     */
    private static boolean canMove(Player player, Vec3 target) {
        Level level = player.level();
        AABB current = player.getBoundingBox();
        double dx = target.x - player.getX();
        double dy = target.y - player.getY();
        double dz = target.z - player.getZ();
        if (!level.noCollision(player, current.move(dx, dy, dz))) {
            return false;
        }
        if (!level.noCollision(player, current.move(0.0D, dy, 0.0D))) {
            return level.noCollision(player, current.move(dx, 0.0D, dz));
        }
        return true;
    }

    private static void moveTo(Player player, Vec3 target) {
        player.setPos(target.x, target.y, target.z);
        player.setDeltaMovement(player.getDeltaMovement().x, 0.0D, player.getDeltaMovement().z);
        land(player);
    }

    private static void land(Player player) {
        player.setOnGround(true);
        player.resetFallDistance();
    }
}