package org.geysermc.geyser.translator.protocol.java.entity;

import java.util.List;
import org.cloudburstmc.math.vector.Vector3d;
import org.cloudburstmc.math.vector.Vector3f;
import org.geysermc.geyser.entity.EntityDefinition;
import org.geysermc.geyser.entity.type.Entity;
import org.geysermc.geyser.entity.type.player.SessionPlayerEntity;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.geyser.session.cache.EntityCache;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 验证常规回显仅对当前控制者过滤，且服务器显式载具纠错和传送始终保留。 */
class CustomMountMovementTranslationTest {
    private final GeyserSession session = mock(GeyserSession.class);
    private final Entity vehicle = mock(Entity.class);
    private final SessionPlayerEntity player = mock(SessionPlayerEntity.class);

    @BeforeEach
    void setup() {
        var cache = mock(EntityCache.class);
        when(session.getEntityCache()).thenReturn(cache);
        when(cache.getEntityByJavaId(anyInt())).thenReturn(vehicle);
        when(session.getPlayerEntity()).thenReturn(player);
        when(player.getVehicle()).thenReturn(vehicle);
        when(vehicle.isLocallyControlledCustomMount()).thenReturn(true);
    }

    @Test
    void ignoresTrackerPositionRotationHeadAndVelocityForController() {
        new JavaMoveEntityPosTranslator().translate(session, mock(ClientboundMoveEntityPosPacket.class));
        new JavaMoveEntityPosRotTranslator().translate(session, mock(ClientboundMoveEntityPosRotPacket.class));
        new JavaMoveEntityRotTranslator().translate(session, mock(ClientboundMoveEntityRotPacket.class));
        new JavaRotateHeadTranslator().translate(session, mock(ClientboundRotateHeadPacket.class));
        new JavaEntityPositionSyncTranslator().translate(session, mock(ClientboundEntityPositionSyncPacket.class));
        new JavaSetEntityMotionTranslator().translate(session, mock(ClientboundSetEntityMotionPacket.class));
        verify(vehicle, never()).moveRelative(anyDouble(), anyDouble(), anyDouble(), anyFloat(), anyFloat(), anyFloat(), anyBoolean());
        verify(vehicle, never()).updatePositionAndRotation(anyDouble(), anyDouble(), anyDouble(), anyFloat(), anyFloat(), anyBoolean());
        verify(vehicle, never()).updateRotation(anyFloat(), anyFloat(), anyBoolean());
        verify(vehicle, never()).updateHeadLookRotation(anyFloat());
        verify(vehicle, never()).moveAbsolute(any(), anyFloat(), anyFloat(), anyFloat(), anyBoolean(), anyBoolean());
        verify(vehicle, never()).setMotion(any());
        verify(session, never()).sendUpstreamPacket(any());
    }

    @Test
    void observersAndOtherEntitiesStillReceiveTrackerMovement() {
        when(vehicle.isLocallyControlledCustomMount()).thenReturn(false);
        var packet = mock(ClientboundMoveEntityPosPacket.class);
        when(packet.getMoveX()).thenReturn(.25);
        when(packet.getMoveY()).thenReturn(.5);
        new JavaMoveEntityPosTranslator().translate(session, packet);
        verify(vehicle).moveRelative(.25, .5, 0, 0, 0, 0, false);
    }

    @Test
    void preservesAuthoritativeVehicleCorrection() {
        var packet = mock(ClientboundMoveVehiclePacket.class);
        when(packet.getPosition()).thenReturn(Vector3d.from(12, 64, 23));
        when(packet.getYRot()).thenReturn(45f);
        when(packet.getXRot()).thenReturn(7f);
        new JavaMoveVehicleTranslator().translate(session, packet);
        verify(vehicle).moveAbsolute(Vector3f.from(12, 64, 23), 45, 7, false, true);
    }

    @Test
    void preservesNearbyExplicitTeleportWithoutUsingFilteredRelativeMovement() {
        var packet = mock(ClientboundTeleportEntityPacket.class);
        when(packet.getPosition()).thenReturn(Vector3d.from(12, 64, 23));
        when(packet.getRelatives()).thenReturn(List.of());
        when(packet.getDeltaMovement()).thenReturn(Vector3d.ZERO);
        when(packet.getYRot()).thenReturn(45f);
        when(packet.getXRot()).thenReturn(7f);
        when(vehicle.getPosition()).thenReturn(Vector3f.from(11, 64, 23));
        when(vehicle.getMotion()).thenReturn(Vector3f.ZERO);
        when(vehicle.getPassengers()).thenReturn(List.of());
        doReturn(mock(EntityDefinition.class)).when(vehicle).getDefinition();
        new JavaTeleportEntityTranslator().translate(session, packet);
        verify(vehicle).moveAbsolute(Vector3f.from(12, 64, 23), 45, 7, 45, false, true);
        verify(vehicle, never()).moveRelative(anyDouble(), anyDouble(), anyDouble(), anyFloat(), anyFloat(), anyFloat(), anyBoolean());
    }
}
