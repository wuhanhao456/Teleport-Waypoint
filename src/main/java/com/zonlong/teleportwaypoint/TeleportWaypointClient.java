package com.zonlong.teleportwaypoint;

import com.zonlong.teleportwaypoint.block.entity.ModBlockEntities;
import com.zonlong.teleportwaypoint.client.ClientWaypointState;
import com.zonlong.teleportwaypoint.client.ClientMapIcons;
import com.zonlong.teleportwaypoint.client.XaeroIntegrationLoader;
import com.zonlong.teleportwaypoint.client.gui.RenamePocketWaypointScreen;
import com.zonlong.teleportwaypoint.client.gui.RenameWaypointScreen;
import com.zonlong.teleportwaypoint.client.gui.WaypointListScreen;
import com.zonlong.teleportwaypoint.client.render.WaypointBlockEntityRenderer;
import com.zonlong.teleportwaypoint.menu.ModMenus;

import net.minecraft.client.resources.model.ModelResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;

// This class will not load on dedicated servers. Accessing client side code from here is safe.
@Mod(value = TeleportWaypoint.MODID, dist = Dist.CLIENT)
public class TeleportWaypointClient {
    public TeleportWaypointClient(IEventBus modEventBus, ModContainer container) {
        // Allows NeoForge to create a config screen for this mod's configs.
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);

        modEventBus.addListener(TeleportWaypointClient::onRegisterMenuScreens);
        modEventBus.addListener(TeleportWaypointClient::onRegisterEntityRenderers);
        modEventBus.addListener(TeleportWaypointClient::onRegisterAdditionalModels);
        modEventBus.addListener((RegisterClientReloadListenersEvent event) -> event.registerReloadListener(
                (net.minecraft.server.packs.resources.ResourceManagerReloadListener) manager -> ClientMapIcons.clearResourceCache()));

        NeoForge.EVENT_BUS.addListener(TeleportWaypointClient::onClientTick);
        NeoForge.EVENT_BUS.addListener(TeleportWaypointClient::onClientLoggingOut);
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        XaeroIntegrationLoader.tick();
    }

    private static void onClientLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientWaypointState.reset();
        ClientMapIcons.reset();
        XaeroIntegrationLoader.reset();
    }

    static void onRegisterMenuScreens(RegisterMenuScreensEvent event) {
        event.register(ModMenus.RENAME_WAYPOINT.get(), RenameWaypointScreen::new);
        event.register(ModMenus.RENAME_POCKET_WAYPOINT.get(), RenamePocketWaypointScreen::new);
        event.register(ModMenus.WAYPOINT_LIST.get(), WaypointListScreen::new);
    }

    /** 注册传送锚点方块实体渲染器（发光部件：柱顶水晶/晶核/能量环/光球） */
    static void onRegisterEntityRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(ModBlockEntities.WAYPOINT.get(), WaypointBlockEntityRenderer::new);
    }

    /** 注册发光部件附加模型（青/红/绿/黄配色组），供 BER 烘焙使用 */
    static void onRegisterAdditionalModels(ModelEvent.RegisterAdditional event) {
        for (ModelResourceLocation model : WaypointBlockEntityRenderer.CAPS_MODELS) {
            event.register(model);
        }
        for (ModelResourceLocation model : WaypointBlockEntityRenderer.CAPS_RED_MODELS) {
            event.register(model);
        }
        for (ModelResourceLocation model : WaypointBlockEntityRenderer.CAPS_GREEN_MODELS) {
            event.register(model);
        }
        for (ModelResourceLocation model : WaypointBlockEntityRenderer.CAPS_YELLOW_MODELS) {
            event.register(model);
        }
        event.register(WaypointBlockEntityRenderer.CRYSTAL_MODEL);
        event.register(WaypointBlockEntityRenderer.RING_MODEL);
        event.register(WaypointBlockEntityRenderer.ORB_MODEL);
        event.register(WaypointBlockEntityRenderer.CRYSTAL_RED_MODEL);
        event.register(WaypointBlockEntityRenderer.RING_RED_MODEL);
        event.register(WaypointBlockEntityRenderer.ORB_RED_MODEL);
        event.register(WaypointBlockEntityRenderer.CRYSTAL_GREEN_MODEL);
        event.register(WaypointBlockEntityRenderer.RING_GREEN_MODEL);
        event.register(WaypointBlockEntityRenderer.ORB_GREEN_MODEL);
        event.register(WaypointBlockEntityRenderer.CRYSTAL_YELLOW_MODEL);
        event.register(WaypointBlockEntityRenderer.RING_YELLOW_MODEL);
        event.register(WaypointBlockEntityRenderer.ORB_YELLOW_MODEL);
    }
}
