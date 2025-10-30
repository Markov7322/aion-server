<?php
declare(strict_types=1);

/**
 * Minimal PHP helper that proxies a web shop purchase to the in-game {@code SystemMailService}.
 *
 * It reads the recipient name and item id/count from a simple HTML form and calls the
 * lightweight HTTP endpoint exposed by the game server. The endpoint performs all safety checks
 * (ID generation, mailbox counters, logging) and immediately delivers the mail to the player if
 * they are online.
 */

$config = [
    'endpoint' => 'http://127.0.0.1:9020/api/system-mail',
    'auth_token' => '',
    'timeout' => 5,
];

$errors = [];
$warnings = [];
$success = false;

$input = [
    'recipient' => trim($_POST['recipient'] ?? ''),
    'item_id' => isset($_POST['item_id']) ? (int) $_POST['item_id'] : 0,
    'item_count' => max(1, (int) ($_POST['item_count'] ?? 1)),
    'sender' => trim($_POST['sender'] ?? 'InGameShop'),
    'title' => trim($_POST['title'] ?? 'In Game Shop'),
    'message' => trim($_POST['message'] ?? 'Спасибо за покупку!'),
];

if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    if ($input['recipient'] === '') {
        $errors[] = 'Введите имя получателя.';
    }
    if ($input['item_id'] <= 0) {
        $errors[] = 'Введите корректный ID предмета (> 0).';
    }
    if ($input['item_count'] <= 0) {
        $errors[] = 'Количество должно быть положительным.';
    }

    // Enforce in-game constraints (same ones as SystemMailService)
    if (mb_strlen($input['recipient']) > 16) {
        $errors[] = 'Имя получателя не может быть длиннее 16 символов.';
    }
    if ($input['sender'] === '') {
        $input['sender'] = 'InGameShop';
    }
    if (mb_strlen($input['sender']) > 16 && strpos($input['sender'], '$$') !== 0) {
        $errors[] = 'Имя отправителя не может быть длиннее 16 символов (кроме системных $$).';
    }

    if ($errors === []) {
        try {
            $payload = [
                'recipient' => $input['recipient'],
                'item_id' => $input['item_id'],
                'item_count' => $input['item_count'],
                'sender' => mb_strimwidth($input['sender'], 0, 16, ''),
                'title' => mb_strimwidth($input['title'], 0, 20, ''),
                'message' => mb_strimwidth($input['message'], 0, 1000, ''),
            ];

            $response = sendMailRequest($payload, $config);
            if (($response['success'] ?? false) === true) {
                $success = true;
            } else {
                $errors[] = $response['error'] ?? 'Не удалось получить положительный ответ от игрового сервера.';
            }
        } catch (Throwable $e) {
            $errors[] = $e->getMessage();
        }
    }
}

function sendMailRequest(array $payload, array $config): array
{
    $endpoint = $config['endpoint'];
    if (!is_string($endpoint) || $endpoint === '') {
        throw new InvalidArgumentException('Не задан URL endpoint-а игрового сервера.');
    }

    $body = http_build_query($payload, '', '&', PHP_QUERY_RFC3986);

    $headers = [
        'Content-Type: application/x-www-form-urlencoded',
        'Content-Length: ' . strlen($body),
    ];

    $authToken = (string) ($config['auth_token'] ?? '');
    if ($authToken !== '') {
        $headers[] = 'X-Auth-Token: ' . $authToken;
    }

    $context = stream_context_create([
        'http' => [
            'method' => 'POST',
            'header' => implode("\r\n", $headers) . "\r\n",
            'content' => $body,
            'timeout' => (int) ($config['timeout'] ?? 5),
            'ignore_errors' => true,
        ],
    ]);

    $result = @file_get_contents($endpoint, false, $context);
    if ($result === false) {
        $error = error_get_last();
        throw new RuntimeException('Не удалось обратиться к игровому серверу: ' . ($error['message'] ?? 'неизвестная ошибка'));
    }

    $statusLine = $http_response_header[0] ?? '';
    if (preg_match('#^HTTP/\S+\s+(\d{3})#', $statusLine, $matches)) {
        $statusCode = (int) $matches[1];
    } else {
        $statusCode = 0;
    }

    $decoded = json_decode($result, true, 512, JSON_THROW_ON_ERROR);

    if ($statusCode !== 200) {
        $errorMessage = $decoded['error'] ?? ('Игровой сервер вернул код ' . $statusCode);
        throw new RuntimeException($errorMessage);
    }

    return is_array($decoded) ? $decoded : ['success' => false, 'error' => 'Некорректный ответ от игрового сервера.'];
}

