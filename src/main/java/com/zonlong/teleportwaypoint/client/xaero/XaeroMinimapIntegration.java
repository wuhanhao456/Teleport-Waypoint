package com.zonlong.teleportwaypoint.client.xaero;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.zonlong.teleportwaypoint.TeleportWaypoint;
import com.zonlong.teleportwaypoint.config.XaeroMinimapConfig;
import com.zonlong.teleportwaypoint.client.ClientWaypointInfo;
import com.zonlong.teleportwaypoint.client.ClientWaypointState;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.neoforged.fml.ModList;

import xaero.common.minimap.waypoints.Waypoint;
import xaero.hud.minimap.BuiltInHudModules;
import xaero.hud.minimap.common.config.option.MinimapProfiledConfigOptions;
import xaero.hud.minimap.config.util.MinimapConfigClientUtils;
import xaero.hud.minimap.module.MinimapSession;
import xaero.hud.minimap.waypoint.WaypointColor;
import xaero.hud.minimap.world.MinimapWorldManager;

/**
 * Injects teleport waypoints into Xaero's minimap custom waypoint store. The
 * minimap then renders them with names, colors and symbols using Xaero's own
 * waypoint rendering.
 *
 * <p>Custom waypoint maps are keyed by a mod-specific {@link ResourceLocation}
 * so we never share an ID namespace with other integrations. IDs are stable
 * hashes of the waypoint UUID with collision probing, and removals verify the
 * stored {@link Waypoint} instance is still ours before deleting it.
 *
 * <p>Xaero's custom waypoint store is global and does not understand dimensions,
 * so this integration only registers waypoints for the player's current
 * dimension and removes them when the player changes dimension.
 */
public final class XaeroMinimapIntegration {
    private static final int MAX_ID = 2_000_000_000;
    private static final ResourceLocation MINIMAP_KEY =
            ResourceLocation.fromNamespaceAndPath(TeleportWaypoint.MODID, "minimap");

    private static final Map<ResourceLocation, Map<UUID, Integer>> UID_TO_ID = new HashMap<>();
    private static final Map<ResourceLocation, Map<Integer, UUID>> ID_TO_UID = new HashMap<>();
    private static final Map<ResourceLocation, Map<Integer, Waypoint>> ID_TO_WAYPOINT = new HashMap<>();
    private static final Map<ResourceLocation, Set<Integer>> OWNED = new HashMap<>();
    private static int lastRevision = -1;
    private static boolean lastShowWaypoints = true;
    private static boolean lastShowInactiveWaypoints = true;
    private static boolean lastShowActiveWaypoints = true;
    private static int lastRange = 256;
    private static int lastAppliedNameScale = -1;
    private static ResourceKey<Level> lastPlayerDimension;
    private static BlockPos lastPlayerPos;
    private static boolean initialized;
    private static boolean minimapRegistered;

    private XaeroMinimapIntegration() {
    }

    public static void tick() {
        if (minimapRegistered) {
            return;
        }
        if (!ModList.get().isLoaded("xaerominimap")) {
            return;
        }
        if (xaero.common.HudMod.INSTANCE == null
                || xaero.common.HudMod.INSTANCE.getMinimap() == null) {
            return;
        }
        init();
        minimapRegistered = true;
        TeleportWaypoint.LOGGER.info("[TeleportWaypoint] Xaero Minimap integration registered");
    }

    public static void init() {
        initialized = true;
        sync();
    }

