package com.aionemu.gameserver.network.aion.clientpackets;

import java.util.Set;

import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.network.aion.AionClientPacket;
import com.aionemu.gameserver.network.aion.AionConnection.State;
import com.aionemu.gameserver.services.wardrobe.WardrobeService;

public class CM_WARDROBE extends AionClientPacket {

        private int action;
        private int firstId;
        private int secondId;

        public CM_WARDROBE(int opcode, Set<State> validStates) {
                super(opcode, validStates);
        }

        @Override
        protected void readImpl() {
                action = readC();
                switch (action) {
                        case 1:
                                firstId = readD();
                                break;
                        case 2:
                                firstId = readD();
                                secondId = readD();
                                break;
                        default:
                                break;
                }
        }

        @Override
        protected void runImpl() {
                Player player = getConnection().getActivePlayer();
                if (player == null)
                        return;

                switch (action) {
                        case 0:
                                WardrobeService.sendWardrobeInfo(player);
                                break;
                        case 1:
                                WardrobeService.unlockSkin(player, firstId);
                                break;
                        case 2:
                                WardrobeService.applyWardrobeSkin(player, firstId, secondId);
                                break;
                        default:
                                break;
                }
        }
}
