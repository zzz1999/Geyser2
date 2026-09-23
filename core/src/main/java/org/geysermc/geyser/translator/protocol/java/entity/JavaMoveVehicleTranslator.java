/*
 * Copyright (c) 2019-2022 GeyserMC. http://geysermc.org
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

package org.geysermc.geyser.translator.protocol.java.entity;

import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.protocol.bedrock.packet.SetEntityMotionPacket;
import org.geysermc.geyser.entity.type.Entity;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.geyser.translator.protocol.PacketTranslator;
import org.geysermc.geyser.translator.protocol.Translator;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundMoveVehiclePacket;

/** 转发服务器对当前载具的显式位置纠错，并清除白名单客户端坐骑的旧速度。 */
@Translator(packet = ClientboundMoveVehiclePacket.class)
public class JavaMoveVehicleTranslator extends PacketTranslator<ClientboundMoveVehiclePacket> {

    @Override
    public void translate(GeyserSession session, ClientboundMoveVehiclePacket packet) {
        Entity entity = session.getPlayerEntity().getVehicle();
        if (entity == null) return;

        if (entity.isLocallyControlledCustomMount()) {
            entity.setMotion(Vector3f.ZERO);
            var motion = new SetEntityMotionPacket();
            motion.setRuntimeEntityId(entity.getGeyserId());
            motion.setMotion(Vector3f.ZERO);
            session.sendUpstreamPacket(motion);
        }
        entity.moveAbsolute(packet.getPosition().toFloat(), packet.getYRot(), packet.getXRot(), false, true);
        // TODO send serverbound move vehicle packet
    }
}
