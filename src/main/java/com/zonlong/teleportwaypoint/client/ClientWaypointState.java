package com.zonlong.teleportwaypoint.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.zonlong.teleportwaypoint.network.ActivatedWaypointInfo;
import com.zonlong.teleportwaypoint.network.WaypointSyncInfo;
import com.zonlong.teleportwaypoint.network.MapIconStyle;
import com.zonlong.teleportwaypoint.network.SyncMapIconStylesPayload;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

/**
 * Client-side waypoint state: full waypoint data for the current dimension plus
 * the local player's activated metadata across all dimensions. Kept in sync by
 * dimension snapshots and incremental payloads. Used by the in-game GUI, the BER
 * state colors, and the optional Xaero map integrations.
 */
public final class ClientWaypointState {
    private static Map<UUID, ClientWaypointInfo> waypoints = Map.of();
    private static List<ActivatedWaypointInfo> activated = List.of();
    private static Set<UUID> activatedUids = Set.of();
    private static ResourceKey<Level> currentDimension;
    private static int revision;
    private static boolean initialized;
    private static final List<Runnable> pending = new ArrayList<>();
    private static List<ActivatedWaypointInfo> activatedSnapshot = new ArrayList<>();
    private static boolean activatedSnapshotInProgress;
    private static final List<Runnable> pendingActivated = new ArrayList<>();

    private ClientWaypointState() {
    }

    public static int getRevision() {
        return revision;
    }

    public static boolean isInitialized() {
        return initialized;
    }

    /** Applies one page of the current dimension snapshot; resets on page 0 and flushes queued increments when done. */
    public static void applyDimensionSnapshot(ResourceLocation dimension, List<WaypointSyncInfo> infos, int page, boolean done) {
        ResourceKey<Level> dim = ResourceKey.create(Registries.DIMENSION, dimension);
        if (page == 0) {
            // A new dimension snapshot invalidates any previous partial state and queued increments.
            currentDimension = dim;
            initialized = false;
            pending.clear();
            waypoints = Map.of();
        }
        Map<UUID, ClientWaypointInfo> map = new HashMap<>(waypoints);
        for (WaypointSyncInfo info : infos) {
            map.put(info.uid(), toClientInfo(info));
        }
        waypoints = Map.copyOf(map);
        if (done) {
            initialized = true;
            revision++;
            syncActivatedNamesFromWaypoints();
            flushPending();
        }
    }

    /** Applies one page of the activated-waypoint snapshot; resets on page 0 and publishes on done. */
    public static void applyActivatedSnapshot(List<ActivatedWaypointInfo> infos, int page, boolean done) {
        if (page == 0) {
            activatedSnapshot = new ArrayList<>();
            activatedSnapshotInProgress = true;
            pendingActivated.clear();
        }
        activatedSnapshot.addAll(infos);
        if (done) {
            activated = List.copyOf(activatedSnapshot);
            Set<UUID> set = new HashSet<>();
            for (ActivatedWaypointInfo info : activated) {
                set.add(info.uid());
            }
            activatedUids = Set.copyOf(set);
            activatedSnapshot = new ArrayList<>();
            activatedSnapshotInProgress = false;
            revision++;
            flushPendingActivated();
        }
    }

