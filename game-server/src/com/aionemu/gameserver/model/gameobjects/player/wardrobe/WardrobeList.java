package com.aionemu.gameserver.model.gameobjects.player.wardrobe;

import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import com.aionemu.gameserver.model.gameobjects.Persistable.PersistentState;
import com.aionemu.gameserver.model.gameobjects.player.Player;

/**
 * Holds all unlocked wardrobe entries for a player.
 */
public class WardrobeList {

        private final Map<Integer, WardrobeEntry> entries = new LinkedHashMap<>();
        private Player owner;

        public void setOwner(Player owner) {
                this.owner = owner;
        }

        public Player getOwner() {
                return owner;
        }

        public boolean contains(int skinId) {
                return entries.containsKey(skinId);
        }

        public WardrobeEntry registerSkin(int skinId) {
                return registerSkin(skinId, System.currentTimeMillis());
        }

        public WardrobeEntry registerSkin(int skinId, long unlockTime) {
                if (entries.containsKey(skinId)) {
                        return null;
                }
                WardrobeEntry entry = new WardrobeEntry(skinId, unlockTime);
                entries.put(skinId, entry);
                return entry;
        }

        public void addLoadedSkin(int skinId, long unlockTime) {
                WardrobeEntry entry = new WardrobeEntry(skinId, unlockTime, PersistentState.UPDATED);
                entries.put(skinId, entry);
        }

        public void remove(int skinId) {
                WardrobeEntry entry = entries.get(skinId);
                if (entry != null) {
                        entry.setPersistentState(PersistentState.DELETED);
                }
        }

        public WardrobeEntry getEntry(int skinId) {
                return entries.get(skinId);
        }

        public Collection<WardrobeEntry> getEntries() {
                return entries.values();
        }

        public int size() {
                return entries.size();
        }

        public Iterator<WardrobeEntry> iterator() {
                return entries.values().iterator();
        }
}
