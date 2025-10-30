package com.aionemu.gameserver.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Iterator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.commons.database.DB;
import com.aionemu.commons.database.DatabaseFactory;
import com.aionemu.commons.database.ParamReadStH;
import com.aionemu.gameserver.model.gameobjects.Persistable.PersistentState;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.gameobjects.player.wardrobe.WardrobeEntry;
import com.aionemu.gameserver.model.gameobjects.player.wardrobe.WardrobeList;

public class PlayerWardrobeDAO {

        private static final Logger log = LoggerFactory.getLogger(PlayerWardrobeDAO.class);

        private static final String SELECT_QUERY = "SELECT skin_id, unlock_time FROM player_wardrobe WHERE player_id = ?";
        private static final String INSERT_QUERY = "INSERT INTO player_wardrobe(player_id, skin_id, unlock_time) VALUES(?,?,?)";
        private static final String DELETE_QUERY = "DELETE FROM player_wardrobe WHERE player_id = ? AND skin_id = ?";

        public static WardrobeList load(Player player) {
                WardrobeList list = new WardrobeList();
                list.setOwner(player);

                DB.select(SELECT_QUERY, new ParamReadStH() {

                        @Override
                        public void setParams(PreparedStatement stmt) throws SQLException {
                                stmt.setInt(1, player.getObjectId());
                        }

                        @Override
                        public void handleRead(ResultSet rset) throws SQLException {
                                while (rset.next()) {
                                        int skinId = rset.getInt("skin_id");
                                        long unlockTime = rset.getLong("unlock_time");
                                        list.addLoadedSkin(skinId, unlockTime);
                                }
                        }
                });

                return list;
        }

        public static void store(Player player) {
                WardrobeList wardrobe = player.getWardrobe();
                if (wardrobe == null) {
                        return;
                }

                Iterator<WardrobeEntry> iterator = wardrobe.iterator();
                while (iterator.hasNext()) {
                        WardrobeEntry entry = iterator.next();
                        switch (entry.getPersistentState()) {
                                case NEW:
                                case UPDATE_REQUIRED:
                                        if (storeEntry(player, entry)) {
                                                entry.setPersistentState(PersistentState.UPDATED);
                                        }
                                        break;
                                case DELETED:
                                        if (deleteEntry(player, entry.getSkinId())) {
                                                iterator.remove();
                                        }
                                        break;
                                default:
                                        break;
                        }
                }
        }

        public static boolean storeEntry(Player player, WardrobeEntry entry) {
                try (Connection con = DatabaseFactory.getConnection(); PreparedStatement stmt = con.prepareStatement(INSERT_QUERY)) {
                        stmt.setInt(1, player.getObjectId());
                        stmt.setInt(2, entry.getSkinId());
                        stmt.setLong(3, entry.getUnlockTime());
                        stmt.execute();
                        return true;
                } catch (SQLException e) {
                        log.error("Could not store wardrobe entry for player {} and skin {}", player.getObjectId(), entry.getSkinId(), e);
                        return false;
                }
        }

        public static boolean deleteEntry(Player player, int skinId) {
                try (Connection con = DatabaseFactory.getConnection(); PreparedStatement stmt = con.prepareStatement(DELETE_QUERY)) {
                        stmt.setInt(1, player.getObjectId());
                        stmt.setInt(2, skinId);
                        stmt.execute();
                        return true;
                } catch (SQLException e) {
                        log.error("Could not delete wardrobe entry for player {} and skin {}", player.getObjectId(), skinId, e);
                        return false;
                }
        }
}
