package org.geysermc.geyser.entity.vehicle;

import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket;
import org.geysermc.geyser.GeyserImpl;
import org.geysermc.geyser.GeyserLogger;
import org.geysermc.geyser.entity.type.living.animal.horse.AbstractHorseEntity;
import org.geysermc.geyser.entity.type.player.SessionPlayerEntity;
import org.geysermc.geyser.session.GeyserSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 验证原版马诊断区分客户端预测与代理模拟，并且每次骑乘限次输出。 */
class HorsePredictionDiagnosticsTest {
    private final AbstractHorseEntity horse = mock(AbstractHorseEntity.class);
    private final GeyserSession session = mock(GeyserSession.class);
    private final GeyserLogger logger = mock(GeyserLogger.class);
    private HorseVehicleComponent component;

    @BeforeEach
    void setup() {
        var geyser = mock(GeyserImpl.class);
        when(horse.getSession()).thenReturn(session);
        when(horse.getPosition()).thenReturn(Vector3f.ZERO);
        when(horse.getGeyserId()).thenReturn(42L);
        when(session.getPlayerEntity()).thenReturn(mock(SessionPlayerEntity.class));
        when(session.getGeyser()).thenReturn(geyser);
        when(geyser.getLogger()).thenReturn(logger);
        component = new HorseVehicleComponent(horse);
        component.onMount();
    }

    @Test
    void reportsFallbackOnceThenReportsGenuinePredictionWithoutMovingAnything() {
        var packet = new PlayerAuthInputPacket();
        when(horse.shouldSimulateMovement()).thenReturn(true);
        for (int i = 0; i < 200; i++) component.observeClientInput(packet);
        verify(logger).info(contains("route=GEYSER_SIMULATION"));
        packet.getInputData().add(PlayerAuthInputData.IN_CLIENT_PREDICTED_IN_VEHICLE);
        packet.setPredictedVehicle(42);
        when(session.isInClientPredictedVehicle()).thenReturn(true);
        for (int i = 0; i < 200; i++) component.observeClientInput(packet);
        verify(logger).info(contains("idMatched=true route=CLIENT_POSITION"));
        verify(logger, times(2)).info(anyString());
        verify(session, never()).sendDownstreamGamePacket(any());
        verify(session, never()).sendUpstreamPacket(any());
    }

    @Test
    void mismatchedPredictionIsVisibleAndDismountRemountResetsReporting() {
        var packet = new PlayerAuthInputPacket();
        packet.getInputData().add(PlayerAuthInputData.IN_CLIENT_PREDICTED_IN_VEHICLE);
        packet.setPredictedVehicle(99);
        when(session.isInClientPredictedVehicle()).thenReturn(true);
        for (int i = 0; i < 100; i++) component.observeClientInput(packet);
        verify(logger).info(contains("predicted=99 vehicleFlag=true idMatched=false"));
        component.onMount();
        for (int i = 0; i < 100; i++) component.observeClientInput(packet);
        verify(logger, times(2)).info(anyString());
    }
}
