package com.zonlong.teleportwaypoint.core;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.zonlong.teleportwaypoint.TeleportWaypoint;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.StringTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Global index mapping waypoint uid -> (dimension, pos, type, name). Persisted as world SavedData
 * and used to resolve teleport targets and (later) for Xaero map integration.
 */
public class WaypointRegistryData extends SavedData {
    private static final String DATA_NAME = "teleportwaypoint_waypoints";
    private static final String TAG_WAYPOINTS = "waypoints";

    private final Map<UUID, WaypointRecord> waypoints = new HashMap<>();

    public static WaypointRegistryData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(WaypointRegistryData::new, WaypointRegistryData::read, null),
                DATA_NAME);
    }

    public void put(WaypointRecord record) {
        waypoints.put(record.uid(), record);
        setDirty();
    }

    public boolean remove(UUID uid) {
        if (waypoints.remove(uid) != null) {
            setDirty();
            return true;
        }
        return false;
    }

    public boolean removeIfAt(UUID uid, ResourceKey<Level> dimension, BlockPos pos) {
        WaypointRecord record = waypoints.get(uid);
        if (record == null || !record.dimension().equals(dimension) || !record.pos().equals(pos)) {
            return false;
        }
        waypoints.remove(uid);
        setDirty();
        return true;
    }

    public Optional<WaypointRecord> get(UUID uid) {
        return Optional.ofNullable(waypoints.get(uid));
    }

    public java.util.Collection<WaypointRecord> getAll() {
        return waypoints.values();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (WaypointRecord record : waypoints.values()) {
            CompoundTag entry = new CompoundTag();
            entry.put("uid", NbtUtils.createUUID(record.uid()));
            entry.putString("dimension", record.dimension().location().toString());
            entry.putLong("pos", record.pos().asLong());
            entry.putBoolean("pocket", record.pocket());
            entry.putString("name", record.name());
            ListTag structures = new ListTag();
            record.structures().forEach(id -> structures.add(StringTag.valueOf(id.toString())));
            entry.put("structures", structures);
            list.add(entry);
        }
        tag.put(TAG_WAYPOINTS, list);
        return tag;
    }

    public static WaypointRegistryData read(CompoundTag tag, HolderLookup.Provider registries) {
        WaypointRegistryData data = new WaypointRegistryData();
        ListTag list = tag.getList(TAG_WAYPOINTS, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            try {
                CompoundTag entry = list.getCompound(i);
                UUID uid = NbtUtils.loadUUID(entry.get("uid"));
                ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION,
                        ResourceLocation.parse(entry.getString("dimension")));
                BlockPos pos = BlockPos.of(entry.getLong("pos"));
                boolean pocket = entry.getBoolean("pocket");
                String name = entry.getString("name");
                var structures = new java.util.ArrayList<ResourceLocation>();
                ListTag storedStructures = entry.getList("structures", Tag.TAG_STRING);
                for (int j = 0; j < storedStructures.size() && j < 32; j++) {
                    ResourceLocation structure = ResourceLocation.tryParse(storedStructures.getString(j));
                    if (structure != null) structures.add(structure);
                }
                data.waypoints.put(uid, new WaypointRecord(uid, dimension, pos, pocket, name, structures));
            } catch (Exception e) {
                TeleportWaypoint.LOGGER.warn("[TeleportWaypoint] Skipping invalid waypoint registry entry at index {}", i, e);
            }
        }
        return data;
    }
}
