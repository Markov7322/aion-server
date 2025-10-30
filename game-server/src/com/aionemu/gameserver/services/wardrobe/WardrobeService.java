package com.aionemu.gameserver.services.wardrobe;

import java.util.Collection;
import java.util.Collections;

import com.aionemu.gameserver.dao.PlayerWardrobeDAO;
import com.aionemu.gameserver.dataholders.DataManager;
import com.aionemu.gameserver.model.Gender;
import com.aionemu.gameserver.model.gameobjects.Item;
import com.aionemu.gameserver.model.gameobjects.Persistable.PersistentState;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.gameobjects.player.wardrobe.WardrobeEntry;
import com.aionemu.gameserver.model.gameobjects.player.wardrobe.WardrobeList;
import com.aionemu.gameserver.model.items.ItemMask;
import com.aionemu.gameserver.model.items.storage.Storage;
import com.aionemu.gameserver.model.templates.item.ItemTemplate;
import com.aionemu.gameserver.model.templates.item.ItemUseLimits;
import com.aionemu.gameserver.model.templates.item.actions.ItemActions;
import com.aionemu.gameserver.model.templates.item.enums.ItemGroup;
import com.aionemu.gameserver.model.templates.item.enums.ItemSubType;
import com.aionemu.gameserver.network.aion.serverpackets.SM_SYSTEM_MESSAGE;
import com.aionemu.gameserver.network.aion.serverpackets.SM_WARDROBE_INFO;
import com.aionemu.gameserver.services.item.ItemPacketService;
import com.aionemu.gameserver.services.trade.PricesService;
import com.aionemu.gameserver.utils.PacketSendUtility;

public final class WardrobeService {

        private WardrobeService() {
        }

        public static void sendWardrobeInfo(Player player) {
                WardrobeList wardrobe = player.getWardrobe();
                if (wardrobe == null)
                        return;

                PacketSendUtility.sendPacket(player, new SM_WARDROBE_INFO(wardrobe.getEntries(), true));
        }

        public static void unlockSkin(Player player, int itemObjId) {
                Storage inventory = player.getInventory();
                Item item = inventory.getItemByObjId(itemObjId);
                if (item == null)
                        return;

                if (item.isEquipped()) {
                        PacketSendUtility.sendMessage(player, "Сначала снимите предмет, чтобы зарегистрировать облик.");
                        return;
                }

                if (!item.isRemodelable()) {
                        PacketSendUtility.sendPacket(player,
                                SM_SYSTEM_MESSAGE.STR_CHANGE_ITEM_SKIN_NOT_SKIN_CHANGABLE_ITEM(item.getItemTemplate().getL10n()));
                        return;
                }

                ItemTemplate skinTemplate = item.getItemSkinTemplate();
                if (skinTemplate == null)
                        skinTemplate = item.getItemTemplate();

                int skinId = skinTemplate.getTemplateId();
                WardrobeList wardrobe = player.getWardrobe();
                if (wardrobe == null)
                        return;

                if (wardrobe.contains(skinId)) {
                        PacketSendUtility.sendMessage(player, "Этот облик уже добавлен в ваш шкаф.");
                        return;
                }

                if (!inventory.decreaseItemCount(item, 1))
                        return;

                WardrobeEntry entry = wardrobe.registerSkin(skinId);
                if (entry == null)
                        return;

                if (PlayerWardrobeDAO.storeEntry(player, entry))
                        entry.setPersistentState(PersistentState.UPDATED);
                else
                        entry.setPersistentState(PersistentState.NEW);

                PacketSendUtility.sendPacket(player, new SM_WARDROBE_INFO(entry));
                PacketSendUtility.sendMessage(player, "Облик успешно сохранён в шкафу.");
        }

