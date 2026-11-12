CREATE TABLE dispatch.dispatch_decisions (
  id             uuid PRIMARY KEY,
  batch_id       uuid NOT NULL REFERENCES dispatch.delivery_batches(id),
  status         text NOT NULL
                 CHECK (status IN ('PENDING_DECISION','AWAITING_DRIVER_RESPONSE','CONFIRMED',
                                   'AWAITING_REASSIGNMENT','CANCELLED')),
  cancel_reason  text,
  created_by     uuid NOT NULL,
  created_at     timestamptz NOT NULL,
  updated_at     timestamptz NOT NULL,
  version        bigint NOT NULL DEFAULT 0,
  CONSTRAINT ck_decisions_cancel CHECK (status <> 'CANCELLED' OR cancel_reason IS NOT NULL)
);
CREATE UNIQUE INDEX uq_decisions_open_per_batch
  ON dispatch.dispatch_decisions (batch_id) WHERE status <> 'CANCELLED';

CREATE TABLE dispatch.recommendation_snapshots (
  id                   uuid PRIMARY KEY,
  decision_id          uuid NOT NULL REFERENCES dispatch.dispatch_decisions(id),
  batch_id             uuid NOT NULL REFERENCES dispatch.delivery_batches(id),
  batch_version        bigint NOT NULL,
  rule_version_id      uuid NOT NULL REFERENCES admin_cfg.dispatch_rule_versions(id),
  status               text NOT NULL
                       CHECK (status IN ('ACTIVE','CONSUMED','EXPIRED','INVALIDATED')),
  generated_at         timestamptz NOT NULL,
  expires_at           timestamptz NOT NULL,
  generated_by         uuid NOT NULL,
  candidate_count      integer NOT NULL CHECK (candidate_count >= 0),
  eligible_count       integer NOT NULL CHECK (eligible_count >= 0),
  persisted_top_n      integer NOT NULL CHECK (persisted_top_n > 0),
  exclusion_summary    jsonb NOT NULL DEFAULT '{}'::jsonb,
  consumed_at          timestamptz,
  invalidated_at       timestamptz,
  invalidation_reason  text,
  CONSTRAINT ck_snapshots_ttl CHECK (expires_at > generated_at),
  CONSTRAINT ck_snapshots_counts CHECK (eligible_count <= candidate_count),
  CONSTRAINT ck_snapshots_terminal
    CHECK ((status = 'CONSUMED') = (consumed_at IS NOT NULL)
       AND (status = 'INVALIDATED') = (invalidated_at IS NOT NULL))
);
CREATE UNIQUE INDEX uq_snapshots_active_per_decision
  ON dispatch.recommendation_snapshots (decision_id) WHERE status = 'ACTIVE';
CREATE INDEX ix_snapshots_decision ON dispatch.recommendation_snapshots (decision_id, generated_at DESC);
CREATE INDEX ix_snapshots_expiry   ON dispatch.recommendation_snapshots (expires_at) WHERE status = 'ACTIVE';

CREATE TABLE dispatch.recommendation_candidates (
  snapshot_id                  uuid NOT NULL REFERENCES dispatch.recommendation_snapshots(id),
  driver_id                    uuid NOT NULL REFERENCES fleet.drivers(id),
  rank                         integer NOT NULL CHECK (rank > 0),
  vehicle_id                   uuid NOT NULL REFERENCES fleet.vehicles(id),
  total_score                  numeric(6,3) NOT NULL CHECK (total_score BETWEEN 0 AND 100),
  distance_m                   numeric(10,1) NOT NULL CHECK (distance_m >= 0),
  score_distance               numeric(6,3) NOT NULL CHECK (score_distance BETWEEN 0 AND 100),
  score_capacity_fit           numeric(6,3) NOT NULL CHECK (score_capacity_fit BETWEEN 0 AND 100),
  score_availability           numeric(6,3) NOT NULL CHECK (score_availability BETWEEN 0 AND 100),
  score_zone_compat            numeric(6,3) NOT NULL CHECK (score_zone_compat BETWEEN 0 AND 100),
  vehicle_capacity_weight_g    integer NOT NULL,
  vehicle_capacity_volume_cm3  bigint NOT NULL,
  breakdown                    jsonb NOT NULL,
  PRIMARY KEY (snapshot_id, driver_id),
  CONSTRAINT uq_candidates_rank UNIQUE (snapshot_id, rank)
);

