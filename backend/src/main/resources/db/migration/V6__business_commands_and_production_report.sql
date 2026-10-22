CREATE TABLE command_record (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,resource_type VARCHAR(30) NOT NULL,
 resource_id BIGINT NOT NULL,action VARCHAR(20) NOT NULL,actor_id BIGINT NOT NULL,
 committed_at DATETIME(3) NOT NULL,trace_id VARCHAR(64) NOT NULL,
 CONSTRAINT uq_business_command UNIQUE(resource_type,resource_id,action),
 CONSTRAINT ck_command_resource CHECK(resource_id>0 AND resource_type IN('PURCHASE_ORDER','PRODUCTION_ORDER','SALES_ORDER','STOCK_DOCUMENT')),
 CONSTRAINT ck_command_action CHECK(action IN('CONFIRM','RECEIVE','ISSUE','REPORT','EXECUTE','SHIP','CANCEL')),
 KEY ix_command_time(committed_at),FOREIGN KEY(actor_id) REFERENCES sys_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE production_report (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,production_order_id BIGINT NOT NULL,
 reported_quantity DECIMAL(12,0) NOT NULL,reported_by BIGINT NOT NULL,
 reported_by_name VARCHAR(80) NOT NULL,reported_at DATETIME(3) NOT NULL,
 CONSTRAINT uq_report_order UNIQUE(production_order_id),
 CONSTRAINT ck_report_quantity CHECK(reported_quantity>0),
 FOREIGN KEY(production_order_id) REFERENCES production_order(id),
 FOREIGN KEY(reported_by) REFERENCES sys_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
