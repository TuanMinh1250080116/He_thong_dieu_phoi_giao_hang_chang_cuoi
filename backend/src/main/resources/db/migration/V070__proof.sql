CREATE TABLE proof.evidence_objects (
  id              uuid PRIMARY KEY,
  purpose         text NOT NULL CHECK (purpose IN ('POD','EXCEPTION')),
  kind            text NOT NULL CHECK (kind IN ('PHOTO','SIGNATURE')),
  owner_user_id   uuid NOT NULL REFERENCES iam.users(id),
  delivery_id     uuid NOT NULL REFERENCES delivery.deliveries(id),
  stop_id         uuid,
  storage_key     text NOT NULL UNIQUE,
  declared_mime   text NOT NULL,
  detected_mime   text,
  size_bytes      bigint CHECK (size_bytes > 0),
  sha256          bytea,
  status          text NOT NULL
                  CHECK (status IN ('PREPARED','UPLOADED','VERIFIED','ATTACHED',
                                    'EXPIRED','QUARANTINED','ORPHANED')),
  created_at      timestamptz NOT NULL,
  expires_at      timestamptz NOT NULL,
  verified_at     timestamptz,
  attached_at     timestamptz,
  FOREIGN KEY (delivery_id, stop_id) REFERENCES delivery.delivery_stops (delivery_id, id),
  CONSTRAINT ck_evidence_verified
    CHECK (status NOT IN ('VERIFIED','ATTACHED')
           OR (detected_mime IS NOT NULL AND size_bytes IS NOT NULL AND sha256 IS NOT NULL))
);
CREATE INDEX ix_evidence_cleanup ON proof.evidence_objects (expires_at)
  WHERE status IN ('PREPARED','UPLOADED','VERIFIED');

CREATE TABLE proof.otp_challenges (
  id            uuid PRIMARY KEY,
  delivery_id   uuid NOT NULL,
  stop_id       uuid NOT NULL,
  code_hmac     bytea NOT NULL,
  status        text NOT NULL CHECK (status IN ('ISSUED','VERIFIED','EXPIRED','LOCKED')),
  attempts      integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
  max_attempts  integer NOT NULL DEFAULT 5 CHECK (max_attempts > 0),
  issued_at     timestamptz NOT NULL,
  expires_at    timestamptz NOT NULL,
  verified_at   timestamptz,
  FOREIGN KEY (delivery_id, stop_id) REFERENCES delivery.delivery_stops (delivery_id, id),
  CONSTRAINT ck_otp_attempts CHECK (attempts <= max_attempts),
  CONSTRAINT ck_otp_verified CHECK ((status = 'VERIFIED') = (verified_at IS NOT NULL))
);
CREATE UNIQUE INDEX uq_otp_active_per_stop ON proof.otp_challenges (stop_id) WHERE status = 'ISSUED';

CREATE TABLE proof.proofs_of_delivery (
  id                 uuid PRIMARY KEY,
  delivery_id        uuid NOT NULL,
  stop_id            uuid NOT NULL,
  order_id           uuid NOT NULL REFERENCES ordering.orders(id),
  captured_at        timestamptz NOT NULL,
  gps_point          geography(Point, 4326) NOT NULL,
  gps_accuracy_m     numeric(8,1) NOT NULL CHECK (gps_accuracy_m >= 0),
  recipient_name     text,
  is_direct_handoff  boolean NOT NULL DEFAULT true,
  otp_challenge_id   uuid REFERENCES proof.otp_challenges(id),
  otp_verified       boolean NOT NULL DEFAULT false,
  note               text,
  policy_version_id  uuid REFERENCES admin_cfg.pod_policy_versions(id),
  submitted_by       uuid NOT NULL REFERENCES iam.users(id),
  command_id         uuid NOT NULL,
  created_at         timestamptz NOT NULL,
  FOREIGN KEY (delivery_id, stop_id) REFERENCES delivery.delivery_stops (delivery_id, id),
  CONSTRAINT uq_pod_per_stop UNIQUE (stop_id),
  CONSTRAINT ck_pod_otp CHECK (otp_verified = (otp_challenge_id IS NOT NULL))
);
CREATE INDEX ix_pod_order ON proof.proofs_of_delivery (order_id);

CREATE TABLE proof.pod_evidence (
  pod_id              uuid NOT NULL REFERENCES proof.proofs_of_delivery(id),
  evidence_object_id  uuid NOT NULL UNIQUE REFERENCES proof.evidence_objects(id),
  kind                text NOT NULL CHECK (kind IN ('PHOTO','SIGNATURE')),
  PRIMARY KEY (pod_id, evidence_object_id)
);
