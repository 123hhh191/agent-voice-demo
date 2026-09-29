CREATE TABLE agent_session (
  id CHAR(36) PRIMARY KEY,
  user_id VARCHAR(128) NOT NULL,
  status VARCHAR(16) NOT NULL,
  active_run_id CHAR(36) NULL,
  context_version BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  KEY idx_session_owner (user_id, created_at),
  CONSTRAINT chk_session_status CHECK (status IN ('ACTIVE','CLOSED'))
);

CREATE TABLE agent_message (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  session_id CHAR(36) NOT NULL,
  run_id CHAR(36) NOT NULL,
  seq BIGINT NOT NULL,
  role VARCHAR(16) NOT NULL,
  content LONGTEXT NULL,
  tool_call_id VARCHAR(128) NULL,
  tool_calls_json JSON NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY uk_message_seq (session_id, seq),
  KEY idx_message_run (run_id),
  CONSTRAINT fk_message_session FOREIGN KEY (session_id) REFERENCES agent_session(id),
  CONSTRAINT chk_message_role CHECK (role IN ('system','user','assistant','tool'))
);

CREATE TABLE agent_summary (
  session_id CHAR(36) NOT NULL,
  version BIGINT NOT NULL,
  covered_through_seq BIGINT NOT NULL,
  summary_json JSON NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (session_id, version),
  UNIQUE KEY uk_summary_coverage (session_id, covered_through_seq),
  CONSTRAINT fk_summary_session FOREIGN KEY (session_id) REFERENCES agent_session(id)
);

CREATE TABLE agent_run (
  id CHAR(36) PRIMARY KEY,
  session_id CHAR(36) NOT NULL,
  request_id VARCHAR(128) NOT NULL,
  status VARCHAR(16) NOT NULL,
  loop_count INT NOT NULL DEFAULT 0,
  answer LONGTEXT NULL,
  error_code VARCHAR(64) NULL,
  trace_id VARCHAR(64) NOT NULL,
  deadline_at TIMESTAMP(6) NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  UNIQUE KEY uk_run_request (session_id, request_id),
  KEY idx_run_owner (session_id, created_at),
  CONSTRAINT fk_run_session FOREIGN KEY (session_id) REFERENCES agent_session(id),
  CONSTRAINT chk_run_status CHECK (status IN ('RUNNING','SUCCEEDED','FAILED'))
);

ALTER TABLE agent_message
  ADD CONSTRAINT fk_message_run FOREIGN KEY (run_id) REFERENCES agent_run(id);

CREATE TABLE todo_item (
  id CHAR(36) PRIMARY KEY,
  user_id VARCHAR(128) NOT NULL,
  session_id CHAR(36) NOT NULL,
  title VARCHAR(240) NOT NULL,
  due_at TIMESTAMP(6) NULL,
  status VARCHAR(16) NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  operation_id VARCHAR(128) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  UNIQUE KEY uk_todo_operation (operation_id),
  KEY idx_todo_scope (user_id, session_id, status),
  CONSTRAINT fk_todo_session FOREIGN KEY (session_id) REFERENCES agent_session(id),
  CONSTRAINT chk_todo_status CHECK (status IN ('PENDING','COMPLETED'))
);
