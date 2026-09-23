package org.geysermc.geyser.entity.vehicle;

import java.util.EnumMap;
import java.util.Locale;
import java.util.List;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityDataTypes;
import java.util.Set;
import java.util.function.Function;
import org.cloudburstmc.math.vector.Vector2f;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.math.vector.Vector3d;
import org.cloudburstmc.protocol.bedrock.data.AttributeData;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityFlag;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.geysermc.geyser.entity.type.LivingEntity;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityProperties;
import org.geysermc.geyser.entity.type.living.animal.HappyGhastEntity;
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket;
import org.cloudburstmc.protocol.bedrock.packet.MovementPredictionSyncPacket;
import org.cloudburstmc.protocol.bedrock.packet.UpdateAttributesPacket;
import org.geysermc.geyser.entity.attribute.GeyserAttributeType;
import org.geysermc.geyser.level.block.BlockStateValues;
import org.geysermc.geyser.level.physics.BoundingBox;
import org.geysermc.geyser.entity.type.Entity;
import org.geysermc.geyser.input.InputLocksFlag;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundMoveVehiclePacket;

/** 接收三种诸界盟约坐骑的真实客户端预测位置；只桥接和诊断，不在 Geyser 模拟移动。 */
public final class ClientPredictedMountComponent {
    private static final Set<String> IDENTIFIERS = Set.of(
        "realmsunderoath:mount_land", "realmsunderoath:mount_water", "realmsunderoath:mount_sky");
    private static final EntityFlag[] CONTROL_FLAGS = {
        EntityFlag.WASD_CONTROLLED, EntityFlag.WASD_AIR_CONTROLLED, EntityFlag.CAN_FLY,
        EntityFlag.CAN_POWER_JUMP, EntityFlag.HAS_GRAVITY, EntityFlag.DOES_SERVER_AUTH_ONLY_DISMOUNT, EntityFlag.CAN_SWIM,
        EntityFlag.TAMED, EntityFlag.SADDLED, EntityFlag.CAN_WALK, EntityFlag.CAN_USE_VERTICAL_MOVEMENT_ACTION,
        EntityFlag.BODY_ROTATION_ALWAYS_FOLLOWS_HEAD, EntityFlag.COLLIDABLE
    };
    // 与客户端行为包生成器保持一致的引擎参数；不是 blocks/s，精确速度需要实机校准。
    private static final float WATER_GROUND_SPEED = 0.156f;
    private static final float SKY_GROUND_SPEED = 0.025254f;
    private static final float LAND_SPEED = 0.208f;
    private static final float SWIM_SPEED = 0.042f;
    private static final float WATER_SPEED = 0.101016f;
    private static final float SKY_SPEED = 0.0404064f;
    private static final String WATER_BUOYANCY = "{\"apply_gravity\":true,\"base_buoyancy\":1.0,"
        + "\"big_wave_probability\":0.0,\"big_wave_speed\":10.0,\"drag_down_on_buoyancy_removed\":0.0,"
        + "\"liquid_blocks\":[\"minecraft:water\",\"minecraft:flowing_water\"],\"simulate_waves\":false}";
    private final Entity vehicle;
    private final boolean waterMount;
    private final Function<Vector3f, Environment> environmentProbe;
    private MovementMode movementMode = MovementMode.GROUND;

    /** 根据环境区分地面、水中和天空坐骑离地；离地不代表已具备持续飞行能力。 */
    enum MovementMode { GROUND, SWIMMING, AIRBORNE }

    /** 保存真实方块水位及脚下支撑探测结果，避免把按键或任意离地当作飞行许可。 */
    record Environment(boolean water, boolean grounded) { }

    private final EnumMap<EntityFlag, Boolean> originalFlags = new EnumMap<>(EntityFlag.class);
    private boolean receivedPrediction;
    private boolean warnedMissingHarness;
    private boolean reportedDismount;
    private boolean warnedMissingPrediction;
    private int inputPackets;
    private int predictionSyncReports;
    private long lastAcceptedTick = Long.MIN_VALUE;
    private long diagnosticTick = Long.MIN_VALUE;
    private MovementMode diagnosticMode;
    private int diagnosticReports;
    private int diagnosticPackets;
    private int diagnosticPredictions;
    private int diagnosticMoveInputs;
    private int diagnosticJumpInputs;
    private Vector3f diagnosticFirstPosition;
    private Vector3f diagnosticLastPosition;
    private double diagnosticDistance;

