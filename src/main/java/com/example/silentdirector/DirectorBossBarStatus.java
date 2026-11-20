package com.example.silentdirector;

import net.minecraft.entity.boss.BossBar;
import net.minecraft.entity.boss.ServerBossBar;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Mirrors the current director state in a vanilla server boss bar.
 */
public final class DirectorBossBarStatus {
	private static final int UPDATE_INTERVAL_TICKS = 20;

	private static MinecraftServer activeServer;
	private static ServerBossBar bossBar;
	private static int ticksSinceUpdate;

	private DirectorBossBarStatus() {
	}

	/** Advances the boss bar clock; called from the server tick loop. */
	public static void tick(MinecraftServer server) {
		if (activeServer != server) {
			clearBossBar();
			activeServer = server;
		}

		List<ServerPlayerEntity> players = server.getPlayerManager().getPlayerList();
		if (players.isEmpty()) {
			clearBossBar();
			return;
		}

		ticksSinceUpdate++;
		if (ticksSinceUpdate < UPDATE_INTERVAL_TICKS) {
			return;
		}

		ticksSinceUpdate = 0;
		updateBossBar(server, players);
	}

	/** Immediately refreshes the boss bar after an out-of-band state change. */
	public static void refresh(MinecraftServer server) {
		if (activeServer != server) {
			clearBossBar();
			activeServer = server;
		}

		List<ServerPlayerEntity> players = server.getPlayerManager().getPlayerList();
		if (players.isEmpty()) {
			clearBossBar();
			return;
		}

		ticksSinceUpdate = 0;
		updateBossBar(server, players);
	}

	private static void updateBossBar(MinecraftServer server, List<ServerPlayerEntity> players) {
		Directive directive = Director.currentDirective();
		String role = directive == null
				? "无"
				: roleLabel(directive.role());
		String target = resolveTargetName(server, directive);
		float pressure = SilentDirectorState.pressure();
		float progress = Math.max(0.0f, Math.min(1.0f, pressure));
		String ruleLabel = DirectorRuleManager.currentRuleLabel();
		String rulePrefix = ruleLabel == null ? "" : "[规则: " + ruleLabel + "] ";

		ensureBossBar();
		if (directive != null && directive.transitionTicks() > 0) {
			bossBar.setName(Text.literal(rulePrefix + String.format(Locale.ROOT,
					"Director: 警告中 (%d ticks) | target: %s | pressure: %.2f",
					directive.transitionTicks(), target, pressure)));
		} else {
			bossBar.setName(Text.literal(rulePrefix + String.format(Locale.ROOT,
					"Director: %s | target: %s | pressure: %.2f",
					role, target, pressure)));
		}
		bossBar.setPercent(progress);
		bossBar.setColor(BossBar.Color.BLUE);
		bossBar.setStyle(BossBar.Style.PROGRESS);
		bossBar.setVisible(true);
		synchronizePlayers(players);
	}

	private static String roleLabel(Directive.Role role) {
		return switch (role) {
			case NEUTRAL -> "中立";
			case HUNTER -> "追猎者";
			case OBSERVER -> "观察者";
			case PROVIDER -> "投喂者";
		};
	}

	private static void ensureBossBar() {
		if (bossBar != null) {
			return;
		}

		bossBar = new ServerBossBar(Text.empty(), BossBar.Color.BLUE, BossBar.Style.PROGRESS);
	}

	private static void synchronizePlayers(List<ServerPlayerEntity> players) {
		Set<ServerPlayerEntity> onlinePlayers = new HashSet<>(players);

		for (ServerPlayerEntity player : List.copyOf(bossBar.getPlayers())) {
			if (!onlinePlayers.contains(player)) {
				bossBar.removePlayer(player);
			}
		}

		for (ServerPlayerEntity player : players) {
			bossBar.addPlayer(player);
		}
	}

	private static String resolveTargetName(MinecraftServer server, Directive directive) {
		UUID targetUuid = directive == null ? null : directive.target();
		if (targetUuid == null) {
			return "none";
		}

		ServerPlayerEntity target = server.getPlayerManager().getPlayer(targetUuid);
		return target == null ? "none" : target.getName().getString();
	}

	private static void clearBossBar() {
		if (bossBar != null) {
			bossBar.setVisible(false);
			bossBar.clearPlayers();
		}

		bossBar = null;
		activeServer = null;
		ticksSinceUpdate = 0;
	}
}
