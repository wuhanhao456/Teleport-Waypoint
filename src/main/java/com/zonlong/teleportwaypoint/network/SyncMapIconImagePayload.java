package com.zonlong.teleportwaypoint.network;

import com.zonlong.teleportwaypoint.TeleportWaypoint;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Each image is a separate bounded packet; a reset packet starts every snapshot. */
public record SyncMapIconImagePayload(boolean reset, ResourceLocation id, byte[] png) implements CustomPacketPayload {
    public static final int MAX_BYTES = 128 * 1024;
    public static final int MAX_IMAGES = 64;
    public static final int MAX_DIMENSION = 128;
    public static final Type<SyncMapIconImagePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(TeleportWaypoint.MODID, "sync_map_icon_image"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SyncMapIconImagePayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.BOOL, SyncMapIconImagePayload::reset,
                    ResourceLocation.STREAM_CODEC, SyncMapIconImagePayload::id,
                    ByteBufCodecs.byteArray(MAX_BYTES), SyncMapIconImagePayload::png,
                    SyncMapIconImagePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
