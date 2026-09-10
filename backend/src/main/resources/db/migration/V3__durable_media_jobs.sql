ALTER TABLE roasts ADD COLUMN response_status text NOT NULL DEFAULT 'TEXT_ONLY';
ALTER TABLE roasts ADD COLUMN settings_revision bigint NOT NULL DEFAULT 0;
CREATE TABLE roast_jobs (
 roast_id uuid PRIMARY KEY REFERENCES roasts(id), reservation_id uuid NOT NULL REFERENCES budget_reservations(id),
 status text NOT NULL CHECK(status IN ('TEXT_DONE','AUDIO_DONE','VIDEO_SUBMITTING','VIDEO_SUBMITTED','VIDEO_DONE','DEGRADED','CANCELLED','SUBMISSION_UNKNOWN','RESULT_UNKNOWN')),
 audio_key text, video_key text, external_job_id text, submission_started_at timestamptz,
 result_deadline_at timestamptz NOT NULL, lease_owner uuid, lease_expires_at timestamptz, lease_version bigint NOT NULL DEFAULT 0,
 last_error_code text, late_result_status text, created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL
);
CREATE TABLE stub_video_submissions (id text PRIMARY KEY, roast_id uuid NOT NULL, created_at timestamptz NOT NULL);
