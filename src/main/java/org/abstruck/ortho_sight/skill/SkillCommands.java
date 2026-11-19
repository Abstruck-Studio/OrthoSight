package org.abstruck.ortho_sight.skill;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.abstruck.ortho_sight.OrthoSight;

/**
 * /orthosight probe [dx dz] - prints what the blink rule sees for the executing player, using exactly the code
 * path the server runs when it handles a blink request. The direction defaults to +Z, so the rule can be tested
 * while standing still.
 */
@EventBusSubscriber(modid = OrthoSight.MODID)
public final class SkillCommands {

    private SkillCommands() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("orthosight")
                .then(Commands.literal("probe")
                        .executes(context -> probe(context.getSource(), 0.0F, 1.0F))
                        .then(Commands.argument("dx", FloatArgumentType.floatArg(-1.0F, 1.0F))
                                .then(Commands.argument("dz", FloatArgumentType.floatArg(-1.0F, 1.0F))
                                        .executes(context -> probe(context.getSource(),
                                                FloatArgumentType.getFloat(context, "dx"),
                                                FloatArgumentType.getFloat(context, "dz")))))));
    }

    private static int probe(CommandSourceStack source, float dirX, float dirZ)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        Player player = source.getPlayerOrException();
        BlinkSkill.Target target = BlinkSkill.findTarget(player, dirX, dirZ);
        String found = target == null
                ? "none"
                : String.format("(%d,%d,%d) top=%.3f", target.pos().getX(), target.pos().getY(),
                        target.pos().getZ(), target.top());
        String message = String.format(
                "[OrthoSight] enabled=%s feetY=%.3f onGround=%s sneaking=%s dir=(%.2f,%.2f) target=%s range=%.1f rise=%.2f drop=%.2f",
                BlinkSkill.enabled(player), player.getY(), player.onGround(), player.isShiftKeyDown(), dirX, dirZ,
                found, SkillConfig.range(), SkillConfig.maxRise(), SkillConfig.maxDrop());
        source.sendSuccess(() -> Component.literal(message), false);
        return 1;
    }
}