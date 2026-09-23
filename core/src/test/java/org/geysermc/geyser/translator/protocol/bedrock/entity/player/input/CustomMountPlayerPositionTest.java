package org.geysermc.geyser.translator.protocol.bedrock.entity.player.input;

import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket;
import org.geysermc.geyser.entity.EntityDefinitions;
import org.geysermc.geyser.entity.type.Entity;
import org.geysermc.geyser.entity.type.player.SessionPlayerEntity;
import org.geysermc.geyser.entity.vehicle.ClientPredictedMountComponent;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosRotPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerRotPacket;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 验证坐骑预测位置不会冒充玩家眼位，且无匹配预测身份的包不能污染玩家缓存。 */
class CustomMountPlayerPositionTest {
    private final GeyserSession session = mock(GeyserSession.class, RETURNS_DEEP_STUBS);
    private final SessionPlayerEntity player = mock(SessionPlayerEntity.class);
    private final Entity vehicle = mock(Entity.class);
    private final Vector3f acceptedVehiclePosition = Vector3f.from(12, 64, 23);
    private final Vector3f seat = Vector3f.from(0, 1.9075, 0);

    @BeforeEach
    void setup() {
        when(session.isSpawned()).thenReturn(true);
        when(session.getPlayerEntity()).thenReturn(player);
        when(session.getUnconfirmedTeleport()).thenReturn(null);
        when(player.getVehicle()).thenReturn(vehicle);
        when(player.getPosition()).thenReturn(Vector3f.from(11, 66, 23));
        when(player.getRiderSeatPosition()).thenReturn(seat);
        when(player.getLastTickEndVelocity()).thenReturn(Vector3f.ZERO);
        when(vehicle.getPosition()).thenReturn(acceptedVehiclePosition);
        when(vehicle.getClientPredictedMount()).thenReturn(mock(ClientPredictedMountComponent.class));
    }

    private PlayerAuthInputPacket input() {
        var packet = new PlayerAuthInputPacket();
        packet.getInputData().add(PlayerAuthInputData.IN_CLIENT_PREDICTED_IN_VEHICLE);
        packet.setPosition(Vector3f.from(1000, 64, 23));
        packet.setDelta(Vector3f.ZERO);
        packet.setRotation(Vector3f.ZERO);
        return packet;
    }

    @Test
    void rejectedPredictedActorCannotWritePlayerCacheOrConfirmTeleport() {
        when(session.isInClientPredictedVehicle()).thenReturn(false);
        BedrockMovePlayer.translate(session, input());
        verify(player, never()).setPositionManual(any());
        verify(session, never()).getUnconfirmedTeleport();
        verify(session, never()).confirmTeleport(any());
        verify(session, never()).sendDownstreamGamePacket(any());
        verify(session, never()).getWorldBorder();
    }

    @Test
    void acceptedPredictionUsesAcceptedVehiclePositionPlusExistingSeatForPlayerCache() {
        when(session.isInClientPredictedVehicle()).thenReturn(true);
        var expected = acceptedVehiclePosition.add(seat);
        var packet = input();
        packet.setPosition(acceptedVehiclePosition);
        BedrockMovePlayer.translate(session, packet);
        verify(player).setPositionManual(expected);
        verify(session.getWorldBorder()).spawnOrMoveBorderCollision(expected.down(EntityDefinitions.PLAYER.offset()));
        verify(session).sendDownstreamGamePacket(isA(ServerboundMovePlayerRotPacket.class));
        verify(session, never()).sendDownstreamGamePacket(isA(ServerboundMovePlayerPosPacket.class));
        verify(session, never()).sendDownstreamGamePacket(isA(ServerboundMovePlayerPosRotPacket.class));
    }

    @Test
    void rejectedWorldBorderCandidateCannotReplaceAcceptedRiderCache() {
        when(session.isInClientPredictedVehicle()).thenReturn(true);
        var packet = input();
        var expected = acceptedVehiclePosition.add(seat);
        BedrockMovePlayer.translate(session, packet);
        verify(player).setPositionManual(expected);
        verify(player, never()).setPositionManual(packet.getPosition().add(seat));
        verify(session.getWorldBorder()).spawnOrMoveBorderCollision(expected.down(EntityDefinitions.PLAYER.offset()));
    }

    @Test
    void ordinaryPlayerPositionWithoutPredictionFlagRetainsExistingSemantics() {
        var packet = input();
        packet.getInputData().clear();
        BedrockMovePlayer.translate(session, packet);
        verify(player).setPositionManual(packet.getPosition());
        verify(session).sendDownstreamGamePacket(isA(ServerboundMovePlayerRotPacket.class));
    }

    @Test
    void otherVehiclesKeepTheirExistingPositionCacheBehavior() {
        when(vehicle.getClientPredictedMount()).thenReturn(null);
        var packet = input();
        BedrockMovePlayer.translate(session, packet);
        verify(player).setPositionManual(packet.getPosition());
    }
}
