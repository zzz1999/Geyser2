/*
 * Copyright (c) 2025 GeyserMC. http://geysermc.org
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

package org.geysermc.geyser.registry.populator;

import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityDataTypes;
import org.geysermc.geyser.GeyserImpl;
import org.geysermc.geyser.entity.EntityDefinition;
import org.geysermc.geyser.entity.type.Entity;
import org.geysermc.geyser.entity.type.LivingEntity;
import org.geysermc.geyser.entity.type.living.MobEntity;
import org.geysermc.geyser.entity.type.living.animal.HappyGhastEntity;
import org.geysermc.geyser.registry.Registries;
import org.geysermc.geyser.registry.mappings.MappingsConfigReader;
import org.geysermc.geyser.registry.mappings.util.CustomEntityMapping;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.MetadataTypes;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.type.BooleanEntityMetadata;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 注册自定义客户端实体与原生运行时身份，并在原版及扩展注册完成后发布专用属性表。 */
public class CustomEntityRegistryPopulator {
    private static int rid = 10000;
    private static final Set<NbtMap> pendingRuntimeProperties = new HashSet<>();
    private static EntityDefinition<LivingEntity> baseEntity;

    static {
        EntityDefinition<Entity> entityBase = EntityDefinition.builder(Entity::new)
            .addTranslator(MetadataTypes.BYTE, Entity::setFlags)
            .addTranslator(MetadataTypes.INT, Entity::setAir) // Air/bubbles
            .addTranslator(MetadataTypes.OPTIONAL_COMPONENT, Entity::setDisplayName)
            .addTranslator(MetadataTypes.BOOLEAN, Entity::setDisplayNameVisible)
            .addTranslator(MetadataTypes.BOOLEAN, Entity::setSilent)
            .addTranslator(MetadataTypes.BOOLEAN, Entity::setGravity)
            .addTranslator(MetadataTypes.POSE, (entity, entityMetadata) -> entity.setPose(entityMetadata.getValue()))
            .addTranslator(MetadataTypes.INT, Entity::setFreezing)
            .build();

        baseEntity = EntityDefinition.inherited(LivingEntity::new, entityBase)
            .addTranslator(MetadataTypes.BYTE, LivingEntity::setLivingEntityFlags)
            .addTranslator(MetadataTypes.FLOAT, LivingEntity::setHealth)
            .addTranslator(MetadataTypes.INT,
                (livingEntity, entityMetadata) -> livingEntity.getDirtyMetadata().put(EntityDataTypes.EFFECT_COLOR, entityMetadata.getValue()))
            .addTranslator(MetadataTypes.BOOLEAN,
                (livingEntity, entityMetadata) -> livingEntity.getDirtyMetadata().put(EntityDataTypes.EFFECT_AMBIENCE, (byte) (((BooleanEntityMetadata) entityMetadata).getPrimitiveValue() ? 1 : 0)))
            .addTranslator(null) // Arrow count
            .addTranslator(null) // Stinger count
            .addTranslator(MetadataTypes.OPTIONAL_BLOCK_POS, LivingEntity::setBedPosition)
            .build();
//        baseEntity = EntityDefinition.inherited(MobEntity::new, livingEntityBase)
//                .addTranslator(MetadataTypes.BYTE, MobEntity::setMobFlags)
//                .build();

    }

    static NbtMap createNetworkEntry(CustomEntityMapping mapping, int runtimeId, List<NbtMap> vanillaEntities) {
        String runtimeIdentifier = mapping.runtimeIdentifier();
        if (!runtimeIdentifier.isEmpty() && vanillaEntities.stream()
            .noneMatch(entry -> runtimeIdentifier.equals(entry.getString("id")))) {
            throw new IllegalArgumentException("Unknown vanilla entity runtime_identifier: " + runtimeIdentifier);
        }
        var entry = NbtMap.builder().putInt("rid", runtimeId)
            .putString("id", mapping.identifier())
            .putString("bid", runtimeIdentifier)
            .putBoolean("hasspawnegg", false)
            .putBoolean("summonable", false);
        if (runtimeIdentifier.isEmpty()) {
            // 未配置继承的实体继续使用网易分支原有的通用类型；显式继承时采用原版条目格式。
            entry.putInt("type", 256);
        }
        return entry.build();
    }

    static NbtMap createRuntimeProperties(CustomEntityMapping mapping) {
        if (!"realmsunderoath:mount_sky".equals(mapping.identifier())
            || !"minecraft:happy_ghast".equals(mapping.runtimeIdentifier())) return null;
        return NbtMap.builder().putString("type", mapping.identifier())
            .putList("properties", NbtType.COMPOUND, HappyGhastEntity.CAN_MOVE_PROPERTY.nbtMap()).build();
    }

    public static void populateProperties() {
        Registries.BEDROCK_ENTITY_PROPERTIES.get().addAll(pendingRuntimeProperties);
        pendingRuntimeProperties.clear();
    }

    public static void populate() {
        pendingRuntimeProperties.clear();
        NbtMap nbtMap = Registries.BEDROCK_ENTITY_IDENTIFIERS.get();

        List<NbtMap> idlist = nbtMap.getList("idlist", NbtType.COMPOUND);
        List<NbtMap> nbtMaps = new ArrayList<>(idlist);
        MappingsConfigReader mappingsConfigReader = new MappingsConfigReader();
        // Load custom entities from mappings files
        mappingsConfigReader.loadEntityMappingsFromJson((key, item) -> {
            rid++;
            NbtMap entityNbt = createNetworkEntry(item, rid, idlist);
            nbtMaps.add(entityNbt);
            NbtMap runtimeProperties = createRuntimeProperties(item);
            if (runtimeProperties != null) pendingRuntimeProperties.add(runtimeProperties);
            if (!item.runtimeIdentifier().isEmpty()) {
                GeyserImpl.getInstance().getLogger().info("[CustomEntity] 原生运行时映射：id=" + key
                    + " bid=" + entityNbt.getString("bid") + " rid=" + entityNbt.getInt("rid"));
            }

            int start = key.indexOf(":");

            String substring = key.substring(start + 1);
            EntityDefinition<MobEntity> mobEntityBase = EntityDefinition.inherited(MobEntity::new, baseEntity)
                .addTranslator(MetadataTypes.BYTE, MobEntity::setMobFlags)
                .identifier(item.identifier())
                .height(item.height())
                .width(item.width())
                .build();

            Registries.CUSTOM_ENTITY_DEFINITIONS.put(substring, mobEntityBase);
        });

        NbtMap entityNbtMaps = NbtMap.builder().putList("idlist", NbtType.COMPOUND, nbtMaps).build();
        Registries.BEDROCK_ENTITY_IDENTIFIERS.set(entityNbtMaps);

    }

}
