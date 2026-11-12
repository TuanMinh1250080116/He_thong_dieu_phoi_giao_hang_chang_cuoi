
CREATE TABLE platform.processed_commands (
  id                   uuid PRIMARY KEY,
  actor_id             uuid NOT NULL,
  idempotency_key      text NOT NULL CHECK (length(idempotency_key) BETWEEN 8 AND 128),
  use_case             text NOT NULL,
  request_fingerprint  bytea NOT NULL,
  status               text NOT NULL CHECK (status IN ('IN_PROGRESS','COMPLETED','FAILED')),
  result_type          text,
  result_ref           uuid,
  result_version       bigint,
  created_at           timestamptz NOT NULL,
  completed_at         timestamptz,
  expires_at           timestamptz NOT NULL,
  CONSTRAINT uq_processed_commands_actor_key UNIQUE (actor_id, idempotency_key),
  CONSTRAINT ck_processed_completed CHECK ((status = 'COMPLETED') = (completed_at IS NOT NULL))
);
CREATE INDEX ix_processed_commands_expiry ON platform.processed_commands (expires_at);

CREATE TABLE platform.event_publications (
  id               uuid PRIMARY KEY,
  event_id         uuid NOT NULL UNIQUE,
  event_type       text NOT NULL,
  event_version    integer NOT NULL DEFAULT 1,
  aggregate_type   text NOT NULL,
  aggregate_id     uuid NOT NULL,
  actor_id         uuid,
  correlation_id   text NOT NULL,
  occurred_at      timestamptz NOT NULL,
  safe_payload     jsonb NOT NULL,
  status           text NOT NULL CHECK (status IN ('PENDING','DISPATCHED','FAILED')),
  attempts         integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
  next_attempt_at  timestamptz,
  last_error_code  text,
  dispatched_at    timestamptz,
  CONSTRAINT ck_event_publications_dispatched
    CHECK ((status = 'DISPATCHED') = (dispatched_at IS NOT NULL))
);
CREATE INDEX ix_event_publications_pending
  ON platform.event_publications (next_attempt_at) WHERE status IN ('PENDING','FAILED');

CREATE TABLE platform.event_handler_runs (
  event_id      uuid NOT NULL,
  handler_name  text NOT NULL,
  completed_at  timestamptz NOT NULL,
  PRIMARY KEY (event_id, handler_name)
);
