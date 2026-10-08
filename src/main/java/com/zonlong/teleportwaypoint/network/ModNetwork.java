package com.zonlong.teleportwaypoint.network;

import com.zonlong.teleportwaypoint.block.entity.WaypointBlockEntity;
import com.zonlong.teleportwaypoint.client.ClientWaypointState;
import com.zonlong.teleportwaypoint.client.ClientMapIcons;
import com.zonlong.teleportwaypoint.core.WaypointManager;
import com.zonlong.teleportwaypoint.core.WaypointTeleporter;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

public class ModNetwork {

    public static void register(final RegisterPayloadHandlersEvent event) {
        final PayloadRegistrar registrar = event.registrar("4");

        // Missing icon channels must not prevent a protocol-4 client/server from joining.
        final PayloadRegistrar icons = event.registrar("map-icons-1").optional();
        icons.playToClient(SyncMapIconImagePayload.TYPE, SyncMapIconImagePayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> ClientMapIcons.apply(payload)));
        icons.playToClient(SyncMapIconStylesPayload.TYPE, SyncMapIconStylesPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() ->
                        ClientWaypointState.applyIconStyles(payload.dimension(), payload.styles())));

        registrar.playToClient(
                SyncActivatedWaypointsPayload.TYPE,
                SyncActivatedWaypointsPayload.STREAM_CODEC,
                ModNetwork::handleSyncActivated);

        registrar.playToClient(
                SyncDimensionWaypointsPayload.TYPE,
                SyncDimensionWaypointsPayload.STREAM_CODEC,
                ModNetwork::handleSyncDimensionWaypoints);

        registrar.playToClient(
                AddWaypointPayload.TYPE,
                AddWaypointPayload.STREAM_CODEC,
                ModNetwork::handleAddWaypoint);

        registrar.playToClient(
                UpdateWaypointPayload.TYPE,
                UpdateWaypointPayload.STREAM_CODEC,
                ModNetwork::handleUpdateWaypoint);

        registrar.playToClient(
                RemoveWaypointPayload.TYPE,
                RemoveWaypointPayload.STREAM_CODEC,
                ModNetwork::handleRemoveWaypoint);

        registrar.playToClient(
                ActivatedWaypointAddPayload.TYPE,
                ActivatedWaypointAddPayload.STREAM_CODEC,
                ModNetwork::handleActivatedWaypointAdd);

        registrar.playToClient(
                ActivatedWaypointRemovePayload.TYPE,
                ActivatedWaypointRemovePayload.STREAM_CODEC,
                ModNetwork::handleActivatedWaypointRemove);

        registrar.playToServer(
                TeleportRequestPayload.TYPE,
                TeleportRequestPayload.STREAM_CODEC,
                ModNetwork::handleTeleportRequest);

        registrar.playToServer(
                MapTeleportRequestPayload.TYPE,
                MapTeleportRequestPayload.STREAM_CODEC,
                ModNetwork::handleMapTeleportRequest);

        registrar.playToServer(
                RenameWaypointPayload.TYPE,
                RenameWaypointPayload.STREAM_CODEC,
                ModNetwork::handleRename);

        registrar.playToServer(
                OpenRenameScreenPayload.TYPE,
                OpenRenameScreenPayload.STREAM_CODEC,
                ModNetwork::handleOpenRename);

        registrar.playToServer(
                DeleteWaypointPayload.TYPE,
                DeleteWaypointPayload.STREAM_CODEC,
                ModNetwork::handleDelete);
    }

    /** Never send extension packets to an original client that did not negotiate them. */
    public static boolean supportsMapIcons(ServerPlayer player) {
        return NetworkRegistry.hasChannel(player.connection, SyncMapIconImagePayload.TYPE.id())
                && NetworkRegistry.hasChannel(player.connection, SyncMapIconStylesPayload.TYPE.id());
    }

    private static void handleSyncActivated(final SyncActivatedWaypointsPayload payload, final IPayloadContext context) {
        context.enqueueWork(() -> ClientWaypointState.applyActivatedSnapshot(payload.waypoints(), payload.page(), payload.done()));
    }

    private static void handleSyncDimensionWaypoints(final SyncDimensionWaypointsPayload payload, final IPayloadContext context) {
        context.enqueueWork(() -> ClientWaypointState.applyDimensionSnapshot(payload.dimension(), payload.waypoints(), payload.page(), payload.done()));
    }

    private static void handleAddWaypoint(final AddWaypointPayload payload, final IPayloadContext context) {
        context.enqueueWork(() -> ClientWaypointState.applyAdd(payload.waypoint()));
    }

    private static void handleUpdateWaypoint(final UpdateWaypointPayload payload, final IPayloadContext context) {
        context.enqueueWork(() -> ClientWaypointState.applyUpdate(payload.waypoint()));
    }

    private static void handleRemoveWaypoint(final RemoveWaypointPayload payload, final IPayloadContext context) {
        context.enqueueWork(() -> ClientWaypointState.applyRemove(payload.uid()));
    }

    private static void handleActivatedWaypointAdd(final ActivatedWaypointAddPayload payload, final IPayloadContext context) {
        context.enqueueWork(() -> ClientWaypointState.applyActivatedAdd(payload.info()));
    }

    private static void handleActivatedWaypointRemove(final ActivatedWaypointRemovePayload payload, final IPayloadContext context) {
        context.enqueueWork(() -> ClientWaypointState.applyActivatedRemove(payload.uid()));
    }

    private static void handleTeleportRequest(final TeleportRequestPayload payload, final IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer serverPlayer) {
                WaypointTeleporter.teleport(serverPlayer, payload.source(), payload.target());
            }
        });
    }

    private static void handleMapTeleportRequest(final MapTeleportRequestPayload payload, final IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer serverPlayer) {
                WaypointTeleporter.teleportTo(serverPlayer, payload.target());
            }
        });
    }

    private static void handleRename(final RenameWaypointPayload payload, final IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer serverPlayer)) {
                return;
            }
            BlockEntity blockEntity = serverPlayer.level().getBlockEntity(payload.pos());
            if (!(blockEntity instanceof WaypointBlockEntity waypointEntity)) {
                return;
            }
            if (!WaypointManager.canRename(serverPlayer, waypointEntity)) {
                return;
            }
            String text = payload.text();
            if (waypointEntity.isPocketWaypoint()) {
                if (!WaypointBlockEntity.isValidName(text)) {
                    return;
                }
                waypointEntity.setName(text);
            } else {
                String newId = text.isEmpty() ? "empty" : text;
                if (!WaypointBlockEntity.isValidId(newId)) {
                    return;
                }
                waypointEntity.setId(newId);
            }
            serverPlayer.level().sendBlockUpdated(payload.pos(), waypointEntity.getBlockState(), waypointEntity.getBlockState(), 3);
            // Update the global registry; register() broadcasts an UpdateWaypointPayload when the record changed.
            WaypointManager.register(waypointEntity);
            WaypointManager.syncTo(serverPlayer);
            // After renaming, open the waypoint list.
            waypointEntity.openListScreen(serverPlayer);
        });
    }

    private static void handleOpenRename(final OpenRenameScreenPayload payload, final IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer serverPlayer)) {
                return;
            }
            BlockEntity blockEntity = serverPlayer.level().getBlockEntity(payload.pos());
            if (!(blockEntity instanceof WaypointBlockEntity waypointEntity)) {
                return;
            }
            if (!WaypointManager.canRename(serverPlayer, waypointEntity)) {
                return;
            }
            waypointEntity.openRenameScreen(serverPlayer);
        });
    }

    private static void handleDelete(final DeleteWaypointPayload payload, final IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer serverPlayer) {
                WaypointManager.deactivate(serverPlayer, payload.uid());
            }
        });
    }
}
