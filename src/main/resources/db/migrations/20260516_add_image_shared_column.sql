ALTER TABLE generated_image
    ADD COLUMN is_shared TINYINT(1) NOT NULL DEFAULT 0 AFTER size;

CREATE INDEX idx_gen_img_shared ON generated_image (is_shared, created_at, id);
