-- STARA mini pipeline schema (PostgreSQL)

CREATE TABLE IF NOT EXISTS customers (
    id         BIGSERIAL PRIMARY KEY,
    name       VARCHAR(100) NOT NULL CHECK (length(trim(name)) > 0),
    phone      VARCHAR(20)  NOT NULL UNIQUE CHECK (phone ~ '^\+62[0-9]{8,13}$'),
    status     VARCHAR(10)  NOT NULL DEFAULT 'lead' CHECK (status IN ('lead', 'active', 'inactive')),
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS messages (
    id          BIGSERIAL PRIMARY KEY,
    customer_id BIGINT        NOT NULL REFERENCES customers (id) ON DELETE CASCADE,
    direction   VARCHAR(8)    NOT NULL CHECK (direction IN ('incoming', 'outgoing')),
    content     VARCHAR(2000) NOT NULL CHECK (length(trim(content)) > 0),
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS messages_customer_id_idx ON messages (customer_id, id);
