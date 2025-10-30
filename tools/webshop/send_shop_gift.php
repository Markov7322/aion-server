<?php
declare(strict_types=1);

/**
 * Minimal PHP helper that mimics the in-game shop gift delivery logic.
 *
 * It reads the recipient name and item id/count from a simple HTML form,
 * creates an inventory entry in the MAILBOX storage and enqueues a letter
 * that the game server will pick up on the next mailbox refresh.
 *
 * ⚠️  Important: because the Java game server keeps an in-memory ID factory,
 *     execute this script only when the game server is offline or expose
 *     a small RPC inside the server that calls SystemMailService.sendMail(...)
 *     on your behalf. Running it while the server is online can lead to
 *     duplicate IDs. See the README block at the bottom for more context.
 */

$config = [
    'dsn' => 'mysql:host=127.0.0.1;dbname=aion_gs;charset=utf8mb4',
    'user' => 'root',
    'password' => '',
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
            $pdo = new PDO($config['dsn'], $config['user'], $config['password'], [
                PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION,
                PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC,
            ]);

            $pdo->beginTransaction();

            $recipientStmt = $pdo->prepare('SELECT id, mailbox_letters FROM players WHERE name = ? LIMIT 1 FOR UPDATE');
            $recipientStmt->execute([$input['recipient']]);
            $recipient = $recipientStmt->fetch();

            if (!$recipient) {
                throw new RuntimeException('Персонаж с таким именем не найден.');
            }
            if ((int) $recipient['mailbox_letters'] >= 200) {
                throw new RuntimeException('Почтовый ящик переполнен (200 писем).');
            }

            $itemTemplateStmt = $pdo->prepare('SELECT 1 FROM ingameshop WHERE item_id = ? LIMIT 1');
            $itemTemplateStmt->execute([$input['item_id']]);
            if (!$itemTemplateStmt->fetch()) {
                // Мы не блокируем выполнение, но выдаём предупреждение.
                $warnings[] = 'В каталоге ingameshop нет записи с таким item_id. Проверьте ID вручную.';
            }

            $itemUniqueId = nextUniqueId($pdo, 'inventory', 'item_unique_id');
            $mailUniqueId = nextUniqueId($pdo, 'mail', 'mail_unique_id');

            $insertItem = $pdo->prepare(
                'INSERT INTO inventory (
                    item_unique_id, item_id, item_count, item_color, color_expires,
                    item_creator, expire_time, activation_count, item_owner, is_equipped,
                    is_soul_bound, slot, item_location, enchant, enchant_bonus, item_skin,
                    fusioned_item, optional_socket, optional_fusion_socket, charge, tune_count,
                    rnd_bonus, fusion_rnd_bonus, tempering, pack_count, is_amplified, buff_skill,
                    rnd_plume_bonus
                ) VALUES (
                    :item_unique_id, :item_id, :item_count, NULL, 0,
                    :item_creator, 0, 0, :item_owner, 0,
                    0, 0, 127, 0, 0, 0,
                    0, 0, 0, 0, 0,
                    0, 0, 0, 0, 0, 0,
                    0
                )'
            );
            $insertItem->execute([
                ':item_unique_id' => $itemUniqueId,
                ':item_id' => $input['item_id'],
                ':item_count' => $input['item_count'],
                ':item_creator' => $input['sender'],
                ':item_owner' => $recipient['id'],
            ]);

            $insertMail = $pdo->prepare(
                'INSERT INTO mail (
                    mail_unique_id, mail_recipient_id, sender_name, mail_title, mail_message,
                    unread, attached_item_id, attached_kinah_count, express, recieved_time
                ) VALUES (
                    :mail_unique_id, :mail_recipient_id, :sender_name, :mail_title, :mail_message,
                    1, :attached_item_id, 0, 2, NOW()
                )'
            );
            $insertMail->execute([
                ':mail_unique_id' => $mailUniqueId,
                ':mail_recipient_id' => $recipient['id'],
                ':sender_name' => mb_strimwidth($input['sender'], 0, 16, ''),
                ':mail_title' => mb_strimwidth($input['title'], 0, 20, ''),
                ':mail_message' => mb_strimwidth($input['message'], 0, 1000, ''),
                ':attached_item_id' => $itemUniqueId,
            ]);

            $updateMailbox = $pdo->prepare('UPDATE players SET mailbox_letters = mailbox_letters + 1 WHERE id = ?');
            $updateMailbox->execute([$recipient['id']]);

            $pdo->commit();
            $success = true;
        } catch (Throwable $e) {
            if (isset($pdo) && $pdo->inTransaction()) {
                $pdo->rollBack();
            }
            $errors[] = $e->getMessage();
        }
    }
}

function nextUniqueId(PDO $pdo, string $table, string $column): int
{
    $stmt = $pdo->query(sprintf(
        'SELECT %s FROM %s ORDER BY %s DESC LIMIT 1 FOR UPDATE',
        $column,
        $table,
        $column
    ));
    $row = $stmt->fetch(PDO::FETCH_NUM);
    if (!$row) {
        return 1;
    }
    return ((int) $row[0]) + 1;
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
            <li>Находит ID персонажа в таблице <code>players</code> и блокирует запись на время транзакции.</li>
            <li>Создаёт предмет в таблице <code>inventory</code> со складом <code>item_location = 127</code> (почтовый ящик).</li>
            <li>Добавляет письмо в таблицу <code>mail</code> с типом <code>LetterType.BLACKCLOUD</code> (значение 2).</li>
            <li>Увеличивает счётчик <code>players.mailbox_letters</code>.</li>
        </ol>
        <p>
            Скрипт повторяет ограничения <a href="../game-server/src/com/aionemu/gameserver/services/mail/SystemMailService.java">SystemMailService</a>
            из игрового сервера и подходит как временный мост между сайтом и сервером.
        </p>
        <p>
            Чтобы избежать конфликтов ID, используйте его только когда сервер выключен или заведите небольшой HTTP/RPC слой
            в самом сервере, который будет вызывать <code>SystemMailService.sendMail()</code> напрямую.
        </p>
    </section>
</body>
</html>
