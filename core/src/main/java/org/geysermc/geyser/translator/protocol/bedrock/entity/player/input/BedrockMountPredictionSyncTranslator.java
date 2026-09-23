package org.geysermc.geyser.translator.protocol.bedrock.entity.player.input;

import org.cloudburstmc.protocol.bedrock.packet.MovementPredictionSyncPacket;
import org.geysermc.geyser.entity.type.Entity;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.geyser.translator.protocol.PacketTranslator;
import org.geysermc.geyser.translator.protocol.Translator;

/** 收集当前受控坐骑的客户端预测属性报告，用于核对引擎是否接收水速和跳跃参数。 */
@Translator(packet = MovementPredictionSyncPacket.class)
public final class BedrockMountPredictionSyncTranslator extends PacketTranslator<MovementPredictionSyncPacket> {
    @Override
    public void translate(GeyserSession session, MovementPredictionSyncPacket packet) {
        Entity vehicle = session.getPlayerEntity().getVehicle();
        if (vehicle != null && vehicle.getClientPredictedMount() != null) {
            vehicle.getClientPredictedMount().recordPredictionSync(packet);
        }
    }
}
