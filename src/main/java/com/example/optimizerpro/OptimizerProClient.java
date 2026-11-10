package com.example.optimizerpro;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.CloudRenderMode;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.SimpleOption;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OptimizerProClient implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("optimizerpro");

    private static final int LOW_FPS = 45;
    private static final int HIGH_FPS = 100;
    private static final int CHECK_INTERVAL_TICKS = 100;
    private static final int HIGH_STREAK_NEEDED = 3;
    private static final int UNFOCUSED_FPS = 10;
    private static final int MAX_LEVEL = 3;
    private static final double[] ENTITY_SCALE = {1.0, 0.85, 0.7, 0.5};

    private boolean enabled = true;
    private int level = 0;
    private int ticks = 0;
    private int highStreak = 0;
    private boolean captured = false;
    private boolean limitedByFocus = false;
    private int userMaxFps = 260;

    private int baseView, baseSim;
    private double baseEntity;
    private String baseParticles = "ALL";
    private CloudRenderMode baseClouds = CloudRenderMode.FAST;

    @Override
    public void onInitializeClient() {
        ClientLifecycleEvents.CLIENT_STARTED.register(c -> userMaxFps = c.options.getMaxFps().getValue());
        ClientLifecycleEvents.CLIENT_STOPPING.register(c -> {
            if (limitedByFocus) c.options.getMaxFps().setValue(userMaxFps);
            if (captured) { level = 0; apply(c); }
        });
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
            dispatcher.register(ClientCommandManager.literal("optimizer")
                .executes(ctx -> { status(ctx.getSource()); return 1; })
                .then(ClientCommandManager.literal("status")
                    .executes(ctx -> { status(ctx.getSource()); return 1; }))
                .then(ClientCommandManager.literal("on")
                    .executes(ctx -> { setEnabled(true, ctx.getSource()); return 1; }))
                .then(ClientCommandManager.literal("off")
                    .executes(ctx -> { setEnabled(false, ctx.getSource()); return 1; }))));
    }

    private void tick(MinecraftClient client) {
        if (client.options == null) return;
        GameOptions o = client.options;

        boolean focused = client.isWindowFocused();
        if (enabled && !focused && !limitedByFocus) {
            userMaxFps = o.getMaxFps().getValue();
            o.getMaxFps().setValue(UNFOCUSED_FPS);
            limitedByFocus = true;
        } else if ((focused || !enabled) && limitedByFocus) {
            o.getMaxFps().setValue(userMaxFps);
            limitedByFocus = false;
        }

        if (client.world == null) {
            if (captured) {
                level = 0;
                apply(client);
                captured = false;
                highStreak = 0;
            }
            return;
        }
        if (!enabled || !focused) return;

        if (!captured) {
            baseView = o.getViewDistance().getValue();
            baseSim = o.getSimulationDistance().getValue();
            baseEntity = o.getEntityDistanceScaling().getValue();
            baseParticles = o.getParticles().getValue().name();
            baseClouds = o.getCloudRenderMode().getValue();
            captured = true;
            level = 0;
        }

        if (++ticks < CHECK_INTERVAL_TICKS) return;
        ticks = 0;

        int cap = userMaxFps >= 260 ? 1000 : userMaxFps;
        int low = Math.min(LOW_FPS, cap / 2);
        int high = Math.min(HIGH_FPS, (int) (cap * 0.9));
        int fps = client.getCurrentFps();

        if (fps < low && level < MAX_LEVEL) {
            level++;
            highStreak = 0;
            apply(client);
        } else if (fps > high && level > 0) {
            if (++highStreak >= HIGH_STREAK_NEEDED) {
                level--;
                highStreak = 0;
                apply(client);
            }
        } else {
            highStreak = 0;
        }
    }

    private void apply(MinecraftClient client) {
        if (!captured) return;
        GameOptions o = client.options;

        o.getViewDistance().setValue(Math.max(Math.min(baseView, 5), baseView - 2 * level));
        o.getSimulationDistance().setValue(Math.max(Math.min(baseSim, 5), baseSim - level));
        o.getEntityDistanceScaling().setValue(Math.max(0.5, baseEntity * ENTITY_SCALE[level]));

        if (level >= 2) {
            setEnum(o.getParticles(), "MINIMAL");
        } else if (level == 1 && baseParticles.equals("ALL")) {
            setEnum(o.getParticles(), "DECREASED");
        } else {
            setEnum(o.getParticles(), baseParticles);
        }

        o.getCloudRenderMode().setValue(level >= 1 ? CloudRenderMode.OFF : baseClouds);
        LOGGER.info("Optimizer Pro: nivel {}", level);
    }

    private static <E extends Enum<E>> void setEnum(SimpleOption<E> option, String name) {
        for (E c : option.getValue().getDeclaringClass().getEnumConstants()) {
            if (c.name().equals(name)) {
                option.setValue(c);
                return;
            }
        }
    }

    private void setEnabled(boolean value, FabricClientCommandSource src) {
        enabled = value;
        if (!value) {
            level = 0;
            highStreak = 0;
            apply(MinecraftClient.getInstance());
        }
        src.sendFeedback(Text.literal("Optimizer Pro: " + (value ? "activado" : "desactivado")));
    }

    private void status(FabricClientCommandSource src) {
        src.sendFeedback(Text.literal("Optimizer Pro: " + (enabled ? "activado" : "desactivado")
            + " | nivel " + level + "/" + MAX_LEVEL
            + " | FPS " + MinecraftClient.getInstance().getCurrentFps()));
    }
                                        }
