package org.abstruck.ortho_sight.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderHighlightEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.abstruck.ortho_sight.OrthoConfig;
import org.abstruck.ortho_sight.OrthoSight;
import org.abstruck.ortho_sight.mixin.MinecraftInvoker;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;

import java.util.OptionalDouble;

/**
 * Client-side state, key handling and rendering for the orthographic ("正交视角") view.
 */
@EventBusSubscriber(modid = OrthoSight.MODID, value = Dist.CLIENT)
public final class OrthoClient {

    /* ------------------------------------------------------------------ tunables */

    /** Fixed elevation of the orthographic camera, in degrees. */
    public static final float PITCH = 45.0F;
    /** Fixed yaw the camera starts at, in degrees. */
    public static final float DEFAULT_ORBIT = 45.0F;
    /** Minimum distance between the player and the camera; also the culling cone's flank length. */
    public static final float CAMERA_DISTANCE = 14.0F;
    /**
     * Near plane. An orthographic depth range does not care about the ratio to FAR, and a value this
     * large stops the near plane from slicing the camera's own block at a non block-aligned angle.
     */
    public static final float NEAR = 1.0F;

    public static final float FAR = 512.0F;

    public static final float ZOOM_MIN = 4.0F;
    public static final float ZOOM_MAX = 48.0F;

    /** Degrees per second while Z / C is held. */
    public static final float ORBIT_SPEED = 40.0F;
    /** Degrees per second the body swings towards the movement direction while walking. */
    public static final float AUTO_FACE_SPEED = 70.0F;
    /** Degrees per second the body swings towards the cursor while standing still. */
    public static final float CURSOR_TURN_SPEED = 720.0F;
    /** How far below the player the ground marker looks, in blocks. */
    public static final int GROUND_MARKER_DEPTH = 80;

    /* ------------------------------------------------------------------ state */

    /** True while the orthographic view is the selected view. */
    public static volatile boolean active;
    /**
     * Whether the occlusion slab between the camera and the player is hidden. On by default, toggled by X.
     * With it off the world is drawn exactly as vanilla would.
     */
    public static volatile boolean coneEnabled = true;
    /** Visible world height, in blocks. Ctrl + mouse wheel. */
    public static volatile float zoom = 12.0F;
    /** Camera yaw around the player, in degrees. Z / C. */
    public static volatile float orbit = DEFAULT_ORBIT;

    /** Camera distance actually used this frame (see placeCamera). */
    private static volatile double cameraDistanceUsed = CAMERA_DISTANCE;
    /** Eased camera distance, so crossing between air pockets is smooth. */
    private static volatile double smoothedDistance = CAMERA_DISTANCE;
    /** Whether the camera ended up inside solid rock, i.e. the view has no geometry to show at all. */
    private static volatile boolean cameraInRock;
    /** Partial tick of the frame the camera was placed for, shared by every Java side test. */
    private static volatile float framePartialTick = 1.0F;

    /** Where the cursor points, in player view angles; used while the player is standing still. */
    private static float cursorAimYaw = DEFAULT_ORBIT;
    private static float cursorAimPitch;
    private static boolean cursorAimValid;

    private static boolean mouseLeftDown;
    private static boolean mouseRightDown;
    private static int viewIndex;
    private static boolean initialised;

    private static final CameraType[] VANILLA_VIEWS = {
            CameraType.FIRST_PERSON, CameraType.THIRD_PERSON_BACK, CameraType.THIRD_PERSON_FRONT
    };
    private static final int ORTHO_VIEW_INDEX = VANILLA_VIEWS.length;

    /* ------------------------------------------------------------------ keys */

    public static final KeyMapping ROTATE_LEFT = key("key.ortho_sight.rotate_left", GLFW.GLFW_KEY_Z);
    public static final KeyMapping ROTATE_RIGHT = key("key.ortho_sight.rotate_right", GLFW.GLFW_KEY_C);
    public static final KeyMapping TOGGLE_CONE = key("key.ortho_sight.toggle_cone", GLFW.GLFW_KEY_X);

    private OrthoClient() {
    }

    private static KeyMapping key(String name, int keyCode) {
        return new KeyMapping(name, InputConstants.Type.KEYSYM, keyCode, "key.categories.ortho_sight");
    }

    public static void register(RegisterKeyMappingsEvent event) {
        event.register(ROTATE_LEFT);
        event.register(ROTATE_RIGHT);
        event.register(TOGGLE_CONE);
    }

    /* ------------------------------------------------------------------ view switching (F5) */

    /** Replaces the vanilla F5 behaviour: first person -> third person back -> third person front -> ortho. */
    public static void cycleView() {
        Minecraft mc = Minecraft.getInstance();
        CameraType old = mc.options.getCameraType();

        viewIndex = (viewIndex + 1) % (VANILLA_VIEWS.length + 1);
        active = viewIndex == ORTHO_VIEW_INDEX;
        if (active) {
            orbit = DEFAULT_ORBIT;
        }

        // The orthographic camera is "detached", so vanilla has to be in a third person mode for
        // Camera#setup to hand control over to us.
        CameraType next = active ? CameraType.THIRD_PERSON_BACK : VANILLA_VIEWS[viewIndex];
        mc.options.setCameraType(next);
        if (old.isFirstPerson() != next.isFirstPerson()) {
            mc.gameRenderer.checkEntityPostEffect(next.isFirstPerson() ? mc.getCameraEntity() : null);
        }
        if (mc.levelRenderer != null) {
            mc.levelRenderer.needsUpdate();
        }

        // The orthographic view is driven by the cursor, not by mouse look: release the mouse so the
        // pointer can be used to click things on screen.
        if (active) {
            mc.mouseHandler.releaseMouse();
        } else {
            mc.mouseHandler.grabMouse();
        }
    }

