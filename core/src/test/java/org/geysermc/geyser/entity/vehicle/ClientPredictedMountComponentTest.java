package org.geysermc.geyser.entity.vehicle;

import java.util.List;
import org.cloudburstmc.math.vector.Vector2f;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityFlag;
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket;
import org.geysermc.geyser.GeyserImpl;
import org.geysermc.geyser.GeyserLogger;
import org.geysermc.geyser.entity.type.Entity;
import org.geysermc.geyser.entity.type.player.SessionPlayerEntity;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.geyser.session.cache.WorldBorder;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundMoveVehiclePacket;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 验证真实预测坐标转发、水中垂直动作与乐魂空中控制的隔离，以及有界位移诊断。 */
class ClientPredictedMountComponentTest {
    private final GeyserSession session = mock(GeyserSession.class);
    private final SessionPlayerEntity player = mock(SessionPlayerEntity.class);
    private final Entity vehicle = mock(Entity.class);
    private final GeyserLogger logger = mock(GeyserLogger.class);
    private final WorldBorder border = mock(WorldBorder.class);
    private ClientPredictedMountComponent component;

    @BeforeEach
    void setup() {
        var geyser = mock(GeyserImpl.class);
        when(session.getGeyser()).thenReturn(geyser);
        when(geyser.getLogger()).thenReturn(logger);
        when(session.getWorldBorder()).thenReturn(border);
        when(session.getPlayerEntity()).thenReturn(player);
        when(player.getDirtyMetadata()).thenReturn(new org.geysermc.geyser.entity.GeyserDirtyMetadata());
        when(vehicle.getSession()).thenReturn(session);
        when(vehicle.isValid()).thenReturn(true);
        when(vehicle.getGeyserId()).thenReturn(42L);
        when(vehicle.getCurIdentifier()).thenReturn("realmsunderoath:mount_land");
        when(vehicle.getPassengers()).thenReturn(List.of(player));
        when(player.getVehicle()).thenReturn(vehicle);
        component = new ClientPredictedMountComponent(vehicle, position -> new ClientPredictedMountComponent.Environment(false, true));
    }

    private PlayerAuthInputPacket input() {
        var packet = new PlayerAuthInputPacket();
        packet.getInputData().add(PlayerAuthInputData.IN_CLIENT_PREDICTED_IN_VEHICLE);
        packet.setPredictedVehicle(42);
        packet.setPosition(Vector3f.from(8.25, 65.5, -13.75));
        packet.setDelta(Vector3f.from(.2, .42, -.3));
        packet.setVehicleRotation(Vector2f.from(17, 72));
        packet.setTick(120);
        packet.setOnGround(true);
        return packet;
    }

    @ParameterizedTest
    @ValueSource(strings = {"realmsunderoath:mount_land", "realmsunderoath:mount_water", "realmsunderoath:mount_sky"})
    void forwardsActualVehiclePositionWithoutPlayerOrBoatOffset(String identifier) {
        assertTrue(ClientPredictedMountComponent.supports(identifier));
        when(vehicle.getCurIdentifier()).thenReturn(identifier);
        var packet = input();
        component.handleInput(packet);
        var sent = ArgumentCaptor.forClass(ServerboundMoveVehiclePacket.class);
        verify(session).sendDownstreamGamePacket(sent.capture());
        assertEquals(packet.getPosition().toDouble(), sent.getValue().getPosition());
        assertEquals(72f, sent.getValue().getYRot());
        assertEquals(17f, sent.getValue().getXRot());
        assertTrue(sent.getValue().isOnGround());
        verify(vehicle).setPosition(packet.getPosition());
        verify(vehicle).setMotion(packet.getDelta());
        verify(session, never()).sendUpstreamPacket(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"minecraft:horse", "mount_land", "other:mount_land", "realmsunderoath:mount_land_extra", "realmsunderoath:pet"})
    void neverEnablesOtherEntities(String identifier) {
        assertFalse(ClientPredictedMountComponent.supports(identifier));
        assertFalse(ClientPredictedMountComponent.supports(null));
    }

