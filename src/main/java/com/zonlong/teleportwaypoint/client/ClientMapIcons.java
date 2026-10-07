package com.zonlong.teleportwaypoint.client;

import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.Map;

import com.mojang.blaze3d.platform.NativeImage;
import com.zonlong.teleportwaypoint.TeleportWaypoint;
import com.zonlong.teleportwaypoint.datapack.MapIconData;
import com.zonlong.teleportwaypoint.network.SyncMapIconImagePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

/** Owns server-provided GPU textures; releases them on reload and disconnect. */
public final class ClientMapIcons {
    private static final Map<ResourceLocation, ResourceLocation> textures = new HashMap<>();
    private static final Map<ResourceLocation, Boolean> available = new HashMap<>();

    private ClientMapIcons() {}

    public static void apply(SyncMapIconImagePayload payload) {
        if (payload.reset()) {
            reset();
            return;
        }
        if (textures.size() >= SyncMapIconImagePayload.MAX_IMAGES || !MapIconData.isValidPng(payload.png())) return;
        NativeImage image = null;
        DynamicTexture texture = null;
        try {
            image = NativeImage.read(new ByteArrayInputStream(payload.png()));
            ResourceLocation location = ResourceLocation.fromNamespaceAndPath(TeleportWaypoint.MODID,
                    "datapack_icons/" + payload.id().getNamespace() + "/" + payload.id().getPath());
            var manager = Minecraft.getInstance().getTextureManager();
            ResourceLocation previous = textures.remove(payload.id());
            if (previous != null) manager.release(previous);
            texture = new DynamicTexture(image);
            manager.register(location, texture);
            textures.put(payload.id(), location);
        } catch (Exception e) {
            if (texture != null) texture.close();
            else if (image != null) image.close();
            TeleportWaypoint.LOGGER.warn("Could not decode map icon {}: {}", payload.id(), e.getMessage());
        }
    }

    /** Missing images retain the original state-specific icon. */
    public static ResourceLocation resolve(String source) {
        if (source.startsWith("data:")) {
            ResourceLocation id = ResourceLocation.tryParse(source.substring(5));
            return id == null ? null : textures.get(id);
        }
        if (source.startsWith("texture:")) {
            ResourceLocation id = ResourceLocation.tryParse(source.substring(8));
            if (id != null && available.computeIfAbsent(id,
                    key -> Minecraft.getInstance().getResourceManager().getResource(key).isPresent())) return id;
        }
        return null;
    }

    public static void clearResourceCache() {
        available.clear();
    }

    public static void reset() {
        var manager = Minecraft.getInstance().getTextureManager();
        textures.values().forEach(manager::release);
        textures.clear();
        available.clear();
    }
}
