package com.example.silentdirector;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.network.ServerPlayerEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Server-side entrypoint for commands, director state, and player lifecycle events.
 */
public class SilentDirectorMod implements ModInitializer {
	public static final String MOD_ID = "silentdirector";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		SilentDirectorCommand.register();
		DirectorRuleManager.register();
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
			if (entity instanceof ServerPlayerEntity) {
				SilentDirectorState.addPressure(0.3f);
			}
		});
		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
			SilentDirectorState.setPressure(0.0f);
			Director.forceNeutral(newPlayer.getUuid());
			DirectorBossBarStatus.refresh(newPlayer.getServerWorld().getServer());
		});
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			SilentDirectorState.tick();
			PlayerJumpEvent.tick(server);
			Director.tick(server);
			DirectorRuleManager.tick(server);
			DirectorAgentController.tick(server);
			DirectorBossBarStatus.tick(server);
		});
		LOGGER.info("[SilentDirector] initialized: /silentdirector status registered");
	}
}