?>
<!DOCTYPE html>
<html lang="ru">
<head>
    <meta charset="UTF-8">
    <title>Веб-магазин Aion — отправка подарка</title>
    <style>
        body { font-family: system-ui, sans-serif; margin: 2rem auto; max-width: 640px; line-height: 1.5; }
        form { display: grid; gap: 1rem; }
        label { display: grid; gap: 0.25rem; }
        input[type="text"], input[type="number"], textarea { padding: 0.5rem; font-size: 1rem; }
        .errors { background: #ffe2e2; border: 1px solid #ff6b6b; padding: 0.75rem; }
        .success { background: #e3ffe2; border: 1px solid #51cf66; padding: 0.75rem; }
    </style>
</head>
<body>
    <h1>Отправка подарка из веб-магазина</h1>
    <?php if ($success): ?>
        <div class="success">Подарок поставлен в очередь. Игрок получит письмо при следующей проверке почты.</div>
    <?php endif; ?>
    <?php if ($errors): ?>
        <div class="errors">
            <ul>
                <?php foreach ($errors as $error): ?>
                    <li><?= htmlspecialchars($error, ENT_QUOTES | ENT_SUBSTITUTE, 'UTF-8') ?></li>
                <?php endforeach; ?>
            </ul>
        </div>
    <?php endif; ?>
    <?php if ($warnings): ?>
        <div class="errors" style="background:#fff3bf;border-color:#fcc419;">
            <ul>
                <?php foreach ($warnings as $warning): ?>
                    <li><?= htmlspecialchars($warning, ENT_QUOTES | ENT_SUBSTITUTE, 'UTF-8') ?></li>
                <?php endforeach; ?>
            </ul>
        </div>
    <?php endif; ?>
    <form method="post">
        <label>
            Имя получателя (игровой ник)
            <input type="text" name="recipient" value="<?= htmlspecialchars($input['recipient'], ENT_QUOTES | ENT_SUBSTITUTE, 'UTF-8') ?>" maxlength="16" required>
        </label>
        <label>
            ID предмета
            <input type="number" name="item_id" value="<?= htmlspecialchars((string) $input['item_id'], ENT_QUOTES | ENT_SUBSTITUTE, 'UTF-8') ?>" min="1" required>
        </label>
        <label>
            Количество
            <input type="number" name="item_count" value="<?= htmlspecialchars((string) $input['item_count'], ENT_QUOTES | ENT_SUBSTITUTE, 'UTF-8') ?>" min="1" required>
        </label>
        <label>
            Имя отправителя
            <input type="text" name="sender" value="<?= htmlspecialchars($input['sender'], ENT_QUOTES | ENT_SUBSTITUTE, 'UTF-8') ?>" maxlength="16">
        </label>
        <label>
            Заголовок письма (до 20 символов)
            <input type="text" name="title" value="<?= htmlspecialchars($input['title'], ENT_QUOTES | ENT_SUBSTITUTE, 'UTF-8') ?>" maxlength="20">
        </label>
        <label>
            Сообщение (до 1000 символов)
            <textarea name="message" rows="4" maxlength="1000"><?= htmlspecialchars($input['message'], ENT_QUOTES | ENT_SUBSTITUTE, 'UTF-8') ?></textarea>
        </label>
        <button type="submit">Отправить подарок</button>
    </form>

    <hr>
    <section>
        <h2>Что делает скрипт</h2>
        <ol>
            <li>Собирает данные формы и отправляет POST-запрос на <code>/api/system-mail</code> игрового сервера.</li>
            <li>Сервер проверяет токен, валидирует параметры и вызывает <code>SystemMailService.sendMail(...)</code>.</li>
            <li>Письмо создаётся штатными DAO, счётчики обновляются через <code>updateRecipientMailbox(...)</code>, а активный игрок сразу получает уведомление.</li>
        </ol>
        <p>
            Убедитесь, что задали секрет и ограничили доступ к endpoint-у на уровне веб-сервера или файрвола.
        </p>
    </section>
</body>
</html>
