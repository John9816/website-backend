ALTER TABLE kb_space ADD COLUMN doc_count INTEGER NOT NULL DEFAULT 0;

ALTER TABLE kb_doc ADD COLUMN summary TEXT;
ALTER TABLE kb_doc ADD COLUMN content_json TEXT;
ALTER TABLE kb_doc ADD COLUMN content_html TEXT;
ALTER TABLE kb_doc ADD COLUMN version_no INTEGER NOT NULL DEFAULT 1;

ALTER TABLE kb_doc_version ADD COLUMN version_no INTEGER NOT NULL DEFAULT 1;
ALTER TABLE kb_doc_version ADD COLUMN summary TEXT;
ALTER TABLE kb_doc_version ADD COLUMN content_json TEXT;
ALTER TABLE kb_doc_version ADD COLUMN content_html TEXT;
ALTER TABLE kb_doc_version ADD COLUMN editor_user_id INTEGER;
ALTER TABLE kb_doc_version ADD COLUMN change_note TEXT;

UPDATE kb_doc
SET content_html = content
WHERE content_html IS NULL AND content IS NOT NULL AND content <> '';

UPDATE kb_doc_version
SET content_html = content
WHERE content_html IS NULL AND content IS NOT NULL AND content <> '';

WITH ranked AS (
  SELECT id, ROW_NUMBER() OVER (PARTITION BY doc_id ORDER BY id) AS rn
  FROM kb_doc_version
)
UPDATE kb_doc_version
SET version_no = COALESCE((SELECT rn FROM ranked WHERE ranked.id = kb_doc_version.id), 1);

UPDATE kb_doc
SET version_no = COALESCE(
  (SELECT MAX(version_no) + 1 FROM kb_doc_version WHERE kb_doc_version.doc_id = kb_doc.id),
  1
);

UPDATE kb_space
SET doc_count = (
  SELECT COUNT(*)
  FROM kb_doc
  WHERE kb_doc.space_id = kb_space.id
);
