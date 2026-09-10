CREATE TABLE budget_reservations (
 id uuid PRIMARY KEY, user_id uuid NOT NULL REFERENCES users(id), operation_id uuid NOT NULL UNIQUE,
 budget_day date NOT NULL, budget_month date NOT NULL,
 estimated_micro_usd bigint NOT NULL CHECK(estimated_micro_usd>=0), outstanding_micro_usd bigint NOT NULL CHECK(outstanding_micro_usd>=0),
 status text NOT NULL CHECK(status IN ('RESERVED','UNKNOWN','SETTLED','RELEASED')), created_at timestamptz NOT NULL
);
CREATE TABLE cost_entries (
 id uuid PRIMARY KEY, reservation_id uuid NOT NULL REFERENCES budget_reservations(id), attempt_id uuid NOT NULL UNIQUE,
 stage text NOT NULL, provider text NOT NULL, actual_micro_usd bigint NOT NULL CHECK(actual_micro_usd>=0), confirmed_at timestamptz NOT NULL
);
CREATE TABLE generation_slot (singleton_id integer PRIMARY KEY CHECK(singleton_id=1), operation_id uuid);
INSERT INTO generation_slot VALUES (1,NULL);
