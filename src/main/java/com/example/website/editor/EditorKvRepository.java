package com.example.website.editor;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 直接操作 editor_kv 表。使用 JdbcTemplate 而非 JPA，因为这是纯粹的 KV 快照，
 * key/value 都是动态 JSON，没有实体映射的必要。
 */
@Repository
@RequiredArgsConstructor
public class EditorKvRepository {

    private final JdbcTemplate jdbc;

    public Map<String, String> findAllAsMap() {
        Map<String, String> out = new LinkedHashMap<>();
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT k, v FROM editor_kv");
        for (Map<String, Object> r : rows) {
            Object k = r.get("k");
            Object v = r.get("v");
            if (k != null) out.put(k.toString(), v == null ? "" : v.toString());
        }
        return out;
    }

    /** MariaDB  upsert：INSERT ... ON DUPLICATE KEY UPDATE。 */
    public void saveAll(Map<String, String> changes) {
        if (changes == null || changes.isEmpty()) return;
        String sql = "INSERT INTO editor_kv (k, v) VALUES (?, ?) " +
                "ON DUPLICATE KEY UPDATE v = VALUES(v)";
        jdbc.batchUpdate(sql, changes.entrySet(), changes.size(), (ps, entry) -> {
            ps.setString(1, entry.getKey());
            ps.setString(2, entry.getValue());
        });
    }

    public void deleteByPrefix(String prefix) {
        jdbc.update("DELETE FROM editor_kv WHERE k LIKE ?", prefix + "%");
    }
}
