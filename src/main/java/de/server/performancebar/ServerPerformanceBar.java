package de.server.performancebar;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.network.chat.Component;

import java.util.Locale;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.Properties;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;

public class ServerPerformanceBar implements ModInitializer {
    private static final String ID = "performance:server";
    private long lastTickNanos = -1;
    private long lastTickStartNanos = -1;
    private long elapsedNanos = 0;
    private long workNanos = 0;
    private int ticks = 0;
    private final Set<UUID> hiddenPlayers = new HashSet<>();
    private final Path preferencesPath = FabricLoader.getInstance().getConfigDir().resolve("server-performance-bar.properties");
    private boolean preferencesLoaded = false;

    private void loadPreferences() {
        if (preferencesLoaded) return;
        preferencesLoaded = true;
        if (!Files.exists(preferencesPath)) return;
        Properties properties = new Properties();
        try (var input = Files.newInputStream(preferencesPath)) {
            properties.load(input);
            for (String key : properties.stringPropertyNames()) {
                if ("hidden".equals(properties.getProperty(key))) {
                    try { hiddenPlayers.add(UUID.fromString(key)); } catch (IllegalArgumentException ignored) { }
                }
            }
        } catch (Exception exception) {
            System.err.println("[ServerPerformanceBar] Could not load preferences: " + exception);
        }
    }

    private void savePreferences() {
        Properties properties = new Properties();
        for (UUID uuid : hiddenPlayers) properties.setProperty(uuid.toString(), "hidden");
        try {
            Files.createDirectories(preferencesPath.getParent());
            try (var output = Files.newOutputStream(preferencesPath)) {
                properties.store(output, "Players who have hidden the TPS bossbar");
            }
        } catch (Exception exception) {
            System.err.println("[ServerPerformanceBar] Could not save preferences: " + exception);
        }
    }

    private int setVisibility(CommandSourceStack source, boolean visible) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        var player = source.getPlayerOrException();
        if (visible) hiddenPlayers.remove(player.getUUID());
        else hiddenPlayers.add(player.getUUID());
        savePreferences();
        updatePlayers(source.getServer());
        source.sendSuccess(() -> Component.literal(visible ? "TPS-Bossbar eingeschaltet." : "TPS-Bossbar ausgeschaltet."), false);
        return 1;
    }

    private void updatePlayers(MinecraftServer server) {
        StringBuilder names = new StringBuilder();
        for (var player : server.getPlayerList().getPlayers()) {
            if (hiddenPlayers.contains(player.getUUID())) continue;
            if (names.length() > 0) names.append(' ');
            names.append(player.getUUID());
        }
        run(server, "bossbar set " + ID + " players " + names);
    }

    @Override
    public void onInitialize() {
        loadPreferences();
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            dispatcher.register(Commands.literal("tps")
                .executes(context -> {
                    var player = context.getSource().getPlayerOrException();
                    return setVisibility(context.getSource(), hiddenPlayers.contains(player.getUUID()));
                })
                .then(Commands.literal("on").executes(context -> setVisibility(context.getSource(), true)))
                .then(Commands.literal("off").executes(context -> setVisibility(context.getSource(), false)))
            )
        );
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            run(server, "bossbar add " + ID + " {\"text\":\"Serverleistung\"}");
            run(server, "bossbar set " + ID + " max 1000");
            run(server, "bossbar set " + ID + " visible true");
            updatePlayers(server);
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> updatePlayers(server));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> updatePlayers(server));
        ServerTickEvents.START_SERVER_TICK.register(server -> {
            long now = System.nanoTime();
            if (lastTickNanos > 0) elapsedNanos += now - lastTickNanos;
            lastTickNanos = now;
            lastTickStartNanos = now;
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (lastTickStartNanos < 0) return;
            workNanos += Math.max(0, System.nanoTime() - lastTickStartNanos);
            ticks++;
            if (ticks >= 100 && elapsedNanos > 0) {
                double tps = Math.min(20.0, ticks * 1_000_000_000.0 / elapsedNanos);
                double mspt = workNanos / ticks / 1_000_000.0;
                int percent = (int)Math.round(tps / 20.0 * 100);
                Runtime runtime = Runtime.getRuntime();
                long usedBytes = runtime.totalMemory() - runtime.freeMemory();
                long maxBytes = runtime.maxMemory();
                double usedGiB = usedBytes / 1073741824.0;
                double maxGiB = maxBytes / 1073741824.0;
                int ramPercent = maxBytes > 0 ? (int)Math.round(usedBytes * 100.0 / maxBytes) : 0;
                String color = tps >= 18.0 ? "green" : tps >= 15.0 ? "yellow" : "red";
                String title = String.format(Locale.ROOT, "TPS: %.1f | MSPT: %.1f ms | RAM: %.1f / %.1f GB (%d%%)", tps, mspt, usedGiB, maxGiB, ramPercent);
                run(server, "bossbar set " + ID + " name {\"text\":\"" + title + "\"}");
                run(server, "bossbar set " + ID + " color " + color);
                run(server, "bossbar set " + ID + " value " + (percent * 10));
                updatePlayers(server);
                ticks = 0;
                elapsedNanos = 0;
                workNanos = 0;
            }
        });
    }

    private static void run(MinecraftServer server, String command) {
        CommandSourceStack source = server.createCommandSourceStack().withPermission(4).withSuppressedOutput();
        server.getCommands().performPrefixedCommand(source, command);
    }
}
