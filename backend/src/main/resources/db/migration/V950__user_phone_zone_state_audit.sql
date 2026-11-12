ALTER TABLE iam.users ADD COLUMN phone text;
ALTER TABLE iam.users ADD CONSTRAINT ck_users_phone CHECK (phone IS NULL OR phone ~ '^\+?[0-9 ]{8,15}$');
