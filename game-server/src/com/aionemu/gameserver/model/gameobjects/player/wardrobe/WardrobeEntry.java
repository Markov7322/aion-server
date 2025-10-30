package com.aionemu.gameserver.model.gameobjects.player.wardrobe;

import com.aionemu.gameserver.model.gameobjects.Persistable;

/**
 * Represents a single unlocked appearance entry inside a player's wardrobe.
 */
public class WardrobeEntry implements Persistable {

        private final int skinId;
        private final long unlockTime;
        private PersistentState persistentState;

        public WardrobeEntry(int skinId, long unlockTime) {
                this(skinId, unlockTime, PersistentState.NEW);
        }

        public WardrobeEntry(int skinId, long unlockTime, PersistentState persistentState) {
                this.skinId = skinId;
                this.unlockTime = unlockTime;
                this.persistentState = persistentState;
        }

        public int getSkinId() {
                return skinId;
        }

        public long getUnlockTime() {
                return unlockTime;
        }

        @Override
        public PersistentState getPersistentState() {
                return persistentState;
        }

        @Override
        public void setPersistentState(PersistentState persistentState) {
                this.persistentState = persistentState;
        }
}
