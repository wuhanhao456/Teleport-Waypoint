package com.zonlong.teleportwaypoint.core;

import java.util.UUID;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

/**
 * A lightweight snapshot of a waypoint. {@code name} holds the raw display text: for pocket
 * waypoints it is the literal name, for structure waypoints it is the translation key suffix.
 */
public record WaypointRecord(UUID uid, ResourceKey<Level> dimension, BlockPos pos, boolean pocket, String name,
                             List<ResourceLocation> structures) {
    public WaypointRecord {
        structures = List.copyOf(structures);
    }
}