    public static void sync() {
        if (!initialized) {
            return;
        }
        syncWaypointNameScaleToDistanceScale();
        if (!ClientWaypointState.isInitialized()) {
            // The paginated snapshot is still in progress; do not render a partial set.
            return;
        }
        int revision = ClientWaypointState.getRevision();
        boolean showWaypoints = XaeroMinimapConfig.SHOW_WAYPOINTS.get();
        boolean showInactiveWaypoints = XaeroMinimapConfig.SHOW_INACTIVE_WAYPOINTS.get();
        boolean showActiveWaypoints = XaeroMinimapConfig.SHOW_ACTIVE_WAYPOINTS.get();
        int range = XaeroMinimapConfig.RANGE.get();
        boolean configChanged = showWaypoints != lastShowWaypoints
                || showInactiveWaypoints != lastShowInactiveWaypoints
                || showActiveWaypoints != lastShowActiveWaypoints
                || range != lastRange;
        boolean rangeRefresh = shouldRefreshForRange();
        if (revision == lastRevision && !configChanged && !rangeRefresh) {
            return;
        }

        MinimapSession session = BuiltInHudModules.MINIMAP.getCurrentSession();
        if (session == null) {
            // Session not ready yet: do not consume the revision/config state.
            return;
        }
        MinimapWorldManager manager = session.getWorldManager();

        lastRevision = revision;
        lastShowWaypoints = showWaypoints;
        lastShowInactiveWaypoints = showInactiveWaypoints;
        lastShowActiveWaypoints = showActiveWaypoints;
        lastRange = range;
        updateLastPlayerState();

        Map<ResourceLocation, List<ClientWaypointInfo>> desired = new HashMap<>();
        if (showWaypoints) {
            Player player = Minecraft.getInstance().player;
            if (player != null) {
                ResourceKey<Level> currentDimension = player.level().dimension();
                for (ClientWaypointInfo info : ClientWaypointState.getWaypoints()) {
                    if (!shouldShow(info.pocket(), ClientWaypointState.isActivated(info.uid()))) {
                        continue;
                    }
                    // Xaero custom waypoints are global and not dimension-aware, so only
                    // register waypoints for the dimension the player is currently in.
                    if (!info.dimension().equals(currentDimension)) {
                        continue;
                    }
                    if (range > 0 && distanceSq(player.blockPosition(), info.pos()) > (long) range * range) {
                        continue;
                    }
                    desired.computeIfAbsent(MINIMAP_KEY, k -> new ArrayList<>()).add(info);
                }
            }
        }

        removeStale(manager, desired);
        addOrUpdate(manager, desired);
    }

    public static void reset() {
        UID_TO_ID.clear();
        ID_TO_UID.clear();
        ID_TO_WAYPOINT.clear();
        OWNED.clear();
        lastRevision = -1;
        lastShowWaypoints = true;
        lastShowInactiveWaypoints = true;
        lastShowActiveWaypoints = true;
        lastRange = 256;
        lastAppliedNameScale = -1;
        lastPlayerDimension = null;
        lastPlayerPos = null;
        initialized = false;
        minimapRegistered = false;
    }

    private static void syncWaypointNameScaleToDistanceScale() {
        try {
            if (xaero.common.HudMod.INSTANCE == null) {
                return;
            }
            var configManager = xaero.common.HudMod.INSTANCE.getHudConfigs().getClientConfigManager();
            var profile = configManager.getCurrentProfile();
            if (profile == null) {
                return;
            }
            // Compute the actual effective distance-text scale, then choose a concrete
            // name-scale value that produces the same rendered size. Copying the raw
            // config index is not enough: the "auto" name scale uses a 0.5 multiplier.
            float distanceScale = MinimapConfigClientUtils.getUIScale(
                    configManager, MinimapProfiledConfigOptions.WAYPOINT_DISTANCE_SCALE_IN_WORLD);
            int nameValue = Math.max(1, Math.min(16, Math.round(distanceScale)));
            if (nameValue != lastAppliedNameScale) {
                profile.set(MinimapProfiledConfigOptions.WAYPOINT_NAME_SCALE_IN_WORLD, nameValue);
                lastAppliedNameScale = nameValue;
            }
        } catch (Exception e) {
            TeleportWaypoint.LOGGER.debug("[TeleportWaypoint] Failed to sync Xaero waypoint name scale", e);
        }
    }

