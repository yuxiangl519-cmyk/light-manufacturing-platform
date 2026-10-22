CREATE TABLE ai_conversation (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 tenant_key VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
 user_id BIGINT NOT NULL,
 title VARCHAR(160) NOT NULL,
 status VARCHAR(24) NOT NULL DEFAULT 'IDLE',
 context_json TEXT NULL,
 run_id VARCHAR(64) NULL,
 created_at DATETIME(3) NOT NULL,
 updated_at DATETIME(3) NOT NULL,
 KEY ix_ai_owner(tenant_key,user_id,updated_at,id),
 FOREIGN KEY (user_id) REFERENCES sys_user(id),
 CHECK(status IN ('IDLE','RUNNING','CLARIFICATION','COMPLETED','ERROR','CANCELLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE ai_message (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 conversation_id BIGINT NOT NULL,
 role VARCHAR(16) NOT NULL,
 content TEXT NOT NULL,
 created_at DATETIME(3) NOT NULL,
 KEY ix_ai_message(conversation_id,id),
 FOREIGN KEY(conversation_id) REFERENCES ai_conversation(id),
 CHECK(role IN ('user','assistant'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
