package com.zonlong.teleportwaypoint.client;

import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import com.zonlong.teleportwaypoint.network.MapIconStyle;

/**
 * Client-side representation of a waypoint known to the server, activated or not.
 */
public record ClientWaypointInfo(
        UUID uid,
        ResourceKey<Level> dimension,
        BlockPos pos,
        boolean pocket,
        String name,
        MapIconStyle iconStyle
) {
    /**
     * Returns the display name: pocket waypoints use their literal name, regular
     * waypoints use the translation key based on their raw id.
     */
    public Component displayName() {
        return pocket ? Component.literal(name) : Component.translatable("teleportwaypoint.waypoint." + name);
    }
}
