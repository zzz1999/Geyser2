package org.geysermc.geyser.registry.populator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicReference;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.geysermc.geyser.GeyserBootstrap;
import org.geysermc.geyser.GeyserImpl;
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineEntityPropertiesEvent;
import org.geysermc.geyser.entity.EntityDefinition;
import org.geysermc.geyser.entity.EntityDefinitions;
import org.geysermc.geyser.entity.type.Entity;
import org.geysermc.geyser.event.GeyserEventBus;
import org.geysermc.geyser.impl.IdentifierImpl;
import org.geysermc.geyser.registry.Registries;
import org.geysermc.geyser.scoreboard.network.util.GeyserMockContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 按启动顺序加载映射和原版实体，验证属性发布不阻断原版初始化或扩展注册。 */
class CustomEntityInitializationTest {
    @TempDir
    Path directory;

    @Test
    void initializesVanillaDefinitionsAfterReadingCustomMappings() throws Exception {
        Path mappings = Files.createDirectories(directory.resolve("custom_mappings"));
        Files.writeString(mappings.resolve("mounts.json"), """
            {"format_version":1,"entities":{
              "realmsunderoath:mount_land":{"runtime_identifier":"minecraft:horse","collision_box":{"width":0.6,"height":0.85}},
              "realmsunderoath:mount_sky":{"runtime_identifier":"minecraft:happy_ghast","collision_box":{"width":0.6,"height":0.85}}
            }}
            """);
        GeyserMockContext.mockContext(context -> {
            var geyser = context.mockOrSpy(GeyserImpl.class);
            var bootstrap = mock(GeyserBootstrap.class);
            when(geyser.getBootstrap()).thenReturn(bootstrap);
            when(bootstrap.getConfigFolder()).thenReturn(directory);
            when(bootstrap.getResourceOrThrow("bedrock/entity_identifiers.dat")).thenAnswer(ignored ->
                CustomEntityInitializationTest.class.getResourceAsStream("/bedrock/entity_identifiers.dat"));
            var bus = mock(GeyserEventBus.class);
            when(geyser.getEventBus()).thenReturn(bus);
            var event = new AtomicReference<GeyserDefineEntityPropertiesEvent>();
            var published = Registries.BEDROCK_ENTITY_PROPERTIES.get();
            var savedProperties = new HashSet<>(published);
            var savedCustom = new HashMap<>(Registries.CUSTOM_ENTITY_DEFINITIONS);
            if (!Registries.BEDROCK_ENTITY_IDENTIFIERS.loaded()) {
                Registries.BEDROCK_ENTITY_IDENTIFIERS.load();
            }
            NbtMap savedIdentifiers = Registries.BEDROCK_ENTITY_IDENTIFIERS.get();
            var testIdentifier = IdentifierImpl.of("realms_test", "registration_probe");
            var propertyIdentifier = IdentifierImpl.of("realms_test", "enabled");
            var savedProbe = Registries.JAVA_ENTITY_IDENTIFIERS.get(testIdentifier.toString());
            published.clear();
            try {
                doAnswer(invocation -> {
                    assertTrue(published.isEmpty(), "扩展事件结束前不能发布属性表");
                    GeyserDefineEntityPropertiesEvent registration = invocation.getArgument(0);
                    event.set(registration);
                    var probe = EntityDefinition.builder(Entity::new).identifier(testIdentifier.toString()).build();
                    Registries.JAVA_ENTITY_IDENTIFIERS.get().put(testIdentifier.toString(), probe);
                    registration.registerBooleanProperty(testIdentifier, propertyIdentifier, true);
                    assertEquals(1, registration.properties(testIdentifier).size());
                    return null;
                }).when(bus).fire(any(GeyserDefineEntityPropertiesEvent.class));

                CustomEntityRegistryPopulator.populate();
                assertDoesNotThrow(EntityDefinitions::init);
                assertNotNull(event.get());
                assertTrue(published.stream().anyMatch(schema -> "minecraft:wolf".equals(schema.getString("type"))));
                assertTrue(published.stream().anyMatch(schema -> "minecraft:happy_ghast".equals(schema.getString("type"))));
                var sky = published.stream().filter(schema ->
                    "realmsunderoath:mount_sky".equals(schema.getString("type"))).findFirst().orElseThrow();
                assertEquals(1, sky.getList("properties", NbtType.COMPOUND).size());
                assertEquals("minecraft:can_move", sky.getList("properties", NbtType.COMPOUND).get(0).getString("name"));
                assertFalse(published.stream().anyMatch(schema -> "realmsunderoath:mount_land".equals(schema.getString("type"))));
                assertThrows(IllegalStateException.class, () -> event.get().registerBooleanProperty(
                    testIdentifier, IdentifierImpl.of("realms_test", "too_late"), true));
            } finally {
                published.clear();
                published.addAll(savedProperties);
                Registries.BEDROCK_ENTITY_IDENTIFIERS.set(savedIdentifiers);
                Registries.CUSTOM_ENTITY_DEFINITIONS.clear();
                Registries.CUSTOM_ENTITY_DEFINITIONS.putAll(savedCustom);
                if (savedProbe == null) Registries.JAVA_ENTITY_IDENTIFIERS.get().remove(testIdentifier.toString());
                else Registries.JAVA_ENTITY_IDENTIFIERS.get().put(testIdentifier.toString(), savedProbe);
            }
        });
    }
}