    @Test
    void rejectsMissingPredictionFlagAndMismatchingActorId() {
        var packet = input();
        packet.getInputData().clear();
        component.handleInput(packet);
        packet.getInputData().add(PlayerAuthInputData.IN_CLIENT_PREDICTED_IN_VEHICLE);
        packet.setPredictedVehicle(43);
        component.handleInput(packet);
        packet.setPredictedVehicle(42);
        packet.setVehicleRotation(null);
        component.handleInput(packet);
        verify(session, never()).sendDownstreamGamePacket(any());
        verify(vehicle, never()).setPosition(any());
    }

    @Test
    void requiresValidVehicleAndBoundFrontPassenger() {
        var packet = input();
        when(vehicle.isValid()).thenReturn(false);
        assertFalse(component.accepts(packet));
        when(vehicle.isValid()).thenReturn(true);
        when(vehicle.getPassengers()).thenReturn(List.of(mock(Entity.class), player));
        assertFalse(component.accepts(packet));
        when(vehicle.getPassengers()).thenReturn(List.of(player));
        when(player.getVehicle()).thenReturn(mock(Entity.class));
        assertFalse(component.accepts(packet));
    }

    @Test
    void rejectsNonFinitePositionRotationAndVelocity() {
        var packet = input();
        for (float invalid : new float[] {Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
            packet.setPosition(Vector3f.from(invalid, 65, 0));
            component.handleInput(packet);
            packet.setPosition(input().getPosition());
            packet.setVehicleRotation(Vector2f.from(0, invalid));
            component.handleInput(packet);
            packet.setVehicleRotation(input().getVehicleRotation());
            packet.setDelta(Vector3f.from(0, invalid, 0));
            component.handleInput(packet);
            packet.setDelta(input().getDelta());
        }
        verify(session, never()).sendDownstreamGamePacket(any());
    }

    @Test
    void logsAtMostOncePerMountAndDoesNotReplayDuplicateInputTicks() {
        var packet = input();
        for (int i = 0; i < 5; i++) component.handleInput(packet);
        verify(session).sendDownstreamGamePacket(any());
        packet.setTick(121);
        component.handleInput(packet);
        verify(logger).info(contains("已接收真实客户端坐骑预测"));
        component.resetPrediction();
        component.handleInput(packet);
        verify(logger, times(2)).info(contains("已接收真实客户端坐骑预测"));
    }

    @Test
    void warnsOnceAfterMissingPredictionAndResetsWhenRemounted() {
        var packet = input();
        packet.getInputData().clear();
        for (int i = 0; i < 250; i++) component.handleInput(packet);
        verify(logger).warning(contains("尚未发送匹配的坐骑预测位置"));
        component.resetPrediction();
        for (int i = 0; i < 100; i++) component.handleInput(packet);
        verify(logger, times(2)).warning(anyString());
        verify(session, never()).sendDownstreamGamePacket(any());
    }

    @Test
    void rejectsWorldBorderCrossingWithExplicitVehicleCorrection() {
        var packet = input();
        var previous = Vector3f.from(1, 64, 2);
        when(vehicle.getPosition()).thenReturn(previous);
        when(border.isPassingIntoBorderBoundaries(packet.getPosition())).thenReturn(true);
        component.handleInput(packet);
        verify(vehicle).moveAbsolute(previous, 0, 0, 0, false, true);
        verify(session, never()).sendDownstreamGamePacket(any());
    }

    @Test
    void groundRiderReceivesNativeJumpAndAllControlFlagsWithoutMovingTheVehicle() {
        for (String kind : List.of("land", "water")) {
            when(vehicle.getCurIdentifier()).thenReturn("realmsunderoath:mount_" + kind);
            component.applyMetadata();
            component.refreshJumpLock();
            component.sendControlAttributes();
            verify(vehicle).setFlag(EntityFlag.TAMED, true);
            verify(vehicle).setFlag(EntityFlag.SADDLED, true);
            verify(vehicle).setFlag(EntityFlag.CAN_WALK, true);
            verify(vehicle).setFlag(EntityFlag.CAN_POWER_JUMP, true);
            verify(vehicle).setFlag(EntityFlag.CAN_USE_VERTICAL_MOVEMENT_ACTION, false);
            var sent = ArgumentCaptor.forClass(org.cloudburstmc.protocol.bedrock.packet.UpdateAttributesPacket.class);
            verify(session).sendUpstreamPacket(sent.capture());
            assertEquals(42, sent.getValue().getRuntimeEntityId());
            var jump = sent.getValue().getAttributes().stream()
                .filter(attribute -> attribute.getName().equals("minecraft:horse.jump_strength")).findFirst().orElseThrow();
            assertEquals(.42f, jump.getValue());
            verify(session, never()).sendDownstreamGamePacket(any());
            clearInvocations(vehicle, session);
        }
    }

    @Test
    void skyEnablesNativeVerticalControlBeforeTakeoffWithoutHorseCharge() {
        when(vehicle.getCurIdentifier()).thenReturn("realmsunderoath:mount_sky");
        component.updateMovementMode(new ClientPredictedMountComponent.Environment(false, true));
        component.applyMetadata();
        verify(vehicle).setFlag(EntityFlag.WASD_AIR_CONTROLLED, true);
        verify(vehicle).setFlag(EntityFlag.WASD_CONTROLLED, false);
        verify(vehicle).setFlag(EntityFlag.HAS_GRAVITY, false);
        verify(vehicle).setFlag(EntityFlag.CAN_POWER_JUMP, false);
        verify(vehicle).setFlag(EntityFlag.CAN_USE_VERTICAL_MOVEMENT_ACTION, true);
        verify(vehicle).setFlag(EntityFlag.BODY_ROTATION_ALWAYS_FOLLOWS_HEAD, true);
        verify(vehicle).setFlag(EntityFlag.COLLIDABLE, true);
        component.sendControlAttributes();
        var sent = ArgumentCaptor.forClass(org.cloudburstmc.protocol.bedrock.packet.UpdateAttributesPacket.class);
        verify(session).sendUpstreamPacket(sent.capture());
        assertFalse(sent.getValue().getAttributes().stream()
            .anyMatch(attribute -> attribute.getName().equals("minecraft:horse.jump_strength")));
        verify(session, never()).sendDownstreamGamePacket(any());
    }

    @Test
    void skyChangesSpeedAtTakeoffAndLandingWhileKeepingAirControl() {
        when(vehicle.getCurIdentifier()).thenReturn("realmsunderoath:mount_sky");
        var ground = new ClientPredictedMountComponent.Environment(false, true);
        var air = new ClientPredictedMountComponent.Environment(false, false);
        component.updateMovementMode(ground);
        assertEquals(ClientPredictedMountComponent.MovementMode.GROUND, component.movementMode());
        assertEquals(.025254f, component.movementAttribute().getValue());
        assertFalse(component.updateMovementMode(ground));
        assertEquals(ClientPredictedMountComponent.MovementMode.GROUND, component.movementMode());
        component.updateMovementMode(air);
        assertEquals(ClientPredictedMountComponent.MovementMode.AIRBORNE, component.movementMode());
        assertEquals(.0404064f, component.movementAttribute().getValue());
        component.updateMovementMode(air);
        assertEquals(ClientPredictedMountComponent.MovementMode.AIRBORNE, component.movementMode());
        component.updateMovementMode(ground);
        assertEquals(ClientPredictedMountComponent.MovementMode.GROUND, component.movementMode());
        assertEquals(.025254f, component.movementAttribute().getValue());
        component.applyMetadata();
        verify(vehicle).setFlag(EntityFlag.WASD_AIR_CONTROLLED, true);
        component.updateMovementMode(air);
        component.applyMetadata();
        verify(vehicle, times(2)).setFlag(EntityFlag.WASD_AIR_CONTROLLED, true);
        verify(vehicle, times(2)).setFlag(EntityFlag.CAN_POWER_JUMP, false);
        assertEquals(ClientPredictedMountComponent.MovementMode.AIRBORNE, component.movementMode());
    }

    @ParameterizedTest
    @ValueSource(strings = {"realmsunderoath:mount_water", "realmsunderoath:mount_land"})
    void environmentTransitionsNeverDisableHorseRiderInput(String identifier) {
        when(vehicle.getCurIdentifier()).thenReturn(identifier);
        for (var environment : List.of(
            new ClientPredictedMountComponent.Environment(false, true),
            new ClientPredictedMountComponent.Environment(true, false),
            new ClientPredictedMountComponent.Environment(false, false),
            new ClientPredictedMountComponent.Environment(false, true))) {
            component.updateMovementMode(environment);
            component.applyMetadata();
        }
        verify(vehicle, times(4)).setFlag(EntityFlag.WASD_CONTROLLED, true);
        verify(vehicle, never()).setFlag(EntityFlag.WASD_CONTROLLED, false);
        verify(session, never()).sendDownstreamGamePacket(any());
    }

    @Test
    void diagnosticsDistinguishStationaryPredictionFromMissingPredictionAndRemainBounded() {
        when(vehicle.getPosition()).thenReturn(input().getPosition());
        var packet = input();
        packet.setMotion(Vector2f.from(0, 1));
        packet.getInputData().add(PlayerAuthInputData.JUMP_DOWN);
        for (int i = 0; i < 100; i++) {
            packet.setTick(120 + i);
            component.handleInput(packet);
        }
        verify(logger).info(argThat((String message) -> message.contains("客户端位移采样")
            && message.contains("matched=100/100") && message.contains("moveInput=100")
            && message.contains("jumpInput=100") && message.contains("positionDistance=0.000")));
        packet.getInputData().clear();
        for (int i = 100; i < 200; i++) {
            packet.setTick(120 + i);
            component.handleInput(packet);
        }
        verify(logger).info(argThat((String message) -> message.contains("客户端位移采样")
            && message.contains("matched=0/100") && message.contains("positionDistance=0.000")));
        verify(session, times(100)).sendDownstreamGamePacket(any());
        for (int i = 200; i < 2000; i++) {
            packet.setTick(120 + i);
            component.handleInput(packet);
        }
        verify(logger, times(12)).info(contains("客户端位移采样"));
        component.resetPrediction();
        for (int i = 0; i < 100; i++) {
            packet.setTick(120 + i);
            component.handleInput(packet);
        }
        verify(logger, times(13)).info(contains("客户端位移采样"));
    }

    @Test
    void diagnosticsMeasureReceivedPositionsWithoutCreatingMovement() {
        var packet = input();
        for (int i = 0; i < 100; i++) {
            packet.setTick(120 + i);
            packet.setPosition(Vector3f.from(0, 64 + i * 0.1, 0));
            component.handleInput(packet);
        }
        verify(logger).info(argThat((String message) -> message.contains("客户端位移采样")
            && message.contains("matched=100/100") && message.contains("moveInput=0")
            && message.contains("positionDistance=9.900")));
        var forwarded = ArgumentCaptor.forClass(ServerboundMoveVehiclePacket.class);
        verify(session, times(100)).sendDownstreamGamePacket(forwarded.capture());
        assertEquals(packet.getPosition().toDouble(), forwarded.getValue().getPosition());
        verify(session, never()).sendUpstreamPacket(any());
    }

    @Test
    void waterTransitionsDisableChargeAndRetainGravityWithoutChangingPredictionCoordinates() {
        when(vehicle.getCurIdentifier()).thenReturn("realmsunderoath:mount_water");
        var water = new ClientPredictedMountComponent.Environment(true, false);
        var ground = new ClientPredictedMountComponent.Environment(false, true);
        component.updateMovementMode(water);
        assertEquals(.156f, component.movementAttribute().getValue());
        assertEquals(.101016f, component.underwaterMovementAttribute().getValue());
        component.applyMetadata();
        verify(vehicle).setFlag(EntityFlag.WASD_CONTROLLED, true);
        verify(vehicle).setFlag(EntityFlag.WASD_AIR_CONTROLLED, false);
        verify(vehicle).setFlag(EntityFlag.CAN_FLY, false);
        verify(vehicle).setFlag(EntityFlag.HAS_GRAVITY, true);
        verify(vehicle).setFlag(EntityFlag.CAN_POWER_JUMP, false);
        verify(vehicle).setFlag(EntityFlag.CAN_USE_VERTICAL_MOVEMENT_ACTION, true);
        clearInvocations(vehicle);
        component.updateMovementMode(ground);
        component.applyMetadata();
        verify(vehicle).setFlag(EntityFlag.CAN_POWER_JUMP, true);
        verify(vehicle).setFlag(EntityFlag.CAN_USE_VERTICAL_MOVEMENT_ACTION, false);
        verify(vehicle).setFlag(EntityFlag.HAS_GRAVITY, true);
        verify(session, never()).sendDownstreamGamePacket(any());
    }

    @Test
    void holdingJumpOnGroundDoesNotSwitchSpeedOrGravityWithoutAnActualTakeoff() {
        when(vehicle.getCurIdentifier()).thenReturn("realmsunderoath:mount_sky");
        var packet = input();
        packet.getInputData().add(PlayerAuthInputData.JUMP_DOWN);
        for (int i = 0; i < 100; i++) {
            packet.setTick(120 + i);
            component.handleInput(packet);
        }
        assertEquals(ClientPredictedMountComponent.MovementMode.GROUND, component.movementMode());
        assertEquals(.025254f, component.movementAttribute().getValue());
        verify(session, never()).sendUpstreamPacket(any());
        verify(vehicle, never()).updateBedrockMetadata();
        verify(logger).info(argThat((String message) -> message.contains("客户端位移采样")
            && message.contains("jumpInput=100") && message.contains("mode=GROUND")
            && message.contains("positionDistance=0.000")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"land", "water", "sky"})
    void riderReceivesSeparateGroundWaterAndJumpAttributesAcrossEnvironmentChanges(String kind) {
        when(vehicle.getCurIdentifier()).thenReturn("realmsunderoath:mount_" + kind);
        for (var environment : List.of(
            new ClientPredictedMountComponent.Environment(false, true),
            new ClientPredictedMountComponent.Environment(true, false),
            new ClientPredictedMountComponent.Environment(false, true))) {
            component.updateMovementMode(environment);
            component.sendControlAttributes();
        }
        var sent = ArgumentCaptor.forClass(org.cloudburstmc.protocol.bedrock.packet.UpdateAttributesPacket.class);
        verify(session, times(3)).sendUpstreamPacket(sent.capture());
        for (int i = 0; i < sent.getAllValues().size(); i++) {
            var attributes = sent.getAllValues().get(i).getAttributes().stream().collect(java.util.stream.Collectors.toMap(
                org.cloudburstmc.protocol.bedrock.data.AttributeData::getName,
                org.cloudburstmc.protocol.bedrock.data.AttributeData::getValue));
            assertEquals(kind.equals("land") ? .208f : kind.equals("water") ? .156f : .025254f, attributes.get("minecraft:movement"));
            assertEquals(kind.equals("water") ? .101016f : .042f, attributes.get("minecraft:underwater_movement"));
            if (kind.equals("sky")) assertFalse(attributes.containsKey("minecraft:horse.jump_strength"));
            else assertEquals(kind.equals("water") && i == 1 ? 0f : .42f, attributes.get("minecraft:horse.jump_strength"));
        }
        verify(session, never()).sendDownstreamGamePacket(any());
    }

    @Test
    void clientPredictionAttributeReportsAreBoundedAndCannotChangeAuthoritativeValues() {
        when(vehicle.getClientPredictedMount()).thenReturn(component);
        var translator = new org.geysermc.geyser.translator.protocol.bedrock.entity.player.input.BedrockMountPredictionSyncTranslator();
        var packet = new org.cloudburstmc.protocol.bedrock.packet.MovementPredictionSyncPacket();
        packet.setRuntimeEntityId(43);
        translator.translate(session, packet);
        verifyNoInteractions(logger);
        packet.setRuntimeEntityId(42);
        packet.setSpeed(999);
        packet.setUnderwaterSpeed(999);
        packet.setFlying(true);
        for (int i = 0; i < 20; i++) translator.translate(session, packet);
        verify(logger, times(8)).info(contains("客户端预测属性报告"));
        assertEquals(.208f, component.movementAttribute().getValue());
        assertEquals(.042f, component.underwaterMovementAttribute().getValue());
        assertEquals(ClientPredictedMountComponent.MovementMode.GROUND, component.movementMode());
        verify(session, never()).sendDownstreamGamePacket(any());
        verify(session, never()).sendUpstreamPacket(any());
        component.resetPrediction();
        translator.translate(session, packet);
        verify(logger, times(9)).info(contains("客户端预测属性报告"));
        when(vehicle.getPassengers()).thenReturn(List.of(mock(Entity.class), player));
        translator.translate(session, packet);
        when(player.getVehicle()).thenReturn(null);
        translator.translate(session, packet);
        verifyNoMoreInteractions(logger);
    }

}