    public ClientPredictedMountComponent(Entity vehicle) {
        this(vehicle, position -> sampleEnvironment(vehicle, position));
    }

    ClientPredictedMountComponent(Entity vehicle, Function<Vector3f, Environment> environmentProbe) {
        this.vehicle = vehicle;
        this.waterMount = "realmsunderoath:mount_water".equals(vehicle.getCurIdentifier());
        this.environmentProbe = environmentProbe;
        for (EntityFlag flag : CONTROL_FLAGS) {
            originalFlags.put(flag, vehicle.getFlag(flag));
        }
    }

    public static boolean supports(String identifier) {
        return identifier != null && IDENTIFIERS.contains(identifier);
    }

    public boolean isControllingPlayer() {
        GeyserSession session = vehicle.getSession();
        return vehicle.isValid() && session.getPlayerEntity().getVehicle() == vehicle
            && !vehicle.getPassengers().isEmpty() && vehicle.getPassengers().get(0) == session.getPlayerEntity();
    }

    public void resetPrediction() {
        receivedPrediction = false;
        reportedDismount = false;
        warnedMissingPrediction = false;
        inputPackets = 0;
        predictionSyncReports = 0;
        lastAcceptedTick = Long.MIN_VALUE;
        diagnosticTick = Long.MIN_VALUE;
        diagnosticMode = null;
        diagnosticReports = 0;
        clearDiagnosticWindow();
        if (movementMode != MovementMode.GROUND) {
            movementMode = MovementMode.GROUND;
            vehicle.updateBedrockMetadata();
            sendControlAttributes();
        }
    }

    public void applyMetadata() {
        boolean sky = isSky();
        boolean swimming = isWaterSwimming();
        if (sky) {
            vehicle.setFlag(EntityFlag.BODY_ROTATION_ALWAYS_FOLLOWS_HEAD, true);
            vehicle.setFlag(EntityFlag.COLLIDABLE, true);
        }
        vehicle.setFlag(EntityFlag.WASD_CONTROLLED, !sky);
        vehicle.setFlag(EntityFlag.WASD_AIR_CONTROLLED, sky);
        vehicle.setFlag(EntityFlag.CAN_FLY, sky);
        vehicle.setFlag(EntityFlag.CAN_SWIM, true);
        vehicle.setFlag(EntityFlag.CAN_WALK, true);
        vehicle.setFlag(EntityFlag.TAMED, true);
        vehicle.setFlag(EntityFlag.SADDLED, true);
        vehicle.setFlag(EntityFlag.HAS_GRAVITY, !sky);
        // 水中使用持续垂直动作；关闭蓄力时必须同时提供跳跃键的新动作。
        vehicle.setFlag(EntityFlag.CAN_POWER_JUMP, !sky && !swimming);
        vehicle.setFlag(EntityFlag.CAN_USE_VERTICAL_MOVEMENT_ACTION, sky || swimming);
        vehicle.setFlag(EntityFlag.DOES_SERVER_AUTH_ONLY_DISMOUNT, true);
        if (waterMount) {
            // 与原版船相同，浮力需要同时发送开关和参数，只有行为包定义还不够。
            vehicle.getDirtyMetadata().put(EntityDataTypes.IS_BUOYANT, true);
            vehicle.getDirtyMetadata().put(EntityDataTypes.BUOYANCY_DATA, WATER_BUOYANCY);
        }
    }

    private boolean isWaterSwimming() {
        return "realmsunderoath:mount_water".equals(vehicle.getCurIdentifier())
            && movementMode == MovementMode.SWIMMING;
    }

    private boolean isSky() {
        return "realmsunderoath:mount_sky".equals(vehicle.getCurIdentifier());
    }

    /** 乐魂属性表只有 can_move；不能把 Java 狼的 sound_variant 按相同索引发给它。 */
    public void applyProperties(EntityProperties properties) {
        if (!isSky()) return;
        properties.getIntProperties().clear();
        properties.getFloatProperties().clear();
        properties.getIntProperties().add(HappyGhastEntity.CAN_MOVE_PROPERTY.createValue(0, true));
    }

