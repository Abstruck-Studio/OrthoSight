package org.abstruck.ortho_sight;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;

@Mod(OrthoSight.MODID)
public class OrthoSight {
    public static final String MODID = "ortho_sight";

    public OrthoSight(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.CLIENT, OrthoConfig.SPEC);
        registerSkillConfig(modContainer);
    }

    /**
     * Registers the server side gameplay config, if this build has it.
     *
     * <p>Resolved reflectively on purpose: the project produces two jars from one source tree, and the
     * client-only build does not contain the {@code skill} package at all. A direct reference here would make
     * that build fail to compile.
     */
    private static void registerSkillConfig(ModContainer modContainer) {
        try {
            Class<?> configClass = Class.forName("org.abstruck.ortho_sight.skill.SkillConfig");
            configClass.getMethod("register", ModContainer.class).invoke(null, modContainer);
        } catch (ClassNotFoundException clientOnlyBuild) {
            // Expected: this jar does not ship the gameplay feature.
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("OrthoSight: could not register the skill config", e);
        }
    }
}
