CREATE SEQUENCE fleet.driver_code_seq START 1;
CREATE INDEX ix_drivers_vehicle_zone ON fleet.driver_zones (zone_id, driver_id);
