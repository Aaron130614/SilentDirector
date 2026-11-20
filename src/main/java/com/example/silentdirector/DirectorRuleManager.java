package com.example.silentdirector;

import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;

import java.util.Random;

/**
 * Selects temporary rules, detects violations, and exposes the active rule to
 * the director boss bar.
 */
public final class DirectorRuleManager {
	public static final int RULE_INTERVAL_TICKS = 2400;
	public static final float VIOLATION_PRESSURE = 0.3f;
	private static final Rule[] RULE_POOL = Rule.values();
	private static final Random RANDOM = new Random();

	private static int ticksUntilNextRule;
	private static Rule currentRule;

	private DirectorRuleManager() {
	}

	/** Registers all rule violation listeners during mod initialization. */
	public static void register() {
		PlayerJumpEvent.EVENT.register(player -> {
			if (currentRule == Rule.NO_JUMP) {
				violate(player);
			}
		});

		PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> {
			if (currentRule == Rule.NO_BLOCK_BREAK && player instanceof ServerPlayerEntity serverPlayer) {
				violate(serverPlayer);
			}
		});

		AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
			if (!world.isClient()
					&& currentRule == Rule.NO_ATTACK
					&& player instanceof ServerPlayerEntity serverPlayer
					&& !serverPlayer.isSpectator()) {
				violate(serverPlayer);
			}

			return ActionResult.PASS;
		});
	}

	/** Advances the rule timer and publishes a new rule every 2400 ticks. */
	public static void tick(MinecraftServer server) {
		ticksUntilNextRule++;
		if (ticksUntilNextRule < RULE_INTERVAL_TICKS) {
			return;
		}

		ticksUntilNextRule = 0;
		currentRule = RULE_POOL[RANDOM.nextInt(RULE_POOL.length)];

		for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
			player.sendMessage(Text.literal("[规则: " + currentRule.label + "] 已生效"), false);
		}

		DirectorBossBarStatus.refresh(server);
	}

	/** Active rule label, or {@code null} before the first rule is published. */
	public static String currentRuleLabel() {
		return currentRule == null ? null : currentRule.label;
	}

	private static void violate(ServerPlayerEntity player) {
		SilentDirectorState.addPressure(VIOLATION_PRESSURE);
		player.sendMessage(Text.literal("你违反了规则"), false);
		DirectorBossBarStatus.refresh(player.getServerWorld().getServer());
	}

	private enum Rule {
		NO_JUMP("禁止跳跃"),
		NO_BLOCK_BREAK("禁止破坏方块"),
		NO_ATTACK("禁止攻击实体");

		private final String label;

		Rule(String label) {
			this.label = label;
		}
	}
}
