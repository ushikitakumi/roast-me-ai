CREATE TABLE users (id uuid PRIMARY KEY, username text NOT NULL UNIQUE);
INSERT INTO users VALUES ('00000000-0000-0000-0000-000000000001', 'owner');
CREATE TABLE voices (id text PRIMARY KEY, name text NOT NULL, credit_text text NOT NULL, is_active boolean NOT NULL);
INSERT INTO voices VALUES ('pending', '音声は準備中', '', false);
CREATE TABLE personas (id text PRIMARY KEY, name text NOT NULL, description text NOT NULL, default_voice_id text REFERENCES voices(id));
INSERT INTO personas VALUES ('rival', 'ライバル', '同じ目線から、宣言と行動の差を突くライバル。', 'pending');
CREATE TABLE persona_voices (persona_id text REFERENCES personas(id), voice_id text REFERENCES voices(id), PRIMARY KEY(persona_id, voice_id));
INSERT INTO persona_voices VALUES ('rival', 'pending');
CREATE TABLE user_settings (
 user_id uuid PRIMARY KEY REFERENCES users(id), roast_intensity text NOT NULL DEFAULT 'NORMAL' CHECK(roast_intensity IN ('MILD','NORMAL','SAVAGE')),
 persona_id text NOT NULL REFERENCES personas(id) DEFAULT 'rival', voice_id text NOT NULL REFERENCES voices(id) DEFAULT 'pending',
 manual_paused boolean NOT NULL DEFAULT false, auto_paused boolean NOT NULL DEFAULT false,
 resume_allowed_at timestamptz, revision bigint NOT NULL DEFAULT 0
);
INSERT INTO user_settings(user_id) SELECT id FROM users;
CREATE TABLE goals (
 id uuid PRIMARY KEY, user_id uuid NOT NULL REFERENCES users(id), title text NOT NULL CHECK(length(title) BETWEEN 1 AND 100),
 success_criteria text NOT NULL CHECK(length(success_criteria) BETWEEN 1 AND 1000), description text NOT NULL DEFAULT '',
 category text NOT NULL, deadline date, status text NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','ACHIEVED','ABANDONED')),
 achieved_at timestamptz, abandoned_at timestamptz, created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL
);
CREATE INDEX goals_owner ON goals(user_id,status,created_at DESC,id DESC);
CREATE TABLE progress_logs (
 id uuid PRIMARY KEY, goal_id uuid NOT NULL REFERENCES goals(id), user_id uuid NOT NULL REFERENCES users(id),
 body text NOT NULL CHECK(length(body) BETWEEN 1 AND 1000),
 progress_category text NOT NULL CHECK(progress_category IN ('DONE','PARTIAL','NOT_DONE','REST','UNWELL')),
 request_video boolean NOT NULL, created_at timestamptz NOT NULL, deleted_at timestamptz
);
CREATE TABLE roasts (
 id uuid PRIMARY KEY, user_id uuid NOT NULL REFERENCES users(id), goal_id uuid NOT NULL REFERENCES goals(id),
 progress_log_id uuid UNIQUE REFERENCES progress_logs(id), trigger text NOT NULL, response_kind text NOT NULL,
 persona_id text NOT NULL REFERENCES personas(id), voice_id text NOT NULL REFERENCES voices(id), intensity text NOT NULL,
 body text NOT NULL, template_id text NOT NULL, visibility text NOT NULL DEFAULT 'VISIBLE', suppression_reason text,
 provider text NOT NULL DEFAULT 'template', prompt_version text NOT NULL DEFAULT 'template-v1', created_at timestamptz NOT NULL
);
CREATE TABLE roast_context_sources (roast_id uuid REFERENCES roasts(id), progress_log_id uuid REFERENCES progress_logs(id), PRIMARY KEY(roast_id,progress_log_id));
CREATE INDEX sources_progress ON roast_context_sources(progress_log_id,roast_id);
CREATE TABLE idempotency_keys (
 user_id uuid REFERENCES users(id), key text NOT NULL, request_hash text NOT NULL,
 goal_id uuid NOT NULL REFERENCES goals(id), progress_id uuid REFERENCES progress_logs(id), roast_id uuid REFERENCES roasts(id),
 video_decision text, status_code integer NOT NULL, created_at timestamptz NOT NULL, PRIMARY KEY(user_id,key)
);
