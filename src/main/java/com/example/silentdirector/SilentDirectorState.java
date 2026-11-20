package com.example.silentdirector;

import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * State pool for SilentDirector.
 *
 * <p>Holds the single global {@code pressure} value and derives per-player
 * {@code readiness} from the player's effective attributes. Nothing here is
 * persisted; state lives only for the lifetime of this server process.</p>
 */
public final class SilentDirectorState {
	/** Pressure lost per server tick. */
	public static final float PRESSURE_DECAY_PER_TICK = 0.001f;

	/** Inclusive pressure range accepted by manual adjustments. */
	public static final float MIN_PRESSURE = 0.0f;
	public static final float MAX_PRESSURE = 10.0f;

	/** Normalization ceiling for the player's attack damage contribution. */
	private static final double MAX_ATTACK_DAMAGE = 20.0;

	/** Normalization ceiling for the player's armor contribution. */
	private static final double MAX_ARMOR = 20.0;

	private static float pressure;

	private SilentDirectorState() {
	}

	/** Current global pressure (never negative). */
	public static float pressure() {
		return pressure;
	}

	/** Adds pressure and clamps the resulting value to the supported range. */
	public static void addPressure(float value) {
		pressure = clampPressure(pressure + value);
	}

	/** Sets pressure and clamps the value to the supported range. */
	public static void setPressure(float value) {
		pressure = clampPressure(value);
	}

	/** Applies one tick of pressure decay; called from the server tick loop. */
	public static void tick() {
		pressure = Math.max(0.0f, pressure - PRESSURE_DECAY_PER_TICK);
	}

	private static float clampPressure(float value) {
		return Math.max(MIN_PRESSURE, Math.min(MAX_PRESSURE, value));
	}

	/**
	 * Reads the player's effective attack damage and armor values.
	 *
	 * <p>The player attribute system resolves base values, equipment modifiers,
	 * and other active modifiers into the effective values used here.</p>
	 */
	public static Readiness read(ServerPlayerEntity player) {
		double attackDamage = player.getAttributeValue(EntityAttributes.GENERIC_ATTACK_DAMAGE);
		double armor = player.getAttributeValue(EntityAttributes.GENERIC_ARMOR);

		double attackScore = clamp01(attackDamage / MAX_ATTACK_DAMAGE);
		double armorScore = clamp01(armor / MAX_ARMOR);

		return new Readiness(attackDamage, armor, (attackScore + armorScore) / 2.0);
	}

	private static double clamp01(double value) {
		return Math.max(0.0, Math.min(1.0, value));
	}

	/** Immutable per-player snapshot used by the command output. */
	public record Readiness(double attackDamage, double armor, double readiness) {
	}
}
