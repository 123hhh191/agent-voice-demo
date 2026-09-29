ALTER TABLE agent_run
  DROP CHECK chk_run_status,
  MODIFY status VARCHAR(24) NOT NULL,
  ADD CONSTRAINT chk_run_status_v2 CHECK (status IN ('RUNNING','WAITING_TOOL','SUCCEEDED','FAILED','NEEDS_REVIEW'));

CREATE TABLE tool_invocation (
  id CHAR(36) PRIMARY KEY,
  run_id CHAR(36) NOT NULL,
  tool_call_id VARCHAR(128) NOT NULL,
  operation_id VARCHAR(128) NOT NULL,
  tool_name VARCHAR(128) NOT NULL,
  args_json JSON NOT NULL,
  args_hash CHAR(64) NOT NULL,
  state VARCHAR(16) NOT NULL,
  result_json JSON NULL,
  owner_version BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  UNIQUE KEY uk_invocation_operation (operation_id),
  UNIQUE KEY uk_invocation_call (run_id, tool_call_id),
  CONSTRAINT fk_invocation_run FOREIGN KEY (run_id) REFERENCES agent_run(id),
  CONSTRAINT chk_invocation_state CHECK (state IN ('PREPARED','RUNNING','SUCCEEDED','FAILED','UNKNOWN'))
);

CREATE TABLE async_task (
  id CHAR(36) PRIMARY KEY,
  invocation_id CHAR(36) NOT NULL,
  state VARCHAR(16) NOT NULL,
  attempt_count INT NOT NULL DEFAULT 0,
  next_retry_at TIMESTAMP(6) NULL,
  lease_until TIMESTAMP(6) NULL,
  owner_id VARCHAR(128) NULL,
  owner_version BIGINT NOT NULL DEFAULT 0,
  deadline_at TIMESTAMP(6) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  UNIQUE KEY uk_task_invocation (invocation_id),
  KEY idx_task_claim (state, next_retry_at, lease_until),
  CONSTRAINT fk_task_invocation FOREIGN KEY (invocation_id) REFERENCES tool_invocation(id),
  CONSTRAINT chk_task_state CHECK (state IN ('PENDING','RUNNING','SUCCEEDED','FAILED','UNKNOWN'))
);

CREATE TABLE outbox_event (
  id CHAR(36) PRIMARY KEY,
  aggregate_id CHAR(36) NOT NULL,
  type VARCHAR(64) NOT NULL,
  payload JSON NOT NULL,
  state VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  next_attempt_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  attempt_count INT NOT NULL DEFAULT 0,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  KEY idx_outbox_dispatch (state, next_attempt_at),
  CONSTRAINT chk_outbox_state CHECK (state IN ('PENDING','SENT'))
);

CREATE TABLE run_event (
  run_id CHAR(36) NOT NULL,
  event_seq BIGINT NOT NULL,
  event_id CHAR(36) NOT NULL,
  type VARCHAR(64) NOT NULL,
  payload JSON NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (run_id, event_seq),
  UNIQUE KEY uk_run_event_id (event_id),
  CONSTRAINT fk_run_event_run FOREIGN KEY (run_id) REFERENCES agent_run(id)
);

ALTER TABLE todo_item ADD COLUMN args_hash CHAR(64) NULL;
