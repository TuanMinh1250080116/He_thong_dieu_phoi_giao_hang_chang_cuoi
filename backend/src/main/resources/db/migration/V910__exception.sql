CREATE SEQUENCE exc.exception_code_seq START 1;

CREATE TABLE exc.delivery_exceptions (
  id                  uuid PRIMARY KEY,
  code                text NOT NULL UNIQUE,
  delivery_id         uuid NOT NULL REFERENCES delivery.deliveries(id),
  stop_id             uuid,
  order_id            uuid REFERENCES ordering.orders(id),
  exception_type      text NOT NULL REFERENCES admin_cfg.exception_type_catalog(code),
  severity            text NOT NULL CHECK (severity IN ('LOW','MEDIUM','HIGH','CRITICAL')),
  impact              text NOT NULL
                      CHECK (impact IN ('NON_BLOCKING','BLOCKS_STOP',
                                        'BLOCKS_DELIVERY_COMPLETION','CUSTOMER_IMPACTING')),
  status              text NOT NULL
                      CHECK (status IN ('OPEN','INVESTIGATING','RESOLVED','CANCELLED')),
  description         text NOT NULL CHECK (btrim(description) <> ''),
  reporter_user_id    uuid NOT NULL REFERENCES iam.users(id),
  assignee_user_id    uuid REFERENCES iam.users(id),
  reported_at         timestamptz NOT NULL,
  investigating_at    timestamptz,
  closed_at           timestamptz,
  closed_by           uuid REFERENCES iam.users(id),
  resolution_outcome  text,
  resolution_note     text,
  cancellation_reason text,
  created_at          timestamptz NOT NULL,
  updated_at          timestamptz NOT NULL,
  version             bigint NOT NULL DEFAULT 0,
  FOREIGN KEY (delivery_id, stop_id) REFERENCES delivery.delivery_stops (delivery_id, id),
  FOREIGN KEY (delivery_id, order_id) REFERENCES delivery.delivery_orders (delivery_id, order_id),
  CONSTRAINT ck_exceptions_investigating
    CHECK (status NOT IN ('INVESTIGATING','RESOLVED')
           OR (assignee_user_id IS NOT NULL AND investigating_at IS NOT NULL)),
  CONSTRAINT ck_delivery_exceptions_closure
    CHECK ((status IN ('RESOLVED','CANCELLED')) = (closed_at IS NOT NULL AND closed_by IS NOT NULL)),
  CONSTRAINT ck_exceptions_resolved
    CHECK (status <> 'RESOLVED'
           OR (resolution_outcome IS NOT NULL AND btrim(resolution_note) <> '')),
  CONSTRAINT ck_exceptions_cancelled
    CHECK (status <> 'CANCELLED' OR (cancellation_reason IS NOT NULL AND btrim(cancellation_reason) <> '')),
  CONSTRAINT ck_exceptions_cancelled_from_open
    CHECK (status <> 'CANCELLED' OR investigating_at IS NULL)
);
CREATE INDEX ix_exceptions_queue    ON exc.delivery_exceptions (status, severity, reported_at);
CREATE INDEX ix_exceptions_delivery ON exc.delivery_exceptions (delivery_id, status);
CREATE INDEX ix_exceptions_reported ON exc.delivery_exceptions (reported_at DESC, id DESC);
CREATE INDEX ix_exceptions_assignee ON exc.delivery_exceptions (assignee_user_id, status)
  WHERE status IN ('OPEN','INVESTIGATING');

CREATE TABLE exc.exception_notes (
  id            uuid PRIMARY KEY,
  exception_id  uuid NOT NULL REFERENCES exc.delivery_exceptions(id),
  author_id     uuid NOT NULL REFERENCES iam.users(id),
  note_type     text NOT NULL CHECK (note_type IN ('CONTEXT','INVESTIGATION','RESOLUTION')),
  body          text NOT NULL CHECK (btrim(body) <> ''),
  created_at    timestamptz NOT NULL
);
CREATE INDEX ix_exception_notes_exception ON exc.exception_notes (exception_id, created_at);

CREATE TRIGGER trg_exception_notes_append_only
  BEFORE UPDATE OR DELETE ON exc.exception_notes
  FOR EACH ROW EXECUTE FUNCTION platform.forbid_modification();

CREATE TABLE exc.exception_evidence (
  exception_id        uuid NOT NULL REFERENCES exc.delivery_exceptions(id),
  evidence_object_id  uuid NOT NULL UNIQUE REFERENCES proof.evidence_objects(id),
  PRIMARY KEY (exception_id, evidence_object_id)
);

CREATE TABLE delivery.delivery_blocking_refs (
  exception_id  uuid PRIMARY KEY REFERENCES exc.delivery_exceptions(id),
  delivery_id   uuid NOT NULL,
  stop_id       uuid,
  scope         text NOT NULL CHECK (scope IN ('STOP','DELIVERY')),
  created_at    timestamptz NOT NULL,
  cleared_at    timestamptz,
  FOREIGN KEY (delivery_id, stop_id) REFERENCES delivery.delivery_stops (delivery_id, id),
  CONSTRAINT ck_blocking_scope CHECK ((scope = 'STOP') = (stop_id IS NOT NULL))
);
CREATE INDEX ix_blocking_refs_open ON delivery.delivery_blocking_refs (delivery_id) WHERE cleared_at IS NULL;
CREATE INDEX ix_blocking_refs_stop_open ON delivery.delivery_blocking_refs (stop_id) WHERE cleared_at IS NULL;
