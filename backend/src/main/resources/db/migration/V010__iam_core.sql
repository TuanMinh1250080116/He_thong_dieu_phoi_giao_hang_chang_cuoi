
CREATE TABLE iam.users (
  id                  uuid PRIMARY KEY,
  email               citext NOT NULL UNIQUE,
  display_name        text NOT NULL,
  password_hash       text NOT NULL,
  status              text NOT NULL DEFAULT 'ACTIVE'
                      CHECK (status IN ('ACTIVE','DISABLED','LOCKED')),
  failed_login_count  integer NOT NULL DEFAULT 0 CHECK (failed_login_count >= 0),
  locked_until        timestamptz,
  created_at          timestamptz NOT NULL,
  updated_at          timestamptz NOT NULL,
  version             bigint NOT NULL DEFAULT 0
);

CREATE TABLE iam.roles (
  id    uuid PRIMARY KEY,
  code  text NOT NULL UNIQUE
);

CREATE TABLE iam.permissions (
  id    uuid PRIMARY KEY,
  code  text NOT NULL UNIQUE
);

CREATE TABLE iam.role_permissions (
  role_id        uuid NOT NULL REFERENCES iam.roles(id),
  permission_id  uuid NOT NULL REFERENCES iam.permissions(id),
  PRIMARY KEY (role_id, permission_id)
);

CREATE TABLE iam.user_roles (
  user_id     uuid NOT NULL REFERENCES iam.users(id),
  role_id     uuid NOT NULL REFERENCES iam.roles(id),
  granted_by  uuid,
  granted_at  timestamptz NOT NULL,
  PRIMARY KEY (user_id, role_id)
);

CREATE TABLE iam.user_scopes (
  user_id     uuid NOT NULL REFERENCES iam.users(id),
  zone_id     uuid NOT NULL,
  granted_by  uuid,
  granted_at  timestamptz NOT NULL,
  PRIMARY KEY (user_id, zone_id)
);

CREATE TABLE iam.refresh_sessions (
  id               uuid PRIMARY KEY,
  user_id          uuid NOT NULL REFERENCES iam.users(id),
  token_hash       text NOT NULL UNIQUE,
  issued_at        timestamptz NOT NULL,
  expires_at       timestamptz NOT NULL,
  revoked_at       timestamptz,
  user_agent_hash  text,
  CONSTRAINT ck_refresh_sessions_expiry CHECK (expires_at > issued_at)
);
CREATE INDEX ix_refresh_sessions_user_active
  ON iam.refresh_sessions (user_id) WHERE revoked_at IS NULL;
