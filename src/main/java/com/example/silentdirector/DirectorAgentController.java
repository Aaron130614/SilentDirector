package com.example.silentdirector;

import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Moves the named fake-player armor stand according to the current directive.
 */
public final class DirectorAgentController {
	private static final String AGENT_NAME = "SilentDirector_Fake";
	private static final double AGENT_SPAWN_DISTANCE = 12.0;
	private static final double HUNTER_SPEED_PER_TICK = 0.15 / 20.0;
	private static final double OBSERVER_SPEED_PER_TICK = 0.30;
	private static final double PROVIDER_SPEED_PER_TICK = 0.06;
	private static final double NEUTRAL_SPEED_PER_TICK = 0.05 / 20.0;
	private static final double WANDER_RADIUS = 5.0;
	private static final double ATTACK_RANGE = 3.0;
	private static final double OBSERVER_MIN_DISTANCE = 10.0;
	private static final double OBSERVER_MAX_DISTANCE = 15.0;
	private static final double PROVIDER_HANDOFF_DISTANCE = 3.0;
	private static final int PROVIDER_PAUSE_TICKS = 100;
	private static final int ATTACK_INTERVAL_TICKS = 20;
	private static final float ATTACK_DAMAGE = 2.0f;
	private static final double ARRIVAL_DISTANCE = 0.02;
	private static final Map<UUID, AgentState> AGENT_STATES = new HashMap<>();

	private DirectorAgentController() {
	}

	/** Called from the server tick loop. */
	public static void tick(MinecraftServer server) {
		Directive directive = Director.currentDirective();
		if (directive == null) {
			return;
		}

		ServerPlayerEntity targetPlayer = directive.target() == null
				? null
				: server.getPlayerManager().getPlayer(directive.target());
		if (targetPlayer == null || targetPlayer.isRemoved()) {
			despawnAgents(server);
			return;
		}

		ensureAgent(server, targetPlayer);

		Set<UUID> activeAgents = new HashSet<>();
		for (ServerWorld world : server.getWorlds()) {
			for (Entity entity : world.iterateEntities()) {
				if (!(entity instanceof ArmorStandEntity armorStand)
						|| armorStand.getCustomName() == null
						|| !AGENT_NAME.equals(armorStand.getCustomName().getString())) {
					continue;
				}

				activeAgents.add(armorStand.getUuid());
				AgentState state = AGENT_STATES.computeIfAbsent(
						armorStand.getUuid(), ignored -> new AgentState(armorStand.getPos()));
				armorStand.setNoGravity(true);

				switch (directive.role()) {
					case HUNTER -> tickHunter(server, world, armorStand, directive, state);
					case OBSERVER -> tickObserver(server, world, armorStand, directive, state);
					case PROVIDER -> tickProvider(server, world, armorStand, directive, state);
					case NEUTRAL -> tickNeutral(world, armorStand, state);
				}
			}
		}

		AGENT_STATES.keySet().removeIf(uuid -> !activeAgents.contains(uuid));
	}

	private static void tickHunter(MinecraftServer server, ServerWorld world, ArmorStandEntity armorStand,
			Directive directive, AgentState state) {
		ServerPlayerEntity targetPlayer = resolveTarget(server, world, directive);
		if (targetPlayer == null) {
			return;
		}

		if (state.attackCooldown > 0) {
			state.attackCooldown--;
		}

		state.wanderTarget = null;
		Vec3d target = targetPlayer.getPos();
		moveToward(world, armorStand, target, HUNTER_SPEED_PER_TICK, targetPlayer.getY());

		double distanceSquared = horizontal(target.subtract(armorStand.getPos())).lengthSquared();
		if (directive.transitionTicks() <= 0
				&& distanceSquared < ATTACK_RANGE * ATTACK_RANGE
				&& state.attackCooldown == 0) {
			targetPlayer.damage(targetPlayer.getDamageSources().mobAttack(armorStand), ATTACK_DAMAGE);
			state.attackCooldown = ATTACK_INTERVAL_TICKS;
		}
	}

	private static void tickObserver(MinecraftServer server, ServerWorld world, ArmorStandEntity armorStand,
			Directive directive, AgentState state) {
		ServerPlayerEntity targetPlayer = resolveTarget(server, world, directive);
		if (targetPlayer == null) {
			return;
		}

		state.wanderTarget = null;
		Vec3d playerPos = targetPlayer.getPos();
		Vec3d offset = horizontal(armorStand.getPos().subtract(playerPos));
		double distance = offset.length();

		if (distance > OBSERVER_MAX_DISTANCE) {
			moveToward(world, armorStand, playerPos, OBSERVER_SPEED_PER_TICK, targetPlayer.getY());
		} else if (distance < OBSERVER_MIN_DISTANCE) {
			Vec3d awayDirection = offset.lengthSquared() < 1.0E-6
					? randomHorizontalDirection(state)
					: offset.normalize();
			Vec3d desiredPos = playerPos.add(awayDirection.multiply(
					(OBSERVER_MIN_DISTANCE + OBSERVER_MAX_DISTANCE) * 0.5));
			moveToward(world, armorStand, desiredPos, OBSERVER_SPEED_PER_TICK, targetPlayer.getY());
		}

		faceToward(armorStand, targetPlayer.getEyePos());
	}

