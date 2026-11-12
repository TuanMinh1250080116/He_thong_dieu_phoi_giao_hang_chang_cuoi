ALTER TABLE dispatch.driver_assignments DROP CONSTRAINT driver_assignments_superseded_by_fkey;
ALTER TABLE dispatch.driver_assignments
  ADD CONSTRAINT driver_assignments_superseded_by_fkey
  FOREIGN KEY (superseded_by) REFERENCES dispatch.driver_assignments(id)
  DEFERRABLE INITIALLY DEFERRED;
