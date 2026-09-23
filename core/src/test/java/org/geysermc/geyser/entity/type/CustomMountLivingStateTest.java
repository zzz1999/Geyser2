package org.geysermc.geyser.entity.type;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.protocol.bedrock.data.AttributeData;
import org.cloudburstmc.protocol.bedrock.packet.AddEntityPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetEntityDataPacket;
import org.cloudburstmc.protocol.bedrock.packet.MobArmorEquipmentPacket;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.cloudburstmc.protocol.bedrock.data.definitions.SimpleItemDefinition;
import org.geysermc.geyser.registry.type.ItemMapping;
import org.geysermc.geyser.registry.type.ItemMappings;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityDataTypes;
import org.cloudburstmc.protocol.bedrock.data.entity.IntEntityProperty;
import org.geysermc.geyser.entity.properties.GeyserEntityProperties;
import org.geysermc.geyser.entity.type.living.animal.tameable.WolfEntity;
import org.geysermc.geyser.entity.EntityDefinition;
import org.geysermc.geyser.scoreboard.network.util.GeyserMockContext;
import org.geysermc.mcprotocollib.protocol.data.game.entity.attribute.Attribute;
import org.geysermc.mcprotocollib.protocol.data.game.entity.attribute.AttributeType;
import org.geysermc.mcprotocollib.protocol.data.game.entity.type.EntityType;
import org.junit.jupiter.api.Test;

import static org.geysermc.geyser.scoreboard.network.util.AssertUtils.assertNextPacketMatch;
import static org.geysermc.geyser.scoreboard.network.util.GeyserMockContextScoreboard.mockContextScoreboard;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 验证坐骑的客户端速度、运行时属性隔离及上马前遗留插值清理。 */
class CustomMountLivingStateTest {
    private void mockHarness(GeyserMockContext context) {
        when(context.session().getTagCache()).thenReturn(mock(org.geysermc.geyser.session.cache.TagCache.class));
        var mappings = mock(ItemMappings.class);
        var mapping = mock(ItemMapping.class);
        when(mapping.getBedrockDefinition()).thenReturn(new SimpleItemDefinition("minecraft:white_harness", 987, false));
        when(mappings.getMapping("minecraft:white_harness")).thenReturn(mapping);
        when(context.session().getItemMappings()).thenReturn(mappings);
    }

    @Test
    void skyReceivesHarnessAfterSpawnAndRetainsItWhenJavaSendsEmptyEquipment() {
        mockContextScoreboard(context -> {
            mockHarness(context);
            var mount = create(context, "realmsunderoath:mount_sky");
            for (int update = 0; update < 3; update++) {
                if (update != 0) mount.updateArmor();
                assertNextPacketMatch(context, MobArmorEquipmentPacket.class, packet -> {
                    assertEquals(10, packet.getRuntimeEntityId());
                    assertEquals("minecraft:white_harness", packet.getBody().getDefinition().getIdentifier());
                    assertEquals(987, packet.getBody().getDefinition().getRuntimeId());
                    assertEquals(1, packet.getBody().getCount());
                    assertEquals(ItemData.AIR, packet.getHelmet());
                });
            }
            assertTrue(mount.getItemInSlot(org.geysermc.mcprotocollib.protocol.data.game.entity.EquipmentSlot.BODY).isEmpty());
            var land = create(context, "realmsunderoath:mount_land");
            assertEquals(0, context.packetCount());
            land.updateArmor();
            assertNextPacketMatch(context, MobArmorEquipmentPacket.class, packet -> assertEquals(ItemData.AIR, packet.getBody()));
        });
    }

    @Test
    void waterSpawnCarriesBuoyancyAndSwitchingAppearanceClearsIt() {
        mockContextScoreboard(context -> {
            var definition = EntityDefinition.<LivingEntity>builder(LivingEntity::new)
                .type(EntityType.WOLF).width(.6f).height(.85f).build(false);
            var entity = new LivingEntity(context.session(), 10, 10, UUID.randomUUID(), definition,
                Vector3f.ZERO, Vector3f.ZERO, 0, 0, 0);
            entity.spawnEntity("realmsunderoath:mount_water");
            assertNextPacketMatch(context, AddEntityPacket.class, packet -> {
                assertEquals(true, packet.getMetadata().get(EntityDataTypes.IS_BUOYANT));
                var settings = com.google.gson.JsonParser.parseString(
                    packet.getMetadata().get(EntityDataTypes.BUOYANCY_DATA)).getAsJsonObject();
                assertEquals(1.0, settings.get("base_buoyancy").getAsDouble());
                assertFalse(settings.get("simulate_waves").getAsBoolean());
                assertEquals(0.0, settings.get("drag_down_on_buoyancy_removed").getAsDouble());
                assertEquals(List.of("minecraft:water", "minecraft:flowing_water"),
                    java.util.stream.StreamSupport.stream(settings.getAsJsonArray("liquid_blocks").spliterator(), false)
                        .map(com.google.gson.JsonElement::getAsString).toList());
            });
            entity.getClientPredictedMount().restoreMetadata();
            assertEquals(false, entity.getDirtyMetadata().get(EntityDataTypes.IS_BUOYANT));
        });
    }

