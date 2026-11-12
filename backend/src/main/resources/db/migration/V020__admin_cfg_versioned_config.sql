CREATE TABLE admin_cfg.delivery_zones (
  id          uuid PRIMARY KEY,
  code        text NOT NULL UNIQUE,
  name        text NOT NULL,
  is_active   boolean NOT NULL DEFAULT true,
  created_at  timestamptz NOT NULL,
  updated_at  timestamptz NOT NULL,
  version     bigint NOT NULL DEFAULT 0
);

CREATE TABLE admin_cfg.delivery_zone_versions (
  id             uuid PRIMARY KEY,
  zone_id        uuid NOT NULL REFERENCES admin_cfg.delivery_zones(id),
  version_no     integer NOT NULL CHECK (version_no > 0),
  boundary       geography(MultiPolygon, 4326) NOT NULL,
  status         text NOT NULL
                 CHECK (status IN ('DRAFT','ACTIVE','RETIRED','CANCELLED')),
  effective_from timestamptz,
  created_by     uuid NOT NULL,
  created_at     timestamptz NOT NULL,
  activated_at   timestamptz,
  CONSTRAINT uq_zone_versions_no UNIQUE (zone_id, version_no),
  CONSTRAINT ck_zone_versions_valid_geom CHECK (ST_IsValid(boundary::geometry)),
  CONSTRAINT ck_zone_versions_active_effective
    CHECK (status <> 'ACTIVE' OR (effective_from IS NOT NULL AND activated_at IS NOT NULL))
);
CREATE UNIQUE INDEX uq_zone_versions_active
  ON admin_cfg.delivery_zone_versions (zone_id) WHERE status = 'ACTIVE';
CREATE INDEX ix_zone_versions_boundary_gist
  ON admin_cfg.delivery_zone_versions USING gist (boundary) WHERE status = 'ACTIVE';

CREATE TABLE admin_cfg.dispatch_rule_versions (
  id                    uuid PRIMARY KEY,
  zone_id               uuid REFERENCES admin_cfg.delivery_zones(id),
  version_no            integer NOT NULL CHECK (version_no > 0),
  name                  text NOT NULL,
  status                text NOT NULL
                        CHECK (status IN ('DRAFT','ACTIVE','RETIRED','CANCELLED')),
  w_distance            numeric(5,4) NOT NULL CHECK (w_distance BETWEEN 0 AND 1),
  w_capacity_fit        numeric(5,4) NOT NULL CHECK (w_capacity_fit BETWEEN 0 AND 1),
  w_availability        numeric(5,4) NOT NULL CHECK (w_availability BETWEEN 0 AND 1),
  w_zone_compat         numeric(5,4) NOT NULL CHECK (w_zone_compat BETWEEN 0 AND 1),
  ttl_seconds           integer NOT NULL DEFAULT 300 CHECK (ttl_seconds BETWEEN 60 AND 900),
  search_radius_m       integer NOT NULL CHECK (search_radius_m > 0),
  max_location_age_s    integer NOT NULL DEFAULT 600 CHECK (max_location_age_s > 0),
  persist_top_n         integer NOT NULL DEFAULT 50 CHECK (persist_top_n BETWEEN 1 AND 200),
  effective_from        timestamptz NOT NULL,
  created_by            uuid NOT NULL,
  created_at            timestamptz NOT NULL,
  CONSTRAINT ck_dispatch_rule_versions_weights
    CHECK (w_distance + w_capacity_fit + w_availability + w_zone_compat = 1.0000)
);
CREATE UNIQUE INDEX uq_rule_versions_no
  ON admin_cfg.dispatch_rule_versions (COALESCE(zone_id, '00000000-0000-0000-0000-000000000000'), version_no);
CREATE UNIQUE INDEX uq_rule_versions_active
  ON admin_cfg.dispatch_rule_versions (COALESCE(zone_id, '00000000-0000-0000-0000-000000000000'))
  WHERE status = 'ACTIVE';

CREATE TABLE admin_cfg.pod_policy_versions (
  id                    uuid PRIMARY KEY,
  zone_id               uuid REFERENCES admin_cfg.delivery_zones(id),
  version_no            integer NOT NULL CHECK (version_no > 0),
  name                  text NOT NULL,
  status                text NOT NULL
                        CHECK (status IN ('DRAFT','ACTIVE','RETIRED','CANCELLED')),
  requires_gps          boolean NOT NULL DEFAULT true,
  requires_server_ts    boolean NOT NULL DEFAULT true,
  max_gps_accuracy_m    integer CHECK (max_gps_accuracy_m > 0),
  evidence_expression   jsonb NOT NULL,
  recipient_name_rule   text NOT NULL DEFAULT 'DIRECT_HANDOFF'
                        CHECK (recipient_name_rule IN ('ALWAYS','DIRECT_HANDOFF','NEVER')),
  effective_from        timestamptz NOT NULL,
  created_by            uuid NOT NULL,
  created_at            timestamptz NOT NULL,
  CONSTRAINT ck_pod_policy_gps_required CHECK (requires_gps AND requires_server_ts),
  CONSTRAINT ck_pod_policy_expression_type CHECK (jsonb_typeof(evidence_expression) = 'object')
);
CREATE UNIQUE INDEX uq_pod_policy_versions_no
  ON admin_cfg.pod_policy_versions (COALESCE(zone_id, '00000000-0000-0000-0000-000000000000'), version_no);
CREATE UNIQUE INDEX uq_pod_policy_versions_active
  ON admin_cfg.pod_policy_versions (COALESCE(zone_id, '00000000-0000-0000-0000-000000000000'))
  WHERE status = 'ACTIVE';

CREATE TABLE admin_cfg.exception_type_catalog (
  code              text PRIMARY KEY,
  name              text NOT NULL,
  default_severity  text NOT NULL CHECK (default_severity IN ('LOW','MEDIUM','HIGH','CRITICAL')),
  impact            text NOT NULL
                    CHECK (impact IN ('NON_BLOCKING','BLOCKS_STOP','BLOCKS_DELIVERY_COMPLETION','CUSTOMER_IMPACTING')),
  is_active         boolean NOT NULL DEFAULT true
);

CREATE TABLE admin_cfg.rejection_reason_catalog (
  code                  text PRIMARY KEY,
  requires_description  boolean NOT NULL DEFAULT false,
  is_active             boolean NOT NULL DEFAULT true
);

ALTER TABLE iam.user_scopes
  ADD CONSTRAINT fk_user_scopes_zone FOREIGN KEY (zone_id) REFERENCES admin_cfg.delivery_zones(id);
