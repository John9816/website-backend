package com.example.website.editor;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 学城编辑器 KV 存储层。
 *
 * <p>1:1 复刻 Cloudflare D1 版本 worker/context.js 的语义：全部集合以 JSON 快照存于单表
 * editor_kv(k, v)。业务代码通过 read/write 系列方法读写内存中的集合，请求结束由 flush() 统一落库。
 *
 * <p>并发策略：用 synchronized 保护单次请求的 load→modify→flush 临界区。编辑器是低并发
 * 单用户工具（原 D1 版本也是模块级单 CTX），粗粒度锁足以保证 read-modify-write 不丢更新。
 */
@Component
@RequiredArgsConstructor
public class EditorKvStore {

    private final EditorKvRepository repository;
    private final ObjectMapper mapper = new ObjectMapper();

    /** 集合名 -> 默认值工厂。与 context.js 的 COLLECTIONS / defaultFor 对齐。 */
    private static final List<String> COLLECTIONS = Arrays.asList(
            "docs", "users", "sessions", "prefs", "accessRequests",
            "notifications", "templates", "trash", "interactions", "visitors", "presence", "loginFails");

    private static final Set<String> OBJECT_COLLECTIONS = new HashSet<>(Arrays.asList(
            "sessions", "prefs", "interactions", "visitors", "presence", "loginFails"));

    private static final Set<String> CHUNKED = new HashSet<>(Arrays.asList("docs", "templates"));

    private Object defaultValue(String k) {
        if (OBJECT_COLLECTIONS.contains(k)) return new LinkedHashMap<String, Object>();
        if ("trash".equals(k)) {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("batches", new ArrayList<Object>());
            return t;
        }
        return new ArrayList<Object>();
    }

    /**
     * 一次请求的编辑会话：持有全部集合的可变快照，改动打 dirty 标记，flush 时整体写回。
     * 用法：<pre>
     *   store.in tx -> {
     *       List&lt;Map&gt; docs = tx.docs();
     *       ... 修改 ...
     *       tx.mark("docs");
     *   };
     * </pre>
     */
    public static class Tx {
        private final Map<String, Object> data = new LinkedHashMap<>();
        private final Set<String> dirty = new HashSet<>();
        private final EditorKvStore store;

        Tx(EditorKvStore store) {
            this.store = store;
            for (String k : COLLECTIONS) data.put(k, store.defaultValue(k));
        }

        @SuppressWarnings("unchecked")
        public <T> T get(String k) {
            Object v = data.get(k);
            if (v == null) {
                v = store.defaultValue(k);
                data.put(k, v);
            }
            return (T) v;
        }

        public List<Map<String, Object>> docs() {
            return get("docs");
        }

        public List<Map<String, Object>> users() {
            return get("users");
        }

        public List<Map<String, Object>> templates() {
            return get("templates");
        }

        public List<Map<String, Object>> accessRequests() {
            return get("accessRequests");
        }

        public List<Map<String, Object>> notifications() {
            return get("notifications");
        }

        public Map<String, Object> prefs() {
            return get("prefs");
        }

        public Map<String, Object> interactions() {
            return get("interactions");
        }

        public Map<String, Object> sessions() {
            return get("sessions");
        }

        public Map<String, Object> visitors() {
            return get("visitors");
        }

        public Map<String, Object> presence() {
            return get("presence");
        }

        public Map<String, Object> loginFails() {
            return get("loginFails");
        }

        @SuppressWarnings("unchecked")
        public Map<String, Object> trash() {
            return get("trash");
        }

        public void mark(String k) {
            dirty.add(k);
        }

        void hydrate(Map<String, String> rows) {
            for (String k : COLLECTIONS) {
                String raw = rows.get(k);
                if (raw != null) {
                    try {
                        data.put(k, store.mapper.readValue(raw, Object.class));
                    } catch (Exception ignore) {
                        // 坏行忽略，与 JS 版本一致
                    }
                }
            }
            // 合并 D1 分片：<k>#meta={chunks:N} + <k>#0..N-1 数组切片
            for (String k : CHUNKED) {
                String metaRaw = rows.get(k + "#meta");
                if (metaRaw == null) continue;
                try {
                    Map<String, Object> meta = store.mapper.readValue(metaRaw, new TypeReference<Map<String, Object>>() {});
                    Object chunksObj = meta.get("chunks");
                    if (chunksObj instanceof Number) {
                        int chunks = ((Number) chunksObj).intValue();
                        List<Object> merged = new ArrayList<>();
                        for (int i = 0; i < chunks; i++) {
                            String partRaw = rows.get(k + "#" + i);
                            if (partRaw != null) {
                                List<Object> part = store.mapper.readValue(partRaw, new TypeReference<List<Object>>() {});
                                merged.addAll(part);
                            }
                        }
                        data.put(k, merged);
                    }
                } catch (Exception ignore) {
                    // 坏分片忽略
                }
            }
        }

        Map<String, String> dehydrate() {
            Map<String, String> out = new LinkedHashMap<>();
            for (String k : dirty) {
                try {
                    out.put(k, store.mapper.writeValueAsString(data.get(k) == null ? store.defaultValue(k) : data.get(k)));
                } catch (Exception e) {
                    throw new IllegalStateException("serialize editor collection failed: " + k, e);
                }
            }
            return out;
        }
    }

    /** 在锁内加载快照、执行业务、落库。返回业务函数的返回值。 */
    public <R> R in(java.util.function.Function<Tx, R> work) {
        synchronized (this) {
            Map<String, String> rows = repository.findAllAsMap();
            Tx tx = new Tx(this);
            tx.hydrate(rows);
            R result = work.apply(tx);
            Map<String, String> changes = tx.dehydrate();
            if (!changes.isEmpty()) repository.saveAll(changes);
            return result;
        }
    }

    public void in(java.util.function.Consumer<Tx> work) {
        in(tx -> { work.accept(tx); return null; });
    }
}
