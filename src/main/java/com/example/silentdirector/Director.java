package com.example.silentdirector;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * Produces one directive every 200 server ticks. It intentionally performs no
 * entity behavior and only stores the most recently produced directive.
 */
public final class Director {
	public static final int UPDATE_INTERVAL_TICKS = 200;
	public static final int NEUTRAL_TO_HUNTER_WARNING_TICKS = 40;
	private static final float PROVIDER_CHANCE = 0.30f;
	private static final Random RANDOM = new Random();

	private static int ticksSinceUpdate;
	private static int transitionTicks;
	private static volatile Directive currentDirective;

	private Director() {
	}

	/** Advances the director clock; called from the server tick loop. */
	public static void tick(MinecraftServer server) {
		tickTransition();
		ticksSinceUpdate++;
		if (ticksSinceUpdate < UPDATE_INTERVAL_TICKS) {
			return;
		}

		ticksSinceUpdate = 0;
		updateDirective(server);
	}

	private static void tickTransition() {
		if (transitionTicks <= 0) {
			return;
		}

		transitionTicks--;
		Directive directive = currentDirective;
		if (directive != null) {
			currentDirective = new Directive(directive.role(), directive.intensity(),
					directive.target(), transitionTicks);
		}
	}

	private static void updateDirective(MinecraftServer server) {
		List<ServerPlayerEntity> players = server.getPlayerManager().getPlayerList();
		UUID target = players.size() == 1 ? players.getFirst().getUuid() : null;

		if (players.size() != 1) {
			publish(Directive.Role.NEUTRAL, 0.5f, target);
			return;
		}

		ServerPlayerEntity player = players.getFirst();
		double readiness = SilentDirectorState.read(player).readiness();
		float pressure = SilentDirectorState.pressure();

		if (readiness < 0.3) {
			publish(Directive.Role.NEUTRAL, 0.5f, target);
		} else if (readiness >= 0.6 && pressure > 0.5f) {
			publish(Directive.Role.HUNTER, 1.0f, target);
		} else if (pressure >= 0.2f && pressure <= 0.5f && readiness > 0.3) {
			Directive.Role role = RANDOM.nextFloat() < PROVIDER_CHANCE
					? Directive.Role.PROVIDER
					: Directive.Role.OBSERVER;
			publish(role, 0.5f, target);
		} else {
			publish(Directive.Role.NEUTRAL, 0.5f, target);
		}
	}

	private static void publish(Directive.Role role, float intensity, UUID target) {
		Directive previous = currentDirective;
		if (role == Directive.Role.HUNTER && (previous == null || previous.role() != Directive.Role.HUNTER)) {
			transitionTicks = NEUTRAL_TO_HUNTER_WARNING_TICKS;
		} else if (role != Directive.Role.HUNTER) {
			transitionTicks = 0;
		}

		currentDirective = new Directive(role, intensity, target, transitionTicks);
	}

	/** Forces an immediate neutral directive and restarts the update interval. */
	public static void forceNeutral(UUID target) {
		ticksSinceUpdate = 0;
		transitionTicks = 0;
		currentDirective = new Directive(Directive.Role.NEUTRAL, 0.5f, target, 0);
	}

	/** Most recently produced directive, or {@code null} before the first update. */
	public static Directive currentDirective() {
		return currentDirective;
	}
}
