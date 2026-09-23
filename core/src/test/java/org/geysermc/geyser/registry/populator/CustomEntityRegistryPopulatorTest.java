package org.geysermc.geyser.registry.populator;

import java.util.List;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.geysermc.geyser.registry.mappings.util.CustomEntityMapping;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 验证网络实体注册保留自定义身份，并只在显式配置时继承存在的原生实体。 */
class CustomEntityRegistryPopulatorTest {
    private final List<NbtMap> vanilla = List.of(NbtMap.builder().putString("id", "minecraft:horse").putInt("rid", 23).build());

    @Test
    void sendsHorseBaseIdentifierWithoutOverridingCustomRuntimeId() {
        var result = CustomEntityRegistryPopulator.createNetworkEntry(
            new CustomEntityMapping("realmsunderoath:mount_land", .6f, .85f, "minecraft:horse"), 10002, vanilla);
        assertEquals("realmsunderoath:mount_land", result.getString("id"));
        assertEquals("minecraft:horse", result.getString("bid"));
        assertEquals(10002, result.getInt("rid"));
        assertFalse(result.containsKey("type"));
        assertFalse(result.getBoolean("hasspawnegg"));
        assertFalse(result.getBoolean("summonable"));
        assertEquals(23, vanilla.get(0).getInt("rid"));
    }

    @Test
    void preservesGenericEntityRegistrationWhenRuntimeIsAbsent() {
        var result = CustomEntityRegistryPopulator.createNetworkEntry(
            new CustomEntityMapping("test:other", 1, 1), 10003, vanilla);
        assertEquals("", result.getString("bid"));
        assertEquals(256, result.getInt("type"));
    }

    @Test
    void refusesUnknownVanillaBaseRatherThanSendingUnresolvableType() {
        assertThrows(IllegalArgumentException.class, () -> CustomEntityRegistryPopulator.createNetworkEntry(
            new CustomEntityMapping("test:other", 1, 1, "minecraft:not_an_entity"), 10004, vanilla));
    }

    @Test
    void customSkyGetsHappyGhastCanMoveSchemaWithoutChangingOtherMappings() {
        var result = CustomEntityRegistryPopulator.createRuntimeProperties(
            new CustomEntityMapping("realmsunderoath:mount_sky", .6f, .85f, "minecraft:happy_ghast"));
        assertEquals("realmsunderoath:mount_sky", result.getString("type"));
        var properties = result.getList("properties", NbtType.COMPOUND);
        assertEquals(1, properties.size());
        assertEquals("minecraft:can_move", properties.get(0).getString("name"));
        assertEquals(2, properties.get(0).getInt("type"));
        assertNull(CustomEntityRegistryPopulator.createRuntimeProperties(
            new CustomEntityMapping("realmsunderoath:mount_land", .6f, .85f, "minecraft:horse")));
        assertNull(CustomEntityRegistryPopulator.createRuntimeProperties(
            new CustomEntityMapping("realmsunderoath:mount_sky", .6f, .85f, "minecraft:horse")));
        assertNull(CustomEntityRegistryPopulator.createRuntimeProperties(
            new CustomEntityMapping("other:mount_sky", .6f, .85f, "minecraft:happy_ghast")));
    }
}