	private static void tickProvider(MinecraftServer server, ServerWorld world, ArmorStandEntity armorStand,
			Directive directive, AgentState state) {
		ServerPlayerEntity targetPlayer = resolveTarget(server, world, directive);
		if (targetPlayer == null) {
			return;
		}

		state.wanderTarget = null;
		if (armorStand.getPos().squaredDistanceTo(targetPlayer.getPos())
				< PROVIDER_HANDOFF_DISTANCE * PROVIDER_HANDOFF_DISTANCE) {
			dropProviderItem(world, targetPlayer);
			state.wanderCooldown = PROVIDER_PAUSE_TICKS;
			Director.forceNeutral(directive.target());
			DirectorBossBarStatus.refresh(server);
			return;
		}

		moveToward(world, armorStand, targetPlayer.getPos(),
				PROVIDER_SPEED_PER_TICK, targetPlayer.getY());
		faceToward(armorStand, targetPlayer.getEyePos());
	}

	private static void tickNeutral(ServerWorld world, ArmorStandEntity armorStand, AgentState state) {
		if (state.wanderCooldown > 0) {
			state.wanderCooldown--;
			return;
		}

		if (state.wanderTarget == null) {
			state.wanderTarget = chooseWanderTarget(world, armorStand, state);
			if (state.wanderTarget == null) {
				state.wanderCooldown = 40 + state.random.nextInt(41);
				return;
			}
		}

		Vec3d horizontalToTarget = horizontal(state.wanderTarget.subtract(armorStand.getPos()));
		if (horizontalToTarget.lengthSquared() <= ARRIVAL_DISTANCE * ARRIVAL_DISTANCE) {
			state.wanderTarget = null;
			state.wanderCooldown = 40 + state.random.nextInt(81);
			return;
		}

		if (!moveToward(world, armorStand, state.wanderTarget,
				NEUTRAL_SPEED_PER_TICK, armorStand.getY())) {
			state.wanderTarget = null;
			state.wanderCooldown = 40;
		}
	}

	private static ServerPlayerEntity resolveTarget(MinecraftServer server, ServerWorld world,
			Directive directive) {
		if (directive.target() == null) {
			return null;
		}

		ServerPlayerEntity player = server.getPlayerManager().getPlayer(directive.target());
		if (player == null || player.getServerWorld() != world || player.isRemoved()) {
			return null;
		}

		return player;
	}

	private static void ensureAgent(MinecraftServer server, ServerPlayerEntity player) {
		ServerWorld targetWorld = player.getServerWorld();
		boolean foundInTargetWorld = false;
		List<ArmorStandEntity> staleAgents = new ArrayList<>();

		for (ServerWorld world : server.getWorlds()) {
			for (Entity entity : world.iterateEntities()) {
				if (!isAgent(entity)) {
					continue;
				}

				if (world == targetWorld) {
					foundInTargetWorld = true;
				} else {
					staleAgents.add((ArmorStandEntity) entity);
				}
			}
		}

		for (ArmorStandEntity staleAgent : staleAgents) {
			staleAgent.discard();
		}

		if (foundInTargetWorld) {
			return;
		}

		Vec3d forward = player.getRotationVec(1.0f);
		Vec3d horizontal = new Vec3d(forward.x, 0.0, forward.z);
		Vec3d direction = horizontal.lengthSquared() < 1.0E-6
				? new Vec3d(1.0, 0.0, 0.0)
				: horizontal.normalize();
		Vec3d spawnPos = player.getPos().add(direction.multiply(AGENT_SPAWN_DISTANCE));

		ArmorStandEntity armorStand = new ArmorStandEntity(targetWorld, spawnPos.x, spawnPos.y, spawnPos.z);
		armorStand.setCustomName(Text.literal(AGENT_NAME));
		armorStand.setCustomNameVisible(true);
		armorStand.setInvulnerable(true);
		armorStand.setNoGravity(true);
		armorStand.setVelocity(Vec3d.ZERO);
		armorStand.refreshPositionAndAngles(spawnPos.x, spawnPos.y, spawnPos.z,
				player.getYaw(), player.getPitch());
		targetWorld.spawnEntity(armorStand);
	}

	private static boolean isAgent(Entity entity) {
		return entity instanceof ArmorStandEntity armorStand
				&& armorStand.getCustomName() != null
				&& AGENT_NAME.equals(armorStand.getCustomName().getString());
	}

