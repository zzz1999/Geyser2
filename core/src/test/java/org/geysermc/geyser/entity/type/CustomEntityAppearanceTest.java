/*
 * Copyright (c) 2019-2022 GeyserMC. http://geysermc.org
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 *
 * @author GeyserMC
 * @link https://github.com/GeyserMC/Geyser
 */

package org.geysermc.geyser.entity.type;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityDataTypes;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityFlag;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityLinkData;
import org.cloudburstmc.protocol.bedrock.packet.AddEntityPacket;
import org.cloudburstmc.protocol.bedrock.packet.RemoveEntityPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetEntityLinkPacket;
import org.geysermc.geyser.entity.EntityDefinition;
import org.geysermc.geyser.registry.Registries;
import org.geysermc.geyser.scoreboard.network.util.GeyserMockContext;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.MetadataTypes;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.type.IntEntityMetadata;
import org.geysermc.mcprotocollib.protocol.data.game.entity.type.EntityType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.geysermc.geyser.scoreboard.network.util.AssertUtils.assertNextPacketMatch;
import static org.geysermc.geyser.scoreboard.network.util.AssertUtils.assertNextPacketType;
import static org.geysermc.geyser.scoreboard.network.util.AssertUtils.assertNoNextPacket;
import static org.geysermc.geyser.scoreboard.network.util.GeyserMockContextScoreboard.mockContextScoreboard;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/** 验证自定义实体换模保留 Java 类型、元数据转换、生成位置和已有骑乘关系。 */
class CustomEntityAppearanceTest {
    private static final String TEST_KEY = "mount_water";
    private static final String DISPLAY_ID = "realmsunderoath:mount_water";

    private static Entity entity(GeyserMockContext context, int id, Vector3f position) {
        var definition = EntityDefinition.<Entity>builder(Entity::new)
            .type(EntityType.WOLF).width(0.6f).height(0.85f)
            .addTranslator(MetadataTypes.INT, Entity::setAir)
            .build(false);
        return new Entity(context.session(), id, id, UUID.randomUUID(),
            definition, position, Vector3f.ZERO, 0, 0, 0);
    }

    private static void withCustomDefinition(Consumer<GeyserMockContext> test) {
        mockContextScoreboard(context -> {
            var custom = EntityDefinition.<Entity>builder(Entity::new)
                .identifier(DISPLAY_ID).width(4).height(5).build(false);
            var previous = Registries.CUSTOM_ENTITY_DEFINITIONS.put(TEST_KEY, custom);
            try {
                test.accept(context);
            } finally {
                if (previous == null) {
                    Registries.CUSTOM_ENTITY_DEFINITIONS.remove(TEST_KEY);
                } else {
                    Registries.CUSTOM_ENTITY_DEFINITIONS.put(TEST_KEY, previous);
                }
            }
        });
    }

