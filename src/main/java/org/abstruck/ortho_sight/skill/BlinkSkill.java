package org.abstruck.ortho_sight.skill;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The blink rule, shared by the client pre-check and the server validation.
 *
 * <p>While sneaking and walking, if the next column ahead is not something vanilla movement already reaches
 * (a gap, a wall, a step higher than the 0.6 block step), the player is teleported onto the first standable
 * surface further along the walking direction. On flat ground nothing happens, which keeps normal movement
 * completely untouched.
 *
 * <p>The walking direction must be supplied by the caller. It cannot be read from
 * {@code Entity#getDeltaMovement()}: a server player's delta is driven by the positions the client sends, and
 * even on the client the delta of the collided axis is zeroed while walking into a wall or a step.
 *
 * <p>Pure function of (level, position, direction, synced config): no client imports, it runs on a dedicated
 * server.
 */
public final class BlinkSkill {

    /** A standable surface the player may blink onto. {@code pos} is the block that provides the surface. */
    public record Target(BlockPos pos, double top) {
    }

    /** Vanilla can step up this much without a jump; anything higher needs the skill. */
    private static final double WALKABLE_STEP = 0.6D;
    private static final double SCAN_STEP = 0.5D;

    /** gameTime of the last accepted blink, per player, so a client cannot spam requests. */
    private static final Map<UUID, Long> LAST_BLINK = new ConcurrentHashMap<>();

    private BlinkSkill() {
    }

    /** Cheap gate: is the skill allowed to run for this entity right now? */
    public static boolean enabled(Entity entity) {
        if (!(entity instanceof Player player)) {
            return false;
        }
        if (!SkillConfig.enabled()) {
            return false;
        }
        if (SkillConfig.requireSneak() && !player.isShiftKeyDown()) {
            return false;
        }
        if (player.isSpectator() || player.isPassenger() || player.getAbilities().flying || player.noPhysics) {
            return false;
        }
        if (player.isFallFlying() || player.isInWater() || player.isInLava() || !player.isAlive()) {
            return false;
        }
        Level level = player.level();
        BlockPos feet = player.blockPosition();
        return level.hasChunkAt(feet) && !level.isOutsideBuildHeight(feet);
    }

    /**
     * The surface to blink onto, or {@code null} when normal walking already reaches the next block or nothing
     * is within reach. Prefers a surface at (or slightly above) foot level so the skill crosses gaps instead of
     * dropping into shallow trenches.
     */
    public static Target findTarget(Entity entity, double dirX, double dirZ) {
        if (!enabled(entity)) {
            return null;
        }
        Player player = (Player) entity;
        double length = Math.sqrt(dirX * dirX + dirZ * dirZ);
        if (length < 1.0E-4D) {
            return null;
        }
        dirX /= length;
        dirZ /= length;

        Level level = player.level();
        double feetY = player.getY();
        int ownX = Mth.floor(player.getX());
        int ownZ = Mth.floor(player.getZ());

        // 1. Is the next column walkable? Then this is ordinary movement and the skill stays out of the way.
        int nextX = Mth.floor(player.getX() + dirX * 0.9D);
        int nextZ = Mth.floor(player.getZ() + dirZ * 0.9D);
        if (nextX == ownX && nextZ == ownZ) {
            // Sampling from right next to the block edge can land in the own column; look one block further.
            nextX = Mth.floor(player.getX() + dirX * 1.6D);
            nextZ = Mth.floor(player.getZ() + dirZ * 1.6D);
        }
        Double next = columnTop(level, player, nextX, nextZ, feetY);
        if (next != null && Math.abs(next - feetY) <= WALKABLE_STEP) {
            return null;
        }

        // 2. First pass: a surface at foot level or above (cross the gap, climb the step).
        Target level1 = scan(level, player, dirX, dirZ, feetY, -WALKABLE_STEP, SkillConfig.maxRise());
        if (level1 != null) {
            return level1;
        }
        // 3. Second pass: allow dropping down onto a lower surface.
        return scan(level, player, dirX, dirZ, feetY, -SkillConfig.maxDrop(), SkillConfig.maxRise());
    }

    private static Target scan(Level level, Player player, double dirX, double dirZ, double feetY,
            double minDelta, double maxDelta) {
        int ownX = Mth.floor(player.getX());
        int ownZ = Mth.floor(player.getZ());
        for (double distance = 1.0D; distance <= SkillConfig.range(); distance += SCAN_STEP) {
            int columnX = Mth.floor(player.getX() + dirX * distance);
            int columnZ = Mth.floor(player.getZ() + dirZ * distance);
            if (columnX == ownX && columnZ == ownZ) {
                continue;
            }
            Double top = columnTop(level, player, columnX, columnZ, feetY);
            if (top == null) {
                continue;
            }
            double delta = top - feetY;
            if (delta > maxDelta || delta < minDelta) {
                continue;
            }
            if (!hasRoomAbove(level, player, columnX, columnZ, top)) {
                continue;
            }
            return new Target(BlockPos.containing(columnX + 0.5D, top - 1.0E-3D, columnZ + 0.5D), top);
        }
        return null;
    }

    /** Top of the walkable surface in this column near the player's feet, or {@code null}. */
    private static Double columnTop(Level level, Entity entity, int x, int z, double feetY) {
        int base = Mth.floor(feetY);
        int from = base + Mth.ceil(SkillConfig.maxRise());
        int to = base - Mth.ceil(SkillConfig.maxDrop()) - 1;
        for (int y = from; y >= to; y--) {
            BlockPos pos = new BlockPos(x, y, z);
            if (!level.hasChunkAt(pos)) {
                return null;
            }
            BlockState state = level.getBlockState(pos);
            if (!state.getFluidState().isEmpty()) {
                return null; // never blink onto water or lava
            }
            VoxelShape shape = state.getCollisionShape(level, pos, CollisionContext.of(entity));
            if (shape.isEmpty()) {
                continue;
            }
            return y + shape.max(Direction.Axis.Y);
        }
        return null;
    }

    /** Two blocks of air above the surface so the player actually fits. */
    private static boolean hasRoomAbove(Level level, Entity entity, int x, int z, double top) {
        int firstY = Mth.floor(top + 0.05D);
        for (int i = 0; i < 2; i++) {
            BlockPos pos = new BlockPos(x, firstY + i, z);
            if (!level.hasChunkAt(pos)) {
                return false;
            }
            BlockState state = level.getBlockState(pos);
            if (!state.getFluidState().isEmpty()) {
                return false;
            }
            if (!state.getCollisionShape(level, pos, CollisionContext.of(entity)).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /** Read-only cooldown probe. */
    public static boolean allowBlink(Player player) {
        Long last = LAST_BLINK.get(player.getUUID());
        return last == null || player.level().getGameTime() - last >= SkillConfig.cooldownTicks();
    }

    /** Commits the cooldown, only after a successful blink. */
    public static void markBlink(Player player) {
        LAST_BLINK.put(player.getUUID(), player.level().getGameTime());
    }

    /**
     * Server side entry point for a blink request: validates everything on the server's own copy of the world
     * and performs the teleport. Never takes a destination from the client.
     */
    public static void handleRequest(ServerPlayer player, float rawX, float rawZ) {
        if (!player.isAlive() || player.isSpectator() || player.isPassenger() || player.isFallFlying()
                || player.isSleeping() || player.isChangingDimension()) {
            return;
        }
        if (!enabled(player)) {
            return;
        }
        if (!player.onGround() && player.fallDistance > 0.5F) {
            return;
        }
        double dx = rawX;
        double dz = rawZ;
        if (!Double.isFinite(dx) || !Double.isFinite(dz)) {
            return;
        }
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < 0.02D || length > 1.5D) {
            return;
        }
        dx /= length;
        dz /= length;

        // Cross-check against the last movement the server accepted: refuse requests pointing the other way.
        Vec3 known = player.getKnownMovement();
        double knownLength = Math.sqrt(known.x * known.x + known.z * known.z);
        if (knownLength > 0.05D && (known.x * dx + known.z * dz) / knownLength < 0.0D) {
            return;
        }
        if (!allowBlink(player)) {
            return;
        }
        Target target = findTarget(player, dx, dz);
        if (target == null) {
            return;
        }
        double x = target.pos().getX() + 0.5D;
        double z = target.pos().getZ() + 0.5D;
        double y = target.top();
        AABB moved = player.getBoundingBox().move(x - player.getX(), y - player.getY(), z - player.getZ());
        if (!player.level().noCollision(player, moved)) {
            return;
        }
        player.teleportTo(x, y, z);
        player.resetFallDistance();
        player.setDeltaMovement(Vec3.ZERO);
        markBlink(player);
    }
}