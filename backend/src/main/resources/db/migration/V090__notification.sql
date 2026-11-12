CREATE TABLE notif.notifications (
  id                 uuid PRIMARY KEY,
  recipient_user_id  uuid NOT NULL REFERENCES iam.users(id),
  channel            text NOT NULL CHECK (channel IN ('IN_APP','EMAIL')),
  template_code      text NOT NULL,
  source_event_id    uuid NOT NULL,
  related_type       text,
  related_id         uuid,
  title              text NOT NULL,
  body               text NOT NULL,
  status             text NOT NULL CHECK (status IN ('PENDING','SENT','FAILED','READ')),
  attempts           integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
  next_attempt_at    timestamptz,
  last_error_code    text,
  created_at         timestamptz NOT NULL,
  sent_at            timestamptz,
  read_at            timestamptz,
  CONSTRAINT uq_notifications_dedup
    UNIQUE (source_event_id, recipient_user_id, channel, template_code),
  CONSTRAINT ck_notifications_read CHECK (status <> 'READ' OR (channel = 'IN_APP' AND read_at IS NOT NULL))
);
CREATE INDEX ix_notifications_inbox ON notif.notifications (recipient_user_id, created_at DESC)
  WHERE channel = 'IN_APP';
CREATE INDEX ix_notifications_unread ON notif.notifications (recipient_user_id)
  WHERE channel = 'IN_APP' AND status <> 'READ';
CREATE INDEX ix_notifications_retry ON notif.notifications (next_attempt_at)
  WHERE status IN ('PENDING','FAILED');
