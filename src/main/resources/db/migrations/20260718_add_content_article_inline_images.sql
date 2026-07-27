ALTER TABLE content_article
    ADD COLUMN inline_images_json LONGTEXT NULL AFTER cover_image_url;
