/*
 * Copyright (c) 2026 GeyserMC. http://geysermc.org
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

package org.geysermc.geyser.registry.mappings.versions;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.geysermc.geyser.api.item.custom.NeteaseFrameAnimationComponent;
import org.geysermc.geyser.item.exception.InvalidCustomMappingsFileException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 验证物品动画及自定义实体运行时映射的解析和非法配置拒绝。 */
public class MappingsReaderV1Test {

    @Test
    public void readsNeteaseFrameAnimationMapping() throws InvalidCustomMappingsFileException {
        JsonObject node = parse("""
                {
                  "netease_frame_anim_in_scene": {
                    "ticks_per_frame": 4,
                    "texture_path": "textures/items/animated_item"
                  }
                }
                """);

        NeteaseFrameAnimationComponent component = MappingsReader_v1.readNeteaseFrameAnimationComponent(node);

        assertEquals(new NeteaseFrameAnimationComponent(4, "textures/items/animated_item"), component);
    }

    @Test
    public void readsNamespacedNeteaseFrameAnimationMapping() throws InvalidCustomMappingsFileException {
        JsonObject node = parse("""
                {
                  "netease:frame_anim_in_scene": {
                    "ticks_per_frame": 20,
                    "texture_path": "textures/items/animated_item"
                  }
                }
                """);

        NeteaseFrameAnimationComponent component = MappingsReader_v1.readNeteaseFrameAnimationComponent(node);

        assertEquals(new NeteaseFrameAnimationComponent(20, "textures/items/animated_item"), component);
    }

    @Test
    public void rejectsInvalidNeteaseFrameAnimationMapping() {
        JsonObject node = parse("""
                {
                  "netease_frame_anim_in_scene": {
                    "ticks_per_frame": 0,
                    "texture_path": ""
                  }
                }
                """);

        assertThrows(InvalidCustomMappingsFileException.class,
                () -> MappingsReader_v1.readNeteaseFrameAnimationComponent(node));
    }

    @Test
    void readsOptionalEntityRuntimeWithoutChangingCustomIdentity() throws InvalidCustomMappingsFileException {
        var entry = new MappingsReader_v1().readEntityMappingEntry("realmsunderoath:mount_land", parse("""
            {"runtime_identifier":"minecraft:horse", "collision_box":{"width":0.6,"height":0.85}}
            """));
        assertEquals("realmsunderoath:mount_land", entry.identifier());
        assertEquals("minecraft:horse", entry.runtimeIdentifier());
        assertEquals(0.6f, entry.width());
        assertEquals(0.85f, entry.height());
    }

    @Test
    void preservesUnconfiguredEntityRuntime() throws InvalidCustomMappingsFileException {
        var entry = new MappingsReader_v1().readEntityMappingEntry("test:other", parse("{}"));
        assertEquals("", entry.runtimeIdentifier());
        assertEquals(1f, entry.width());
        assertEquals(1f, entry.height());
    }

    @Test
    void rejectsInvalidEntityRuntimeValues() {
        for (String value : new String[] {"null", "42", "{}", "[]", "\"\"", "\"horse\"", "\"custom:horse\""}) {
            assertThrows(InvalidCustomMappingsFileException.class,
                () -> new MappingsReader_v1().readEntityMappingEntry("test:mount",
                    parse("{\"runtime_identifier\":" + value + "}")));
        }
    }

    private static JsonObject parse(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }
}
