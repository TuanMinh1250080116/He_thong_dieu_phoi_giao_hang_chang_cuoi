ALTER TABLE iam.users ADD COLUMN username citext;
ALTER TABLE iam.users ADD CONSTRAINT ck_users_username CHECK (username IS NULL OR username ~ '^[a-z0-9._-]{3,32}$');
CREATE UNIQUE INDEX uq_users_username ON iam.users (username) WHERE username IS NOT NULL;
