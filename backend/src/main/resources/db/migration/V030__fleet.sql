CREATE TABLE fleet.vehicles (
  id                   uuid PRIMARY KEY,
  code                 text NOT NULL UNIQUE,
  vehicle_type         text NOT NULL,
  capacity_weight_g    integer NOT NULL CHECK (capacity_weight_g > 0),
  capacity_volume_cm3  bigint NOT NULL CHECK (capacity_volume_cm3 > 0),
  is_active            boolean NOT NULL DEFAULT true,
  note                 text,
  created_at           timestamptz NOT NULL,
  updated_at           timestamptz NOT NULL,
  version              bigint NOT NULL DEFAULT 0
);

CREATE TABLE fleet.drivers (
  id            uuid PRIMARY KEY,
  user_id       uuid NOT NULL UNIQUE REFERENCES iam.users(id),
  code          text NOT NULL UNIQUE,
  full_name     text NOT NULL,
  contact       text,
  is_active     boolean NOT NULL DEFAULT true,
  availability  text NOT NULL DEFAULT 'OFFLINE'
                CHECK (availability IN ('OFFLINE','AVAILABLE','RESERVED','BUSY')),
  vehicle_id    uuid REFERENCES fleet.vehicles(id),
  created_at    timestamptz NOT NULL,
  updated_at    timestamptz NOT NULL,
  version       bigint NOT NULL DEFAULT 0,
  CONSTRAINT ck_drivers_inactive_offline CHECK (is_active OR availability = 'OFFLINE')
);
CREATE UNIQUE INDEX uq_drivers_vehicle ON fleet.drivers (vehicle_id) WHERE vehicle_id IS NOT NULL;
CREATE INDEX ix_drivers_candidates ON fleet.drivers (is_active, availability)
  WHERE is_active AND availability = 'AVAILABLE';

CREATE TABLE fleet.driver_zones (
  driver_id uuid NOT NULL REFERENCES fleet.drivers(id),
  zone_id   uuid NOT NULL REFERENCES admin_cfg.delivery_zones(id),
  PRIMARY KEY (driver_id, zone_id)
);

CREATE TABLE fleet.driver_locations (
  driver_id    uuid PRIMARY KEY REFERENCES fleet.drivers(id),
  location     geography(Point, 4326) NOT NULL,
  accuracy_m   numeric(8,1),
  recorded_at  timestamptz NOT NULL
);
CREATE INDEX ix_driver_locations_gist ON fleet.driver_locations USING gist (location);
