package com.zonlong.teleportwaypoint.network;

import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;

/**
 * Client-facing snapshot of a waypoint's full data. Sent by
 * {@link SyncDimensionWaypointsPayload} and incremental waypoint payloads so
 * Xaero map integrations can render waypoints.
 */
public record WaypointSyncInfo(UUID uid, ResourceLocation dimension, BlockPos pos, boolean pocket, String name,
                               MapIconStyle iconStyle) {
    public static final StreamCodec<RegistryFriendlyByteBuf, WaypointSyncInfo> STREAM_CODEC =
            StreamCodec.composite(
                    net.minecraft.core.UUIDUtil.STREAM_CODEC, WaypointSyncInfo::uid,
                    ResourceLocation.STREAM_CODEC, WaypointSyncInfo::dimension,
                    BlockPos.STREAM_CODEC, WaypointSyncInfo::pos,
                    ByteBufCodecs.BOOL, WaypointSyncInfo::pocket,
                    ByteBufCodecs.STRING_UTF8, WaypointSyncInfo::name,
                    MapIconStyle.STREAM_CODEC, WaypointSyncInfo::iconStyle,
                    WaypointSyncInfo::new);
}
