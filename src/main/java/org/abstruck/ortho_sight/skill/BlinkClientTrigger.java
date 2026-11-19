package org.abstruck.ortho_sight.skill;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.abstruck.ortho_sight.OrthoSight;
import org.abstruck.ortho_sight.illusion.IllusionSupport;
import org.abstruck.ortho_sight.client.OrthoClient;

/**
 * Client side trigger: while the orthographic view is on and the player is sneaking and walking, ask the server
 * to blink as soon as the rule finds a step ordinary movement cannot make.
 *
 * <p>The direction comes from the <b>input vector</b>, not from the entity's delta movement: {@code Entity#move}
 * zeroes the delta of the collided axis, so walking straight into a step or a wall would leave a zero delta
 * exactly in the cases this skill exists for. {@code Input#leftImpulse / forwardImpulse} are what
 * {@code Entity#moveRelative} feeds into the delta in the first place, and the mod's InputMixin has already
 * pre-rotated them, so rotating by the body yaw yields the on-screen walking direction.
 *
 * <p>Lives in the skill package so the client-only build does not ship it. It only says <i>when</i> and
 * <i>which way</i>; the destination is computed by the server.
 */
@EventBusSubscriber(modid = OrthoSight.MODID, value = Dist.CLIENT)
public final class BlinkClientTrigger {

    private static int cooldown;

    private BlinkClientTrigger() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.level == null || minecraft.screen != null) {
            return;
        }
        if (cooldown > 0) {
            cooldown--;
            return;
        }
        if (!OrthoClient.active || !BlinkSkill.enabled(player)) {
            return;
        }
        Vec3 direction = inputDirection(player);
        if (direction == null) {
            return; // no movement keys held
        }
        if (BlinkSkill.findTarget(player, direction.x, direction.z) == null) {
            return;
        }
        BlinkRequest.send((float) direction.x, (float) direction.z);
        cooldown = Math.max(1, SkillConfig.cooldownTicks());
    }

    /** The same vector {@code Entity#getInputVector} builds, in world space. */
    private static Vec3 inputDirection(LocalPlayer player) {
        Input input = player.input;
        if (input == null) {
            return null;
        }
        double sideways = input.leftImpulse;
        double forward = input.forwardImpulse;
        if (sideways == 0.0D && forward == 0.0D) {
            return null;
        }
        double length = Math.sqrt(sideways * sideways + forward * forward);
        float sin = Mth.sin(player.getYRot() * Mth.DEG_TO_RAD);
        float cos = Mth.cos(player.getYRot() * Mth.DEG_TO_RAD);
        double x = (sideways * cos - forward * sin) / length;
        double z = (forward * cos + sideways * sin) / length;
        return new Vec3(x, 0.0D, z);
    }
}