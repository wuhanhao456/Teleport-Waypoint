package com.zonlong.teleportwaypoint.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/** Resolved server-side rule. Empty sources retain the original crystal icon. */
public record MapIconStyle(String active, String inactive, int size, String symbol) {
    public static final MapIconStyle DEFAULT = new MapIconStyle("", "", 32, "");
    public static final StreamCodec<RegistryFriendlyByteBuf, MapIconStyle> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.stringUtf8(256), MapIconStyle::active,
                    ByteBufCodecs.stringUtf8(256), MapIconStyle::inactive,
                    ByteBufCodecs.VAR_INT, MapIconStyle::size,
                    ByteBufCodecs.stringUtf8(8), MapIconStyle::symbol,
                    MapIconStyle::new);
}