	private static void despawnAgents(MinecraftServer server) {
		List<ArmorStandEntity> agents = new ArrayList<>();
		for (ServerWorld world : server.getWorlds()) {
			for (Entity entity : world.iterateEntities()) {
				if (isAgent(entity)) {
					agents.add((ArmorStandEntity) entity);
				}
			}
		}

		for (ArmorStandEntity agent : agents) {
			agent.discard();
		}

		AGENT_STATES.clear();
	}

	private static void dropProviderItem(ServerWorld world, ServerPlayerEntity player) {
		ItemStack stack = new ItemStack(Items.BREAD);
		ItemEntity itemEntity = new ItemEntity(world,
				player.getX(), player.getY() + 0.01, player.getZ(), stack);
		itemEntity.setToDefaultPickupDelay();
		world.spawnEntity(itemEntity);
	}

	private static Vec3d randomHorizontalDirection(AgentState state) {
		double angle = state.random.nextDouble() * Math.PI * 2.0;
		return new Vec3d(Math.cos(angle), 0.0, Math.sin(angle));
	}

	private static Vec3d chooseWanderTarget(ServerWorld world, ArmorStandEntity armorStand, AgentState state) {
		for (int attempt = 0; attempt < 12; attempt++) {
			double angle = state.random.nextDouble() * Math.PI * 2.0;
			double radius = Math.sqrt(state.random.nextDouble()) * WANDER_RADIUS;
			double x = state.anchor.x + Math.cos(angle) * radius;
			double z = state.anchor.z + Math.sin(angle) * radius;
			double groundY = findGroundY(world, armorStand, x, z, state.anchor.y);
			if (Double.isFinite(groundY)) {
				return new Vec3d(x, groundY, z);
			}
		}

		return null;
	}

	private static boolean moveToward(ServerWorld world, ArmorStandEntity armorStand,
			Vec3d target, double maxStep, double preferredGroundY) {
		Vec3d current = armorStand.getPos();
		Vec3d horizontalDelta = horizontal(target.subtract(current));
		double distance = horizontalDelta.length();
		if (distance <= 1.0E-6) {
			faceToward(armorStand, target);
			return true;
		}

		double step = Math.min(maxStep, distance);
		Vec3d direction = horizontalDelta.multiply(step / distance);
		double nextX = current.x + direction.x;
		double nextZ = current.z + direction.z;
		double nextY = findGroundY(world, armorStand, nextX, nextZ, preferredGroundY);
		if (!Double.isFinite(nextY)) {
			return false;
		}

		float yaw = yawToward(current, target, armorStand.getYaw());
		armorStand.refreshPositionAndAngles(nextX, nextY, nextZ, yaw, armorStand.getPitch());
		armorStand.setHeadYaw(yaw);
		armorStand.setVelocity(Vec3d.ZERO);
		return true;
	}

	private static void faceToward(ArmorStandEntity armorStand, Vec3d target) {
		float yaw = yawToward(armorStand.getPos(), target, armorStand.getYaw());
		armorStand.setYaw(yaw);
		armorStand.setHeadYaw(yaw);
	}

	private static float yawToward(Vec3d from, Vec3d target, float fallbackYaw) {
		double deltaX = target.x - from.x;
		double deltaZ = target.z - from.z;
		if (deltaX * deltaX + deltaZ * deltaZ < 1.0E-6) {
			return fallbackYaw;
		}

		return MathHelper.wrapDegrees(
				(float) (MathHelper.atan2(deltaZ, deltaX) * 180.0 / Math.PI) - 90.0f);
	}

	/**
	 * Finds nearby collision ground within one block vertically and verifies the
	 * armor stand's full bounding box can occupy the destination.
	 */
	private static double findGroundY(ServerWorld world, ArmorStandEntity armorStand,
			double x, double z, double preferredY) {
		int preferredBlockY = MathHelper.floor(preferredY);
		Vec3d current = armorStand.getPos();

		for (int y = preferredBlockY + 1; y >= preferredBlockY - 1; y--) {
			BlockPos feetPos = BlockPos.ofFloored(x, y, z);
			BlockPos supportPos = feetPos.down();
			VoxelShape support = world.getBlockState(supportPos).getCollisionShape(world, supportPos);
			if (support.isEmpty()) {
				continue;
			}

			double groundY = supportPos.getY() + support.getMax(Direction.Axis.Y);
			Box destinationBox = armorStand.getBoundingBox().offset(
					x - current.x, groundY - current.y, z - current.z);
			if (world.isSpaceEmpty(armorStand, destinationBox)) {
				return groundY;
			}
		}

		return Double.NaN;
	}

	private static Vec3d horizontal(Vec3d vector) {
		return new Vec3d(vector.x, 0.0, vector.z);
	}

	private static final class AgentState {
		private final Vec3d anchor;
		private final Random random;
		private Vec3d wanderTarget;
		private int wanderCooldown;
		private int attackCooldown;

		private AgentState(Vec3d anchor) {
			this.anchor = anchor;
			this.random = new Random(anchor.hashCode());
		}
	}
}
