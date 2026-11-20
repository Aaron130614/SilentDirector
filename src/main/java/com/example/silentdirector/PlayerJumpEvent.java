package com.example.silentdirector;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Server-side jump event for Fabric 1.21.1.
 *
 * <p>Fabric API 0.116 does not expose a player jump callback, so this bridge
 * detects the ground-to-rising transition once per airborne period.</p>
 */
public final class PlayerJumpEvent {
	public static final Event<Jump> EVENT = EventFactory.createArrayBacked(Jump.class,
			listeners -> player -> {
				for (Jump listener : listeners) {
					listener.afterJump(player);
				}
			});

	private static final Set<UUID> AIRBORNE_PLAYERS = new HashSet<>();
	private static final Map<UUID, Double> LAST_Y = new HashMap<>();
	private static MinecraftServer activeServer;

	private PlayerJumpEvent() {
	}

	/** Advances jump detection once at the end of each server tick. */
	public static void tick(MinecraftServer server) {
		if (activeServer != server) {
			clear();
			activeServer = server;
		}

		Set<UUID> onlinePlayers = new HashSet<>();
		for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
			UUID uuid = player.getUuid();
			onlinePlayers.add(uuid);

			double currentY = player.getY();
			Double previousY = LAST_Y.put(uuid, currentY);
			boolean onGround = player.isOnGround();
			boolean wasAirborne = AIRBORNE_PLAYERS.contains(uuid);

			if (onGround) {
				AIRBORNE_PLAYERS.remove(uuid);
				continue;
			}

			if (previousY != null
					&& !wasAirborne
					&& (player.getVelocity().y > 0.01
					|| currentY > previousY + 0.01)) {
				AIRBORNE_PLAYERS.add(uuid);
				EVENT.invoker().afterJump(player);
			} else if (previousY == null) {
				AIRBORNE_PLAYERS.add(uuid);
			}
		}

		AIRBORNE_PLAYERS.removeIf(uuid -> !onlinePlayers.contains(uuid));
		LAST_Y.keySet().removeIf(uuid -> !onlinePlayers.contains(uuid));
	}

	private static void clear() {
		AIRBORNE_PLAYERS.clear();
		LAST_Y.clear();
		activeServer = null;
	}

	@FunctionalInterface
	public interface Jump {
		void afterJump(ServerPlayerEntity player);
	}
}
