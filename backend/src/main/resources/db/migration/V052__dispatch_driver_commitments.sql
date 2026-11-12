CREATE TABLE dispatch.driver_commitments (
  id             uuid PRIMARY KEY,
  driver_id      uuid NOT NULL REFERENCES fleet.drivers(id),
  assignment_id  uuid NOT NULL UNIQUE REFERENCES dispatch.driver_assignments(id),
  time_window    tstzrange NOT NULL,
  status         text NOT NULL CHECK (status IN ('ACTIVE','RELEASED')),
  created_at     timestamptz NOT NULL,
  released_at    timestamptz,
  CONSTRAINT ck_commitments_window
    CHECK (NOT isempty(time_window) AND NOT lower_inf(time_window) AND NOT upper_inf(time_window)),
  CONSTRAINT ck_commitments_release CHECK ((status = 'RELEASED') = (released_at IS NOT NULL)),
  CONSTRAINT ex_driver_commitments_no_overlap
    EXCLUDE USING gist (driver_id WITH =, time_window WITH &&) WHERE (status = 'ACTIVE')
);
CREATE INDEX ix_commitments_driver_active ON dispatch.driver_commitments (driver_id) WHERE status = 'ACTIVE';
