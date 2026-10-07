package com.zonlong.teleportwaypoint.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.zonlong.teleportwaypoint.block.entity.WaypointBlockEntity;
import com.zonlong.teleportwaypoint.network.ActivatedWaypointAddPayload;
import com.zonlong.teleportwaypoint.network.ActivatedWaypointInfo;
import com.zonlong.teleportwaypoint.network.ActivatedWaypointRemovePayload;
import com.zonlong.teleportwaypoint.network.AddWaypointPayload;
import com.zonlong.teleportwaypoint.network.RemoveWaypointPayload;
import com.zonlong.teleportwaypoint.network.SyncActivatedWaypointsPayload;
import com.zonlong.teleportwaypoint.network.SyncDimensionWaypointsPayload;
import com.zonlong.teleportwaypoint.network.UpdateWaypointPayload;
import com.zonlong.teleportwaypoint.network.WaypointSyncInfo;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.Registries;
import com.zonlong.teleportwaypoint.datapack.MapIconData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Facade over the two SavedData stores, plus the shared activation / use logic.
 */
public class WaypointManager {

    public static void register(WaypointBlockEntity be) {
        if (be.getLevel() == null || be.getLevel().isClientSide()) {
            return;
        }
        if (!(be.getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }
        WaypointRegistryData registry = WaypointRegistryData.get(serverLevel.getServer());
        UUID uid = ensureUniqueUid(be, registry, serverLevel);
        if (uid == null) {
            return;
        }
        String name;
        if (be.isPocketWaypoint()) {
            name = be.getName();
        } else {
            name = WaypointBlockEntity.isValidId(be.getId()) ? be.getId() : "empty";
        }
        WaypointRecord existing = registry.get(uid).orElse(null);
        List<ResourceLocation> structures = identifyStructures(serverLevel, be);
        WaypointRecord record = new WaypointRecord(uid, serverLevel.dimension(), be.getBlockPos(),
                be.isPocketWaypoint(), name, structures);
        if (existing == null) {
            registry.put(record);
            broadcastAdd(serverLevel.getServer(), record);
        } else if (!existing.equals(record)) {
            registry.put(record);
            broadcastUpdate(serverLevel.getServer(), record);
        } else {
            // Already registered with identical data; nothing to persist or broadcast.
        }
    }

    public static void unregister(WaypointBlockEntity be) {
        UUID uid = be.getExistingUid();
        if (uid == null || be.getLevel() == null || be.getLevel().isClientSide()) {
            return;
        }
        if (!(be.getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }
        WaypointRegistryData registry = WaypointRegistryData.get(serverLevel.getServer());
        WaypointRecord record = registry.get(uid).orElse(null);
        if (registry.removeIfAt(uid, serverLevel.dimension(), be.getBlockPos())) {
            removeWaypoint(serverLevel.getServer(), uid, record);
        }
    }

    public static boolean isActivated(ServerPlayer player, UUID uid) {
        return PlayerWaypointData.get(player.getServer()).isActivated(player.getUUID(), uid);
    }

    public static Set<UUID> getActivated(ServerPlayer player) {
        return PlayerWaypointData.get(player.getServer()).getActivated(player.getUUID());
    }

    public static void activate(ServerPlayer player, WaypointBlockEntity be, boolean showMessage) {
        UUID uid = be.getUid();
        if (uid == null) {
            return;
        }
        register(be);
        PlayerWaypointData.get(player.getServer()).activate(player.getUUID(), uid);

        // Send the incremental activated-add to the player. Pocket waypoints in the
        // player's current dimension also need their full data if not already present.
        WaypointRegistryData.get(player.getServer()).get(uid)
                .ifPresent(record -> {
                    PacketDistributor.sendToPlayer(player, new ActivatedWaypointAddPayload(toActivatedInfo(record)));
                    if (record.pocket() && record.dimension().equals(player.level().dimension())) {
                        PacketDistributor.sendToPlayer(player, new AddWaypointPayload(toSyncInfo(player.getServer(), record)));
                    }
                });

        // 激活音效：经验球拾取声，在方块位置播放（附近玩家可闻）
        if (be.getLevel() instanceof ServerLevel serverLevel) {
            serverLevel.playSound(null, be.getBlockPos(),
                    net.minecraft.sounds.SoundEvents.EXPERIENCE_ORB_PICKUP,
                    net.minecraft.sounds.SoundSource.BLOCKS, 1.0F, 1.0F);
        }

        if (showMessage) {
            String key = be.isPocketWaypoint() ? "chat.teleportwaypoint.pocket_waypoint_activated" : "chat.teleportwaypoint.waypoint_activated";
            player.sendSystemMessage(Component.translatable(key, be.getDisplayName()));
        }
    }

    public static void deactivate(ServerPlayer player, UUID uid) {
        PlayerWaypointData.get(player.getServer()).deactivate(player.getUUID(), uid);
        PacketDistributor.sendToPlayer(player, new ActivatedWaypointRemovePayload(uid));

        // Pocket waypoints in the current dimension are no longer visible once deactivated.
        WaypointRegistryData.get(player.getServer()).get(uid).ifPresent(record -> {
            if (record.pocket() && record.dimension().equals(player.level().dimension())) {
                PacketDistributor.sendToPlayer(player, new RemoveWaypointPayload(uid));
            }
        });
    }

    public static void removeWaypoint(MinecraftServer server, UUID uid) {
        WaypointRegistryData registry = WaypointRegistryData.get(server);
        removeWaypoint(server, uid, registry.get(uid).orElse(null));
    }

    private static void removeWaypoint(MinecraftServer server, UUID uid, WaypointRecord record) {
        WaypointRegistryData.get(server).remove(uid);
        Set<UUID> affectedPlayers = PlayerWaypointData.get(server).deactivateAll(uid);
        // Remove full data from clients that may hold it, and remove activated metadata
        // from every player that had it activated.
        broadcastRemove(server, record, uid, affectedPlayers);
        for (ServerPlayer onlinePlayer : server.getPlayerList().getPlayers()) {
            if (affectedPlayers.contains(onlinePlayer.getUUID())) {
                PacketDistributor.sendToPlayer(onlinePlayer, new ActivatedWaypointRemovePayload(uid));
            }
        }
    }

    public static boolean isValidTeleportRequest(ServerPlayer player, UUID sourceUid, UUID targetUid) {
        if (sourceUid == null || targetUid == null || sourceUid.equals(targetUid)
                || !isActivated(player, sourceUid) || !isActivated(player, targetUid)) {
            return false;
        }
        if (!(player.level() instanceof ServerLevel sourceLevel)) {
            return false;
        }

        WaypointRegistryData registry = WaypointRegistryData.get(sourceLevel.getServer());
        WaypointRecord source = registry.get(sourceUid).orElse(null);
        if (source == null || !source.dimension().equals(sourceLevel.dimension())
                || player.distanceToSqr(source.pos().getX() + 0.5, source.pos().getY() + 0.5, source.pos().getZ() + 0.5) > 64.0) {
            return false;
        }
        if (!(sourceLevel.getBlockEntity(source.pos()) instanceof WaypointBlockEntity sourceEntity)
                || !sourceUid.equals(sourceEntity.getExistingUid())) {
            return false;
        }
        return true;
    }

    /**
     * Sends the player's current activated-waypoint list (with names) to the client.
     * Used on login and after rename so display names stay fresh.
     */
    public static void syncTo(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        List<ActivatedWaypointInfo> infos = new ArrayList<>();
        WaypointRegistryData registry = WaypointRegistryData.get(server);
        for (UUID uid : getActivated(player)) {
            registry.get(uid).ifPresent(record -> infos.add(toActivatedInfo(record)));
        }

        int pageSize = SyncActivatedWaypointsPayload.MAX_PAGE_SIZE;
        int total = infos.size();
        int pages = Math.max(1, (total + pageSize - 1) / pageSize);
        for (int page = 0; page < pages; page++) {
            int from = page * pageSize;
            int to = Math.min(total, from + pageSize);
            List<ActivatedWaypointInfo> pageEntries = infos.subList(from, to);
            PacketDistributor.sendToPlayer(player,
                    new SyncActivatedWaypointsPayload(pageEntries, page, page == pages - 1));
        }
    }

    /**
     * Sends the full waypoint data for the player's current dimension: every normal
     * waypoint plus every pocket waypoint the player has activated in that dimension.
     */
    public static void syncDimensionTo(ServerPlayer player) {
        syncDimensionTo(player, player.level().dimension());
    }

    /**
     * Sends the full waypoint data for the given dimension to the player, paginated
     * so large dimensions do not exceed the network packet size limit.
     */
    public static void syncDimensionTo(ServerPlayer player, ResourceKey<Level> dimension) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        List<WaypointSyncInfo> infos = new ArrayList<>();
        WaypointRegistryData registry = WaypointRegistryData.get(server);
        Set<UUID> activated = getActivated(player);
        for (WaypointRecord record : registry.getAll()) {
            if (!record.dimension().equals(dimension)) {
                continue;
            }
            if (record.pocket() && !activated.contains(record.uid())) {
                continue;
            }
            infos.add(toSyncInfo(server, record));
        }

        int pageSize = SyncDimensionWaypointsPayload.MAX_PAGE_SIZE;
        int total = infos.size();
        int pages = Math.max(1, (total + pageSize - 1) / pageSize);
        for (int page = 0; page < pages; page++) {
            int from = page * pageSize;
            int to = Math.min(total, from + pageSize);
            List<WaypointSyncInfo> pageEntries = infos.subList(from, to);
            PacketDistributor.sendToPlayer(player,
                    new SyncDimensionWaypointsPayload(dimension.location(), pageEntries, page, page == pages - 1));
        }
    }

    public static boolean canRename(Player player, WaypointBlockEntity be) {
        if (player.isCreative()) {
            return true;
        }
        return be.isPocketWaypoint() && be.getOwner() != null && be.getOwner().equals(player.getUUID());
    }

    public static InteractionResult onUse(Level level, BlockPos pos, Player player, WaypointBlockEntity be) {
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.FAIL;
        }
        UUID uid = be.getUid();
        if (uid == null) {
            return InteractionResult.FAIL;
        }
        if (!isActivated(serverPlayer, uid)) {
            // 未解锁：仅激活（提示 + 音效），不弹 GUI；再次右键（已解锁）才打开列表
            activate(serverPlayer, be, true);
            return InteractionResult.SUCCESS;
        }
        be.openListScreen(serverPlayer);
        return InteractionResult.SUCCESS;
    }

