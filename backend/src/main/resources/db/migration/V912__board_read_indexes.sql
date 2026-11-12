CREATE INDEX ix_decisions_status_batch ON dispatch.dispatch_decisions (status, batch_id) WHERE status <> 'CANCELLED';
CREATE INDEX ix_exceptions_open_delivery ON exc.delivery_exceptions (delivery_id) WHERE status IN ('OPEN','INVESTIGATING');
CREATE INDEX ix_deliveries_created ON delivery.deliveries (created_at DESC, id DESC);
CREATE INDEX ix_assignments_decision_seq ON dispatch.driver_assignments (decision_id, sequence_no DESC);
