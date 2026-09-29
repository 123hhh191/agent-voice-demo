CREATE TABLE device_registry (
  device_id VARCHAR(96) NOT NULL PRIMARY KEY,
  sn VARCHAR(128) NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  credential_ref VARCHAR(96) NOT NULL,
  active_turn_id CHAR(36) NULL,
  owner_version BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY uk_device_sn (sn)
);

CREATE TABLE device_challenge (
  challenge_id CHAR(36) NOT NULL PRIMARY KEY,
  device_id VARCHAR(96) NOT NULL,
  nonce_hash CHAR(64) NOT NULL,
  expires_at TIMESTAMP(6) NOT NULL,
  consumed_at TIMESTAMP(6) NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  KEY idx_challenge_device (device_id, expires_at),
  CONSTRAINT fk_challenge_device FOREIGN KEY (device_id) REFERENCES device_registry(device_id)
);

CREATE TABLE device_access_token (
  token_hash CHAR(64) NOT NULL PRIMARY KEY,
  device_id VARCHAR(96) NOT NULL,
  expires_at TIMESTAMP(6) NOT NULL,
  revoked_at TIMESTAMP(6) NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  KEY idx_device_token_owner (device_id, expires_at),
  CONSTRAINT fk_token_device FOREIGN KEY (device_id) REFERENCES device_registry(device_id)
);

CREATE TABLE voice_turn (
  turn_id CHAR(36) NOT NULL PRIMARY KEY,
  device_id VARCHAR(96) NOT NULL,
  start_request_id VARCHAR(128) NOT NULL,
  state VARCHAR(24) NOT NULL,
  format_json JSON NOT NULL,
  attempt_id CHAR(36) NOT NULL,
  resume_token_hash CHAR(64) NOT NULL,
  owner_version BIGINT NOT NULL DEFAULT 1,
  started_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  deadline_at TIMESTAMP(6) NOT NULL,
  disconnected_at TIMESTAMP(6) NULL,
  final_text TEXT NULL,
  final_version BIGINT NOT NULL DEFAULT 0,
  completed_at TIMESTAMP(6) NULL,
  UNIQUE KEY uk_turn_start_request (device_id, start_request_id),
  KEY idx_turn_device_state (device_id, state),
  CONSTRAINT fk_turn_device FOREIGN KEY (device_id) REFERENCES device_registry(device_id),
  CONSTRAINT chk_voice_turn_state CHECK (state IN ('RECORDING','PROCESSING','FINAL','FAILED','CANCELLED'))
);

ALTER TABLE device_registry
  ADD CONSTRAINT fk_device_active_turn FOREIGN KEY (active_turn_id) REFERENCES voice_turn(turn_id);
