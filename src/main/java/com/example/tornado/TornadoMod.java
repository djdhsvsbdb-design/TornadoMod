package com.example.tornado;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.commands.Commands;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Tornado Realism: /tornado [ef 0-5]
 * Server-side only logic: a moving vortex that lifts entities, rips weak blocks
 * into flying debris and renders a rotating funnel with particles.
 */
@Mod(TornadoMod.MOD_ID)
public class TornadoMod {

    public static final String MOD_ID = "tornado_realism";

    private static final List<Tornado> ACTIVE = new ArrayList<>();

    public TornadoMod() {
        RegisterCommandsEvent.BUS.addListener(this::registerCommands);
        TickEvent.ServerTickEvent.Post.BUS.addListener(event -> tickTornadoes());
    }

    private void registerCommands(RegisterCommandsEvent event) {
        var dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("tornado")
            .requires(src -> src.permissions().hasPermission(Permissions.COMMANDS_MODERATOR))
            .executes(ctx -> spawn(ctx.getSource().getLevel(), ctx.getSource().getPosition(), 2, ctx.getSource()))
            .then(Commands.argument("ef", IntegerArgumentType.integer(0, 5))
                .executes(ctx -> spawn(ctx.getSource().getLevel(), ctx.getSource().getPosition(),
                    IntegerArgumentType.getInteger(ctx, "ef"), ctx.getSource()))));
        dispatcher.register(Commands.literal("tornado_clear")
            .requires(src -> src.permissions().hasPermission(Permissions.COMMANDS_MODERATOR))
            .executes(ctx -> {
                ACTIVE.clear();
                return 1;
            }));
    }

    private void tickTornadoes() {
        Iterator<Tornado> it = ACTIVE.iterator();
        while (it.hasNext()) {
            Tornado t = it.next();
            if (!t.tick()) it.remove();
        }
    }

    private static int spawn(ServerLevel level, Vec3 pos, int ef, net.minecraft.commands.CommandSourceStack src) {
        // spawn ~24 blocks ahead of where the command was run so the player sees it form
        ACTIVE.add(new Tornado(level, pos.add(24, 0, 0), ef));
        src.sendSuccess(() -> Component.literal("Tornado EF" + ef + " spawned"), true);
        return 1;
    }

    static final class Tornado {
        final ServerLevel level;
        double x, z;
        final int ef;
        final double baseRadius;     // radius at ground
        final double height;         // funnel height
        final double spin;           // tangential speed
        final double suction;        // inward + upward pull
        double heading;              // radians
        int ticksLeft;
        int age = 0;

        Tornado(ServerLevel level, Vec3 start, int ef) {
            this.level = level;
            this.x = start.x;
            this.z = start.z;
            this.ef = ef;
            this.baseRadius = 2.5 + ef * 1.6;
            this.height = 40 + ef * 14;
            this.spin = 0.35 + ef * 0.12;
            this.suction = 0.06 + ef * 0.035;
            this.heading = level.getRandom().nextDouble() * Math.PI * 2;
            this.ticksLeft = 20 * (90 + ef * 30); // lives 1.5 to 4 minutes
        }

        double groundY() {
            return level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                (int) Math.floor(x), (int) Math.floor(z));
        }

        boolean tick() {
            if (--ticksLeft <= 0) return false;
            age++;

            // wandering path: heading drifts slowly, speed ~0.25 blocks/tick (~18 km/h scaled)
            heading += (level.getRandom().nextDouble() - 0.5) * 0.04;
            x += Math.cos(heading) * 0.22;
            z += Math.sin(heading) * 0.22;

            // do not simulate in unloaded chunks
            if (!level.hasChunkAt(BlockPos.containing(x, 64, z))) return true;

            double gy = groundY();
            // growth phase (first 10 s) and dying "rope-out" phase (last 10 s)
            double life = Math.min(1.0, Math.min(age / 200.0, ticksLeft / 200.0));

            renderFunnel(gy, life);
            affectEntities(gy, life);
            if (age % 2 == 0) ripBlocks(gy, life);
            return true;
        }

        double radiusAt(double h, double life) {
            // narrow at ground, widening towards the cloud base (cone shape)
            double f = h / height;
            return (baseRadius * life) * (0.45 + 2.2 * f * f);
        }

        void renderFunnel(double gy, double life) {
            int rings = 24;
            for (int i = 0; i < rings; i++) {
                double h = (height * i) / rings;
                double r = radiusAt(h, life);
                double angle = age * (0.5 + spin) + i * 0.7;
                for (int k = 0; k < 3; k++) {
                    double a = angle + k * (Math.PI * 2 / 3);
                    double px = x + Math.cos(a) * r;
                    double pz = z + Math.sin(a) * r;
                    level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, px, gy + h, pz, 0, 0, 0.02, 0, 1.0);
                }
                if (i % 4 == 0) {
                    level.sendParticles(ParticleTypes.POOF, x + Math.cos(angle) * r * 0.6, gy + h, z + Math.sin(angle) * r * 0.6, 1, 0.2, 0.2, 0.2, 0.01);
                }
            }
            // dust ring at the ground
            for (int k = 0; k < 6; k++) {
                double a = age * 0.9 + k;
                level.sendParticles(ParticleTypes.CLOUD, x + Math.cos(a) * baseRadius * 1.4, gy + 0.3,
                    z + Math.sin(a) * baseRadius * 1.4, 1, 0.3, 0.1, 0.3, 0.05);
            }
        }

        void affectEntities(double gy, double life) {
            double reach = radiusAt(height, life) + 6;
            AABB box = new AABB(x - reach, gy - 4, z - reach, x + reach, gy + height, z + reach);
            for (Entity e : level.getEntities((Entity) null, box, ent -> true)) {
                if (e instanceof ServerPlayer p && (p.isCreative() || p.isSpectator())) continue;
                if (e instanceof FallingBlockEntity || e.isRemoved()) {
                    // debris also gets swirled below
                }
                double dx = x - e.getX();
                double dz = z - e.getZ();
                double dist = Math.sqrt(dx * dx + dz * dz);
                double h = Math.max(0, e.getY() - gy);
                double rHere = radiusAt(Math.min(h, height), life) + 4;
                if (dist > rHere * 2.2) continue;

                // strength falls off with distance (inverse-ish), full inside the core
                double k = Math.max(0.0, 1.0 - dist / (rHere * 2.2));
                double nx = dist < 1e-4 ? 0 : dx / dist;
                double nz = dist < 1e-4 ? 0 : dz / dist;
                // tangential (counter-clockwise) component
                double tx = -nz;
                double tz = nx;

                Vec3 v = e.getDeltaMovement();
                double nvx = v.x + (nx * suction + tx * spin * 0.5) * k;
                double nvz = v.z + (nz * suction + tz * spin * 0.5) * k;
                double nvy = v.y + (0.04 + ef * 0.025) * k;
                // cap speed so entities are not launched into orbit instantly
                double cap = 1.2 + ef * 0.25;
                nvx = Math.max(-cap, Math.min(cap, nvx));
                nvz = Math.max(-cap, Math.min(cap, nvz));
                nvy = Math.max(-0.6, Math.min(0.9 + ef * 0.1, nvy));
                e.setDeltaMovement(nvx, nvy, nvz);
                if (e instanceof ServerPlayer player) {
                    player.connection.send(new ClientboundSetEntityMotionPacket(player));
                }
                e.fallDistance = 0; // fall damage is replaced by being dropped from height only on EF3+
                if (ef >= 3 && k > 0.6 && age % 20 == 0) {
                    e.hurtServer(level, level.damageSources().flyIntoWall(), 1.0f + ef);
                }
            }
        }

        void ripBlocks(double gy, double life) {
            // weaker tornadoes only touch the surface; stronger ones tear harder blocks
            float maxHardness = 0.5f + ef * 0.9f;
            double r = baseRadius * life * 1.3;
            int tries = 2 + ef * 2;
            for (int i = 0; i < tries; i++) {
                double a = level.getRandom().nextDouble() * Math.PI * 2;
                double d = Math.sqrt(level.getRandom().nextDouble()) * r;
                int bx = (int) Math.floor(x + Math.cos(a) * d);
                int bz = (int) Math.floor(z + Math.sin(a) * d);
                int by = (int) gy + level.getRandom().nextInt(4) - 1;
                BlockPos pos = new BlockPos(bx, by, bz);
                BlockState st = level.getBlockState(pos);
                if (st.isAir() || !st.getFluidState().isEmpty()) continue;
                if (level.getBlockEntity(pos) != null) continue;
                float hard = st.getDestroySpeed(level, pos);
                if (hard < 0 || hard > maxHardness) continue;
                // keep the terrain: do not rip grass-like ground below EF2
                if (ef < 2 && by < gy - 1) continue;
                FallingBlockEntity fb = FallingBlockEntity.fall(level, pos, st);
                fb.setDeltaMovement(
                    (x - bx) * 0.05 + (level.getRandom().nextDouble() - 0.5) * 0.3,
                    0.5 + ef * 0.12,
                    (z - bz) * 0.05 + (level.getRandom().nextDouble() - 0.5) * 0.3);

            }
        }
    }
}
