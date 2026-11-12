CREATE SEQUENCE ordering.order_code_seq START 1;

CREATE TABLE ordering.orders (
  id                     uuid PRIMARY KEY,
  code                   text NOT NULL UNIQUE,
  customer_id            uuid NOT NULL REFERENCES iam.users(id),
  status                 text NOT NULL
                         CHECK (status IN ('DRAFT','READY_FOR_DISPATCH','BATCHED','ASSIGNED',
                                           'IN_DELIVERY','DELIVERED','CANCELLED')),
  pickup_address         text NOT NULL,
  pickup_point           geography(Point, 4326),
  dropoff_address        text NOT NULL,
  dropoff_point          geography(Point, 4326),
  recipient_name         text NOT NULL,
  recipient_contact      text,
  weight_g               integer NOT NULL CHECK (weight_g > 0),
  length_cm              integer CHECK (length_cm > 0),
  width_cm               integer CHECK (width_cm > 0),
  height_cm              integer CHECK (height_cm > 0),
  volume_cm3             bigint CHECK (volume_cm3 > 0),
  package_note           text,
  window_start           timestamptz NOT NULL,
  window_end             timestamptz NOT NULL,
  zone_id                uuid REFERENCES admin_cfg.delivery_zones(id),
  zone_version_id        uuid REFERENCES admin_cfg.delivery_zone_versions(id),
  cancelled_from_status  text,
  cancel_reason          text,
  created_at             timestamptz NOT NULL,
  updated_at             timestamptz NOT NULL,
  version                bigint NOT NULL DEFAULT 0,
  CONSTRAINT ck_orders_window CHECK (window_end > window_start),
  CONSTRAINT ck_orders_ready_geo
    CHECK (status IN ('DRAFT','CANCELLED')
           OR (pickup_point IS NOT NULL AND dropoff_point IS NOT NULL
               AND zone_id IS NOT NULL AND volume_cm3 IS NOT NULL)),
  CONSTRAINT ck_orders_cancel
    CHECK (status <> 'CANCELLED'
           OR (cancelled_from_status IS NOT NULL
               AND (cancelled_from_status = 'DRAFT'
                    OR (cancel_reason IS NOT NULL AND btrim(cancel_reason) <> ''))))
);
CREATE INDEX ix_orders_customer      ON ordering.orders (customer_id, created_at DESC);
CREATE INDEX ix_orders_zone_ready    ON ordering.orders (zone_id, window_start)
                                      WHERE status = 'READY_FOR_DISPATCH';
CREATE INDEX ix_orders_status_zone   ON ordering.orders (status, zone_id, window_start);
CREATE INDEX ix_orders_pickup_gist   ON ordering.orders USING gist (pickup_point);
