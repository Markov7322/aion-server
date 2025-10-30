package com.aionemu.gameserver.services.mail;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.gameserver.configs.main.InGameShopConfig;
import com.aionemu.gameserver.model.gameobjects.LetterType;
import com.aionemu.gameserver.utils.ThreadPoolManager;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

/**
 * Small HTTP endpoint that allows trusted services (e.g. the web shop) to trigger {@link SystemMailService#sendMail} without
 * touching the database directly.
 */
public final class SystemMailEndpointService {

        private static final Logger log = LoggerFactory.getLogger(SystemMailEndpointService.class);
        private static final String CONTEXT = "/api/system-mail";
        private static final SystemMailEndpointService INSTANCE = new SystemMailEndpointService();

        private HttpServer server;

        private SystemMailEndpointService() {
        }

        public static SystemMailEndpointService getInstance() {
                return INSTANCE;
        }

        public synchronized void start() {
                if (!InGameShopConfig.ENABLE_MAIL_RPC_ENDPOINT) {
                        log.debug("System mail RPC endpoint is disabled by configuration");
                        return;
                }
                if (server != null)
                        return;

                try {
                        InetSocketAddress bindAddress = new InetSocketAddress(InGameShopConfig.MAIL_RPC_HOST, InGameShopConfig.MAIL_RPC_PORT);
                        server = HttpServer.create(bindAddress, 0);
                        server.createContext(CONTEXT, new MailHandler());
                        server.setExecutor(ThreadPoolManager.getInstance());
                        server.start();
                        log.info("System mail RPC endpoint listening on {}:{}", bindAddress.getHostString(), bindAddress.getPort());
                } catch (IOException e) {
                        throw new UncheckedIOException("Failed to start system mail RPC endpoint", e);
                }
        }

        public synchronized void stop() {
                if (server != null) {
                        server.stop(0);
                        server = null;
                        log.info("System mail RPC endpoint stopped");
                }
        }

        private static final class MailHandler implements HttpHandler {

                private static final String HEADER_AUTH_TOKEN = "X-Auth-Token";

                @Override
                public void handle(HttpExchange exchange) throws IOException {
                        try (exchange) {
                                if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                                        sendJson(exchange, 405, "{\"error\":\"Only POST is supported\"}");
                                        return;
                                }

                                if (!isAuthorized(exchange.getRequestHeaders())) {
                                        log.warn("Rejected system mail RPC call from {} due to missing/invalid auth token", exchange.getRemoteAddress());
                                        sendJson(exchange, 401, "{\"error\":\"Unauthorized\"}");
                                        return;
                                }

                                String rawBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                                Map<String, String> params = parseFormUrlEncoded(rawBody);

                                MailRequest request = MailRequest.from(params);
                                if (!request.isValid()) {
                                        sendJson(exchange, 400, jsonError(request.validationError));
                                        return;
                                }

                                boolean success = SystemMailService.sendMail(request.sender, request.recipient, request.title, request.message,
                                                request.itemId, request.itemCount, request.kinah, request.letterType);

                                if (success) {
                                        log.info("System mail RPC sent mail to '{}' (itemId={}, itemCount={}, kinah={}, letterType={}) by '{}' from {}", request.recipient,
                                                        request.itemId, request.itemCount, request.kinah, request.letterType, request.sender, exchange.getRemoteAddress());
                                        sendJson(exchange, 200, "{\"success\":true}");
                                } else {
                                        log.warn("System mail RPC failed to send mail to '{}' (itemId={}, itemCount={}, kinah={}, letterType={}) by '{}' from {}", request.recipient,
                                                        request.itemId, request.itemCount, request.kinah, request.letterType, request.sender, exchange.getRemoteAddress());
                                        sendJson(exchange, 409, jsonError("Unable to enqueue mail. Check server logs for details."));
                                }
                        }
                }

                private static boolean isAuthorized(Headers headers) {
                        String expectedSecret = InGameShopConfig.MAIL_RPC_SECRET;
                        if (expectedSecret == null || expectedSecret.isEmpty())
                                return true;

                        String providedSecret = headers.getFirst(HEADER_AUTH_TOKEN);
                        if (providedSecret == null)
                                return false;

                        byte[] expectedBytes = expectedSecret.getBytes(StandardCharsets.UTF_8);
                        byte[] providedBytes = providedSecret.getBytes(StandardCharsets.UTF_8);

                        if (expectedBytes.length != providedBytes.length)
                                return false;

                        int result = 0;
                        for (int i = 0; i < expectedBytes.length; i++)
                                result |= expectedBytes[i] ^ providedBytes[i];
                        return result == 0;
                }