    @Test
    void preservesDefinitionAndMetadataAndAvoidsDuplicateRespawn() {
        withCustomDefinition(context -> {
            var mount = entity(context, 10, Vector3f.ZERO);
            var original = mount.getDefinition();
            mount.spawnEntity();
            assertNextPacketType(context, AddEntityPacket.class);

            mount.setNametag("@cet_" + TEST_KEY + "@name", false);

            assertNextPacketType(context, RemoveEntityPacket.class);
            assertNextPacketMatch(context, AddEntityPacket.class, packet -> {
                assertEquals(DISPLAY_ID, packet.getIdentifier());
                assertEquals(0.6f, packet.getMetadata().get(EntityDataTypes.WIDTH));
                assertEquals(0.85f, packet.getMetadata().get(EntityDataTypes.HEIGHT));
                assertEquals((short) 300, packet.getMetadata().get(EntityDataTypes.AIR_SUPPLY));
            });
            assertSame(original, mount.getDefinition());
            assertEquals(EntityType.WOLF, mount.getDefinition().entityType());
            assertSame(original.translators(), mount.getDefinition().translators());
            assertEquals(0.6f, mount.getBoundingBoxWidth());
            assertEquals(0.85f, mount.getBoundingBoxHeight());
            @SuppressWarnings("unchecked")
            var originalDefinition = (EntityDefinition<Entity>) original;
            originalDefinition.translateMetadata(mount, new IntEntityMetadata(0, MetadataTypes.INT, 42));
            assertEquals((short) 42, mount.getDirtyMetadata().get(EntityDataTypes.AIR_SUPPLY));

            mount.setNametag("@cet_" + DISPLAY_ID + "@renamed", false);
            assertNoNextPacket(context);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"mount_water", "mount_land", "mount_sky"})
    void acceptsFullNamespacedIdentifiersBeforeSpawn(String key) {
        mockContextScoreboard(context -> {
            String identifier = "realmsunderoath:" + key;
            var custom = EntityDefinition.<Entity>builder(Entity::new).identifier(identifier).build(false);
            var previous = Registries.CUSTOM_ENTITY_DEFINITIONS.put(key, custom);
            try {
                var mount = entity(context, 10, Vector3f.ZERO);
                mount.setNametag("@cet_" + identifier + "@", false);
                assertNoNextPacket(context);
                mount.spawnEntity();
                assertNextPacketMatch(context, AddEntityPacket.class,
                    packet -> assertEquals(identifier, packet.getIdentifier()));
            } finally {
                if (previous == null) {
                    Registries.CUSTOM_ENTITY_DEFINITIONS.remove(key);
                } else {
                    Registries.CUSTOM_ENTITY_DEFINITIONS.put(key, previous);
                }
            }
        });
    }

    @Test
    void usesSafeSpawnPositionAndRetainsActualPosition() {
        withCustomDefinition(context -> {
            when(context.session().isSpawned()).thenReturn(true);
            when(context.session().getServerRenderDistance()).thenReturn(16);
            when(context.session().getClientRenderDistance()).thenReturn(2);
            when(context.session().getPlayerEntity().position()).thenReturn(Vector3f.ZERO);
            var actualPosition = Vector3f.from(1000, 70, 0);
            var mount = entity(context, 10, actualPosition);
            mount.spawnEntity();
            assertNextPacketType(context, AddEntityPacket.class);

            mount.setNametag("@cet_" + TEST_KEY + "@", false);
            assertNextPacketType(context, RemoveEntityPacket.class);
            assertNextPacketMatch(context, AddEntityPacket.class, packet -> {
                assertEquals(DISPLAY_ID, packet.getIdentifier());
                assertEquals(Vector3f.from(16, 70, 0), packet.getPosition());
            });
            assertEquals(actualPosition, mount.getPosition());

            mount.spawnEntity(DISPLAY_ID);
            assertNextPacketMatch(context, AddEntityPacket.class,
                packet -> assertEquals(Vector3f.from(16, 70, 0), packet.getPosition()));
            assertEquals(actualPosition, mount.getPosition());
        });
    }

    @Test
    void restoresParentAndPassengerLinksWithoutDismounting() {
        withCustomDefinition(context -> {
            var parent = entity(context, 10, Vector3f.ZERO);
            var mount = entity(context, 11, Vector3f.ZERO);
            var front = entity(context, 12, Vector3f.ZERO);
            var rear = entity(context, 13, Vector3f.ZERO);
            for (var actor : List.of(parent, mount, front, rear)) {
                actor.spawnEntity();
                assertNextPacketType(context, AddEntityPacket.class);
            }
            parent.setPassengers(List.of(mount));
            mount.setVehicle(parent);
            mount.setFlag(EntityFlag.RIDING, true);
            mount.setPassengers(List.of(front, rear));
            front.setVehicle(mount);
            rear.setVehicle(mount);
            front.setFlag(EntityFlag.RIDING, true);
            rear.setFlag(EntityFlag.RIDING, true);

            mount.setNametag("@cet_" + TEST_KEY + "@", false);
            assertNextPacketType(context, RemoveEntityPacket.class);
            assertNextPacketType(context, AddEntityPacket.class);
            var links = context.packets().stream()
                .filter(SetEntityLinkPacket.class::isInstance)
                .map(SetEntityLinkPacket.class::cast)
                .map(SetEntityLinkPacket::getEntityLink)
                .collect(Collectors.toSet());
            assertEquals(Set.of(
                new EntityLinkData(10, 11, EntityLinkData.Type.RIDER, false, false, 0f),
                new EntityLinkData(11, 12, EntityLinkData.Type.RIDER, false, false, 0f),
                new EntityLinkData(11, 13, EntityLinkData.Type.PASSENGER, false, false, 0f)
            ), links);
            while (context.nextPacket() != null) {
                // 同时消费座位偏移元数据。
            }
            assertSame(parent, mount.getVehicle());
            assertSame(mount, front.getVehicle());
            assertSame(mount, rear.getVehicle());
            assertEquals(List.of(front, rear), mount.getPassengers());
            assertTrue(mount.getFlag(EntityFlag.RIDING));
            assertTrue(front.getFlag(EntityFlag.RIDING));
            assertTrue(rear.getFlag(EntityFlag.RIDING));
        });
    }

    @Test
    void customRespawnDoesNotInvokeSubclassSpawnLifecycle() {
        withCustomDefinition(context -> {
            var definition = entity(context, 10, Vector3f.ZERO).getDefinition();
            var mount = new Entity(context.session(), 11, 11, UUID.randomUUID(),
                definition, Vector3f.ZERO, Vector3f.ZERO, 0, 0, 0) {
                @Override
                public void spawnEntity() {
                    throw new AssertionError("外观替换不能重新执行子类实体生成流程");
                }
            };
            mount.setValid(true);
            mount.setNametag("@cet_" + TEST_KEY + "@", false);
            assertNextPacketType(context, RemoveEntityPacket.class);
            assertNextPacketMatch(context, AddEntityPacket.class,
                packet -> assertEquals(DISPLAY_ID, packet.getIdentifier()));

            mount.spawnEntity(DISPLAY_ID);
            assertNextPacketMatch(context, AddEntityPacket.class,
                packet -> assertEquals(DISPLAY_ID, packet.getIdentifier()));
            assertSame(definition, mount.getDefinition());
        });
    }
    @Test
    void lateMountAppearanceUnlocksJumpWithoutChangingOtherCustomEntities() {
        withCustomDefinition(context -> {
            var mount = entity(context, 10, Vector3f.ZERO);
            when(context.session().getPlayerEntity().getVehicle()).thenReturn(mount);
            when(context.session().getPlayerEntity().getDirtyMetadata()).thenReturn(new org.geysermc.geyser.entity.GeyserDirtyMetadata());
            mount.setPassengers(List.of(context.session().getPlayerEntity()));
            assertTrue(mount.doesJumpDismount());
            mount.setNametag("@cet_" + TEST_KEY + "@", false);
            org.junit.jupiter.api.Assertions.assertFalse(mount.doesJumpDismount());
            org.junit.jupiter.api.Assertions.assertNotNull(mount.getClientPredictedMount());
            org.mockito.Mockito.verify(context.session()).setLockInput(
                org.geysermc.geyser.input.InputLocksFlag.JUMP, false);
            org.mockito.Mockito.verify(context.session()).updateInputLocks();
            mount.setFlag(EntityFlag.HAS_GRAVITY, false);
            mount.updateBedrockMetadata();
            assertTrue(mount.getFlag(EntityFlag.HAS_GRAVITY));
            mount.spawnEntity("realmsunderoath:another_model");
            assertNextPacketType(context, AddEntityPacket.class);
            assertTrue(mount.doesJumpDismount());
            org.junit.jupiter.api.Assertions.assertNull(mount.getClientPredictedMount());
            org.junit.jupiter.api.Assertions.assertFalse(mount.getFlag(EntityFlag.WASD_CONTROLLED));
            org.mockito.Mockito.verify(context.session()).setLockInput(
                org.geysermc.geyser.input.InputLocksFlag.JUMP, true);
        });
    }

    @Test
    void localRiderRespawnResendsJumpAttributesAndPreservesControlFlagsAgainstWolfMetadata() {
        withCustomDefinition(context -> {
            var mount = entity(context, 10, Vector3f.ZERO);
            when(context.session().getPlayerEntity().getVehicle()).thenReturn(mount);
            when(context.session().getPlayerEntity().getDirtyMetadata()).thenReturn(new org.geysermc.geyser.entity.GeyserDirtyMetadata());
            mount.spawnEntity(DISPLAY_ID);
            assertNextPacketMatch(context, AddEntityPacket.class, packet -> {
                for (EntityFlag flag : List.of(EntityFlag.TAMED, EntityFlag.SADDLED,
                    EntityFlag.CAN_WALK, EntityFlag.WASD_CONTROLLED, EntityFlag.CAN_POWER_JUMP)) {
                    assertTrue(Boolean.TRUE.equals(packet.getMetadata().getFlags().get(flag)), flag.name());
                }
            });
            assertNextPacketMatch(context, org.cloudburstmc.protocol.bedrock.packet.UpdateAttributesPacket.class,
                packet -> assertTrue(packet.getAttributes().stream().anyMatch(attribute ->
                    attribute.getName().equals("minecraft:horse.jump_strength") && attribute.getValue() == .42f)));
            mount.setFlag(EntityFlag.TAMED, false);
            mount.setFlag(EntityFlag.SADDLED, false);
            mount.updateBedrockMetadata();
            assertNextPacketMatch(context, org.cloudburstmc.protocol.bedrock.packet.SetEntityDataPacket.class, packet -> {
                assertTrue(Boolean.TRUE.equals(packet.getMetadata().getFlags().get(EntityFlag.TAMED)));
                assertTrue(Boolean.TRUE.equals(packet.getMetadata().getFlags().get(EntityFlag.SADDLED)));
            });
            assertNoNextPacket(context);
        });
    }

    @Test
    void ignoresUnknownIdentifiersWithoutReplacingTheActor() {
        withCustomDefinition(context -> {
            var mount = entity(context, 10, Vector3f.ZERO);
            var original = mount.getDefinition();
            mount.spawnEntity();
            assertNextPacketType(context, AddEntityPacket.class);
            mount.setNametag("@cet_unregistered:mount_water@", false);
            assertSame(original, mount.getDefinition());
            assertNoNextPacket(context);
        });
    }
}
