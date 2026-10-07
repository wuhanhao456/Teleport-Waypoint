package com.zonlong.teleportwaypoint.datapack;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.UUID;

import com.google.gson.JsonParser;
import com.mojang.serialization.Lifecycle;
import com.zonlong.teleportwaypoint.core.WaypointRecord;
import com.zonlong.teleportwaypoint.core.WaypointRegistryData;
import com.zonlong.teleportwaypoint.network.MapIconStyle;
import com.zonlong.teleportwaypoint.network.SyncMapIconImagePayload;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment;
import org.junit.jupiter.api.Test;

class MapIconTest {
    private static ResourceLocation id(String name) { return ResourceLocation.parse(name); }
    private static MapIconRule rule(String json) {
        return MapIconRule.parse(id("test:rule"), JsonParser.parseString(json).getAsJsonObject());
    }
    private static WaypointRecord waypoint(boolean pocket, String name, ResourceKey<Level> dimension) {
        return new WaypointRecord(UUID.randomUUID(), dimension, BlockPos.ZERO, pocket, name,
                List.of(id("test:castle")));
    }

    @Test
    void selectorsAndFieldsMustAllMatchWhileArrayValuesAreAlternatives() {
        MapIconRule rule = rule("""
                {"match":{"structures":["test:tower","test:castle"],"dimensions":"minecraft:overworld",
                 "waypoint_ids":"castle","pocket":false},"icon":{"texture":"minecraft:textures/item/map.png"}}
                """);
        assertTrue(rule.matches(waypoint(false, "castle", Level.OVERWORLD), null));
        assertFalse(rule.matches(waypoint(false, "castle", Level.NETHER), null));
        assertFalse(rule.matches(waypoint(false, "village", Level.OVERWORLD), null));
        assertFalse(rule.matches(waypoint(true, "castle", Level.OVERWORLD), null));
        assertFalse(rule.matches(new WaypointRecord(UUID.randomUUID(), Level.OVERWORLD, BlockPos.ZERO,
                false, "castle", List.of(id("test:ruin"))), null));
    }

    @Test
    void namesCanMatchPocketWaypointsButWaypointIdsCannot() {
        var pocket = waypoint(true, "Home", Level.OVERWORLD);
        assertTrue(rule("""
                {"match":{"names":"Home","pocket":true},"symbol":"H"}
                """).matches(pocket, null));
        assertFalse(rule("""
                {"match":{"waypoint_ids":"Home"},"symbol":"H"}
                """).matches(pocket, null));
    }

    @Test
    void structureTagsUseServerRegistryMembershipAndReflectReloadedTags() {
        var registry = new MappedRegistry<Structure>(Registries.STRUCTURE, Lifecycle.stable());
        Structure structure = new Structure(new Structure.StructureSettings(HolderSet.direct(), java.util.Map.of(),
                GenerationStep.Decoration.SURFACE_STRUCTURES, TerrainAdjustment.NONE)) {
            @Override
            protected java.util.Optional<GenerationStub> findGenerationPoint(GenerationContext context) {
                return java.util.Optional.empty();
            }
            @Override
            public StructureType<?> type() { return null; }
        };
        var holder = registry.register(ResourceKey.create(Registries.STRUCTURE, id("test:castle")),
                structure, RegistrationInfo.BUILT_IN);
        var tag = TagKey.create(Registries.STRUCTURE, id("test:castles"));
        var rule = rule("""
                {"match":{"structure_tags":"test:castles"},"symbol":"C"}
                """);
        registry.bindTags(java.util.Map.of(tag, List.of(holder)));
        assertTrue(rule.matches(waypoint(false, "castle", Level.OVERWORLD), registry));
        registry.bindTags(java.util.Map.of(tag, List.of()));
        assertFalse(rule.matches(waypoint(false, "castle", Level.OVERWORLD), registry));
    }

