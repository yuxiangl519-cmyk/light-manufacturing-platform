CREATE TABLE purchase_order (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 created_at DATETIME(3) NOT NULL, updated_at DATETIME(3) NOT NULL,
 created_by BIGINT NOT NULL, updated_by BIGINT NOT NULL, version BIGINT NOT NULL DEFAULT 0,
 order_no VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
 supplier_id BIGINT NOT NULL, partner_name VARCHAR(120) NOT NULL,
 warehouse_id BIGINT NOT NULL, warehouse_name VARCHAR(120) NOT NULL,
 created_by_name VARCHAR(80) NOT NULL,
 status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
 total_amount DECIMAL(18,2) NOT NULL, remark VARCHAR(500) NOT NULL DEFAULT '',
 confirmed_at DATETIME(3) NULL, received_at DATETIME(3) NULL, cancelled_at DATETIME(3) NULL, deleted_at DATETIME(3) NULL,
 UNIQUE KEY uq_purchase_no(order_no),
 KEY ix_purchase_status(status,created_at,id), KEY ix_purchase_partner(supplier_id,created_at,id),
 KEY ix_purchase_warehouse(warehouse_id,created_at,id),
 FOREIGN KEY (supplier_id) REFERENCES supplier(id), FOREIGN KEY (warehouse_id) REFERENCES warehouse(id),
 FOREIGN KEY (created_by) REFERENCES sys_user(id), FOREIGN KEY (updated_by) REFERENCES sys_user(id),
 CHECK(version>=0), CHECK(total_amount>=0), CHECK(status IN ('DRAFT','CONFIRMED','RECEIVED','CANCELLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE purchase_order_item (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, purchase_order_id BIGINT NOT NULL,
 line_no INT NOT NULL, material_id BIGINT NOT NULL, material_code VARCHAR(50) NOT NULL,
 material_name VARCHAR(120) NOT NULL, unit VARCHAR(10) NOT NULL,
 quantity DECIMAL(12,0) NOT NULL, unit_price DECIMAL(18,2) NOT NULL, line_amount DECIMAL(18,2) NOT NULL,
 UNIQUE KEY uq_purchase_item_material(purchase_order_id,material_id), UNIQUE KEY uq_purchase_item_line(purchase_order_id,line_no),
 FOREIGN KEY (purchase_order_id) REFERENCES purchase_order(id), FOREIGN KEY (material_id) REFERENCES material(id),
 CHECK(line_no>0), CHECK(quantity>0), CHECK(unit_price>=0), CHECK(line_amount>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE sales_order (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 created_at DATETIME(3) NOT NULL, updated_at DATETIME(3) NOT NULL,
 created_by BIGINT NOT NULL, updated_by BIGINT NOT NULL, version BIGINT NOT NULL DEFAULT 0,
 order_no VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
 customer_id BIGINT NOT NULL, partner_name VARCHAR(120) NOT NULL,
 warehouse_id BIGINT NOT NULL, warehouse_name VARCHAR(120) NOT NULL,
 created_by_name VARCHAR(80) NOT NULL,
 status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
 total_amount DECIMAL(18,2) NOT NULL, remark VARCHAR(500) NOT NULL DEFAULT '',
 confirmed_at DATETIME(3) NULL, shipped_at DATETIME(3) NULL, cancelled_at DATETIME(3) NULL, deleted_at DATETIME(3) NULL,
 UNIQUE KEY uq_sales_no(order_no),
 KEY ix_sales_status(status,created_at,id), KEY ix_sales_partner(customer_id,created_at,id),
 KEY ix_sales_warehouse(warehouse_id,created_at,id),
 FOREIGN KEY (customer_id) REFERENCES customer(id), FOREIGN KEY (warehouse_id) REFERENCES warehouse(id),
 FOREIGN KEY (created_by) REFERENCES sys_user(id), FOREIGN KEY (updated_by) REFERENCES sys_user(id),
 CHECK(version>=0), CHECK(total_amount>=0), CHECK(status IN ('DRAFT','CONFIRMED','SHIPPED','CANCELLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE sales_order_item (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, sales_order_id BIGINT NOT NULL,
 line_no INT NOT NULL, material_id BIGINT NOT NULL, material_code VARCHAR(50) NOT NULL,
 material_name VARCHAR(120) NOT NULL, unit VARCHAR(10) NOT NULL,
 quantity DECIMAL(12,0) NOT NULL, unit_price DECIMAL(18,2) NOT NULL, line_amount DECIMAL(18,2) NOT NULL,
 UNIQUE KEY uq_sales_item_material(sales_order_id,material_id), UNIQUE KEY uq_sales_item_line(sales_order_id,line_no),
 FOREIGN KEY (sales_order_id) REFERENCES sales_order(id), FOREIGN KEY (material_id) REFERENCES material(id),
 CHECK(line_no>0), CHECK(quantity>0), CHECK(unit_price>=0), CHECK(line_amount>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
