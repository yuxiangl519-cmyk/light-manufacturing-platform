-- Preserve the documented case-sensitive identifier contract without changing applied migrations.
ALTER TABLE material MODIFY code VARCHAR(50) COLLATE utf8mb4_bin NOT NULL;
ALTER TABLE customer MODIFY code VARCHAR(50) COLLATE utf8mb4_bin NOT NULL;
ALTER TABLE supplier MODIFY code VARCHAR(50) COLLATE utf8mb4_bin NOT NULL;
ALTER TABLE warehouse MODIFY code VARCHAR(50) COLLATE utf8mb4_bin NOT NULL;
ALTER TABLE bom MODIFY code VARCHAR(50) COLLATE utf8mb4_bin NOT NULL;
CREATE INDEX ix_production_product_time ON production_order(product_material_id,created_at,id);
CREATE INDEX ix_stock_balance_material ON stock_balance(material_id,warehouse_id);
