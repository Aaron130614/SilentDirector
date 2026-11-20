package com.example.silentdirector;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.List;
import java.util.Locale;

/**
 * Registers the SilentDirector command tree on the server command dispatcher.
 *
 * <p>Only the production status command is kept; the temporary spawn/pressure
 * test commands from the prototype are gone.</p>
 */
public final class SilentDirectorCommand {
	private static final String ROOT_LITERAL = "silentdirector";
	private static final String STATUS_LITERAL = "status";

	private SilentDirectorCommand() {
	}

	/** Registers the command tree; must run during mod initialization. */
	public static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			LiteralArgumentBuilder<ServerCommandSource> root = CommandManager.literal(ROOT_LITERAL)
					.requires(source -> source.hasPermissionLevel(0))
					.then(CommandManager.literal(STATUS_LITERAL).executes(SilentDirectorCommand::executeStatus));

			dispatcher.register(root);
		});
	}

	private static int executeStatus(CommandContext<ServerCommandSource> context) {
		ServerCommandSource source = context.getSource();
		List<ServerPlayerEntity> players = source.getServer().getPlayerManager().getPlayerList();

		source.sendFeedback(() -> Text.literal("SilentDirector status"), false);
		source.sendFeedback(() -> Text.literal("online players: " + players.size()), false);

		for (ServerPlayerEntity player : players) {
			SilentDirectorState.Readiness readiness = SilentDirectorState.read(player);
			String name = player.getName().getString();

			source.sendFeedback(() -> Text.literal(String.format(Locale.ROOT,
					" - %s readiness=%.3f (attack_damage=%.2f, armor=%.2f)",
					name, readiness.readiness(), readiness.attackDamage(), readiness.armor())), false);
		}

		source.sendFeedback(() -> Text.literal(String.format(Locale.ROOT,
				"global pressure=%.3f", SilentDirectorState.pressure())), false);

		Directive directive = Director.currentDirective();
		if (directive == null) {
			source.sendFeedback(() -> Text.literal(
					"current directive: role=none, intensity=0.0, target=none"), false);
		} else {
			source.sendFeedback(() -> Text.literal(String.format(Locale.ROOT,
					"current directive: role=%s, intensity=%.1f, target=%s",
					directive.role(), directive.intensity(), directive.target())), false);
		}

		return Command.SINGLE_SUCCESS;
	}
}