        public static void applyWardrobeSkin(Player player, int keepItemObjId, int skinId) {
                Storage inventory = player.getInventory();
                Item keepItem = inventory.getItemByObjId(keepItemObjId);
                if (keepItem == null)
                        return;

                WardrobeList wardrobe = player.getWardrobe();
                if (wardrobe == null || !wardrobe.contains(skinId)) {
                        PacketSendUtility.sendMessage(player, "Указанный облик не найден в шкафу.");
                        return;
                }

                ItemTemplate skinTemplate = DataManager.ITEM_DATA.getItemTemplate(skinId);
                if (skinTemplate == null)
                        return;

                if (player.getLevel() < 10) {
                        PacketSendUtility.sendPacket(player, SM_SYSTEM_MESSAGE.STR_CHANGE_ITEM_SKIN_PC_LEVEL_LIMIT());
                        return;
                }

                ItemUseLimits keepItemLimits = keepItem.getItemTemplate().getUseLimits();
                ItemUseLimits skinLimits = skinTemplate.getUseLimits();
                if (keepItemLimits != null && skinLimits != null) {
                        Gender keepGender = keepItemLimits.getGenderPermitted();
                        Gender skinGender = skinLimits.getGenderPermitted();
                        if (keepGender != null && skinGender != null && keepGender != skinGender) {
                                PacketSendUtility.sendPacket(player, SM_SYSTEM_MESSAGE.STR_MSG_CANT_CHANGE_SKIN_OPPOSITE_REQUIREMENT(
                                                keepItem.getItemTemplate().getL10n(), skinTemplate.getL10n()));
                                return;
                        }
                }

                long remodelCost = PricesService.getPriceForService(1000, player.getRace());
                if (!inventory.tryDecreaseKinah(remodelCost)) {
                        PacketSendUtility.sendPacket(player,
                                SM_SYSTEM_MESSAGE.STR_CHANGE_ITEM_SKIN_NOT_ENOUGH_GOLD(keepItem.getItemTemplate().getL10n()));
                        return;
                }

                if (!keepItem.isRemodelable()) {
                        PacketSendUtility.sendPacket(player,
                                SM_SYSTEM_MESSAGE.STR_CHANGE_ITEM_SKIN_NOT_SKIN_CHANGABLE_ITEM(keepItem.getItemTemplate().getL10n()));
                        return;
                }

                if ((skinTemplate.getMask() & ItemMask.REMODELABLE) != ItemMask.REMODELABLE) {
                        PacketSendUtility.sendPacket(player,
                                SM_SYSTEM_MESSAGE.STR_CHANGE_ITEM_SKIN_CAN_NOT_REMOVE_SKIN_ITEM(skinTemplate.getL10n()));
                        return;
                }

                ItemGroup keepGroup = keepItem.getItemTemplate().getItemGroup();
                ItemGroup skinGroup = skinTemplate.getItemGroup();
                if ((keepGroup != skinGroup && !(skinGroup.getItemSubType() == ItemSubType.CLOTHES
                        || skinGroup.getItemSubType() == ItemSubType.ALL_ARMOR
                                && keepGroup.getValidEquipmentSlots() == skinGroup.getValidEquipmentSlots()))
                        || keepGroup.getItemSubType() == ItemSubType.CLOTHES) {
                        PacketSendUtility.sendPacket(player, SM_SYSTEM_MESSAGE.STR_CHANGE_ITEM_SKIN_NOT_COMPATIBLE(
                                        keepItem.getItemTemplate().getL10n(), skinTemplate.getL10n()));
                        return;
                }

                ItemActions actions = skinTemplate.getActions();
                if (actions != null && actions.getRemodelAction() != null && actions.getRemodelAction().getExtractType() == 2) {
                        PacketSendUtility.sendPacket(player,
                                SM_SYSTEM_MESSAGE.STR_CHANGE_ITEM_SKIN_CAN_NOT_REMOVE_SKIN_ITEM(skinTemplate.getL10n()));
                        return;
                }

                keepItem.setItemSkinTemplate(skinTemplate);
                ItemPacketService.updateItemAfterInfoChange(player, keepItem);
                PacketSendUtility.sendPacket(player,
                        SM_SYSTEM_MESSAGE.STR_CHANGE_ITEM_SKIN_SUCCEED(keepItem.getItemTemplate().getL10n()));
        }

        public static Collection<WardrobeEntry> getEntries(Player player) {
                WardrobeList wardrobe = player.getWardrobe();
                return wardrobe == null ? Collections.emptyList() : wardrobe.getEntries();
        }
}