    public static void applyAdd(WaypointSyncInfo info) {
        if (!initialized) {
            pending.add(() -> applyAdd(info));
            return;
        }
        ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, info.dimension());
        if (!dimension.equals(currentDimension)) {
            return;
        }
        Map<UUID, ClientWaypointInfo> map = new HashMap<>(waypoints);
        map.put(info.uid(), toClientInfo(info));
        waypoints = Map.copyOf(map);
        revision++;
    }

    public static void applyUpdate(WaypointSyncInfo info) {
        if (!initialized) {
            pending.add(() -> applyUpdate(info));
            return;
        }
        ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, info.dimension());
        if (dimension.equals(currentDimension)) {
            Map<UUID, ClientWaypointInfo> map = new HashMap<>(waypoints);
            map.put(info.uid(), toClientInfo(info));
            waypoints = Map.copyOf(map);
        }

        // Keep activated-list display names in sync for every player who has this waypoint activated.
        List<ActivatedWaypointInfo> list = new ArrayList<>(activated);
        boolean activatedChanged = false;
        for (int i = 0; i < list.size(); i++) {
            ActivatedWaypointInfo activatedInfo = list.get(i);
            if (activatedInfo.uid().equals(info.uid())) {
                list.set(i, new ActivatedWaypointInfo(activatedInfo.uid(), activatedInfo.pocket(), info.name()));
                activatedChanged = true;
            }
        }
        if (activatedChanged) {
            activated = List.copyOf(list);
        }
        revision++;
    }

    /** Styles can arrive between snapshot pages; apply them only after the base snapshot is complete. */
    public static void applyIconStyles(ResourceLocation dimension, List<SyncMapIconStylesPayload.Entry> styles) {
        if (!initialized) {
            pending.add(() -> applyIconStyles(dimension, styles));
            return;
        }
        if (!ResourceKey.create(Registries.DIMENSION, dimension).equals(currentDimension)) return;
        Map<UUID, ClientWaypointInfo> map = new HashMap<>(waypoints);
        boolean changed = false;
        for (var entry : styles) {
            ClientWaypointInfo info = map.get(entry.uid());
            if (info == null || info.iconStyle().equals(entry.style())) continue;
            map.put(entry.uid(), new ClientWaypointInfo(info.uid(), info.dimension(), info.pos(),
                    info.pocket(), info.name(), entry.style()));
            changed = true;
        }
        if (changed) {
            waypoints = Map.copyOf(map);
            revision++;
        }
    }

    public static void applyRemove(UUID uid) {
        if (!initialized) {
            pending.add(() -> applyRemove(uid));
            return;
        }
        if (!waypoints.containsKey(uid)) {
            return;
        }
        Map<UUID, ClientWaypointInfo> map = new HashMap<>(waypoints);
        map.remove(uid);
        waypoints = Map.copyOf(map);
        revision++;
    }

    public static void applyActivatedAdd(ActivatedWaypointInfo info) {
        if (!initialized) {
            pending.add(() -> applyActivatedAdd(info));
            return;
        }
        if (activatedSnapshotInProgress) {
            pendingActivated.add(() -> applyActivatedAdd(info));
            return;
        }
        List<ActivatedWaypointInfo> list = new ArrayList<>(activated);
        if (activatedUids.contains(info.uid())) {
            list.replaceAll(existing -> existing.uid().equals(info.uid()) ? info : existing);
        } else {
            list.add(info);
        }
        activated = List.copyOf(list);
        Set<UUID> set = new HashSet<>(activatedUids);
        set.add(info.uid());
        activatedUids = Set.copyOf(set);
        revision++;
    }

    public static void applyActivatedRemove(UUID uid) {
        if (!initialized) {
            pending.add(() -> applyActivatedRemove(uid));
            return;
        }
        if (activatedSnapshotInProgress) {
            pendingActivated.add(() -> applyActivatedRemove(uid));
            return;
        }
        if (!activatedUids.contains(uid)) {
            return;
        }
        activated = activated.stream().filter(info -> !info.uid().equals(uid)).toList();
        Set<UUID> set = new HashSet<>(activatedUids);
        set.remove(uid);
        activatedUids = Set.copyOf(set);
        revision++;
    }

    private static void syncActivatedNamesFromWaypoints() {
        boolean changed = false;
        List<ActivatedWaypointInfo> list = new ArrayList<>(activated);
        for (int i = 0; i < list.size(); i++) {
            ActivatedWaypointInfo activatedInfo = list.get(i);
            ClientWaypointInfo full = waypoints.get(activatedInfo.uid());
            if (full != null && !full.name().equals(activatedInfo.name())) {
                list.set(i, new ActivatedWaypointInfo(activatedInfo.uid(), full.pocket(), full.name()));
                changed = true;
            }
        }
        if (changed) {
            activated = List.copyOf(list);
        }
    }

    public static List<ClientWaypointInfo> getWaypoints() {
        return List.copyOf(waypoints.values());
    }

    public static List<ClientWaypointInfo> getWaypointsIn(ResourceKey<Level> dimension) {
        List<ClientWaypointInfo> result = new ArrayList<>();
        for (ClientWaypointInfo info : waypoints.values()) {
            if (info.dimension().equals(dimension)) {
                result.add(info);
            }
        }
        return result;
    }

    public static ClientWaypointInfo getWaypoint(UUID uid) {
        return waypoints.get(uid);
    }

    public static boolean isActivated(UUID uid) {
        return uid != null && activatedUids.contains(uid);
    }

    public static List<ActivatedWaypointInfo> getActivated() {
        return activated;
    }

    public static void removeActivated(UUID uid) {
        if (uid == null) {
            return;
        }
        activated = activated.stream().filter(info -> !info.uid().equals(uid)).toList();
        if (activatedUids.contains(uid)) {
            Set<UUID> set = new HashSet<>(activatedUids);
            set.remove(uid);
            activatedUids = Set.copyOf(set);
        }
        revision++;
    }

    /** Clears all cached state, e.g. when leaving a server. */
    public static void reset() {
        waypoints = Map.of();
        activated = List.of();
        activatedUids = Set.of();
        currentDimension = null;
        initialized = false;
        pending.clear();
        activatedSnapshot = new ArrayList<>();
        activatedSnapshotInProgress = false;
        pendingActivated.clear();
        revision++;
    }

    private static ClientWaypointInfo toClientInfo(WaypointSyncInfo info) {
        return new ClientWaypointInfo(
                info.uid(),
                ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, info.dimension()),
                info.pos(),
                info.pocket(),
                info.name(), MapIconStyle.DEFAULT);
    }

    private static void flushPending() {
        List<Runnable> copy = new ArrayList<>(pending);
        pending.clear();
        for (Runnable runnable : copy) {
            runnable.run();
        }
    }

    private static void flushPendingActivated() {
        List<Runnable> copy = new ArrayList<>(pendingActivated);
        pendingActivated.clear();
        for (Runnable runnable : copy) {
            runnable.run();
        }
    }
}