    /** 给乐魂外观提供原生挽具，只改变客户端装备，不向 Java 背包生成物品。 */
    public ItemData bodyEquipment(ItemData original) {
        if (!isSky()) return original;
        var mappings = vehicle.getSession().getItemMappings();
        var harness = mappings == null ? null : mappings.getMapping("minecraft:white_harness");
        if (harness == null || harness.getBedrockDefinition() == null
            || !"minecraft:white_harness".equals(harness.getBedrockDefinition().getIdentifier())) {
            if (!warnedMissingHarness) {
                warnedMissingHarness = true;
                vehicle.getSession().getGeyser().getLogger().warning(
                    "[RealmsMount] 天空坐骑缺少当前协议的 white_harness 映射，未发送替代物品。");
            }
            return original;
        }
        return ItemData.builder().definition(harness.getBedrockDefinition())
            .damage(harness.getBedrockData()).count(1).build();
    }

    public void sendControlEquipment() {
        if (isSky() && vehicle instanceof LivingEntity living) living.updateArmor();
    }

    /** 记录客户端主动离乘请求与最近输入，不吞掉潜行或触屏下马操作。 */
    public void reportDismount() {
        if (reportedDismount) return;
        reportedDismount = true;
        GeyserSession session = vehicle.getSession();
        session.getGeyser().getLogger().info("[RealmsMount] 客户端请求下马："
            + vehicle.getCurIdentifier() + " actor=" + vehicle.getGeyserId()
            + " mode=" + movementMode + " lastJump=" + session.getInputCache().wasJumping()
            + " lastSneak=" + session.isSneaking()
            + " vertical=" + vehicle.getFlag(EntityFlag.CAN_USE_VERTICAL_MOVEMENT_ACTION)
            + " powerJump=" + vehicle.getFlag(EntityFlag.CAN_POWER_JUMP));
    }

    public void restoreMetadata() {
        originalFlags.forEach(vehicle::setFlag);
        if (waterMount) {
            vehicle.getDirtyMetadata().put(EntityDataTypes.IS_BUOYANT, false);
        }
    }

    public void refreshJumpLock() {
        GeyserSession session = vehicle.getSession();
        if (session.getPlayerEntity().getVehicle() == vehicle) {
            session.setLockInput(InputLocksFlag.JUMP, false);
            session.updateInputLocks();
            session.getPlayerEntity().getDirtyMetadata().put(EntityDataTypes.CONTROLLING_RIDER_SEAT_INDEX, (byte) 0);

        }
    }

    public boolean accepts(PlayerAuthInputPacket packet) {
        return isControllingPlayer()
            && packet.getInputData().contains(PlayerAuthInputData.IN_CLIENT_PREDICTED_IN_VEHICLE)
            // 协议字段是 ActorUniqueID；Geyser 生成时将 unique/runtime ID 都设为 geyserId。
            && packet.getPredictedVehicle() == vehicle.getGeyserId()
            && finite(packet.getPosition()) && finite(packet.getDelta()) && finite(packet.getVehicleRotation());
    }

    public void handleInput(PlayerAuthInputPacket packet) {
        if (!isControllingPlayer()) {
            return;
        }
        boolean validPrediction = accepts(packet);
        Vector3f samplePosition = validPrediction ? packet.getPosition() : vehicle.getPosition();
        Environment environment = finite(samplePosition) ? environmentProbe.apply(samplePosition) : new Environment(false, false);
        boolean jumpDown = packet.getInputData().contains(PlayerAuthInputData.JUMP_CURRENT_RAW)
            || packet.getInputData().contains(PlayerAuthInputData.JUMP_DOWN);
        if (updateMovementMode(environment)) {
            vehicle.updateBedrockMetadata();
            sendControlAttributes();
        }
        recordDiagnostics(packet, validPrediction, jumpDown);
        if (!validPrediction) {
            if (!receivedPrediction && !warnedMissingPrediction && ++inputPackets >= 100) {
                warnedMissingPrediction = true;
                vehicle.getSession().getGeyser().getLogger().warning("[RealmsMount] 客户端尚未发送匹配的坐骑预测位置："
                    + vehicle.getCurIdentifier() + " actor=" + vehicle.getGeyserId()
                    + " predicted=" + packet.getPredictedVehicle()
                    + " vehicleFlag=" + packet.getInputData().contains(PlayerAuthInputData.IN_CLIENT_PREDICTED_IN_VEHICLE)
                    + " inputs=" + packet.getInputData() + " motion=" + packet.getMotion()
                    + "；不会将玩家位置当作坐骑位置转发。");
            }
            return;
        }
        if (packet.getTick() <= lastAcceptedTick) {
            return;
        }
        lastAcceptedTick = packet.getTick();
        GeyserSession session = vehicle.getSession();
        Vector3f position = packet.getPosition();
        Vector2f rotation = packet.getVehicleRotation();
        if (session.getWorldBorder().isPassingIntoBorderBoundaries(position)) {
            vehicle.moveAbsolute(vehicle.getPosition(), vehicle.getYaw(), vehicle.getPitch(), vehicle.getHeadYaw(),
                vehicle.isOnGround(), true);
            return;
        }
        vehicle.setPosition(position);
        vehicle.setYaw(rotation.getY());
        vehicle.setHeadYaw(rotation.getY());
        vehicle.setPitch(rotation.getX());
        vehicle.setMotion(packet.getDelta());
        boolean onGround = packet.isOnGround() || environment.grounded();
        vehicle.setOnGround(onGround);
        session.sendDownstreamGamePacket(new ServerboundMoveVehiclePacket(position.toDouble(), rotation.getY(),
            rotation.getX(), onGround));
        if (!receivedPrediction) {
            receivedPrediction = true;
            session.getGeyser().getLogger().info("[RealmsMount] 已接收真实客户端坐骑预测："
                + vehicle.getCurIdentifier() + " actor=" + vehicle.getGeyserId()
                + " predicted=" + packet.getPredictedVehicle() + " tick=" + packet.getTick()
                + " position=" + position + " rotation=" + rotation + " mode=" + movementMode
                + " movement=" + movementAttribute().getValue());
        }
    }