    private static boolean shouldRefreshForRange() {
        Player player = Minecraft.getInstance().player;
        if (player == null) {
            return false;
        }
        ResourceKey<Level> dimension = player.level().dimension();
        if (!dimension.equals(lastPlayerDimension)) {
            return true;
        }
        if (lastPlayerPos == null) {
            return true;
        }
        if (XaeroMinimapConfig.RANGE.get() <= 0) {
            return false;
        }
        return distanceSq(lastPlayerPos, player.blockPosition()) > 16L * 16L;
    }

    private static void updateLastPlayerState() {
        Player player = Minecraft.getInstance().player;
        if (player != null) {
            lastPlayerDimension = player.level().dimension();
            lastPlayerPos = player.blockPosition();
        } else {
            lastPlayerDimension = null;
            lastPlayerPos = null;
        }
    }

    private static long distanceSq(BlockPos a, BlockPos b) {
        long dx = a.getX() - b.getX();
        long dy = a.getY() - b.getY();
        long dz = a.getZ() - b.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    private static boolean shouldShow(boolean pocket, boolean activated) {
        if (!XaeroMinimapConfig.SHOW_WAYPOINTS.get()) {
            return false;
        }
        if (pocket) {
            // Pocket waypoints are only shown after the local player activates them.
            return activated;
        }
        return activated
                ? XaeroMinimapConfig.SHOW_ACTIVE_WAYPOINTS.get()
                : XaeroMinimapConfig.SHOW_INACTIVE_WAYPOINTS.get();
    }

    private static void removeStale(MinimapWorldManager manager,
                                    Map<ResourceLocation, List<ClientWaypointInfo>> desired) {
        Iterator<Map.Entry<ResourceLocation, Set<Integer>>> it = OWNED.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<ResourceLocation, Set<Integer>> entry = it.next();
            ResourceLocation key = entry.getKey();
            Int2ObjectMap<Waypoint> map = manager.getCustomWaypoints(key);
            Set<Integer> owned = entry.getValue();

            if (!desired.containsKey(key)) {
                for (int id : owned) {
                    removeOwnedWaypoint(map, key, id);
                }
                it.remove();
                continue;
            }

            Set<UUID> desiredUids = new HashSet<>();
            for (ClientWaypointInfo info : desired.get(key)) {
                desiredUids.add(info.uid());
            }

            owned.removeIf(id -> {
                UUID uid = idToUidMap(key).get(id);
                if (uid == null || !desiredUids.contains(uid)) {
                    removeOwnedWaypoint(map, key, id);
                    return true;
                }
                return false;
            });
        }
    }

    private static void addOrUpdate(MinimapWorldManager manager,
                                    Map<ResourceLocation, List<ClientWaypointInfo>> desired) {
        for (Map.Entry<ResourceLocation, List<ClientWaypointInfo>> entry : desired.entrySet()) {
            ResourceLocation key = entry.getKey();
            Int2ObjectMap<Waypoint> map = manager.getCustomWaypoints(key);
            Set<Integer> owned = OWNED.computeIfAbsent(key, k -> new HashSet<>());
            Map<UUID, Integer> idByUid = uidToIdMap(key);
            Map<Integer, UUID> uidById = idToUidMap(key);
            Map<Integer, Waypoint> waypointById = idToWaypointMap(key);

            for (ClientWaypointInfo info : entry.getValue()) {
                Integer oldId = idByUid.get(info.uid());
                if (oldId != null) {
                    Waypoint current = map.get(oldId.intValue());
                    if (current != null && current != waypointById.get(oldId)) {
                        // Our old slot was overwritten by another integration; release it and allocate a fresh id.
                        waypointById.remove(oldId);
                        uidById.remove(oldId);
                        idByUid.remove(info.uid());
                        owned.remove(oldId);
                    }
                }

                int id = allocateId(map, key, info.uid());
                idByUid.put(info.uid(), id);
                uidById.put(id, info.uid());
                owned.add(id);

                boolean activated = ClientWaypointState.isActivated(info.uid());
                WaypointColor color;
                if (activated) {
                    color = info.pocket() ? WaypointColor.GREEN : WaypointColor.AQUA;
                } else {
                    color = info.pocket() ? WaypointColor.YELLOW : WaypointColor.RED;
                }
                String symbol = info.iconStyle().symbol().isEmpty()
                        ? (info.pocket() ? "P" : "W") : info.iconStyle().symbol();
                // Pocket waypoints use their literal name; regular waypoints use the raw
                // translation key so Xaero can localize them in the current client language.
                String displayName = info.pocket() ? info.name() : "teleportwaypoint.waypoint." + info.name();

                Waypoint existing = map.get(id);
                if (existing != null
                        && existing == waypointById.get(id)
                        && existing.getX() == info.pos().getX()
                        && existing.getY() == info.pos().getY()
                        && existing.getZ() == info.pos().getZ()
                        && Objects.equals(existing.getInitials(), symbol)
                        && Objects.equals(existing.getName(), displayName)
                        && existing.getWaypointColor() == color) {
                    continue;
                }

                Waypoint waypoint = new Waypoint(
                        info.pos().getX(),
                        info.pos().getY(),
                        info.pos().getZ(),
                        displayName,
                        symbol,
                        color);
                waypoint.setTemporary(true);
                map.put(id, waypoint);
                waypointById.put(id, waypoint);
            }
        }
    }

