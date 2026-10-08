package com.zonlong.teleportwaypoint.network;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.zonlong.teleportwaypoint.client.ClientWaypointState;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.negotiation.NegotiableNetworkComponent;
import net.neoforged.neoforge.network.negotiation.NetworkComponentNegotiator;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import net.neoforged.neoforge.network.registration.PayloadRegistration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class LegacyNetworkTest {
    private static final ResourceLocation OVERWORLD = ResourceLocation.parse("minecraft:overworld");
    private static final ResourceLocation NETHER = ResourceLocation.parse("minecraft:the_nether");
    private static final MapIconStyle CASTLE = new MapIconStyle("data:test:castle.png", "", 24, "C");

    private static RegistryFriendlyByteBuf buffer() {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
    }

    private static WaypointSyncInfo waypoint(String name) {
        return new WaypointSyncInfo(UUID.randomUUID(), OVERWORLD, new BlockPos(12, 70, -34), false, name);
    }

    // Independent fixture for the upstream 0.3.0 wire format, including field order.
    private static void writeLegacyWaypoint(RegistryFriendlyByteBuf buf, WaypointSyncInfo info) {
        buf.writeUUID(info.uid());
        buf.writeResourceLocation(info.dimension());
        buf.writeBlockPos(info.pos());
        buf.writeBoolean(info.pocket());
        buf.writeUtf(info.name());
    }

    private static WaypointSyncInfo readLegacyWaypoint(RegistryFriendlyByteBuf buf) {
        return new WaypointSyncInfo(buf.readUUID(), buf.readResourceLocation(), buf.readBlockPos(),
                buf.readBoolean(), buf.readUtf());
    }

    @AfterEach
    void resetClientState() {
        ClientWaypointState.reset();
    }

    @Test
    void newClientDecodesAnUpstreamSnapshotWithoutReadingTheNextEntryOrPageFieldsAsIcons() {
        var first = waypoint("village");
        var second = waypoint("trial_chambers");
        var buf = buffer();
        try {
            buf.writeResourceLocation(OVERWORLD);
            buf.writeVarInt(2);
            writeLegacyWaypoint(buf, first);
            writeLegacyWaypoint(buf, second);
            buf.writeVarInt(7);
            buf.writeBoolean(true);
            var decoded = SyncDimensionWaypointsPayload.STREAM_CODEC.decode(buf);
            assertEquals(List.of(first, second), decoded.waypoints());
            assertEquals(7, decoded.page());
            assertTrue(decoded.done());
            assertEquals(0, buf.readableBytes());
            ClientWaypointState.applyDimensionSnapshot(OVERWORLD, decoded.waypoints(), 0, true);
            assertEquals(MapIconStyle.DEFAULT, ClientWaypointState.getWaypoint(first.uid()).iconStyle());
        } finally {
            buf.release();
        }
    }

    @Test
    void newServerPacketsRemainReadableByTheUpstreamSnapshotAndIncrementalCodecs() {
        var first = waypoint("village");
        var second = waypoint("castle");
        var buf = buffer();
        try {
            SyncDimensionWaypointsPayload.STREAM_CODEC.encode(buf,
                    new SyncDimensionWaypointsPayload(OVERWORLD, List.of(first, second), 3, true));
            assertEquals(OVERWORLD, buf.readResourceLocation());
            assertEquals(2, buf.readVarInt());
            assertEquals(first, readLegacyWaypoint(buf));
            assertEquals(second, readLegacyWaypoint(buf));
            assertEquals(3, buf.readVarInt());
            assertTrue(buf.readBoolean());
            assertEquals(0, buf.readableBytes());
            buf.clear();
            AddWaypointPayload.STREAM_CODEC.encode(buf, new AddWaypointPayload(first));
            assertEquals(first, readLegacyWaypoint(buf));
            assertEquals(0, buf.readableBytes());
            buf.clear();
            UpdateWaypointPayload.STREAM_CODEC.encode(buf, new UpdateWaypointPayload(second));
            assertEquals(second, readLegacyWaypoint(buf));
            assertEquals(0, buf.readableBytes());
        } finally {
            buf.release();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void actualRegistrationsNegotiateWithUpstreamOnEitherSideAndRetainIconsBetweenNewPeers() throws Exception {
        var field = NetworkRegistry.class.getDeclaredField("PAYLOAD_REGISTRATIONS");
        field.setAccessible(true);
        var registrations = (Map<ConnectionProtocol, Map<ResourceLocation, PayloadRegistration<?>>>) field.get(null);
        if (!registrations.get(ConnectionProtocol.PLAY).containsKey(AddWaypointPayload.TYPE.id())) {
            ModNetwork.register(new RegisterPayloadHandlersEvent());
        }
        var modern = registrations.get(ConnectionProtocol.PLAY).values().stream()
                .filter(reg -> reg.id().getNamespace().equals("teleportwaypoint"))
                .map(NegotiableNetworkComponent::new).toList();
        List<NegotiableNetworkComponent> legacy = new ArrayList<>();
        for (String name : List.of("sync_activated_waypoints", "sync_dimension_waypoints", "add_waypoint",
                "update_waypoint", "remove_waypoint", "activated_waypoint_add", "activated_waypoint_remove")) {
            legacy.add(legacyChannel(name, PacketFlow.CLIENTBOUND));
        }
        for (String name : List.of("teleport_request", "map_teleport_request", "rename_waypoint",
                "open_rename_screen", "delete_waypoint")) {
            legacy.add(legacyChannel(name, PacketFlow.SERVERBOUND));
        }
        for (var result : List.of(NetworkComponentNegotiator.negotiate(legacy, modern),
                NetworkComponentNegotiator.negotiate(modern, legacy))) {
            assertTrue(result.success(), () -> result.failureReasons().toString());
            assertEquals(12, result.components().size());
            assertFalse(result.components().stream().anyMatch(c -> c.id().equals(SyncMapIconStylesPayload.TYPE.id())));
            assertFalse(result.components().stream().anyMatch(c -> c.id().equals(SyncMapIconImagePayload.TYPE.id())));
        }
        var bothNew = NetworkComponentNegotiator.negotiate(modern, modern);
        assertTrue(bothNew.success());
        assertEquals(14, bothNew.components().size());
    }

    private static NegotiableNetworkComponent legacyChannel(String path, PacketFlow flow) {
        return new NegotiableNetworkComponent(ResourceLocation.fromNamespaceAndPath("teleportwaypoint", path),
                "4", Optional.of(flow), false);
    }

    @Test
    void optionalStylesRoundTripAndRejectOversizedPages() {
        var entry = new SyncMapIconStylesPayload.Entry(UUID.randomUUID(), CASTLE);
        var payload = new SyncMapIconStylesPayload(OVERWORLD, List.of(entry));
        var buf = buffer();
        try {
            SyncMapIconStylesPayload.STREAM_CODEC.encode(buf, payload);
            assertEquals(payload, SyncMapIconStylesPayload.STREAM_CODEC.decode(buf));
            assertEquals(0, buf.readableBytes());
            assertThrows(IllegalArgumentException.class, () -> new SyncMapIconStylesPayload(OVERWORLD,
                    java.util.Collections.nCopies(SyncMapIconStylesPayload.MAX_ENTRIES + 1, entry)));
            buf.clear();
            buf.writeResourceLocation(OVERWORLD);
            buf.writeVarInt(SyncMapIconStylesPayload.MAX_ENTRIES + 1);
            assertThrows(RuntimeException.class, () -> SyncMapIconStylesPayload.STREAM_CODEC.decode(buf));
        } finally {
            buf.release();
        }
    }

    @Test
    void stylesBetweenSnapshotPagesAreAppliedAndReloadingWithoutTheRuleClearsThem() {
        var first = waypoint("castle");
        var second = waypoint("village");
        ClientWaypointState.applyDimensionSnapshot(OVERWORLD, List.of(first), 0, false);
        ClientWaypointState.applyIconStyles(OVERWORLD,
                List.of(new SyncMapIconStylesPayload.Entry(first.uid(), CASTLE)));
        assertEquals(MapIconStyle.DEFAULT, ClientWaypointState.getWaypoint(first.uid()).iconStyle());
        ClientWaypointState.applyDimensionSnapshot(OVERWORLD, List.of(second), 1, true);
        assertEquals(CASTLE, ClientWaypointState.getWaypoint(first.uid()).iconStyle());
        assertEquals(MapIconStyle.DEFAULT, ClientWaypointState.getWaypoint(second.uid()).iconStyle());
        ClientWaypointState.applyDimensionSnapshot(OVERWORLD, List.of(first, second), 0, true);
        assertEquals(MapIconStyle.DEFAULT, ClientWaypointState.getWaypoint(first.uid()).iconStyle());
    }

    @Test
    void incrementsCanQueueWithStylesAndLegacyUpdatesDoNotKeepObsoleteStyles() {
        var info = waypoint("castle");
        ClientWaypointState.applyDimensionSnapshot(OVERWORLD, List.of(), 0, false);
        ClientWaypointState.applyAdd(info);
        ClientWaypointState.applyIconStyles(OVERWORLD,
                List.of(new SyncMapIconStylesPayload.Entry(info.uid(), CASTLE)));
        ClientWaypointState.applyDimensionSnapshot(OVERWORLD, List.of(), 1, true);
        assertEquals(CASTLE, ClientWaypointState.getWaypoint(info.uid()).iconStyle());
        ClientWaypointState.applyUpdate(info);
        assertEquals(MapIconStyle.DEFAULT, ClientWaypointState.getWaypoint(info.uid()).iconStyle());
    }

    @Test
    void removedUnknownAndOtherDimensionStylesCannotLeakAcrossConnections() {
        var info = waypoint("castle");
        var style = List.of(new SyncMapIconStylesPayload.Entry(info.uid(), CASTLE));
        ClientWaypointState.applyDimensionSnapshot(OVERWORLD, List.of(info), 0, true);
        ClientWaypointState.applyIconStyles(NETHER, style);
        assertEquals(MapIconStyle.DEFAULT, ClientWaypointState.getWaypoint(info.uid()).iconStyle());
        ClientWaypointState.applyRemove(info.uid());
        ClientWaypointState.applyIconStyles(OVERWORLD, style);
        assertNull(ClientWaypointState.getWaypoint(info.uid()));
        ClientWaypointState.reset();
        ClientWaypointState.applyIconStyles(OVERWORLD, style);
        ClientWaypointState.applyDimensionSnapshot(OVERWORLD, List.of(info), 0, true);
        assertEquals(MapIconStyle.DEFAULT, ClientWaypointState.getWaypoint(info.uid()).iconStyle());
    }
}
