package com.zonlong.teleportwaypoint.network;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.zonlong.teleportwaypoint.TeleportWaypoint;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Optional styles follow the corresponding legacy snapshot page or waypoint update. */
public record SyncMapIconStylesPayload(ResourceLocation dimension, List<Entry> styles) implements CustomPacketPayload {
    public static final int MAX_ENTRIES = SyncDimensionWaypointsPayload.MAX_PAGE_SIZE;
    public static final Type<SyncMapIconStylesPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(TeleportWaypoint.MODID, "sync_map_icon_styles"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SyncMapIconStylesPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ResourceLocation.STREAM_CODEC, SyncMapIconStylesPayload::dimension,
                    ByteBufCodecs.collection(ArrayList::new, Entry.STREAM_CODEC, MAX_ENTRIES),
                    SyncMapIconStylesPayload::styles,
                    SyncMapIconStylesPayload::new);

    public SyncMapIconStylesPayload {
        styles = List.copyOf(styles);
        if (styles.size() > MAX_ENTRIES) throw new IllegalArgumentException("Too many waypoint icon styles");
    }

    public record Entry(UUID uid, MapIconStyle style) {
        public static final StreamCodec<RegistryFriendlyByteBuf, Entry> STREAM_CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, Entry::uid,
                MapIconStyle.STREAM_CODEC, Entry::style,
                Entry::new);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
