package com.example.silentdirector;

import java.util.Objects;
import java.util.UUID;

/**
 * A single director output. Directives describe intent only; they do not
 * trigger any entity or player behavior.
 */
public record Directive(Role role, float intensity, UUID target, int transitionTicks) {
	public static final float MIN_INTENSITY = 0.0f;
	public static final float MAX_INTENSITY = 2.0f;

	public Directive {
		Objects.requireNonNull(role, "role");

		if (!Float.isFinite(intensity)
				|| intensity < MIN_INTENSITY
				|| intensity > MAX_INTENSITY) {
			throw new IllegalArgumentException("intensity must be between 0.0 and 2.0");
		}
	}

	/** Available director roles. */
	public enum Role {
		NEUTRAL,
		HUNTER,
		OBSERVER,
		PROVIDER
	}
}
