CREATE TABLE sys_user (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 username VARCHAR(50) COLLATE utf8mb4_bin NOT NULL,
 password_hash VARCHAR(100) NOT NULL, display_name VARCHAR(80) NOT NULL,
 enabled BOOLEAN NOT NULL DEFAULT TRUE, auth_version BIGINT NOT NULL DEFAULT 0,
 created_at DATETIME(3) NOT NULL,updated_at DATETIME(3) NOT NULL,
 created_by BIGINT NULL,updated_by BIGINT NULL,version BIGINT NOT NULL DEFAULT 0,
 CONSTRAINT uq_user_username UNIQUE(username),KEY ix_user_enabled(enabled),
 CONSTRAINT ck_user_version CHECK(version>=0 AND auth_version>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE sys_role (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,code VARCHAR(40) COLLATE utf8mb4_bin NOT NULL,
 name VARCHAR(80) NOT NULL,description VARCHAR(255) NOT NULL,builtin BOOLEAN NOT NULL DEFAULT FALSE,
 created_at DATETIME(3) NOT NULL,updated_at DATETIME(3) NOT NULL,created_by BIGINT NULL,updated_by BIGINT NULL,version BIGINT NOT NULL DEFAULT 0,
 CONSTRAINT uq_role_code UNIQUE(code),CONSTRAINT ck_role_version CHECK(version>=0),
 FOREIGN KEY(created_by) REFERENCES sys_user(id),FOREIGN KEY(updated_by) REFERENCES sys_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
ALTER TABLE sys_user ADD FOREIGN KEY(created_by) REFERENCES sys_user(id),ADD FOREIGN KEY(updated_by) REFERENCES sys_user(id);
CREATE TABLE sys_permission (
 code VARCHAR(80) COLLATE utf8mb4_bin NOT NULL PRIMARY KEY,name VARCHAR(80) NOT NULL,module VARCHAR(30) NOT NULL,sort_order INT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE sys_user_role (
 user_id BIGINT NOT NULL,role_id BIGINT NOT NULL,PRIMARY KEY(user_id,role_id),KEY ix_role_user(role_id,user_id),
 FOREIGN KEY(user_id) REFERENCES sys_user(id),FOREIGN KEY(role_id) REFERENCES sys_role(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE sys_role_permission (
 role_id BIGINT NOT NULL,permission_code VARCHAR(80) COLLATE utf8mb4_bin NOT NULL,PRIMARY KEY(role_id,permission_code),KEY ix_permission_role(permission_code,role_id),
 FOREIGN KEY(role_id) REFERENCES sys_role(id),FOREIGN KEY(permission_code) REFERENCES sys_permission(code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE operation_log (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,actor_id BIGINT NULL,actor_name VARCHAR(80) NOT NULL,module VARCHAR(30) NOT NULL,action VARCHAR(40) NOT NULL,
 resource_type VARCHAR(30) NULL,resource_id BIGINT NULL,resource_no VARCHAR(64) NULL,success BOOLEAN NOT NULL,error_code VARCHAR(64) NULL,
 summary VARCHAR(500) NOT NULL,trace_id VARCHAR(64) NOT NULL,client_ip VARCHAR(64) NOT NULL,occurred_at DATETIME(3) NOT NULL,
 KEY ix_log_time(occurred_at,id),KEY ix_log_actor(actor_id,occurred_at),KEY ix_log_resource(resource_type,resource_id),FOREIGN KEY(actor_id) REFERENCES sys_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
INSERT INTO sys_permission(code,name,module,sort_order) VALUES
 ('system:user:read','查看用户','system',1),('system:user:write','维护用户','system',2),
 ('system:role:read','查看角色','system',3),('system:role:write','维护角色','system',4),
 ('system:log:read','查看日志','system',5),('system:api-docs:read','查看接口文档','system',6),
 ('master:material:read','查看物料','master',7),('master:material:write','维护物料','master',8),
 ('master:customer:read','查看客户','master',9),('master:customer:write','维护客户','master',10),
 ('master:supplier:read','查看供应商','master',11),('master:supplier:write','维护供应商','master',12),
 ('master:warehouse:read','查看仓库','master',13),('master:warehouse:write','维护仓库','master',14),
 ('erp:purchase:read','查看采购','erp',15),('erp:purchase:write','维护采购','erp',16),('erp:purchase:confirm','确认采购','erp',17),('erp:purchase:receive','采购收货','erp',18),
 ('erp:sales:read','查看销售','erp',19),('erp:sales:write','维护销售','erp',20),('erp:sales:confirm','确认销售','erp',21),('erp:sales:ship','销售发货','erp',22),
 ('mes:bom:read','查看BOM','mes',23),('mes:bom:write','维护BOM','mes',24),
 ('mes:production:read','查看工单','mes',25),('mes:production:write','维护工单','mes',26),('mes:production:confirm','确认工单','mes',27),('mes:production:issue','工单领料','mes',28),('mes:production:report','工单报工','mes',29),
 ('wms:document:read','查看库存单','wms',30),('wms:stock:read','查看库存','wms',31),('wms:ledger:read','查看流水','wms',32),('wms:receipt:execute','生产入库','wms',33),('workbench:read','查看工作台','workbench',34);
INSERT INTO sys_role(code,name,description,builtin,created_at,updated_at) VALUES
 ('ADMIN','管理员','固定全部权限',TRUE,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3)),
 ('OPERATOR','业务操作员','业务操作角色',TRUE,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3)),
 ('VIEWER','只读用户','业务只读角色',TRUE,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3));
INSERT INTO sys_role_permission SELECT r.id,p.code FROM sys_role r CROSS JOIN sys_permission p WHERE r.code='ADMIN' OR (r.code='OPERATOR' AND p.module<>'system') OR (r.code='VIEWER' AND p.module<>'system' AND p.code LIKE '%:read');