    private void recordDiagnostics(PlayerAuthInputPacket packet, boolean validPrediction, boolean jumpDown) {
        if (diagnosticReports >= 12 || packet.getTick() <= diagnosticTick) return;
        diagnosticTick = packet.getTick();
        if (diagnosticMode != movementMode) {
            reportDiagnosticWindow("模式切换");
            diagnosticMode = movementMode;
        }
        diagnosticPackets++;
        if (finite(packet.getMotion()) && packet.getMotion().lengthSquared() > 0.0001f) diagnosticMoveInputs++;
        if (jumpDown) diagnosticJumpInputs++;
        if (validPrediction) {
            diagnosticPredictions++;
            Vector3f position = packet.getPosition();
            if (diagnosticFirstPosition == null) diagnosticFirstPosition = position;
            if (diagnosticLastPosition != null) diagnosticDistance += position.toDouble().distance(diagnosticLastPosition.toDouble());
            diagnosticLastPosition = position;
        } else {
            // 缺失期间不能把玩家坐标或两段不连续的预测轨迹算作坐骑移动。
            diagnosticLastPosition = null;
        }
        if (diagnosticPackets >= 100) reportDiagnosticWindow("采样");
    }

    private void reportDiagnosticWindow(String reason) {
        if (diagnosticPackets == 0) return;
        if (diagnosticReports < 12) {
            diagnosticReports++;
            String displacement = diagnosticFirstPosition != null && diagnosticLastPosition != null
                ? diagnosticLastPosition.sub(diagnosticFirstPosition).toString() : "无连续终点";
            vehicle.getSession().getGeyser().getLogger().info("[RealmsMount] 客户端位移采样："
                + vehicle.getCurIdentifier() + " actor=" + vehicle.getGeyserId()
                + " mode=" + diagnosticMode + " reason=" + reason
                + " matched=" + diagnosticPredictions + "/" + diagnosticPackets
                + " moveInput=" + diagnosticMoveInputs + " jumpInput=" + diagnosticJumpInputs
                + " positionDistance=" + String.format(Locale.ROOT, "%.3f", diagnosticDistance)
                + " displacement=" + displacement);
        }
        clearDiagnosticWindow();
    }

    private void clearDiagnosticWindow() {
        diagnosticPackets = 0;
        diagnosticPredictions = 0;
        diagnosticMoveInputs = 0;
        diagnosticJumpInputs = 0;
        diagnosticFirstPosition = null;
        diagnosticLastPosition = null;
        diagnosticDistance = 0;
    }

    boolean updateMovementMode(Environment environment) {
        MovementMode previous = movementMode;
        boolean sky = "realmsunderoath:mount_sky".equals(vehicle.getCurIdentifier());
        if (environment.water()) {
            movementMode = MovementMode.SWIMMING;
        } else {
            // 只有实际离开支撑面才切换；按住空格但高度未变不能当作起飞。
            movementMode = sky && !environment.grounded() ? MovementMode.AIRBORNE : MovementMode.GROUND;
        }
        return movementMode != previous;
    }

    MovementMode movementMode() {
        return movementMode;
    }