    private static void removeOwnedWaypoint(Int2ObjectMap<Waypoint> map, ResourceLocation key, int id) {
        Map<Integer, Waypoint> waypointById = idToWaypointMap(key);
        Map<Integer, UUID> uidById = idToUidMap(key);
        Map<UUID, Integer> idByUid = uidToIdMap(key);

        Waypoint current = map.get(id);
        Waypoint expected = waypointById.get(id);
        // Only delete when the current entry is still the exact object we created.
        // If another integration overwrote this id, leave their waypoint alone.
        if (current != null && current == expected) {
            map.remove(id);
        }
        waypointById.remove(id);
        UUID uid = uidById.remove(id);
        if (uid != null) {
            idByUid.remove(uid);
        }
    }

    private static int allocateId(Int2ObjectMap<Waypoint> map, ResourceLocation key, UUID uid) {
        Map<UUID, Integer> idByUid = uidToIdMap(key);
        Map<Integer, UUID> uidById = idToUidMap(key);
        Map<Integer, Waypoint> waypointById = idToWaypointMap(key);

        Integer existing = idByUid.get(uid);
        if (existing != null) {
            Waypoint current = map.get(existing.intValue());
            if (current == null || current == waypointById.get(existing)) {
                return existing;
            }
            // Our old slot was taken over by another integration; release it.
            waypointById.remove(existing);
            uidById.remove(existing);
            idByUid.remove(uid);
        }
        int id = stableId(uid);
        while (map.containsKey(id) && !Objects.equals(uidById.get(id), uid)) {
            Waypoint occupant = map.get(id);
            if (occupant != null && occupant == waypointById.get(id) && Objects.equals(uidById.get(id), uid)) {
                return id;
            }
            id = nextId(id);
        }
        return id;
    }

    private static int stableId(UUID uid) {
        return Math.floorMod(uid.hashCode(), MAX_ID) + 1;
    }

    private static int nextId(int id) {
        return id >= MAX_ID ? 1 : id + 1;
    }

    private static Map<UUID, Integer> uidToIdMap(ResourceLocation key) {
        return UID_TO_ID.computeIfAbsent(key, k -> new HashMap<>());
    }

    private static Map<Integer, UUID> idToUidMap(ResourceLocation key) {
        return ID_TO_UID.computeIfAbsent(key, k -> new HashMap<>());
    }

    private static Map<Integer, Waypoint> idToWaypointMap(ResourceLocation key) {
        return ID_TO_WAYPOINT.computeIfAbsent(key, k -> new HashMap<>());
    }
}
