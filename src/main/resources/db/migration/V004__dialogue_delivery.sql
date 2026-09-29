CREATE TABLE device_dialogue_session (
  device_id VARCHAR(96) NOT NULL PRIMARY KEY,
  session_id CHAR(36) NOT NULL UNIQUE,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT fk_dialogue_session_device FOREIGN KEY (device_id) REFERENCES device_registry(device_id)
);

CREATE TABLE dialogue_delivery (
  turn_id CHAR(36) NOT NULL PRIMARY KEY,
  device_id VARCHAR(96) NOT NULL,
  session_id CHAR(36) NOT NULL,
  request_id VARCHAR(160) NOT NULL,
  state VARCHAR(16) NOT NULL,
  final_text_hash CHAR(64) NOT NULL,
  response_text LONGTEXT NULL,
  error_code VARCHAR(64) NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  UNIQUE KEY uk_dialogue_delivery_request (request_id),
  CONSTRAINT fk_dialogue_delivery_turn FOREIGN KEY (turn_id) REFERENCES voice_turn(turn_id),
  CONSTRAINT fk_dialogue_delivery_session FOREIGN KEY (session_id) REFERENCES device_dialogue_session(session_id),
  CONSTRAINT chk_dialogue_delivery_state CHECK (state IN ('PENDING','SUCCEEDED','FAILED','UNKNOWN'))
);

CREATE TABLE device_command (
  command_id CHAR(36) NOT NULL PRIMARY KEY,
  device_id VARCHAR(96) NOT NULL,
  turn_id CHAR(36) NOT NULL,
  event_seq BIGINT NOT NULL,
  command_type VARCHAR(32) NOT NULL,
  payload_json JSON NOT NULL,
  deadline_at TIMESTAMP(6) NOT NULL,
  acked_at TIMESTAMP(6) NULL,
  playback_finished_at TIMESTAMP(6) NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  KEY idx_device_command_delivery (device_id, acked_at, deadline_at),
  CONSTRAINT fk_device_command_turn FOREIGN KEY (turn_id) REFERENCES voice_turn(turn_id),
  CONSTRAINT chk_device_command_type CHECK (command_type IN ('PLAY_AUDIO','STOP_PLAYBACK','START_LISTENING','ERROR'))
);
