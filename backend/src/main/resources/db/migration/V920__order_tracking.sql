CREATE TABLE ordering.order_tracking_events (
  id                uuid PRIMARY KEY,
  order_id          uuid NOT NULL REFERENCES ordering.orders(id),
  milestone         text NOT NULL,
  occurred_at       timestamptz NOT NULL,
  source_event_id   uuid NOT NULL,
  customer_visible  boolean NOT NULL DEFAULT true,
  public_note       text,
  CONSTRAINT uq_tracking_source UNIQUE (source_event_id, order_id, milestone)
);
CREATE INDEX ix_tracking_order_time ON ordering.order_tracking_events (order_id, occurred_at);

CREATE TRIGGER trg_order_tracking_append_only
  BEFORE UPDATE OR DELETE ON ordering.order_tracking_events
  FOR EACH ROW EXECUTE FUNCTION platform.forbid_modification();

INSERT INTO ordering.order_tracking_events (id, order_id, milestone, occurred_at, source_event_id, customer_visible, public_note)
SELECT gen_random_uuid(), o.id, 'ORDER_RECEIVED', o.created_at, gen_random_uuid(), true, 'Đơn hàng đã được tiếp nhận'
FROM ordering.orders o WHERE o.status <> 'DRAFT';