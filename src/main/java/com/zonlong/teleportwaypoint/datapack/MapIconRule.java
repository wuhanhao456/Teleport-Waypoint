package com.zonlong.teleportwaypoint.datapack;

import java.util.List;
import java.util.Set;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.zonlong.teleportwaypoint.core.WaypointRecord;
import com.zonlong.teleportwaypoint.network.MapIconStyle;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.levelgen.structure.Structure;

/** Fields are ANDed; values within a field are ORed. Tags use server registries. */
public record MapIconRule(ResourceLocation id, int priority, List<String> structures,
                          List<String> structureTags, List<String> waypointIds, List<String> names,
                          List<String> dimensions, Boolean pocket, MapIconStyle style) {
    private static final Set<String> MATCH_FIELDS = Set.of(
            "structures", "structure_tags", "waypoint_ids", "names", "dimensions", "pocket");

    public static MapIconRule parse(ResourceLocation id, JsonObject json) {
        JsonObject match = json.getAsJsonObject("match");
        if (match == null) {
            throw new IllegalArgumentException("Missing match object (use {} for a default rule)");
        }
        for (String field : match.keySet()) {
            if (!MATCH_FIELDS.contains(field)) {
                throw new IllegalArgumentException("Unknown match field: " + field);
            }
        }
        if (match.has("pocket") && (!match.get("pocket").isJsonPrimitive()
                || !match.get("pocket").getAsJsonPrimitive().isBoolean())) {
            throw new IllegalArgumentException("pocket must be a boolean");
        }
        String common = source(json.get("icon"));
        String active = json.has("active_icon") ? source(json.get("active_icon")) : common;
        String inactive = json.has("inactive_icon") ? source(json.get("inactive_icon")) : common;
        int size = json.has("size") ? json.get("size").getAsInt() : 32;
        String symbol = json.has("symbol") ? json.get("symbol").getAsString() : "";
        if (size < 8 || size > 64 || symbol.length() > 8) {
            throw new IllegalArgumentException("size must be 8..64; symbol must be at most 8 characters");
        }
        if (active.isEmpty() && inactive.isEmpty() && symbol.isEmpty()) {
            throw new IllegalArgumentException("Rule must define an icon or symbol");
        }
        return new MapIconRule(id, json.has("priority") ? json.get("priority").getAsInt() : 0,
                values(match, "structures", true), values(match, "structure_tags", true),
                values(match, "waypoint_ids", false), values(match, "names", false),
                values(match, "dimensions", true), match.has("pocket") ? match.get("pocket").getAsBoolean() : null,
                new MapIconStyle(active, inactive, size, symbol));
    }

    private static List<String> values(JsonObject json, String field, boolean resourceIds) {
        if (!json.has(field)) {
            return List.of();
        }
        var values = new java.util.ArrayList<String>();
        JsonElement element = json.get(field);
        if (element.isJsonArray()) {
            element.getAsJsonArray().forEach(value -> values.add(value.getAsString()));
        } else {
            values.add(element.getAsString());
        }
        if (values.isEmpty()) {
            throw new IllegalArgumentException("Empty selector: " + field);
        }
        for (int i = 0; i < values.size(); i++) {
            String value = values.get(i);
            if (value.length() > 256 || (resourceIds && ResourceLocation.tryParse(value) == null)) {
                throw new IllegalArgumentException("Invalid selector: " + value);
            }
            if (resourceIds) values.set(i, ResourceLocation.parse(value).toString());
        }
        return List.copyOf(values);
    }

    private static String source(JsonElement element) {
        if (element == null) {
            return "";
        }
        JsonObject source = element.getAsJsonObject();
        if (source.size() != 1 || (!source.has("png") && !source.has("texture"))) {
            throw new IllegalArgumentException("Icon must contain exactly one of png or texture");
        }
        boolean png = source.has("png");
        String value = source.get(png ? "png" : "texture").getAsString();
        ResourceLocation location = ResourceLocation.tryParse(value);
        if (location == null || value.length() > 240 || location.getPath().contains("..")
                || !location.getPath().endsWith(".png")) {
            throw new IllegalArgumentException("Invalid PNG resource: " + value);
        }
        return (png ? "data:" : "texture:") + location;
    }

    public boolean matches(WaypointRecord record, Registry<Structure> registry) {
        if (pocket != null && pocket != record.pocket()) return false;
        if (!dimensions.isEmpty() && !dimensions.contains(record.dimension().location().toString())) return false;
        if (!waypointIds.isEmpty() && (record.pocket() || !waypointIds.contains(record.name()))) return false;
        if (!names.isEmpty() && !names.contains(record.name())) return false;
        if (!structures.isEmpty() && record.structures().stream().noneMatch(s -> structures.contains(s.toString()))) return false;
        if (!structureTags.isEmpty() && record.structures().stream().noneMatch(s ->
                registry.getHolder(ResourceKey.create(Registries.STRUCTURE, s)).map(holder ->
                        structureTags.stream().anyMatch(tag -> holder.is(
                                TagKey.create(Registries.STRUCTURE, ResourceLocation.parse(tag))))).orElse(false))) return false;
        return true;
    }
}