CREATE TABLE dispatch.driver_assignments (
  id                     uuid PRIMARY KEY,
  decision_id            uuid NOT NULL REFERENCES dispatch.dispatch_decisions(id),
  sequence_no            integer NOT NULL CHECK (sequence_no > 0),
  driver_id              uuid NOT NULL REFERENCES fleet.drivers(id),
  vehicle_id             uuid NOT NULL REFERENCES fleet.vehicles(id),
  kind                   text NOT NULL
                         CHECK (kind IN ('ACCEPT_RECOMMENDATION','OVERRIDE','REASSIGN')),
  status                 text NOT NULL
                         CHECK (status IN ('PENDING','ACCEPTED','REJECTED','CANCELLED','SUPERSEDED')),
  is_current             boolean NOT NULL,
  snapshot_id            uuid REFERENCES dispatch.recommendation_snapshots(id),
  recommended_driver_id  uuid REFERENCES fleet.drivers(id),
  decided_by             uuid NOT NULL,
  decided_at             timestamptz NOT NULL,
  override_reason        text,
  reassign_reason        text,
  responded_at           timestamptz,
  rejection_reason_code  text REFERENCES admin_cfg.rejection_reason_catalog(code),
  rejection_description  text,
  cancel_reason          text,
  superseded_by          uuid REFERENCES dispatch.driver_assignments(id),
  CONSTRAINT uq_assignments_seq UNIQUE (decision_id, sequence_no),
  CONSTRAINT ck_driver_assignments_current
    CHECK (is_current = (status IN ('PENDING','ACCEPTED'))),
  CONSTRAINT ck_driver_assignments_accept_kind
    CHECK (kind <> 'ACCEPT_RECOMMENDATION'
           OR (snapshot_id IS NOT NULL AND recommended_driver_id = driver_id)),
  CONSTRAINT ck_driver_assignments_override
    CHECK (kind <> 'OVERRIDE'
           OR (snapshot_id IS NOT NULL
               AND recommended_driver_id IS NOT NULL
               AND recommended_driver_id <> driver_id
               AND override_reason IS NOT NULL AND btrim(override_reason) <> '')),
  CONSTRAINT ck_driver_assignments_reassign
    CHECK (kind <> 'REASSIGN'
           OR (reassign_reason IS NOT NULL AND btrim(reassign_reason) <> '')),
  CONSTRAINT ck_driver_assignments_rejection
    CHECK (status <> 'REJECTED'
           OR (rejection_reason_code IS NOT NULL AND responded_at IS NOT NULL
               AND (rejection_reason_code <> 'OTHER'
                    OR (rejection_description IS NOT NULL AND btrim(rejection_description) <> '')))),
  CONSTRAINT ck_driver_assignments_cancel
    CHECK (status <> 'CANCELLED' OR cancel_reason IS NOT NULL),
  CONSTRAINT ck_driver_assignments_superseded
    CHECK ((status = 'SUPERSEDED') = (superseded_by IS NOT NULL))
);
CREATE UNIQUE INDEX uq_driver_assignments_current
  ON dispatch.driver_assignments (decision_id) WHERE is_current;
CREATE INDEX ix_driver_assignments_driver_current
  ON dispatch.driver_assignments (driver_id) WHERE is_current;
CREATE INDEX ix_driver_assignments_decision ON dispatch.driver_assignments (decision_id, sequence_no);
