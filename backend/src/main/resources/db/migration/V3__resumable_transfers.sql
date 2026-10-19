CREATE TABLE uploads (
  id UUID PRIMARY KEY,
  secret_hash VARCHAR(64) NOT NULL UNIQUE,
  name VARCHAR(240) NOT NULL,
  byte_size BIGINT NOT NULL CHECK (byte_size > 0),
  fingerprint VARCHAR(64) NOT NULL,
  uploaded_bytes BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
  max_downloads INTEGER NOT NULL CHECK (max_downloads BETWEEN 1 AND 10),
  password_hash VARCHAR(256),
  transfer_id UUID REFERENCES transfers(id),
  parts_purged BOOLEAN NOT NULL DEFAULT FALSE,
  CHECK (uploaded_bytes BETWEEN 0 AND byte_size)
);
CREATE INDEX uploads_expiration ON uploads(expires_at);
CREATE TABLE download_sessions (
  id UUID PRIMARY KEY,
  secret_hash VARCHAR(64) NOT NULL UNIQUE,
  transfer_id UUID NOT NULL REFERENCES transfers(id),
  start_before TIMESTAMP WITH TIME ZONE NOT NULL,
  expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
  started BOOLEAN NOT NULL DEFAULT FALSE,
  state VARCHAR(12) NOT NULL DEFAULT 'READY',
  sent_through BIGINT NOT NULL DEFAULT 0 CHECK (sent_through >= 0),
  active_lease UUID
);
CREATE INDEX download_sessions_expiration ON download_sessions(expires_at);
