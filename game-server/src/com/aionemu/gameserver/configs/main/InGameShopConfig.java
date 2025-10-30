package com.aionemu.gameserver.configs.main;

import com.aionemu.commons.configuration.Property;

/**
 * @author xTz
 */
public class InGameShopConfig {

	/**
	 * Enable in game shop
	 */
	@Property(key = "gameserver.ingameshop.enable", defaultValue = "false")
	public static boolean ENABLE_IN_GAME_SHOP;

	/**
	 * Enable gift system between factions
	 */
	@Property(key = "gameserver.ingameshop.gift", defaultValue = "false")
	public static boolean ENABLE_GIFT_OTHER_RACE;

	@Property(key = "gameserver.ingameshop.allow.gift", defaultValue = "true")
	public static boolean ALLOW_GIFTS;

	@Property(key = "gameserver.ingameshop.mailrpc.enable", defaultValue = "false")
	public static boolean ENABLE_MAIL_RPC_ENDPOINT;

	@Property(key = "gameserver.ingameshop.mailrpc.host", defaultValue = "127.0.0.1")
	public static String MAIL_RPC_HOST;

	@Property(key = "gameserver.ingameshop.mailrpc.port", defaultValue = "9020")
	public static int MAIL_RPC_PORT;

	@Property(key = "gameserver.ingameshop.mailrpc.secret", defaultValue = "")
	public static String MAIL_RPC_SECRET;
}
