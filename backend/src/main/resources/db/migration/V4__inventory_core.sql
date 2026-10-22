CREATE TABLE stock_document (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 document_no VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
 kind VARCHAR(30) NOT NULL,direction VARCHAR(3) NOT NULL,status VARCHAR(12) NOT NULL,
 source_type VARCHAR(30) NOT NULL,source_id BIGINT NOT NULL,source_no VARCHAR(64) NOT NULL,
 warehouse_id BIGINT NOT NULL,warehouse_name VARCHAR(120) NOT NULL,
 fingerprint CHAR(64) COLLATE utf8mb4_bin NOT NULL,
 executed_by BIGINT NULL,executed_by_name VARCHAR(80) NULL,executed_at DATETIME(3) NULL,
 created_at DATETIME(3) NOT NULL,updated_at DATETIME(3) NOT NULL,
 created_by BIGINT NOT NULL,updated_by BIGINT NOT NULL,version BIGINT NOT NULL DEFAULT 0,
 CONSTRAINT uq_stock_document_no UNIQUE(document_no),
 CONSTRAINT uq_stock_source UNIQUE(kind,source_type,source_id),
 CONSTRAINT ck_stock_source CHECK(source_id>0),
 CONSTRAINT ck_stock_document_state CHECK((status='PENDING' AND executed_at IS NULL AND executed_by IS NULL AND executed_by_name IS NULL) OR (status='EXECUTED' AND executed_at IS NOT NULL AND executed_by IS NOT NULL AND executed_by_name IS NOT NULL)),
 CONSTRAINT ck_stock_document_kind CHECK(
 (kind='PURCHASE_RECEIPT' AND direction='IN' AND source_type='PURCHASE_ORDER') OR
 (kind='PRODUCTION_ISSUE' AND direction='OUT' AND source_type='PRODUCTION_ORDER') OR
 (kind='PRODUCTION_RECEIPT' AND direction='IN' AND source_type='PRODUCTION_ORDER') OR
 (kind='SALES_SHIPMENT' AND direction='OUT' AND source_type='SALES_ORDER')),
 CONSTRAINT ck_stock_document_version CHECK(version>=0),
 KEY ix_stock_document_time(created_at,id),KEY ix_stock_document_filter(warehouse_id,status,kind,id),
 FOREIGN KEY(warehouse_id) REFERENCES warehouse(id),FOREIGN KEY(executed_by) REFERENCES sys_user(id),
 FOREIGN KEY(created_by) REFERENCES sys_user(id),FOREIGN KEY(updated_by) REFERENCES sys_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE stock_document_item (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,stock_document_id BIGINT NOT NULL,
 line_no INT NOT NULL,source_line_id BIGINT NOT NULL,material_id BIGINT NOT NULL,
 material_code VARCHAR(50) NOT NULL,material_name VARCHAR(120) NOT NULL,unit VARCHAR(10) NOT NULL,
 quantity DECIMAL(12,0) NOT NULL,
 CONSTRAINT uq_stock_document_line UNIQUE(stock_document_id,line_no),
 CONSTRAINT uq_stock_source_line UNIQUE(stock_document_id,source_line_id),
 CONSTRAINT uq_stock_document_material UNIQUE(stock_document_id,material_id),
 CONSTRAINT ck_stock_item CHECK(quantity>0 AND line_no>0 AND source_line_id>0),
 FOREIGN KEY(stock_document_id) REFERENCES stock_document(id),FOREIGN KEY(material_id) REFERENCES material(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE stock_balance (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,warehouse_id BIGINT NOT NULL,material_id BIGINT NOT NULL,
 quantity DECIMAL(12,0) NOT NULL DEFAULT 0,version BIGINT NOT NULL DEFAULT 0,updated_at DATETIME(3) NOT NULL,
 CONSTRAINT uq_stock_balance UNIQUE(warehouse_id,material_id),
 CONSTRAINT ck_stock_balance CHECK(quantity>=0 AND version>=0),
 FOREIGN KEY(warehouse_id) REFERENCES warehouse(id),FOREIGN KEY(material_id) REFERENCES material(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE stock_ledger (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,balance_id BIGINT NOT NULL,
 warehouse_id BIGINT NOT NULL,material_id BIGINT NOT NULL,
 stock_document_id BIGINT NOT NULL,stock_document_item_id BIGINT NOT NULL,
 source_type VARCHAR(30) NOT NULL,source_id BIGINT NOT NULL,source_no VARCHAR(64) NOT NULL,source_line_id BIGINT NOT NULL,
 kind VARCHAR(30) NOT NULL,change_quantity DECIMAL(12,0) NOT NULL,
 before_quantity DECIMAL(12,0) NOT NULL,after_quantity DECIMAL(12,0) NOT NULL,
 operator_id BIGINT NOT NULL,operator_name VARCHAR(80) NOT NULL,occurred_at DATETIME(3) NOT NULL,
 CONSTRAINT uq_ledger_document_item UNIQUE(stock_document_item_id),
 CONSTRAINT ck_stock_ledger CHECK(change_quantity<>0 AND before_quantity>=0 AND after_quantity>=0 AND before_quantity+change_quantity=after_quantity),
 KEY ix_ledger_balance(balance_id,id),KEY ix_ledger_material(warehouse_id,material_id,id),
 KEY ix_ledger_source(source_type,source_id,source_line_id),KEY ix_ledger_time(occurred_at,id),
 FOREIGN KEY(balance_id) REFERENCES stock_balance(id),FOREIGN KEY(warehouse_id) REFERENCES warehouse(id),
 FOREIGN KEY(material_id) REFERENCES material(id),FOREIGN KEY(stock_document_id) REFERENCES stock_document(id),
 FOREIGN KEY(stock_document_item_id) REFERENCES stock_document_item(id),FOREIGN KEY(operator_id) REFERENCES sys_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
