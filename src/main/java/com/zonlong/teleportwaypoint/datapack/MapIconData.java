package com.zonlong.teleportwaypoint.datapack;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.zonlong.teleportwaypoint.TeleportWaypoint;
import com.zonlong.teleportwaypoint.core.WaypointManager;
import com.zonlong.teleportwaypoint.core.WaypointRecord;
import com.zonlong.teleportwaypoint.network.MapIconStyle;
import com.zonlong.teleportwaypoint.network.ModNetwork;
import com.zonlong.teleportwaypoint.network.SyncMapIconImagePayload;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/** World-scoped reload listener. No renderer ever queries structures or parses JSON. */
@EventBusSubscriber(modid = TeleportWaypoint.MODID)
public final class MapIconData extends SimpleJsonResourceReloadListener {
    private static MapIconData current;
    private List<MapIconRule> rules = List.of();
    private Map<ResourceLocation, byte[]> images = Map.of();

    private MapIconData() {
        super(new Gson(), "teleportwaypoint/map_icons");
    }

    @SubscribeEvent
    public static void onReloadListeners(AddReloadListenerEvent event) {
        current = new MapIconData();
        event.addListener(current);
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> json, ResourceManager manager, ProfilerFiller profiler) {
        var parsed = new java.util.ArrayList<MapIconRule>();
        Map<ResourceLocation, byte[]> loadedImages = new LinkedHashMap<>();
        json.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            if (parsed.size() >= 1024) return;
            try {
                MapIconRule rule = MapIconRule.parse(entry.getKey(), entry.getValue().getAsJsonObject());
                // A rejected rule must not consume the image budget or leak orphan images into the snapshot.
                Map<ResourceLocation, byte[]> ruleImages = new LinkedHashMap<>(loadedImages);
                loadImage(rule.style().active(), manager, ruleImages);
                loadImage(rule.style().inactive(), manager, ruleImages);
                loadedImages.clear();
                loadedImages.putAll(ruleImages);
                parsed.add(rule);
            } catch (Exception e) {
                TeleportWaypoint.LOGGER.warn("Skipping map icon rule {}: {}", entry.getKey(), e.getMessage());
            }
        });
        parsed.sort(Comparator.comparingInt(MapIconRule::priority).reversed().thenComparing(rule -> rule.id().toString()));
        rules = List.copyOf(parsed);
        images = Map.copyOf(loadedImages);
        TeleportWaypoint.LOGGER.info("Loaded {} map icon rules and {} PNGs", rules.size(), images.size());
    }

    private static void loadImage(String source, ResourceManager manager, Map<ResourceLocation, byte[]> images) throws Exception {
        if (!source.startsWith("data:")) return;
        ResourceLocation id = ResourceLocation.parse(source.substring(5));
        if (images.containsKey(id)) return;
        if (images.size() >= SyncMapIconImagePayload.MAX_IMAGES) {
            throw new IllegalArgumentException("Too many PNGs (maximum 64)");
        }
        try (InputStream input = manager.getResourceOrThrow(id).open()) {
            byte[] bytes = input.readNBytes(SyncMapIconImagePayload.MAX_BYTES + 1);
            if (!isValidPng(bytes)) throw new IllegalArgumentException("Invalid or oversized PNG: " + id);
            images.put(id, bytes);
        }
    }

    /** Check dimensions before decoding, on both sides of the network. */
    public static boolean isValidPng(byte[] png) {
        byte[] signature = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
        if (png.length < 33 || png.length > SyncMapIconImagePayload.MAX_BYTES
                || !Arrays.equals(signature, Arrays.copyOf(png, 8))) return false;
        ByteBuffer header = ByteBuffer.wrap(png);
        if (header.getInt(8) != 13 || header.getInt(12) != 0x49484452) return false;
        int width = header.getInt(16), height = header.getInt(20);
        return width > 0 && height > 0 && width <= SyncMapIconImagePayload.MAX_DIMENSION
                && height <= SyncMapIconImagePayload.MAX_DIMENSION;
    }

    public static MapIconStyle resolve(MinecraftServer server, WaypointRecord record) {
        if (current == null) return MapIconStyle.DEFAULT;
        var registry = server.registryAccess().registryOrThrow(Registries.STRUCTURE);
        for (MapIconRule rule : current.rules) {
            if (rule.matches(record, registry)) return rule.style();
        }
        return MapIconStyle.DEFAULT;
    }

    @SubscribeEvent
    public static void onSync(OnDatapackSyncEvent event) {
        if (event.getPlayer() != null) {
            syncImages(event.getPlayer());
        } else {
            for (ServerPlayer player : event.getPlayerList().getPlayers()) {
                syncImages(player);
                // Re-resolve all visible waypoint styles after /reload, including deleted rules.
                WaypointManager.syncDimensionTo(player);
            }
        }
    }

    private static void syncImages(ServerPlayer player) {
        if (!ModNetwork.supportsMapIcons(player)) return;
        PacketDistributor.sendToPlayer(player, new SyncMapIconImagePayload(true,
                ResourceLocation.fromNamespaceAndPath(TeleportWaypoint.MODID, "reset"), new byte[0]));
        if (current != null) {
            current.images.forEach((id, png) -> PacketDistributor.sendToPlayer(player,
                    new SyncMapIconImagePayload(false, id, png)));
        }
    }

    @SubscribeEvent
    public static void onStopped(ServerStoppedEvent event) {
        current = null;
    }
}
