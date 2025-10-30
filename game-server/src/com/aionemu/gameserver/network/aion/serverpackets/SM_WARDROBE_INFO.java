package com.aionemu.gameserver.network.aion.serverpackets;

import java.util.Collection;
import java.util.Collections;

import com.aionemu.gameserver.model.gameobjects.player.wardrobe.WardrobeEntry;
import com.aionemu.gameserver.network.aion.AionConnection;
import com.aionemu.gameserver.network.aion.AionServerPacket;

public class SM_WARDROBE_INFO extends AionServerPacket {

        private final Collection<WardrobeEntry> entries;
        private final boolean fullList;

        public SM_WARDROBE_INFO(Collection<WardrobeEntry> entries, boolean fullList) {
                this.entries = entries == null ? Collections.emptyList() : entries;
                this.fullList = fullList;
        }

        public SM_WARDROBE_INFO(WardrobeEntry entry) {
                this(Collections.singleton(entry), false);
        }

        @Override
        protected void writeImpl(AionConnection con) {
                writeC(fullList ? 1 : 0);
                writeH(entries.size());
                for (WardrobeEntry entry : entries) {
                        writeD(entry.getSkinId());
                        writeQ(entry.getUnlockTime());
                }
        }
}
