CREATE TABLE ai_quota_owner (
 tenant_key VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
 user_id BIGINT NOT NULL,
 PRIMARY KEY(tenant_key,user_id),
 FOREIGN KEY(user_id) REFERENCES sys_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE ai_call (
 run_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL PRIMARY KEY,
 tenant_key VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
 user_id BIGINT NOT NULL,
 conversation_id BIGINT NOT NULL,
 quota_day DATE NOT NULL,
 status VARCHAR(24) NOT NULL,
 started_at DATETIME(3) NOT NULL,
 finished_at DATETIME(3) NULL,
 duration_ms BIGINT NULL,
 tool_calls INT NOT NULL DEFAULT 0,
 model_calls INT NULL,
 input_tokens BIGINT NULL,
 output_tokens BIGINT NULL,
 KEY ix_ai_call_daily(tenant_key,user_id,quota_day),
 KEY ix_ai_call_recent(tenant_key,user_id,started_at),
 FOREIGN KEY(conversation_id) REFERENCES ai_conversation(id),
 FOREIGN KEY(user_id) REFERENCES sys_user(id),
 CHECK(status IN ('RUNNING','COMPLETED','CLARIFICATION','ERROR','CANCELLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
