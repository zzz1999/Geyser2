package org.geysermc.geyser.session.cache;

import java.util.List;
import org.cloudburstmc.protocol.bedrock.data.InputMode;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket;
import org.geysermc.geyser.entity.type.Entity;
import org.geysermc.geyser.entity.type.player.SessionPlayerEntity;
import org.geysermc.geyser.entity.vehicle.ClientVehicle;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundPlayerInputPacket;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/** 验证服务端控制的自定义坐骑过滤自动水跳，同时保留真实跳跃和原版输入语义。 */
class InputCacheMountJumpTest {
    private final GeyserSession session = mock(GeyserSession.class);
    private final SessionPlayerEntity player = mock(SessionPlayerEntity.class);
    private final InputCache cache = new InputCache(session);

    private static Entity customVehicle(boolean clientControlled) {
        Entity vehicle = clientControlled
            ? mock(Entity.class, withSettings().extraInterfaces(ClientVehicle.class)) : mock(Entity.class);
        when(vehicle.getCurIdentifier()).thenReturn("realmsunderoath:mount_water");
        return vehicle;
    }

    private void input(PlayerAuthInputData... flags) {
        var packet = new PlayerAuthInputPacket();
        packet.setInputMode(InputMode.MOUSE);
        packet.getInputData().addAll(List.of(flags));
        cache.processInputs(player, packet);
    }

    private void assertJumpSent() {
        var sent = ArgumentCaptor.forClass(ServerboundPlayerInputPacket.class);
        verify(session).sendDownstreamGamePacket(sent.capture());
        assertTrue(sent.getValue().isJump());
    }

    @Test
    void ignoresAutomaticWaterJumpOnServerControlledCustomVehicle() {
        Entity vehicle = customVehicle(false);
        when(player.getVehicle()).thenReturn(vehicle);
        input(PlayerAuthInputData.AUTO_JUMPING_IN_WATER);
        assertFalse(cache.wasJumping());
        verify(session, never()).sendDownstreamGamePacket(any());
    }

    @ParameterizedTest
    @EnumSource(value = PlayerAuthInputData.class, names = {"JUMP_CURRENT_RAW", "JUMP_DOWN"})
    void forwardsManualJumpOnServerControlledCustomVehicle(PlayerAuthInputData flag) {
        Entity vehicle = customVehicle(false);
        when(player.getVehicle()).thenReturn(vehicle);
        input(flag);
        assertJumpSent();
    }

    @ParameterizedTest
    @EnumSource(value = PlayerAuthInputData.class, names = {"JUMP_CURRENT_RAW", "JUMP_DOWN"})
    void forwardsManualJumpEvenWhenAutomaticWaterJumpIsPresent(PlayerAuthInputData flag) {
        Entity vehicle = customVehicle(false);
        when(player.getVehicle()).thenReturn(vehicle);
        input(flag, PlayerAuthInputData.AUTO_JUMPING_IN_WATER);
        assertJumpSent();
    }

    @Test
    void preservesAutomaticWaterJumpWithoutVehicle() {
        input(PlayerAuthInputData.AUTO_JUMPING_IN_WATER);
        assertJumpSent();
    }

    @Test
    void preservesAutomaticWaterJumpOnVanillaVehicle() {
        when(player.getVehicle()).thenReturn(mock(Entity.class));
        input(PlayerAuthInputData.AUTO_JUMPING_IN_WATER);
        assertJumpSent();
    }

    @Test
    void preservesAutomaticWaterJumpOnClientControlledCustomVehicle() {
        Entity vehicle = customVehicle(true);
        when(player.getVehicle()).thenReturn(vehicle);
        input(PlayerAuthInputData.AUTO_JUMPING_IN_WATER);
        assertJumpSent();
    }

    @Test
    void releasesManualJumpWhenOnlyAutomaticWaterJumpRemains() {
        Entity vehicle = customVehicle(false);
        when(player.getVehicle()).thenReturn(vehicle);
        input(PlayerAuthInputData.JUMP_CURRENT_RAW);
        input(PlayerAuthInputData.AUTO_JUMPING_IN_WATER);
        var sent = ArgumentCaptor.forClass(ServerboundPlayerInputPacket.class);
        verify(session, times(2)).sendDownstreamGamePacket(sent.capture());
        assertEquals(List.of(true, false), sent.getAllValues().stream().map(ServerboundPlayerInputPacket::isJump).toList());
        assertFalse(cache.wasJumping());
    }
}
