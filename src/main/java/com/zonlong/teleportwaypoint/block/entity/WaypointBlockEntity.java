package com.zonlong.teleportwaypoint.block.entity;

import java.util.UUID;

import com.zonlong.teleportwaypoint.block.ModBlocks;
import com.zonlong.teleportwaypoint.core.WaypointManager;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import com.zonlong.teleportwaypoint.menu.ModMenus;
import com.zonlong.teleportwaypoint.menu.RenamePocketWaypointMenu;
import com.zonlong.teleportwaypoint.menu.RenameWaypointMenu;
import com.zonlong.teleportwaypoint.menu.WaypointListMenu;

public class WaypointBlockEntity extends BlockEntity {
    public static final int MAX_TEXT_LENGTH = 64;
    private static final String TAG_UID = "uid";
    private static final String TAG_ID = "waypoint_id";
    private static final String TAG_NAME = "name";
    private static final String TAG_OWNER = "owner";

    private static final String ID_PATTERN = "[a-z0-9_]+";

    private UUID uid;
    private String id = "empty";
    private String name = "";
    private UUID owner;
    // Optional override for template authors or waypoints outside a structure's bounding box.
    private ResourceLocation structureId;

    public ResourceLocation getStructureId() {
        return structureId;
    }

    public WaypointBlockEntity(BlockPos pos, BlockState blockState) {
        super(ModBlockEntities.WAYPOINT.get(), pos, blockState);
    }

    public boolean isPocketWaypoint() {
        return getBlockState().is(ModBlocks.POCKET_WAYPOINT);
    }

    /**
     * Returns the unique id of this waypoint, lazily generating it on the server if absent.
     */
    public UUID getUid() {
        if (uid == null && level != null && !level.isClientSide()) {
            uid = UUID.randomUUID();
            setChanged();
        }
        return uid;
    }

    /** Assigns a new server-side identity when a copied block entity conflicts with an existing waypoint. */
    public void regenerateUid() {
        if (level != null && !level.isClientSide()) {
            uid = UUID.randomUUID();
            setChanged();
        }
    }

    /**
     * Returns the uid without triggering lazy generation; may be null.
     */
    public UUID getExistingUid() {
        return uid;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        if (!isValidId(id)) {
            return;
        }
        this.id = id;
        setChanged();
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        if (!isValidName(name)) {
            return;
        }
        this.name = (name == null || name.isEmpty()) ? "Pocket Waypoint" : name;
        setChanged();
    }

    public UUID getOwner() {
        return owner;
    }

    public void setOwner(UUID owner) {
        this.owner = owner;
        setChanged();
    }

    public Component getDisplayName() {
        if (isPocketWaypoint()) {
            return Component.literal(name);
        }
        return (id.isEmpty() || !isValidId(id)) ? Component.translatable("teleportwaypoint.waypoint.empty") : Component.translatable("teleportwaypoint.waypoint." + id);
    }

    public static boolean isValidId(String id) {
        return id != null && id.length() <= MAX_TEXT_LENGTH && id.matches(ID_PATTERN);
    }

    public static boolean isValidName(String name) {
        return name != null && name.length() <= MAX_TEXT_LENGTH;
    }

    public boolean canRename(Player player) {
        if (player.isCreative()) {
            return true;
        }
        return isPocketWaypoint() && owner != null && owner.equals(player.getUUID());
    }

    public void openRenameScreen(net.minecraft.server.level.ServerPlayer player) {
        openRenameScreen(player, false);
    }

    /** Opens the rename screen, optionally starting with an empty input without changing the stored default name. */
    public void openRenameScreen(net.minecraft.server.level.ServerPlayer player, boolean clearInitialText) {
        boolean canEdit = canRename(player);
        String name = clearInitialText ? "" : (isPocketWaypoint() ? this.name : this.id);
        player.openMenu(new MenuProvider() {
            @Override
            public Component getDisplayName() {
                return isPocketWaypoint()
                        ? Component.translatable("gui.teleportwaypoint.rename_pocket_waypoint")
                        : Component.translatable("gui.teleportwaypoint.rename_waypoint");
            }

            @Override
            public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
                if (isPocketWaypoint()) {
                    return new RenamePocketWaypointMenu(ModMenus.RENAME_POCKET_WAYPOINT.get(), containerId, getBlockPos(), canEdit, name);
                }
                return new RenameWaypointMenu(ModMenus.RENAME_WAYPOINT.get(), containerId, getBlockPos(), canEdit, name);
            }
        }, buf -> {
            buf.writeBlockPos(getBlockPos());
            buf.writeBoolean(canEdit);
            buf.writeUtf(name);
        });
    }

    public void openListScreen(net.minecraft.server.level.ServerPlayer player) {
        player.openMenu(new MenuProvider() {
            @Override
            public Component getDisplayName() {
                return Component.translatable("gui.teleportwaypoint.waypoint_list");
            }

            @Override
            public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
                return new WaypointListMenu(ModMenus.WAYPOINT_LIST.get(), containerId, getBlockPos());
            }
        }, buf -> buf.writeBlockPos(getBlockPos()));
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (structureId != null) tag.putString("structure_id", structureId.toString());
        if (uid != null) {
            tag.put(TAG_UID, NbtUtils.createUUID(uid));
        }
        if (isPocketWaypoint()) {
            if (!name.isEmpty()) {
                tag.putString(TAG_NAME, name);
            }
            if (owner != null) {
                tag.put(TAG_OWNER, NbtUtils.createUUID(owner));
            }
        } else {
            if (!id.isEmpty()) {
                tag.putString(TAG_ID, id);
            }
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        structureId = ResourceLocation.tryParse(tag.getString("structure_id"));
        if (tag.contains(TAG_UID, Tag.TAG_INT_ARRAY)) {
            uid = NbtUtils.loadUUID(tag.get(TAG_UID));
        }
        if (isPocketWaypoint()) {
            String storedName = tag.getString(TAG_NAME);
            name = isValidName(storedName) && !storedName.isEmpty() ? storedName : "Pocket Waypoint";
            if (tag.contains(TAG_OWNER, Tag.TAG_INT_ARRAY)) {
                owner = NbtUtils.loadUUID(tag.get(TAG_OWNER));
            }
        } else {
            if (tag.contains(TAG_ID)) {
                String storedId = tag.getString(TAG_ID);
                id = isValidId(storedId) ? storedId : "empty";
            }
        }
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && !level.isClientSide()) {
            WaypointManager.register(this);
        }
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        saveAdditional(tag, registries);
        return tag;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
