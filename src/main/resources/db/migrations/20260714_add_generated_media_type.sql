ALTER TABLE generated_image
    ADD COLUMN media_type VARCHAR(20) NOT NULL DEFAULT 'image' AFTER image_data;

CREATE INDEX idx_gen_img_media_type ON generated_image (media_type, created_at, id);