    private static void broadcastAdd(MinecraftServer server, WaypointRecord record) {
        WaypointSyncInfo info = toSyncInfo(server, record);
        for (ServerPlayer onlinePlayer : server.getPlayerList().getPlayers()) {
            if (record.pocket()) {
                // Pocket waypoints are only sent to players who activated them and are in the same dimension.
                if (onlinePlayer.level().dimension().equals(record.dimension())
                        && isActivated(onlinePlayer, record.uid())) {
                    PacketDistributor.sendToPlayer(onlinePlayer, new AddWaypointPayload(info));
                }
            } else if (onlinePlayer.level().dimension().equals(record.dimension())) {
                // Normal waypoints are fully synced per dimension.
                PacketDistributor.sendToPlayer(onlinePlayer, new AddWaypointPayload(info));
            }
        }
    }

    private static void broadcastUpdate(MinecraftServer server, WaypointRecord record) {
        WaypointSyncInfo info = toSyncInfo(server, record);
        ActivatedWaypointInfo activatedInfo = toActivatedInfo(record);
        for (ServerPlayer onlinePlayer : server.getPlayerList().getPlayers()) {
            boolean activated = isActivated(onlinePlayer, record.uid());
            boolean sameDimension = onlinePlayer.level().dimension().equals(record.dimension());
            if (record.pocket()) {
                // Same-dimension activated players get the full data update; players in
                // other dimensions only need the metadata/name update.
                if (activated && sameDimension) {
                    PacketDistributor.sendToPlayer(onlinePlayer, new UpdateWaypointPayload(info));
                } else if (activated) {
                    PacketDistributor.sendToPlayer(onlinePlayer, new ActivatedWaypointAddPayload(activatedInfo));
                }
            } else if (sameDimension) {
                PacketDistributor.sendToPlayer(onlinePlayer, new UpdateWaypointPayload(info));
            } else if (activated) {
                // Normal waypoint names are also used by the cross-dimension teleport list.
                PacketDistributor.sendToPlayer(onlinePlayer, new ActivatedWaypointAddPayload(activatedInfo));
            }
        }
    }