    private LivingEntity create(GeyserMockContext context, String identifier) {
        var definition = EntityDefinition.<LivingEntity>builder(LivingEntity::new)
            .type(EntityType.WOLF).width(.6f).height(.85f).build(false);
        var entity = new LivingEntity(context.session(), 10, 10, UUID.randomUUID(), definition,
            Vector3f.ZERO, Vector3f.ZERO, 0, 0, 0);
        entity.spawnEntity(identifier);
        assertNextPacketMatch(context, AddEntityPacket.class, packet -> {
            if (identifier.equals("realmsunderoath:mount_land")) {
                assertEquals(.208f, packet.getAttributes().get(0).getValue());
                assertTrue(packet.getAttributes().stream().anyMatch(attribute ->
                    attribute.getName().equals("minecraft:underwater_movement") && attribute.getValue() == .042f));
            }
        });
        return entity;
    }

    @Test
    void wolfMovementAttributesCannotOverwriteMountBehaviorButOtherModelsRemainUnchanged() {
        mockContextScoreboard(context -> {
            var mount = create(context, "realmsunderoath:mount_land");
            var attributes = new ArrayList<AttributeData>();
            for (var type : List.of(AttributeType.Builtin.MOVEMENT_SPEED, AttributeType.Builtin.FLYING_SPEED,
                AttributeType.Builtin.JUMP_STRENGTH)) {
                var attribute = mock(Attribute.class);
                when(attribute.getType()).thenReturn(type);
                mount.updateAttribute(attribute, attributes);
            }
            assertTrue(attributes.isEmpty());
            var ordinary = create(context, "realmsunderoath:unrelated_model");
            var attribute = mock(Attribute.class);
            when(attribute.getType()).thenReturn(AttributeType.Builtin.MOVEMENT_SPEED);
            when(attribute.getValue()).thenReturn(.3);
            ordinary.updateAttribute(attribute, attributes);
            assertEquals(1, attributes.size());
            assertEquals(.3f, attributes.get(0).getValue());
        });
    }

    @Test
    void stopsQueuedInterpolationWhenPlayerTakesControl() {
        mockContextScoreboard(context -> {
            var mount = create(context, "realmsunderoath:mount_land");
            var player = context.session().getPlayerEntity();
            when(player.position()).thenReturn(Vector3f.ZERO);
            mount.moveRelative(1, 0, 0, 0, 0, 0, true);
            mount.setPassengers(List.of(player));
            when(player.getVehicle()).thenReturn(mount);
            var predicted = Vector3f.from(3, 1, 2);
            mount.setPosition(predicted);
            mount.tick();
            assertEquals(predicted, mount.getPosition());
            verify(context.session(), never()).getQueuedImmediatelyPackets();
        });
    }

    @Test
    void happyGhastCanMoveSurvivesWolfPropertiesInSpawnAndEveryUpdatePath() {
        mockContextScoreboard(context -> {
            mockHarness(context);
            var properties = mock(GeyserEntityProperties.class);
            when(properties.getProperties()).thenReturn(List.of(WolfEntity.SOUND_VARIANT));
            when(properties.getPropertyIndex("minecraft:sound_variant")).thenReturn(0);
            var definition = new EntityDefinition<LivingEntity>(LivingEntity::new, EntityType.WOLF,
                "minecraft:wolf", .6f, .85f, 0, properties, List.of());
            for (String identifier : List.of("realmsunderoath:mount_sky", "realmsunderoath:mount_land")) {
                var mount = new LivingEntity(context.session(), 10, 10, UUID.randomUUID(), definition,
                    Vector3f.ZERO, Vector3f.ZERO, 0, 0, 0);
                int expected = identifier.endsWith("mount_sky") ? 1 : 0;
                mount.spawnEntity(identifier);
                assertSame(definition, mount.getDefinition());
                assertNextPacketMatch(context, AddEntityPacket.class, packet ->
                    assertEquals(List.of(new IntEntityProperty(0, expected)), packet.getProperties().getIntProperties()));
                if (identifier.endsWith("mount_sky")) {
                    assertNextPacketMatch(context, MobArmorEquipmentPacket.class, packet ->
                        assertEquals("minecraft:white_harness", packet.getBody().getDefinition().getIdentifier()));
                }
                mount.getPropertyManager().addProperty(WolfEntity.SOUND_VARIANT, "default");
                mount.updateBedrockEntityProperties();
                assertNextPacketMatch(context, SetEntityDataPacket.class, packet ->
                    assertEquals(List.of(new IntEntityProperty(0, expected)), packet.getProperties().getIntProperties()));
                mount.getPropertyManager().addProperty(WolfEntity.SOUND_VARIANT, "default");
                mount.getDirtyMetadata().put(EntityDataTypes.NAME, "测试属性隔离");
                mount.updateBedrockMetadata();
                assertNextPacketMatch(context, SetEntityDataPacket.class, packet ->
                    assertEquals(List.of(new IntEntityProperty(0, expected)), packet.getProperties().getIntProperties()));
                mount.updatePropertiesBatched(updater -> updater.update(WolfEntity.SOUND_VARIANT, "default"), false);
                assertNextPacketMatch(context, SetEntityDataPacket.class, packet ->
                    assertEquals(List.of(new IntEntityProperty(0, expected)), packet.getProperties().getIntProperties()));
            }
        });
    }
}
