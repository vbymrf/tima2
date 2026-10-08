-- 0072: лента состояний — «приди и забери» для того, что заменяется, а не копится
-- (ПЛАН-(ОП)-ОТМЕТОК-И-ПРИСУТСТВИЯ, ОП0–ОП4).
--
-- Как лента звонков (0056): номер на человека, сигнал `state.poke {rev}`, телефон забирает
-- изменившееся после своего номера. Разница в том, что здесь **строка — последняя правда**:
-- отметка «прочитано до» заменяется, а не дописывается, и лента отдаёт текущее, а не историю.
-- Каждая строка несёт номер своего последнего изменения.

-- Вершина ленты человека.
CREATE TABLE IF NOT EXISTS state_tops (
    user_id UUID   PRIMARY KEY,
    rev     BIGINT NOT NULL
);

-- «Доставлено» и «прочитано» в личной переписке — в списке ОТПРАВИТЕЛЯ (owner_id):
-- его сообщения в чате chat_id у собеседника peer_id доставлены и прочитаны до времени
-- написания (created_at_unix_ms — одно у обоих, подписано отправителем). Только вверх.
CREATE TABLE IF NOT EXISTS chat_receipts (
    owner_id     UUID        NOT NULL,
    chat_id      UUID        NOT NULL,
    peer_id      UUID        NOT NULL,
    delivered_ms BIGINT      NOT NULL DEFAULT 0,
    read_ms      BIGINT      NOT NULL DEFAULT 0,
    rev          BIGINT      NOT NULL,
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (owner_id, chat_id)
);
CREATE INDEX IF NOT EXISTS idx_chat_receipts_rev ON chat_receipts (owner_id, rev);

-- «Печатает» — в списке СОБЕСЕДНИКА (owner_id): from_id набирает в чате chat_id до until_ms.
-- 0 — перестал. Срок, а не только отмена: потерянная отмена гаснет сама.
CREATE TABLE IF NOT EXISTS typing_states (
    owner_id   UUID        NOT NULL,
    from_id    UUID        NOT NULL,
    chat_id    UUID        NOT NULL,
    until_ms   BIGINT      NOT NULL,
    rev        BIGINT      NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (owner_id, from_id)
);
CREATE INDEX IF NOT EXISTS idx_typing_states_rev ON typing_states (owner_id, rev);

-- «В сети» — копия у того, кто СМОТРИТ переписку (owner_id) с target_id.
CREATE TABLE IF NOT EXISTS presence_states (
    owner_id     UUID        NOT NULL,
    target_id    UUID        NOT NULL,
    online       BOOLEAN     NOT NULL,
    until_ms     BIGINT      NOT NULL DEFAULT 0,
    last_seen_ms BIGINT      NOT NULL DEFAULT 0,
    rev          BIGINT      NOT NULL,
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (owner_id, target_id)
);
CREATE INDEX IF NOT EXISTS idx_presence_states_rev ON presence_states (owner_id, rev);

-- Приложение на экране — по устройству. «В сети» человека — хоть одно устройство на экране и
-- отзывалось недавно (seen_at): убитое приложение кадр «свернули» не пришлёт.
CREATE TABLE IF NOT EXISTS device_presence (
    device_id  UUID        PRIMARY KEY,
    user_id    UUID        NOT NULL,
    foreground BOOLEAN     NOT NULL,
    seen_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_device_presence_user ON device_presence (user_id);

-- Кто сейчас смотрит переписку с target_id: ему и шлём смену «в сети». Срок — смотрящий
-- подтверждает раз в минуту; закрыл приложение, не сказав, — подписка истекает сама.
CREATE TABLE IF NOT EXISTS presence_watchers (
    watcher_device UUID        NOT NULL,
    watcher_id     UUID        NOT NULL,
    target_id      UUID        NOT NULL,
    until_at       TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (watcher_device, target_id)
);
CREATE INDEX IF NOT EXISTS idx_presence_watchers_target ON presence_watchers (target_id);

-- «Был(а) в …»: когда последнее устройство человека ушло с экрана.
ALTER TABLE users ADD COLUMN IF NOT EXISTS last_seen_at TIMESTAMPTZ;
