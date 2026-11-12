CREATE SEQUENCE delivery.delivery_code_seq START 1;

CREATE TABLE delivery.deliveries (
  id                     uuid PRIMARY KEY,
  code                   text NOT NULL UNIQUE,
  batch_id               uuid NOT NULL REFERENCES dispatch.delivery_batches(id),
  decision_id            uuid NOT NULL REFERENCES dispatch.dispatch_decisions(id),
  status                 text NOT NULL
                         CHECK (status IN ('ASSIGNED','ACCEPTED','AWAITING_REASSIGNMENT',
                                           'IN_PROGRESS','COMPLETED','CANCELLED')),
  current_assignment_id  uuid REFERENCES dispatch.driver_assignments(id),
  assigned_driver_id     uuid REFERENCES fleet.drivers(id),
  vehicle_id             uuid REFERENCES fleet.vehicles(id),
  pod_policy_version_id  uuid REFERENCES admin_cfg.pod_policy_versions(id),
  pod_policy_snapshot    jsonb,
  started_at             timestamptz,
  completed_at           timestamptz,
  cancelled_at           timestamptz,
  cancel_reason          text,
  created_at             timestamptz NOT NULL,
  updated_at             timestamptz NOT NULL,
  version                bigint NOT NULL DEFAULT 0,
  CONSTRAINT ck_deliveries_assignment
    CHECK (status NOT IN ('ASSIGNED','ACCEPTED','IN_PROGRESS')
           OR (current_assignment_id IS NOT NULL AND assigned_driver_id IS NOT NULL)),
  CONSTRAINT ck_deliveries_started
    CHECK (status NOT IN ('IN_PROGRESS','COMPLETED')
           OR (started_at IS NOT NULL AND pod_policy_snapshot IS NOT NULL)),
  CONSTRAINT ck_deliveries_completed CHECK ((status = 'COMPLETED') = (completed_at IS NOT NULL)),
  CONSTRAINT ck_deliveries_cancelled
    CHECK (status <> 'CANCELLED' OR (cancelled_at IS NOT NULL AND cancel_reason IS NOT NULL))
);
CREATE UNIQUE INDEX uq_deliveries_open_per_batch
  ON delivery.deliveries (batch_id) WHERE status <> 'CANCELLED';
CREATE INDEX ix_deliveries_driver_active
  ON delivery.deliveries (assigned_driver_id)
  WHERE status IN ('ASSIGNED','ACCEPTED','IN_PROGRESS');
CREATE INDEX ix_deliveries_status ON delivery.deliveries (status, updated_at DESC);

CREATE TABLE delivery.delivery_orders (
  delivery_id  uuid NOT NULL REFERENCES delivery.deliveries(id),
  order_id     uuid NOT NULL REFERENCES ordering.orders(id),
  PRIMARY KEY (delivery_id, order_id)
);
CREATE INDEX ix_delivery_orders_order ON delivery.delivery_orders (order_id);

CREATE TABLE delivery.delivery_stops (
  id                    uuid PRIMARY KEY,
  delivery_id           uuid NOT NULL REFERENCES delivery.deliveries(id),
  sequence_no           integer NOT NULL CHECK (sequence_no > 0),
  stop_type             text NOT NULL DEFAULT 'DROPOFF' CHECK (stop_type IN ('PICKUP','DROPOFF')),
  order_id              uuid NOT NULL REFERENCES ordering.orders(id),
  address_text          text NOT NULL,
  location              geography(Point, 4326) NOT NULL,
  window_start          timestamptz NOT NULL,
  window_end            timestamptz NOT NULL,
  status                text NOT NULL DEFAULT 'PENDING'
                        CHECK (status IN ('PENDING','IN_PROGRESS','COMPLETED','BLOCKED','SKIPPED')),
  arrived_at            timestamptz,
  completed_at          timestamptz,
  skip_resolution_note  text,
  FOREIGN KEY (delivery_id, order_id) REFERENCES delivery.delivery_orders (delivery_id, order_id),
  CONSTRAINT uq_stops_delivery_id UNIQUE (delivery_id, id),
  CONSTRAINT uq_stops_sequence UNIQUE (delivery_id, sequence_no) DEFERRABLE INITIALLY DEFERRED,
  CONSTRAINT uq_stops_order_type UNIQUE (delivery_id, order_id, stop_type),
  CONSTRAINT ck_stops_completed CHECK ((status = 'COMPLETED') = (completed_at IS NOT NULL)),
  CONSTRAINT ck_stops_window CHECK (window_end > window_start)
);
CREATE INDEX ix_stops_delivery ON delivery.delivery_stops (delivery_id, sequence_no);
CREATE INDEX ix_stops_order ON delivery.delivery_stops (order_id);

CREATE TABLE delivery.stop_transitions (
  id            uuid PRIMARY KEY,
  stop_id       uuid NOT NULL,
  delivery_id   uuid NOT NULL,
  from_status   text NOT NULL,
  to_status     text NOT NULL,
  action        text NOT NULL,
  actor_id      uuid NOT NULL,
  occurred_at   timestamptz NOT NULL,
  command_id    uuid,
  FOREIGN KEY (delivery_id, stop_id) REFERENCES delivery.delivery_stops (delivery_id, id)
);
CREATE INDEX ix_stop_transitions_stop ON delivery.stop_transitions (stop_id, occurred_at);