    private static void syncViewIndex() {
        Minecraft mc = Minecraft.getInstance();
        CameraType type = mc.options.getCameraType();
        viewIndex = 0;
        for (int i = 0; i < VANILLA_VIEWS.length; i++) {
            if (type == VANILLA_VIEWS[i]) {
                viewIndex = i;
            }
        }
        active = false;
    }

    /* ------------------------------------------------------------------ per frame input */

    /**
     * Camera rotation and look angles are integrated per <b>frame</b>, not per tick: the tick runs at 20 Hz
     * while the game renders at 60-240 Hz, so ticking it made everything advance in visible steps.
     */
    @SubscribeEvent
    public static void onRenderFrame(RenderFrameEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (!active || mc.screen != null || mc.player == null) {
            return;
        }
        float deltaSeconds = Mth.clamp(event.getPartialTick().getRealtimeDeltaTicks(), 0.0F, 5.0F) / 20.0F;

        // Z / C rotate the camera around the player.
        float orbitDirection = 0.0F;
        if (ROTATE_LEFT.isDown()) {
            orbitDirection -= 1.0F;
        }
        if (ROTATE_RIGHT.isDown()) {
            orbitDirection += 1.0F;
        }
        if (orbitDirection != 0.0F) {
            orbit = Mth.wrapDegrees(orbit + orbitDirection * ORBIT_SPEED * deltaSeconds);
        }

        updatePlayerFacing(mc, deltaSeconds);
    }

    /**
     * Where the body points: while walking it swings towards the movement direction, while standing still it
     * follows the cursor.
     */
    private static void updatePlayerFacing(Minecraft mc, float deltaSeconds) {
        double strafe = 0.0D;
        double forward = 0.0D;
        if (mc.options.keyUp.isDown()) {
            forward += 1.0D;
        }
        if (mc.options.keyDown.isDown()) {
            forward -= 1.0D;
        }
        if (mc.options.keyLeft.isDown()) {
            strafe += 1.0D;
        }
        if (mc.options.keyRight.isDown()) {
            strafe -= 1.0D;
        }

        if (strafe != 0.0D || forward != 0.0D) {
            // Screen relative world direction, matching what InputMixin feeds vanilla.
            double orbitRadians = Math.toRadians(orbit);
            double worldX = strafe * Math.cos(orbitRadians) - forward * Math.sin(orbitRadians);
            double worldZ = strafe * Math.sin(orbitRadians) + forward * Math.cos(orbitRadians);
            float targetYaw = (float) Math.toDegrees(Math.atan2(-worldX, worldZ));
            mc.player.setYRot(Mth.approachDegrees(mc.player.getYRot(), targetYaw, AUTO_FACE_SPEED * deltaSeconds));
        } else if (cursorAimValid) {
            mc.player.setYRot(Mth.approachDegrees(mc.player.getYRot(), cursorAimYaw, CURSOR_TURN_SPEED * deltaSeconds));
            mc.player.setXRot(Mth.approachDegrees(mc.player.getXRot(), cursorAimPitch, CURSOR_TURN_SPEED * deltaSeconds));
        }
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (!initialised && mc.options != null) {
            syncViewIndex();
            initialised = true;
        }

        boolean inGame = mc.screen == null;
        // Always drain the click queues, otherwise presses pile up and fire later.
        while (TOGGLE_CONE.consumeClick()) {
            if (inGame) {
                coneEnabled = !coneEnabled;
            }
        }

        if (!active) {
            return;
        }
        // Keep the cursor free; vanilla grabs it again when a screen closes.
        if (inGame && mc.mouseHandler.isMouseGrabbed()) {
            mc.mouseHandler.releaseMouse();
        }
        // Holding the left button keeps mining, like holding it does in vanilla.
        // Every frame, keep the directional PVS off while the ortho view is active: with it on, a camera
        // sitting inside a 100% solid section stops the visibility BFS after one ring (about seven sections),
        // which is what blacked out whole screens. Restored as soon as the view is left.
        mc.smartCull = !active;
        if (inGame && mouseLeftDown) {
            clearUnusableTarget(mc);
            ((MinecraftInvoker) mc).orthosight$continueAttack(true);
        }
    }

    /** Ctrl + mouse wheel zooms; a plain wheel keeps scrolling the hotbar like vanilla. */
    @SubscribeEvent
    public static void onMouseScroll(InputEvent.MouseScrollingEvent event) {
        if (!active || Minecraft.getInstance().screen != null || !Screen.hasControlDown()) {
            return;
        }
        zoom = Mth.clamp(zoom - (float) event.getScrollDeltaY(), ZOOM_MIN, ZOOM_MAX);
        event.setCanceled(true);
    }