    @Test
    void stateSpecificIconsOverrideTheCommonIconAndUnspecifiedStatesUseDefaults() {
        var style = rule("""
                {"match":{},"icon":{"texture":"minecraft:textures/item/map.png"},
                 "inactive_icon":{"png":"test:icons/locked.png"},"size":24,"symbol":"C"}
                """).style();
        assertEquals("texture:minecraft:textures/item/map.png", style.active());
        assertEquals("data:test:icons/locked.png", style.inactive());
        assertEquals(24, style.size());
        assertEquals("", rule("""
                {"match":{},"active_icon":{"png":"test:icons/castle.png"}}
                """).style().inactive());
    }

    @Test
    void invalidRulesMustNotAccidentallyBecomeDefaults() {
        for (String json : List.of(
                "{\"match\":{\"strucutres\":\"test:castle\"},\"symbol\":\"C\"}",
                "{\"match\":{\"structures\":[]},\"symbol\":\"C\"}",
                "{\"match\":{\"pocket\":\"false\"},\"symbol\":\"C\"}",
                "{\"match\":{\"structures\":\"Test:Invalid\"},\"symbol\":\"C\"}",
                "{\"match\":{},\"icon\":{\"png\":\"test:../icon.png\"}}",
                "{\"match\":{},\"icon\":{\"png\":\"test:icon.png\",\"texture\":\"test:icon.png\"}}",
                "{\"match\":{},\"size\":65,\"symbol\":\"C\"}",
                "{\"match\":{},\"symbol\":\"123456789\"}",
                "{\"symbol\":\"C\"}", "{\"match\":{}}")) {
            assertThrows(RuntimeException.class, () -> rule(json), json);
        }
    }

    @Test
    void pngLimitsAreCheckedBeforeNativeDecoding() {
        byte[] header = new byte[33];
        byte[] signature = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
        System.arraycopy(signature, 0, header, 0, signature.length);
        var buffer = ByteBuffer.wrap(header);
        buffer.putInt(8, 13).putInt(12, 0x49484452).putInt(16, 32).putInt(20, 64);
        assertTrue(MapIconData.isValidPng(header));
        buffer.putInt(16, 129);
        assertFalse(MapIconData.isValidPng(header));
        buffer.putInt(16, 0);
        assertFalse(MapIconData.isValidPng(header));
        buffer.putInt(16, Integer.MAX_VALUE);
        assertFalse(MapIconData.isValidPng(header));
        assertFalse(MapIconData.isValidPng(new byte[8]));
        assertFalse(MapIconData.isValidPng(new byte[SyncMapIconImagePayload.MAX_BYTES + 1]));
    }

    @Test
    void persistedStructureMetadataRoundTripsAndOldRecordsStillLoad() {
        var record = waypoint(false, "castle", ResourceKey.create(Registries.DIMENSION, id("test:dimension")));
        var data = new WaypointRegistryData();
        data.put(record);
        CompoundTag saved = data.save(new CompoundTag(), RegistryAccess.EMPTY);
        assertEquals(record, WaypointRegistryData.read(saved, RegistryAccess.EMPTY).get(record.uid()).orElseThrow());
        saved.getList("waypoints", 10).getCompound(0).remove("structures");
        var legacy = WaypointRegistryData.read(saved, RegistryAccess.EMPTY).get(record.uid()).orElseThrow();
        assertTrue(legacy.structures().isEmpty());
        assertEquals(record.name(), legacy.name());
        assertEquals(record.dimension(), legacy.dimension());
    }

    @Test
    void styleNetworkCodecRetainsBothStatesAndEnforcesSourceLength() {
        var style = new MapIconStyle("data:test:icons/on.png", "texture:test:textures/off.png", 24, "C");
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            MapIconStyle.STREAM_CODEC.encode(buffer, style);
            assertEquals(style, MapIconStyle.STREAM_CODEC.decode(buffer));
            assertThrows(RuntimeException.class, () -> MapIconStyle.STREAM_CODEC.encode(buffer,
                    new MapIconStyle("x".repeat(257), "", 32, "")));
        } finally {
            buffer.release();
        }
    }
}
