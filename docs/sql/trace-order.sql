SELECT o.code AS don, o.status AS trang_thai_don,
       b.code AS lo, b.status AS trang_thai_lo,
       dd.status AS quyet_dinh,
       dr.code AS tai_xe, a.status AS phan_cong,
       dl.code AS chuyen, dl.status AS trang_thai_chuyen,
       s.status AS diem_giao,
       p.captured_at AS giao_luc
FROM ordering.orders o
LEFT JOIN dispatch.batch_orders bo ON bo.order_id = o.id AND bo.released_at IS NULL
LEFT JOIN dispatch.delivery_batches b ON b.id = bo.batch_id
LEFT JOIN dispatch.dispatch_decisions dd ON dd.batch_id = b.id AND dd.status <> 'CANCELLED'
LEFT JOIN dispatch.driver_assignments a ON a.decision_id = dd.id AND a.is_current
LEFT JOIN fleet.drivers dr ON dr.id = a.driver_id
LEFT JOIN delivery.deliveries dl ON dl.decision_id = dd.id AND dl.status <> 'CANCELLED'
LEFT JOIN delivery.delivery_stops s ON s.delivery_id = dl.id AND s.order_id = o.id
LEFT JOIN proof.proofs_of_delivery p ON p.stop_id = s.id
WHERE o.code = 'ORD-000012';