    /**
     * Mouse buttons are handled here instead of by vanilla: in the orthographic view the pointer is free, so
     * a click means "hit whatever is under the cursor" and vanilla's own click handling (which needs a
     * grabbed mouse) is cancelled. Left button attacks / mines, right button uses the held item exactly like
     * vanilla's right click.
     */
    @SubscribeEvent
    public static void onMouseButton(InputEvent.MouseButton.Pre event) {
        if (!active || Minecraft.getInstance().screen != null) {
            return;
        }
        int button = event.getButton();
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT && button != GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            return;
        }
        boolean pressed = event.getAction() == GLFW.GLFW_PRESS;
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            mouseLeftDown = pressed;
        } else {
            mouseRightDown = pressed;
        }
        if (pressed) {
            performClick(button == GLFW.GLFW_MOUSE_BUTTON_RIGHT);
        }
        event.setCanceled(true);
    }

    /** @param useItem {@code true} for the right button (use / place / interact), {@code false} to attack */
    private static void performClick(boolean useItem) {
        Minecraft mc = Minecraft.getInstance();
        if (!active || mc.player == null || mc.gameMode == null) {
            return;
        }
        // Refresh the target with the current cursor position so a click always hits what is under the
        // pointer right now, not where it was on the previous tick.
        applyCursorPick(framePartialTick);
        if (clearUnusableTarget(mc)) {
            // The target is a block that is not drawn: it must not be mined or right-clicked either.
            return;
        }
        if (useItem) {
            ((MinecraftInvoker) mc).orthosight$startUseItem();
        } else {
            ((MinecraftInvoker) mc).orthosight$startAttack();
        }
    }

    /**
     * Blocks that are invisible must not be usable either - neither blocks hidden by the occlusion rule nor
     * blocks whose face towards the camera was never meshed. The pick already skips both, but the target is
     * only recomputed once per tick, so this clears it immediately before an action runs.
     *
     * @return true if the current target is not something you can see, and has been cleared
     */
    private static boolean clearUnusableTarget(Minecraft mc) {
        if (mc.player == null || mc.level == null) {
            return false;
        }
        if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
            return false;
        }
        BlockPos pos = hit.getBlockPos();
        boolean hidden = isHiddenBlock(Vec3.atCenterOf(pos), originOf(mc.player, framePartialTick), axis(),
                mc.player.getY());
        if (!hidden && surfaceIsDrawn(mc.level, pos, hit.getDirection())) {
            return false;
        }
        mc.hitResult = BlockHitResult.miss(hit.getLocation(), hit.getDirection(), hit.getBlockPos());
        mc.crosshairPickEntity = null;
        return true;
    }
    /**
     * Whether the face of {@code pos} in {@code face} direction was actually meshed. The chunk mesher culls
     * the faces between two solid blocks, so a block buried inside a wall has no geometry on the side the
     * camera looks from: it cannot be seen, so it must not be mineable either. This is exactly the case when
     * you are boxed in and the block in front of you has a discarded (hidden) neighbour on the camera side.
     */
    private static boolean surfaceIsDrawn(net.minecraft.world.level.BlockGetter level, BlockPos pos,
            Direction face) {
        if (!OrthoConfig.requireVisibleFace()) {
            return true;
        }
        BlockPos neighbour = pos.relative(face);
        if (level.getBlockState(neighbour).isSolidRender(level, neighbour)) {
            return false;
        }
        // The face must also point towards the camera. A ray that EXITS a block hits a face whose normal
        // points away from the camera, and that quad is back-face culled - which is exactly how an invisible
        // block used to be minable.
        Vec3 camera = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        return camera.subtract(Vec3.atCenterOf(pos)).dot(Vec3.atLowerCornerOf(face.getNormal())) > 0.0D;
    }
    /**
     * Authoritative test used by the game mode hooks: may the player act on this block at all?
     *
     * <p>A block is usable only when it is not hidden by the occlusion rule and the face the camera looks
     * from actually has geometry - the chunk mesher culls the faces between two solid blocks, so a block
     * buried in a wall (or behind a discarded block) has nothing to see there.
     */
    public static boolean isBlockUsable(BlockPos pos, Direction face) {
        Minecraft mc = Minecraft.getInstance();
        if (!active || mc.player == null || mc.level == null) {
            return true;
        }
        if (isHiddenBlock(Vec3.atCenterOf(pos), originOf(mc.player, framePartialTick), axis(), feetOf(mc.player, framePartialTick))) {
            return false;
        }
        // Check the face that was actually hit, not the one pointing most towards the camera: a block on a
        // wall usually has its top buried while its side - the face you are looking at - is perfectly visible.
        return surfaceIsDrawn(mc.level, pos, face);
    }
    /** Leaving the world must not leave the mod in a half-initialised orthographic state. */
    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        active = false;
        initialised = false;
        orbit = DEFAULT_ORBIT;
        mouseLeftDown = false;
        mouseRightDown = false;
    }

    /* ------------------------------------------------------------------ camera geometry */

    /** Centre of the body the camera orbits (the player's mid height). */
    public static Vec3 originOf(Entity entity, float partialTick) {
        return entity.getPosition(partialTick).add(0.0, entity.getBbHeight() * 0.5, 0.0);
    }

    /** Unit vector pointing from the player towards the camera. Shared by the camera and the cone. */
    public static Vec3 axis() {
        double pitch = Math.toRadians(PITCH);
        double yaw = Math.toRadians(orbit);
        return new Vec3(Math.cos(pitch) * Math.sin(yaw), Math.sin(pitch), -Math.cos(pitch) * Math.cos(yaw));
    }

    public static Vec3 cameraPosition(Vec3 origin) {
        return origin.add(axis().scale(cameraDistance()));
    }

    /* ------------------------------------------------------------------ camera distance */

    /**
     * Distance between the player and the camera.
     *
     * <p>It depends on the visible height <b>only</b> - never on the terrain. The bottom edge of the
     * visible slab sits at {@code sin(45) * (distance - zoom / 2)} above the player's centre and has to
     * stay above the ground, otherwise the rays along the bottom of the screen start inside solid rock and
     * you see straight through the world; growing the distance with the zoom guarantees that.
     *
     * <p>Being a pure function of the zoom means the camera can never move on its own: in an orthographic
     * projection moving along the view axis does not change the picture at all, so the image is completely
     * stable while walking around. (Vanilla's third person camera, by contrast, slides in when something is
     * behind the player.)
     */
    public static float cameraDistance() {
        return Math.max(CAMERA_DISTANCE, zoom * 0.5F + 4.0F);
    }

    /**
     * Far bound of the hidden volume. Slightly past the camera plane, otherwise the block the camera itself
     * sits in - which is exactly the rock that makes an underground view unreadable - would be the only one
     * left standing.
     */
    public static float hideFarBound() {
        return (float) (cameraDistanceUsed + 2.0D);
    }

    /** Camera distance actually used this frame, after { @link #placeCamera} may have pulled it in. */
    public static boolean cameraInRock() {
        return cameraInRock;
    }

    public static double cameraDistanceUsed() {
        return cameraDistanceUsed;
    }

    /**
     * Places the camera on the view axis, pulling it in out of solid rock if needed.
     *
     * <p>In an orthographic projection moving the camera along the view axis changes nothing in the picture -
     * the projection only keeps the components perpendicular to the axis - so the camera can be moved freely
     * along that line. This matters underground: a camera buried in stone sees nothing at all, because the
     * chunk mesher culls every face between two solid blocks, so rock interiors have no geometry. Pulling the
     * camera into the player's own air pocket is what makes a tunnel view possible.
     */
    public static Vec3 placeCamera(Vec3 origin, net.minecraft.world.level.BlockGetter level, float partialTick) {
        framePartialTick = partialTick;
        double ideal = cameraDistance();
        Vec3 axis = axis();
        // Walk OUTWARD from the player and stop at the first solid sample: the camera must never end up
        // inside rock. Rock interiors have no faces at all (the mesher culls every face between two solid
        // blocks), so a camera buried in stone renders nothing but the clear colour - a black screen that
        // looks identical with the occlusion rule on or off.
        double target = ideal;
        if (OrthoConfig.pullCameraOutOfRock()) {
            target = 0.5D;
            for (double distance = 0.5D; distance <= ideal; distance += 0.25D) {
                if (solidAt(level, origin.add(axis.scale(distance)))) {
                    break;
                }
                target = distance;
            }
        }
        // The near plane's bottom edge sits 0.707*(halfH - d) below the player's centre. Push the camera far
        // enough that the edge stays above the floor - along an orthographic view axis this costs nothing
        // visually, it only moves the clipping planes, and it removes the black band along the bottom.
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            // Interpolated feet on purpose: `origin` is the interpolated centre, so deriving the feet from it
            // keeps (origin.y - feetY) a constant (half the hitbox height). Using the raw tick value here made
            // this term oscillate every tick, which jittered the camera distance - and with it the near/far
            // clip planes and the far bound of the hidden volume: blocks popped in and out.
            double feetY = mc.player.getY();
            if (framePartialTick >= 0.0F) {
                feetY = origin.y - mc.player.getBbHeight() * 0.5D;
            }
            // The near plane eats the ground up to NEAR blocks in front of it, so it needs its own term
            // here; without it the cut line stays on screen (it is what made a razor-straight line across
            // the terrain).
            double minForNearPlane = effectiveHalfHeight() + NEAR
                    - (origin.y - feetY) / Math.sin(Math.toRadians(PITCH)) + 0.2D;
            target = Math.max(target, minForNearPlane);
        }
        // Pull in immediately (never sit inside rock for a frame) and ease only when moving back out. Moving
        // along the axis does not change an orthographic picture, so this is invisible apart from the
        // clipping planes it controls.
        if (target < smoothedDistance) {
            smoothedDistance = target;
        } else {
            smoothedDistance += (target - smoothedDistance) * 0.25D;
            if (target - smoothedDistance < 0.02D) {
                smoothedDistance = target;
            }
        }
        cameraDistanceUsed = smoothedDistance;
        Vec3 placed = origin.add(axis.scale(smoothedDistance));
        cameraInRock = solidAt(level, placed);
        return placed;
    }

    private static boolean solidAt(net.minecraft.world.level.BlockGetter level, Vec3 point) {
        BlockPos pos = BlockPos.containing(point);
        return !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
    }

    /**
     * Half of the visible world height (the orthographic projection's half height).
     *
     * <p>Besides the zoom this is limited by how tall the air pocket around the camera is: the bottom edge of
     * the near plane sits {@code 0.707 * halfHeight} below the camera, so in a two block high tunnel a wide
     * view puts that edge inside the floor - and every ray starting there renders as nothing. Keeping the
     * near plane inside the pocket is what removes that black band along the bottom of the screen.
     */
    public static float effectiveHalfHeight() {
        float half = Math.max(0.5F, zoom) * 0.5F;
        if (OrthoConfig.clampViewToAirPocket()) {
            half = Math.min(half, (float) (cameraDistanceUsed + 1.0D));
        }
        return Math.max(0.5F, half);
    }

    /* ------------------------------------------------------------------ occlusion hiding */

    /** Whether the occlusion hiding is in effect right now (X toggles it). */
    public static boolean hideActive() {
        return active && coneEnabled;
    }

    /**
     * The hidden volume is the <b>square around the player, projected towards the camera</b>: a prism whose
     * cross-section lies in the plane perpendicular to the view axis, is centred on the player and has the
     * radius {@code boxRadius}. It is extruded from the player's plane to just past the
     * camera's plane.
     *
     * <p>Two extra limits keep it minimal: only blocks <b>above</b> the player's head plus a margin are
     * hidden, and nothing before the player's plane is touched. Because the cross-section is measured in
     * the view plane and the camera sits on the axis, the camera is always inside the prism - which is what
     * keeps the rock around the camera from blocking the view, at any zoom level.
     *
     * <p>Hidden blocks are also skipped by the cursor pick, so you can only interact with blocks that are
     * actually drawn. Visible blocks inside your reach keep working normally.
     */
    /** Interpolated feet height, consistent with {@link #originOf} so the hidden volume does not jitter. */
    public static double feetOf(net.minecraft.world.entity.Entity entity, float partialTick) {
        return entity.getPosition(partialTick).y;
    }

    public static boolean isHiddenBlock(Vec3 blockCentre, Vec3 playerCentre, Vec3 axis, double playerFeetY) {
        if (!hideActive()) {
            return false;
        }
        if (blockCentre.y <= playerFeetY + OrthoConfig.minHeightAboveFeet()) {
            return false;
        }
        double offsetX = blockCentre.x - playerCentre.x;
        double offsetY = blockCentre.y - playerCentre.y;
        double offsetZ = blockCentre.z - playerCentre.z;
        // The tube is a corridor towards the camera only: nothing on the far side of the player's vertical
        // plane (anything behind them) may ever be hidden, no matter how wide the tube is.
        Vec3 horizontal = new Vec3(axis.x, 0.0D, axis.z).normalize();
        if (offsetX * horizontal.x + offsetZ * horizontal.z <= 0.0D) {
            return false;
        }
        double along = offsetX * axis.x + offsetY * axis.y + offsetZ * axis.z;
        if (along < OrthoConfig.nearOffset() || along >= hideFarBound()) {
            return false;
        }
        double offsetInPlaneSqr = offsetX * offsetX + offsetY * offsetY + offsetZ * offsetZ - along * along;
        // Never hide the block column the player is standing in - expressed in screen space: a half block
        // radius in the plane perpendicular to the view axis. The old world-axis test (|offset.x| <= 0.5 &&
        // |offset.z| <= 0.5) missed screen-aligned geometry, so an illusion bridge under the player was
        // removed while they were standing on it.
        if (offsetInPlaneSqr <= 0.25D) {
            return false;
        }
        // Circular cross-section in the plane perpendicular to the view axis.
        double radius = OrthoConfig.boxRadius();
        return offsetInPlaneSqr <= radius * radius;
    }

    /** Block-entity variant, so chests / signs / furnaces in that volume disappear together with the blocks. */
    public static boolean isHiddenBlockEntity(Vec3 blockCentre, float partialTick) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return false;
        }
        return isHiddenBlock(blockCentre, originOf(mc.player, partialTick), axis(), feetOf(mc.player, partialTick));
    }
    /* ------------------------------------------------------------------ cursor picking */

    /**
     * Replaces vanilla's crosshair pick while the orthographic view is active: the target becomes whatever
     * is under the mouse cursor instead of what the player happens to be looking at.
     *
     * <p>Because an orthographic projection has no perspective divide, the cursor's screen position maps
     * directly onto an offset from the camera: the pick ray starts at {@code camera + right * x + up * y}
     * and runs along the view direction.
     */
    public static void applyCursorPick(float partialTick) {
        Minecraft mc = Minecraft.getInstance();
        if (!active || mc.level == null || mc.player == null || mc.screen != null) {
            return;
        }
        Window window = mc.getWindow();
        if (window.getWidth() <= 0 || window.getHeight() <= 0) {
            return;
        }
        MouseHandler mouse = mc.mouseHandler;
        // xpos()/ypos() are in window coordinates, so the NDC must use the screen size - using the
        // framebuffer size shifts the pick ray whenever the two differ (content scale != 1).
        float ndcX = (float) (2.0D * (mouse.xpos() / (double) window.getScreenWidth()) - 1.0D);
        float ndcY = (float) (1.0D - 2.0D * (mouse.ypos() / (double) window.getScreenHeight()));
        float halfH = effectiveHalfHeight();
        float halfW = halfH * (float) window.getWidth() / (float) window.getHeight();

        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 cameraPos = camera.getPosition();
        Vector3f look = camera.getLookVector();
        Vector3f up = camera.getUpVector();
        Vector3f left = camera.getLeftVector();
        // getLeftVector() points to the viewer's left; screen +x is to the right.
        Vec3 rayOrigin = cameraPos
                .add(-left.x() * (ndcX * halfW), -left.y() * (ndcX * halfW), -left.z() * (ndcX * halfW))
                .add(up.x() * (ndcY * halfH), up.y() * (ndcY * halfH), up.z() * (ndcY * halfH));
        Vec3 direction = new Vec3(look.x(), look.y(), look.z());

        double blockRange = mc.player.blockInteractionRange();
        double entityRange = mc.player.entityInteractionRange();
        double maxLength = cameraDistanceUsed + Math.max(blockRange, entityRange) + 1.0D;
        Vec3 rayEnd = rayOrigin.add(direction.scale(maxLength));

        Vec3 playerCentre = originOf(mc.player, partialTick);
        Vec3 viewAxis = axis();
        double playerFeetY = mc.player.getY();

        // Hidden blocks are not drawn, so they must not be clickable either. The ray starts at the camera,
        // which is usually inside the hidden volume, so skip forward block by block until something visible
        // is reached. Stepping to the far side of each skipped block (instead of nudging by a tiny epsilon)
        // guarantees progress even when the ray only grazes a block's corner.
        BlockHitResult blockHit = null;
        Vec3 from = rayOrigin;
        for (int attempt = 0; attempt < 96; attempt++) {
            BlockHitResult hit = mc.level.clip(new ClipContext(from, rayEnd, ClipContext.Block.OUTLINE,
                    ClipContext.Fluid.NONE, mc.player));
            if (hit.getType() != HitResult.Type.BLOCK) {
                break;
            }
            BlockPos hitPos = hit.getBlockPos();
            if (!isHiddenBlock(Vec3.atCenterOf(hitPos), playerCentre, viewAxis, playerFeetY)
                    && surfaceIsDrawn(mc.level, hitPos, hit.getDirection())) {
                blockHit = hit;
                break;
            }
            AABB box = new AABB(hitPos.getX(), hitPos.getY(), hitPos.getZ(),
                    hitPos.getX() + 1.0D, hitPos.getY() + 1.0D, hitPos.getZ() + 1.0D);
            from = from.add(direction.scale(Math.max(exitDistance(box, from, direction), 0.0D) + 1.0E-3D));
        }

        EntityHitResult entityHit = ProjectileUtil.getEntityHitResult(mc.player, rayOrigin, rayEnd,
                new AABB(rayOrigin, rayEnd).inflate(1.0D),
                entity -> !entity.isSpectator() && entity.isPickable(), maxLength * maxLength);

        Vec3 eye = mc.player.getEyePosition(partialTick);
        double blockDistanceSqr = blockHit == null ? Double.MAX_VALUE : blockHit.getLocation().distanceToSqr(eye);
        double entityDistanceSqr = entityHit == null ? Double.MAX_VALUE : entityHit.getLocation().distanceToSqr(eye);
        boolean blockInRange = blockDistanceSqr <= blockRange * blockRange;
        boolean entityInRange = entityDistanceSqr <= entityRange * entityRange;

        if (entityInRange && (!blockInRange || entityDistanceSqr <= blockDistanceSqr)) {
            mc.hitResult = entityHit;
            mc.crosshairPickEntity = entityHit.getEntity();
        } else if (blockInRange) {
            mc.hitResult = blockHit;
            mc.crosshairPickEntity = null;
        } else {
            mc.hitResult = BlockHitResult.miss(rayEnd, Direction.getNearest(direction.x, direction.y, direction.z),
                    BlockPos.containing(rayEnd));
            mc.crosshairPickEntity = null;
        }

        updateCursorAim(mc, rayOrigin, direction, partialTick);
    }

    /**
     * Records where the cursor points. The player is only turned towards it while standing still; while
     * walking the body follows the movement direction instead (see {@link #onRenderFrame}).
     */
    private static void updateCursorAim(Minecraft mc, Vec3 rayOrigin, Vec3 direction, float partialTick) {
        Vec3 aim = mc.hitResult.getType() == HitResult.Type.MISS
                // Nothing under the cursor: aim at the cursor's own position on the player's plane.
                ? rayOrigin.add(direction.scale(cameraDistance()))
                : mc.hitResult.getLocation();
        Vec3 look = aim.subtract(mc.player.getEyePosition(partialTick));
        if (look.lengthSqr() < 1.0E-6D) {
            return;
        }
        double horizontal = Math.sqrt(look.x * look.x + look.z * look.z);
        cursorAimYaw = (float) Math.toDegrees(Math.atan2(-look.x, look.z));
        cursorAimPitch = (float) (-Math.toDegrees(Math.atan2(look.y, horizontal)));
        cursorAimValid = true;
    }
    /** Distance along {@code direction} at which a ray leaving {@code origin} exits {@code box}. */
    private static double exitDistance(AABB box, Vec3 origin, Vec3 direction) {
        double distance = Double.MAX_VALUE;
        distance = Math.min(distance, faceDistance(box.minX, box.maxX, origin.x, direction.x));
        distance = Math.min(distance, faceDistance(box.minY, box.maxY, origin.y, direction.y));
        distance = Math.min(distance, faceDistance(box.minZ, box.maxZ, origin.z, direction.z));
        return distance == Double.MAX_VALUE ? 0.0D : distance;
    }

    private static double faceDistance(double min, double max, double origin, double direction) {
        if (Math.abs(direction) < 1.0E-7D) {
            return Double.MAX_VALUE;
        }
        return ((direction > 0.0D ? max : min) - origin) / direction;
    }
    /* ------------------------------------------------------------------ highlight */

    /** Bright, thick highlight for the block / entity under the cursor. */
    @SubscribeEvent
    public static void onHighlightBlock(RenderHighlightEvent.Block event) {
        if (!active) {
            return;
        }
        // (RenderHighlightEvent.Block is cancellable; the base RenderHighlightEvent is not.)
        event.setCanceled(true);
        if (!OrthoConfig.highlightBlocks()) {
            // Switched off: no highlight at all, not even vanilla's thin one.
            return;
        }
        // We draw a much louder highlight than vanilla's 1px black wireframe.

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }
        BlockPos pos = event.getTarget().getBlockPos();
        BlockState state = mc.level.getBlockState(pos);
        VoxelShape shape = state.getShape(mc.level, pos);
        if (shape.isEmpty()) {
            return;
        }
        AABB box = shape.bounds().move(pos);
        Vec3 cam = event.getCamera().getPosition();

        // No translucent fill: an alpha box reads as a "half shown" block. The outline alone is unambiguous,
        // and it is only ever drawn around a block you can actually see (see surfaceIsDrawn).
        LevelRenderer.renderVoxelShape(event.getPoseStack(), event.getMultiBufferSource().getBuffer(HighlightTypes.LINES),
                shape, pos.getX() - cam.x, pos.getY() - cam.y, pos.getZ() - cam.z, 1.0F, 0.90F, 0.25F, 1.0F, true);
    }

    @SubscribeEvent
    public static void onHighlightEntity(RenderHighlightEvent.Entity event) {
        if (!active || !OrthoConfig.highlightEntities()) {
            return;
        }
        Entity entity = event.getTarget().getEntity();
        Vec3 cam = event.getCamera().getPosition();
        AABB box = entity.getBoundingBox().inflate(0.03).move(-cam.x, -cam.y, -cam.z);
        highlight(event.getPoseStack(), event.getMultiBufferSource(), box, 1.0F, 0.25F, 0.10F);
    }

    private static void highlight(PoseStack pose, MultiBufferSource buffers, AABB box, float r, float g, float b) {
        // Filled translucent box: always obvious, and GPU line width is unreliable in a core profile.
        VertexConsumer quads = buffers.getBuffer(HighlightTypes.FILL);
        PoseStack.Pose entry = pose.last();
        float x1 = (float) box.minX, y1 = (float) box.minY, z1 = (float) box.minZ;
        float x2 = (float) box.maxX, y2 = (float) box.maxY, z2 = (float) box.maxZ;
        float a = 0.30F;
        // -X, +X
        quad(quads, entry, x1, y1, z1, x1, y1, z2, x1, y2, z2, x1, y2, z1, r, g, b, a);
        quad(quads, entry, x2, y1, z2, x2, y1, z1, x2, y2, z1, x2, y2, z2, r, g, b, a);
        // -Y, +Y
        quad(quads, entry, x1, y1, z2, x2, y1, z2, x2, y1, z1, x1, y1, z1, r, g, b, a);
        quad(quads, entry, x1, y2, z1, x2, y2, z1, x2, y2, z2, x1, y2, z2, r, g, b, a);
        // -Z, +Z
        quad(quads, entry, x1, y1, z1, x2, y1, z1, x2, y2, z1, x1, y2, z1, r, g, b, a);
        quad(quads, entry, x2, y1, z2, x1, y1, z2, x1, y2, z2, x2, y2, z2, r, g, b, a);

        // Bright edges on top.
        LevelRenderer.renderLineBox(pose, buffers.getBuffer(HighlightTypes.LINES), box, r, g, b, 1.0F);
    }

    /* ------------------------------------------------------------------ ground marker */

    /**
     * Fills every empty pixel with a dark rock tone.
     *
     * <p>An orthographic ray that leaves the world - easy underground, where nothing below the floor has any
     * geometry - hits the sky box and shows the sky through the ground. Painting a dark plane just under the
     * lowest build height makes that read as "bottom of the world" instead of a hole to the sky. It sits
     * below every block, so it is invisible whenever there is terrain in the way.
     */
    private static void drawVoidFloor(RenderLevelStageEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }
        // A plane perpendicular to the view axis, just inside the far plane: it is behind every piece of
        // geometry, so it only shows where nothing was drawn, and being screen-parallel it cannot introduce a
        // straight line of its own. A world-horizontal floor does (its far clip is a horizontal line).
        float far = Math.min(FAR, mc.options.getEffectiveRenderDistance() * 16.0F);
        float depth = far * 0.98F;
        Vec3 axis = axis();
        Vec3 right = new Vec3(0.0D, 1.0D, 0.0D).cross(axis).normalize();
        Vec3 up = axis.cross(right).normalize();
        Vec3 centre = axis.scale(depth);
        double r = depth;
        Vec3 a = centre.add(right.scale(r)).add(up.scale(r));
        Vec3 b = centre.add(right.scale(-r)).add(up.scale(r));
        Vec3 c = centre.add(right.scale(-r)).add(up.scale(-r));
        Vec3 d = centre.add(right.scale(r)).add(up.scale(-r));
        PoseStack pose = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        quad(buffers.getBuffer(HighlightTypes.FILL), pose.last(),
                (float) a.x, (float) a.y, (float) a.z,
                (float) b.x, (float) b.y, (float) b.z,
                (float) c.x, (float) c.y, (float) c.z,
                (float) d.x, (float) d.y, (float) d.z,
                0.055F, 0.055F, 0.065F, 1.0F);
        buffers.endBatch(HighlightTypes.FILL);
    }
    /**
     * Marks the top face of the block directly below the player, so their position stays readable while
     * flying (the player themselves is a tiny dot once zoomed out).
     */
    @SubscribeEvent
    public static void onRenderStage(RenderLevelStageEvent event) {
        if (!active) {
            return;
        }
        // The cursor pick has to run AFTER Camera#setup, otherwise it uses the previous frame's camera.
        // GameRenderer.pick runs before it, so the target is refreshed here instead.
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_SKY) {
            // Rock interiors have no faces at all (the mesher culls every face between two solid blocks), so
            // wherever the camera cannot see anything the frame keeps the clear colour - a black hole in an
            // otherwise solid world. When the camera is actually buried, the sky is invisible anyway, so
            // repainting the background with a dark rock tone costs nothing and reads as unlit stone.
            // No background clear here: a screen-wide colour switch keyed on a heightmap test flips the whole
            // picture when you step under or out of an overhang, and it erases the real sky. The void floor
            // quad (drawVoidFloor) handles the only case that needs covering.
            applyCursorPick(framePartialTick);
        }
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            drawVoidFloor(event);
        }
        if (!OrthoConfig.groundMarker()
                || event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.screen != null) {
            return;
        }
        Vec3 feet = mc.player.position();
        BlockHitResult hit = mc.level.clip(new ClipContext(feet.add(0.0D, 0.05D, 0.0D),
                feet.add(0.0D, -GROUND_MARKER_DEPTH, 0.0D),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
        if (hit.getType() != HitResult.Type.BLOCK) {
            return;
        }
        BlockPos pos = hit.getBlockPos();
        double top = pos.getY() + 1.0D + 0.02D;
        Vec3 cam = event.getCamera().getPosition();
        AABB box = new AABB(pos.getX() + 0.16D, top, pos.getZ() + 0.16D,
                pos.getX() + 0.84D, top, pos.getZ() + 0.84D)
                .move(-cam.x, -cam.y, -cam.z);

        PoseStack pose = event.getPoseStack();
        MultiBufferSource buffers = mc.renderBuffers().bufferSource();
        VertexConsumer quads = buffers.getBuffer(HighlightTypes.FILL);
        PoseStack.Pose entry = pose.last();
        quad(quads, entry,
                (float) box.minX, (float) box.minY, (float) box.minZ,
                (float) box.maxX, (float) box.minY, (float) box.minZ,
                (float) box.maxX, (float) box.minY, (float) box.maxZ,
                (float) box.minX, (float) box.minY, (float) box.maxZ,
                0.25F, 0.90F, 1.0F, 0.35F);
        LevelRenderer.renderLineBox(pose, buffers.getBuffer(HighlightTypes.LINES), box, 0.25F, 0.90F, 1.0F, 1.0F);
        if (buffers instanceof MultiBufferSource.BufferSource bufferSource) {
            bufferSource.endBatch();
        }
    }

    /* ------------------------------------------------------------------ render types */

    private static void quad(VertexConsumer consumer, PoseStack.Pose entry,
                             float ax, float ay, float az, float bx, float by, float bz,
                             float cx, float cy, float cz, float dx, float dy, float dz,
                             float r, float g, float b, float a) {
        consumer.addVertex(entry, ax, ay, az).setColor(r, g, b, a);
        consumer.addVertex(entry, bx, by, bz).setColor(r, g, b, a);
        consumer.addVertex(entry, cx, cy, cz).setColor(r, g, b, a);
        consumer.addVertex(entry, dx, dy, dz).setColor(r, g, b, a);
    }

    /**
     * Render types are built lazily so nothing touches the renderer while the mod is still being constructed.
     */
    private static final class HighlightTypes {
        static final RenderType LINES = RenderType.create(
                "ortho_sight_highlight_lines",
                DefaultVertexFormat.POSITION_COLOR_NORMAL,
                VertexFormat.Mode.LINES,
                1536,
                RenderType.CompositeState.builder()
                        .setShaderState(RenderStateShard.RENDERTYPE_LINES_SHADER)
                        .setLineState(new RenderStateShard.LineStateShard(OptionalDouble.empty()))
                        // Without this the wireframe z-fights with the block's own faces and comes out dashed.
                        .setLayeringState(RenderStateShard.VIEW_OFFSET_Z_LAYERING)
                        .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                        .setOutputState(RenderStateShard.MAIN_TARGET)
                        .setWriteMaskState(RenderStateShard.COLOR_DEPTH_WRITE)
                        .setCullState(RenderStateShard.NO_CULL)
                        .createCompositeState(false));

        /**
         * Translucent fill. Deliberately {@code COLOR_WRITE} only (no depth write) so the box never hides
         * the entity or item behind it.
         */
        static final RenderType FILL = RenderType.create(
                "ortho_sight_highlight_fill",
                DefaultVertexFormat.POSITION_COLOR,
                VertexFormat.Mode.QUADS,
                1536,
                RenderType.CompositeState.builder()
                        .setShaderState(RenderStateShard.POSITION_COLOR_SHADER)
                        .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                        .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
                        .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                        .setCullState(RenderStateShard.NO_CULL)
                        .setOutputState(RenderStateShard.MAIN_TARGET)
                        .createCompositeState(false));
    }
}
