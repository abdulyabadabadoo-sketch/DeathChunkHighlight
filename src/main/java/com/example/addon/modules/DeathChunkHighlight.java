package com.example.addon.modules;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.world.ChunkPos;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

// ──────────────────────────────────────────────────────────────────────────────
// Import for the render event. Meteor uses a custom event bus; pick whichever
// render event your version of Meteor exposes:
//   • For Meteor 0.5.x  →  meteordevelopment.meteorclient.events.render.Render3DEvent
//   • For older builds  →  meteordevelopment.meteorclient.events.render.RenderEvent
// Adjust the import below to match your Meteor version.
// ──────────────────────────────────────────────────────────────────────────────
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.Renderer3D;

// NOTE: Register this module in your addon's onInitialize() with:
//   Modules.get().add(new DeathChunkHighlight());

public class DeathChunkHighlight extends Module {

    // ── Settings ──────────────────────────────────────────────────────────────

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> highlightDuration = sgGeneral.add(
        new IntSetting.Builder()
            .name("duration")
            .description("How many seconds the chunk stays highlighted after a player dies.")
            .defaultValue(5)
            .min(1)
            .sliderMax(30)
            .build()
    );

    private final Setting<SettingColor> sideColor = sgGeneral.add(
        new ColorSetting.Builder()
            .name("side-color")
            .description("Fill color of the highlighted chunk.")
            .defaultValue(new SettingColor(255, 0, 0, 40))
            .build()
    );

    private final Setting<SettingColor> lineColor = sgGeneral.add(
        new ColorSetting.Builder()
            .name("line-color")
            .description("Outline color of the highlighted chunk.")
            .defaultValue(new SettingColor(255, 0, 0, 200))
            .build()
    );

    private final Setting<ShapeMode> shapeMode = sgGeneral.add(
        new EnumSetting.Builder<ShapeMode>()
            .name("shape-mode")
            .description("How the chunk highlight is rendered.")
            .defaultValue(ShapeMode.Both)
            .build()
    );

    // ── State ─────────────────────────────────────────────────────────────────

    // Maps chunk position → System.currentTimeMillis() when the highlight expires
    private final Map<ChunkPos, Long> highlightedChunks = new HashMap<>();

    // Track which players we've already seen so we can detect when they die
    // (i.e. disappear from the entity list / their health hits 0)
    private final Map<UUID, Float> playerHealthMap = new HashMap<>();

    // ── Constructor ───────────────────────────────────────────────────────────

    public DeathChunkHighlight() {
        super(
            // Replace the category with whichever Category your addon uses, e.g.:
            //   meteordevelopment.meteorclient.systems.modules.Categories.Combat
            meteordevelopment.meteorclient.systems.modules.Categories.Combat,
            "death-chunk-highlight",
            "Highlights the chunk of any player (except you) who dies, for a configurable duration."
        );
    }

    // ── Module lifecycle ──────────────────────────────────────────────────────

    @Override
    public void onDeactivate() {
        highlightedChunks.clear();
        playerHealthMap.clear();
    }

    // ── Tick: detect deaths & expire old highlights ───────────────────────────

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null) return;

        long now = System.currentTimeMillis();

        // 1. Remove expired highlights
        highlightedChunks.entrySet().removeIf(e -> now >= e.getValue());

        // 2. Scan all loaded players to detect deaths
        for (PlayerEntity player : mc.world.getPlayers()) {
            UUID id = player.getUuid();

            // Skip ourselves
            if (id.equals(mc.player.getUuid())) continue;

            float currentHealth = player.getHealth();
            Float previousHealth = playerHealthMap.get(id);

            if (previousHealth != null && previousHealth > 0f && currentHealth <= 0f) {
                // This player just died — record their chunk
                markChunk(player.getBlockPos(), now);
            }

            playerHealthMap.put(id, currentHealth);
        }

        // 3. Clean up health entries for players who left the world
        playerHealthMap.keySet().removeIf(id ->
            mc.world.getPlayers().stream().noneMatch(p -> p.getUuid().equals(id))
        );
    }

    // ── Render: draw the red chunk boxes ─────────────────────────────────────

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (mc.world == null) return;

        long now = System.currentTimeMillis();

        for (Map.Entry<ChunkPos, Long> entry : highlightedChunks.entrySet()) {
            long expiresAt = entry.getValue();
            if (now >= expiresAt) continue;

            ChunkPos cp = entry.getKey();

            // Compute world-space bounds for the entire chunk column
            int minX = cp.x << 4;          // cp.x * 16
            int minZ = cp.z << 4;          // cp.z * 16
            int maxX = minX + 16;
            int maxZ = minZ + 16;
            int minY = mc.world.getBottomY();
            int maxY = mc.world.getTopY();

            // Optional: fade out the alpha as the timer expires
            float progress = (float)(expiresAt - now) / (highlightDuration.get() * 1000f);
            int sideAlpha = (int)(sideColor.get().a * progress);
            int lineAlpha = (int)(lineColor.get().a * progress);

            event.renderer.box(
                minX, minY, minZ,
                maxX, maxY, maxZ,
                new SettingColor(sideColor.get().r, sideColor.get().g, sideColor.get().b, sideAlpha),
                new SettingColor(lineColor.get().r, lineColor.get().g, lineColor.get().b, lineAlpha),
                shapeMode.get(),
                0  // excludeDir — 0 = render all faces
            );
        }
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private void markChunk(BlockPos pos, long now) {
        // ChunkPos constructor takes chunk coordinates (block coords >> 4)
        ChunkPos cp = new ChunkPos(pos.getX() >> 4, pos.getZ() >> 4);
        long expiry = now + (highlightDuration.get() * 1000L);
        // If the chunk is already highlighted, extend/refresh the timer
        highlightedChunks.merge(cp, expiry, Math::max);
    }
}