    private static void broadcastRemove(MinecraftServer server, WaypointRecord record, UUID uid, Set<UUID> affectedPlayers) {
        if (record == null) {
            // Unknown record: fall back to broadcasting the removal to everyone.
            for (ServerPlayer onlinePlayer : server.getPlayerList().getPlayers()) {
                PacketDistributor.sendToPlayer(onlinePlayer, new RemoveWaypointPayload(uid));
            }
            return;
        }
        for (ServerPlayer onlinePlayer : server.getPlayerList().getPlayers()) {
            if (!onlinePlayer.level().dimension().equals(record.dimension())) {
                continue;
            }
            if (record.pocket() && !affectedPlayers.contains(onlinePlayer.getUUID())) {
                continue;
            }
            PacketDistributor.sendToPlayer(onlinePlayer, new RemoveWaypointPayload(uid));
        }
    }

    private static WaypointSyncInfo toSyncInfo(MinecraftServer server, WaypointRecord record) {
        return new WaypointSyncInfo(
                record.uid(),
                record.dimension().location(),
                record.pos(),
                record.pocket(),
                record.name(), MapIconData.resolve(server, record));
    }

    private static ActivatedWaypointInfo toActivatedInfo(WaypointRecord record) {
        return new ActivatedWaypointInfo(record.uid(), record.pocket(), record.name());
    }

    /** Detect structure registry keys once when registering, never during map rendering. */
    private static List<ResourceLocation> identifyStructures(ServerLevel level, WaypointBlockEntity be) {
        if (be.getStructureId() != null) return List.of(be.getStructureId());
        var registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        return level.structureManager().getAllStructuresAt(be.getBlockPos()).keySet().stream()
                .filter(structure -> level.structureManager().getStructureAt(be.getBlockPos(), structure).isValid())
                .map(registry::getKey).filter(java.util.Objects::nonNull)
                .sorted().limit(32).toList();
    }

    private static UUID ensureUniqueUid(WaypointBlockEntity be, WaypointRegistryData registry, ServerLevel level) {
        UUID uid = be.getUid();
        while (uid != null && registry.get(uid).filter(record -> !isRegisteredAt(record, level, be.getBlockPos())).isPresent()) {
            be.regenerateUid();
            uid = be.getUid();
        }
        return uid;
    }

    private static boolean isRegisteredAt(WaypointRecord record, ServerLevel level, BlockPos pos) {
        return record.dimension().equals(level.dimension()) && record.pos().equals(pos);
    }
}