    public AttributeData movementAttribute() {
        float speed = isSky() ? (movementMode == MovementMode.AIRBORNE ? SKY_SPEED : SKY_GROUND_SPEED)
            : "realmsunderoath:mount_water".equals(vehicle.getCurIdentifier()) ? WATER_GROUND_SPEED : LAND_SPEED;
        return GeyserAttributeType.MOVEMENT_SPEED.getAttribute(speed);
    }

    public AttributeData underwaterMovementAttribute() {
        float speed = "realmsunderoath:mount_water".equals(vehicle.getCurIdentifier()) ? WATER_SPEED : SWIM_SPEED;
        return new AttributeData("minecraft:underwater_movement", 0, 1024, speed, speed);
    }

    public void addMovementAttributes(List<AttributeData> attributes) {
        attributes.add(movementAttribute());
        attributes.add(underwaterMovementAttribute());
    }

    /** 在坐骑生成后或玩家骑上时单独同步属性，避免生成包将跳跃强度按默认范围归一化。 */
    public void sendControlAttributes() {
        if (!vehicle.isValid()) return;
        var packet = new UpdateAttributesPacket();
        packet.setRuntimeEntityId(vehicle.getGeyserId());
        packet.setTick(vehicle.getSession().getClientTicks());
        addMovementAttributes(packet.getAttributes());
        if (!isSky()) {
            float strength = isWaterSwimming() ? 0 : 0.42f;
            packet.getAttributes().add(GeyserAttributeType.HORSE_JUMP_STRENGTH.getAttribute(strength, 2));
        }
        vehicle.getSession().sendUpstreamPacket(packet);
    }

    /** 仅记录客户端报告的预测参数；不把客户端属性作为服务器的移动许可。 */
    public void recordPredictionSync(MovementPredictionSyncPacket packet) {
        if (!isControllingPlayer() || packet.getRuntimeEntityId() != vehicle.getGeyserId()
            || predictionSyncReports >= 8) return;
        predictionSyncReports++;
        vehicle.getSession().getGeyser().getLogger().info("[RealmsMount] 客户端预测属性报告："
            + vehicle.getCurIdentifier() + " actor=" + vehicle.getGeyserId()
            + " speed=" + packet.getSpeed() + " underwaterSpeed=" + packet.getUnderwaterSpeed()
            + " jumpStrength=" + packet.getJumpStrength() + " flying=" + packet.isFlying()
            + " powerJump=" + packet.getFlags().contains(EntityFlag.CAN_POWER_JUMP)
            + " gravity=" + packet.getFlags().contains(EntityFlag.HAS_GRAVITY)
            + " wasd=" + packet.getFlags().contains(EntityFlag.WASD_CONTROLLED)
            + " airControl=" + packet.getFlags().contains(EntityFlag.WASD_AIR_CONTROLLED)
            + " verticalAction=" + packet.getFlags().contains(EntityFlag.CAN_USE_VERTICAL_MOVEMENT_ACTION));
    }

    private static Environment sampleEnvironment(Entity vehicle, Vector3f position) {
        GeyserSession session = vehicle.getSession();
        double radius = Math.max(0.01, vehicle.getBoundingBoxWidth() / 2.0 - 0.001);
        double feet = position.getY() + 0.1;
        boolean water = false;
        for (int xSide : new int[] {-1, 1}) {
            for (int zSide : new int[] {-1, 1}) {
                int x = (int) Math.floor(position.getX() + xSide * radius);
                int y = (int) Math.floor(feet);
                int z = (int) Math.floor(position.getZ() + zSide * radius);
                int block = session.getChunkCache().getBlockAt(x, y, z);
                double height = BlockStateValues.getWaterHeight(block);
                water |= height >= 0 && y + height > feet;
            }
        }
        var box = new BoundingBox(position.up(vehicle.getBoundingBoxHeight() / 2).toDouble(),
            vehicle.getBoundingBoxWidth(), vehicle.getBoundingBoxHeight(), vehicle.getBoundingBoxWidth());
        // 仅探测脚下支撑，不修正或重新模拟客户端已提交的位置。
        var probe = Vector3d.from(0, -0.03, 0);
        var support = session.getCollisionManager().correctMovementForCollisions(probe, box, true, false);
        return new Environment(water, support.getY() > probe.getY() + 1.0e-7);
    }

    public static boolean finite(Vector3f value) {
        return value != null && Float.isFinite(value.getX()) && Float.isFinite(value.getY()) && Float.isFinite(value.getZ());
    }

    private static boolean finite(Vector2f value) {
        return value != null && Float.isFinite(value.getX()) && Float.isFinite(value.getY());
    }
}
