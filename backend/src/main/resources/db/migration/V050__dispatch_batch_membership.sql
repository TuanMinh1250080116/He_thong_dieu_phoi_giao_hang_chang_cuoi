CREATE SEQUENCE dispatch.batch_code_seq START 1;

CREATE TABLE dispatch.delivery_batches (
  id                uuid PRIMARY KEY,
  code              text NOT NULL UNIQUE,
  zone_id           uuid NOT NULL REFERENCES admin_cfg.delivery_zones(id),
  status            text NOT NULL
                    CHECK (status IN ('DRAFT','LOCKED','COMPLETED','CANCELLED')),
  planned_window    tstzrange,
  order_count       integer NOT NULL DEFAULT 0 CHECK (order_count BETWEEN 0 AND 50),
  total_weight_g    bigint NOT NULL DEFAULT 0 CHECK (total_weight_g >= 0),
  total_volume_cm3  bigint NOT NULL DEFAULT 0 CHECK (total_volume_cm3 >= 0),
  created_by        uuid NOT NULL,
  locked_at         timestamptz,
  locked_by         uuid,
  reopen_count      integer NOT NULL DEFAULT 0,
  cancel_reason     text,
  created_at        timestamptz NOT NULL,
  updated_at        timestamptz NOT NULL,
  version           bigint NOT NULL DEFAULT 0,
  CONSTRAINT ck_batches_locked
    CHECK (status NOT IN ('LOCKED','COMPLETED')
           OR (locked_at IS NOT NULL AND planned_window IS NOT NULL AND order_count > 0)),
  CONSTRAINT ck_batches_cancel_reason
    CHECK (status <> 'CANCELLED' OR cancel_reason IS NOT NULL)
);
CREATE INDEX ix_batches_zone_status ON dispatch.delivery_batches (zone_id, status, created_at DESC);

CREATE TABLE dispatch.batch_orders (
  id              uuid PRIMARY KEY,
  batch_id        uuid NOT NULL REFERENCES dispatch.delivery_batches(id),
  order_id        uuid NOT NULL REFERENCES ordering.orders(id),
  added_at        timestamptz NOT NULL,
  added_by        uuid NOT NULL,
  released_at     timestamptz,
  release_reason  text CHECK (release_reason IN
                    ('REMOVED','BATCH_CANCELLED','ORDER_CANCELLED','BATCH_COMPLETED')),
  CONSTRAINT ck_batch_orders_release
    CHECK ((released_at IS NULL) = (release_reason IS NULL))
);
CREATE UNIQUE INDEX uq_batch_orders_active_order
  ON dispatch.batch_orders (order_id) WHERE released_at IS NULL;
CREATE UNIQUE INDEX uq_batch_orders_active_pair
  ON dispatch.batch_orders (batch_id, order_id) WHERE released_at IS NULL;
CREATE INDEX ix_batch_orders_batch_active
  ON dispatch.batch_orders (batch_id) WHERE released_at IS NULL;