                private static Map<String, String> parseFormUrlEncoded(String rawBody) {
                        Map<String, String> params = new HashMap<>();
                        if (rawBody == null || rawBody.isEmpty())
                                return params;

                        for (String pair : rawBody.split("&")) {
                                if (pair.isEmpty())
                                        continue;
                                int idx = pair.indexOf('=');
                                if (idx <= 0)
                                        continue;
                                String key = urlDecode(pair.substring(0, idx));
                                String value = urlDecode(pair.substring(idx + 1));
                                params.put(key, value);
                        }
                        return params;
                }

                private static String urlDecode(String value) {
                        return URLDecoder.decode(value, StandardCharsets.UTF_8);
                }

                private static void sendJson(HttpExchange exchange, int statusCode, String payload) throws IOException {
                        byte[] responseBytes = payload.getBytes(StandardCharsets.UTF_8);
                        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                        exchange.sendResponseHeaders(statusCode, responseBytes.length);
                        exchange.getResponseBody().write(responseBytes);
                }

                private static String jsonError(String message) {
                        return "{\"error\":\"" + escapeJson(message) + "\"}";
                }

                private static String escapeJson(String message) {
                        return message.replace("\\", "\\\\").replace("\"", "\\\"");
                }
        }

        private static final class MailRequest {

                private final String sender;
                private final String recipient;
                private final String title;
                private final String message;
                private final int itemId;
                private final long itemCount;
                private final long kinah;
                private final LetterType letterType;
                private final String validationError;

                private MailRequest(String sender, String recipient, String title, String message, int itemId, long itemCount, long kinah, LetterType letterType,
                                String validationError) {
                        this.sender = sender;
                        this.recipient = recipient;
                        this.title = title;
                        this.message = message;
                        this.itemId = itemId;
                        this.itemCount = itemCount;
                        this.kinah = kinah;
                        this.letterType = letterType;
                        this.validationError = validationError;
                }

                private static MailRequest from(Map<String, String> params) {
                        String recipient = params.getOrDefault("recipient", "").trim();
                        if (recipient.isEmpty())
                                return invalid("Recipient is required");

                        String itemIdRaw = params.get("itemId");
                        if (itemIdRaw == null)
                                itemIdRaw = params.get("item_id");
                        if (itemIdRaw == null)
                                return invalid("itemId is required");

                        int itemId;
                        try {
                                itemId = Integer.parseInt(itemIdRaw);
                        } catch (NumberFormatException e) {
                                return invalid("itemId must be numeric");
                        }
                        if (itemId <= 0)
                                return invalid("itemId must be > 0");

                        String itemCountRaw = params.getOrDefault("itemCount", params.getOrDefault("item_count", "1"));
                        long itemCount;
                        try {
                                itemCount = Long.parseLong(itemCountRaw);
                        } catch (NumberFormatException e) {
                                return invalid("itemCount must be numeric");
                        }
                        if (itemCount <= 0)
                                return invalid("itemCount must be > 0");

                        String kinahRaw = params.getOrDefault("kinah", "0");
                        long kinah;
                        try {
                                kinah = Long.parseLong(kinahRaw);
                        } catch (NumberFormatException e) {
                                return invalid("kinah must be numeric");
                        }
                        if (kinah < 0)
                                return invalid("kinah must be >= 0");

                        String sender = Objects.requireNonNullElse(params.get("sender"), "InGameShop").trim();
                        String title = Objects.requireNonNullElse(params.get("title"), "In Game Shop").trim();
                        String message = Objects.requireNonNullElse(params.get("message"), "Спасибо за покупку!").trim();

                        LetterType letterType = LetterType.BLACKCLOUD;
                        String letterTypeRaw = params.get("letterType");
                        if (letterTypeRaw != null && !letterTypeRaw.isEmpty()) {
                                try {
                                        letterType = LetterType.valueOf(letterTypeRaw.toUpperCase(Locale.ROOT));
                                } catch (IllegalArgumentException e) {
                                        return invalid("Unknown letterType value: " + letterTypeRaw);
                                }
                        }

                        return new MailRequest(sender, recipient, title, message, itemId, itemCount, kinah, letterType, null);
                }

                private static MailRequest invalid(String validationError) {
                        return new MailRequest(null, null, null, null, 0, 0, 0, LetterType.BLACKCLOUD, validationError);
                }

                private boolean isValid() {
                        return validationError == null;
                }
        }
}
