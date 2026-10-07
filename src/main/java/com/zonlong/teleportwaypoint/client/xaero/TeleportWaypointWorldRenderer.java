package com.zonlong.teleportwaypoint.client.xaero;

import com.zonlong.teleportwaypoint.TeleportWaypoint;
import com.zonlong.teleportwaypoint.client.ClientMapIcons;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.resources.ResourceLocation;

import xaero.map.element.render.ElementRenderInfo;
import xaero.map.element.render.ElementRenderLocation;
import xaero.map.element.render.ElementRenderProvider;
import xaero.map.element.render.ElementReader;
import xaero.map.element.render.ElementRenderer;
import xaero.map.graphics.renderer.multitexture.MultiTextureRenderTypeRendererProvider;

/**
 * Renders teleport waypoint markers on Xaero's world map using dedicated crystal
 * icons: red for inactive regular waypoints, cyan for active regular waypoints,
 * yellow for inactive pocket waypoints and green for active pocket waypoints.
 * Hover names are shown via {@link TeleportWaypointTooltip} above the mouse.
 */
public class TeleportWaypointWorldRenderer
        extends ElementRenderer<TeleportWaypointElement, TeleportWaypointContext, TeleportWaypointWorldRenderer> {

    private static final int ICON_SIZE = 32;
    private static final ResourceLocation WAYPOINT_ACTIVE = ResourceLocation.fromNamespaceAndPath(
            TeleportWaypoint.MODID, "textures/gui/waypoint_active.png");
    private static final ResourceLocation WAYPOINT_INACTIVE = ResourceLocation.fromNamespaceAndPath(
            TeleportWaypoint.MODID, "textures/gui/waypoint_inactive.png");
    private static final ResourceLocation POCKET_WAYPOINT_ACTIVE = ResourceLocation.fromNamespaceAndPath(
            TeleportWaypoint.MODID, "textures/gui/pocket_waypoint_active.png");
    private static final ResourceLocation POCKET_WAYPOINT_INACTIVE = ResourceLocation.fromNamespaceAndPath(
            TeleportWaypoint.MODID, "textures/gui/pocket_waypoint_inactive.png");

    static int iconSize(TeleportWaypointElement element) {
        var style = element.info().iconStyle();
        return ClientMapIcons.resolve(element.activated() ? style.active() : style.inactive()) == null
                ? ICON_SIZE : Math.max(8, Math.min(64, style.size()));
    }

    public TeleportWaypointWorldRenderer(
            TeleportWaypointContext context,
            ElementRenderProvider<TeleportWaypointElement, TeleportWaypointContext> provider,
            ElementReader<TeleportWaypointElement, TeleportWaypointContext, TeleportWaypointWorldRenderer> reader) {
        super(context, provider, reader);
    }

    @Override
    public void preRender(
            ElementRenderInfo renderInfo,
            MultiBufferSource.BufferSource bufferSource,
            MultiTextureRenderTypeRendererProvider rendererProvider,
            boolean hovered) {
        getContext().mapDimension = renderInfo.mapDimension;
    }

    @Override
    public void postRender(
            ElementRenderInfo renderInfo,
            MultiBufferSource.BufferSource bufferSource,
            MultiTextureRenderTypeRendererProvider rendererProvider,
            boolean hovered) {
    }

    @Override
    public void renderElementShadow(
            TeleportWaypointElement element,
            boolean hovered,
            float partialTicks,
            double x,
            double z,
            ElementRenderInfo renderInfo,
            GuiGraphics guiGraphics,
            MultiBufferSource.BufferSource bufferSource,
            MultiTextureRenderTypeRendererProvider rendererProvider) {
    }

    @Override
    public boolean renderElement(
            TeleportWaypointElement element,
            boolean hovered,
            double depth,
            float scale,
            double partialX,
            double partialZ,
            ElementRenderInfo renderInfo,
            GuiGraphics guiGraphics,
            MultiBufferSource.BufferSource bufferSource,
            MultiTextureRenderTypeRendererProvider rendererProvider) {
        ResourceLocation texture;
        if (!element.activated()) {
            texture = element.pocket() ? POCKET_WAYPOINT_INACTIVE : WAYPOINT_INACTIVE;
        } else {
            texture = element.pocket() ? POCKET_WAYPOINT_ACTIVE : WAYPOINT_ACTIVE;
        }
        var style = element.info().iconStyle();
        ResourceLocation custom = ClientMapIcons.resolve(element.activated() ? style.active() : style.inactive());
        int size = custom == null ? ICON_SIZE : Math.max(8, Math.min(64, style.size()));
        if (custom != null) texture = custom;
        int half = size / 2;
        guiGraphics.blit(texture, -half, -half, 0.0F, 0.0F, size, size, size, size);
        return true;
    }

    @Override
    public boolean shouldRender(ElementRenderLocation location, boolean hovered) {
        return location == ElementRenderLocation.WORLD_MAP;
    }

    @Override
    public int getOrder() {
        return 201;
    }

    @Override
    public boolean shouldBeDimScaled() {
        // Our reader returns actual block coordinates; Xaero's world map already
        // applies the current dimension's coordinate scale when rendering the map,
        // so we must not divide element coordinates by the dimension scale again.
        return false;
    }
}
