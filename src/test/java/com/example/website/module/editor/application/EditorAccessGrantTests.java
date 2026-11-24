package com.example.website.module.editor.application;

import com.example.website.common.BusinessException;
import com.example.website.module.editor.domain.EditorUserData;
import com.example.website.module.editor.infrastructure.persistence.EditorPresenceRepository;
import com.example.website.module.editor.infrastructure.persistence.EditorShareRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 跨用户「申请 -> 审批 -> 授权 -> 只读访问」全链路单测。
 * 用内存 Map 模拟 EditorStore 的 readColl/writeColl/findOwnerOfDoc 行为。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EditorAccessGrantTests {

    static final long OWNER = 2L;
    static final long APPLICANT = 4L;
    static final String DOC_ID = "bd56131e78a4";

    @Mock EditorStore store;
    @Mock EditorShareRepository shareRepo;
    @Mock EditorPresenceRepository presenceRepo;
    @Mock JdbcTemplate jdbcTemplate;

    ObjectMapper mapper = new ObjectMapper();
    EditorService service;

    // userId -> (Coll name -> JSON string)
    Map<Long, Map<String, String>> mem = new HashMap<>();

    @BeforeEach
    void setUp() {
        mem.put(OWNER, new HashMap<>());
        mem.put(APPLICANT, new HashMap<>());

        // 所有者有一篇文档
        List<Object> docs = new ArrayList<>();
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("id", DOC_ID); doc.put("title", "自制卡丁车"); doc.put("content", "内容正文");
        docs.add(doc);
        put(OWNER, "DOCS", docs);

        // readColl: 从 mem 读
        when(store.readColl(anyLong(), any(EditorStore.Coll.class))).thenAnswer(inv -> {
            long uid = inv.getArgument(0);
            EditorStore.Coll coll = inv.getArgument(1);
            String json = mem.getOrDefault(uid, new HashMap<>()).get(coll.name());
            if (json == null) return new ArrayList<Object>();
            return mapper.readValue(json, List.class);
        });
        // writeColl: 写回 mem
        doAnswer(inv -> {
            long uid = inv.getArgument(0);
            EditorStore.Coll coll = inv.getArgument(1);
            List<?> value = inv.getArgument(2);
            mem.computeIfAbsent(uid, k -> new HashMap<>()).put(coll.name(), mapper.writeValueAsString(value));
            return null;
        }).when(store).writeColl(anyLong(), any(EditorStore.Coll.class), anyList());

        // findOwnerOfDoc: 反查
        when(store.findOwnerOfDoc(anyString())).thenAnswer(inv -> {
            String id = inv.getArgument(0);
            if (!DOC_ID.equals(id)) return null;
            for (Map.Entry<Long, Map<String, String>> e : mem.entrySet()) {
                String json = e.getValue().get("DOCS");
                if (json == null) continue;
                for (Object o : mapper.readValue(json, List.class)) {
                    if (id.equals(String.valueOf(((Map) o).get("id")))) return e.getKey();
                }
            }
            return null;
        });

        // ownerName 走 jdbc，取不到会回退 id 字符串；测试不依赖具体名称，不 stub（走 catch）

        service = new EditorService(store, shareRepo, presenceRepo, mapper, jdbcTemplate);
    }

    void put(long uid, String coll, List<Object> v) {
        try { mem.computeIfAbsent(uid, k -> new HashMap<>()).put(coll, mapper.writeValueAsString(v)); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> get(long uid, String coll) {
        try {
            String json = mem.getOrDefault(uid, new HashMap<>()).get(coll);
            return json == null ? new ArrayList<>() : mapper.readValue(json, List.class);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    @Test
    void fullFlow_request_approve_readonly() {
        // 1) 申请者发起申请
        Map<String, Object> body = new HashMap<>();
        body.put("docId", DOC_ID);
        body.put("message", "想学习卡丁车制作");
        body.put("role", "viewer");
        service.addAccessRequest(APPLICANT, body, "3268252305");

        // 申请应写进所有者的收件箱（不是申请者自己）
        List<Map<String, Object>> ownerInbox = get(OWNER, "ACCESS_REQUESTS");
        assertEquals(1, ownerInbox.size(), "所有者应收到 1 条申请");
        assertEquals("request", ownerInbox.get(0).get("kind"));
        assertEquals("pending", ownerInbox.get(0).get("status"));
        assertEquals(APPLICANT, ((Number) ownerInbox.get(0).get("applicantId")).longValue());
        assertTrue(get(APPLICANT, "ACCESS_REQUESTS").stream().anyMatch(r -> "my_request".equals(r.get("kind"))),
                "申请者应有状态跟踪记录");

        // 2) 审批前：申请者读不到文档
        assertThrows(BusinessException.class, () -> service.getDoc(APPLICANT, DOC_ID));

        // 3) 所有者通过
        String reqId = String.valueOf(ownerInbox.get(0).get("id"));
        service.decideAccessRequest(OWNER, reqId, true, "1255542159");

        // 4) 审批后：申请者名下有 grant 授权
        List<Map<String, Object>> applicantReqs = get(APPLICANT, "ACCESS_REQUESTS");
        assertTrue(applicantReqs.stream().anyMatch(r -> "grant".equals(r.get("kind")) && "active".equals(r.get("status"))),
                "审批通过应给申请者写入 grant 授权");

        // 5) 申请者能读到文档，且被标记为只读
        Map<String, Object> doc = service.getDoc(APPLICANT, DOC_ID);
        assertEquals("自制卡丁车", doc.get("title"));
        assertEquals("viewer", doc.get("permission"));
        assertEquals(Boolean.TRUE, doc.get("readOnly"));
        assertEquals(Boolean.TRUE, doc.get("granted"));

        // 6) listDocs 里也应出现这篇授权文档
        assertTrue(service.listDocs(APPLICANT).stream().anyMatch(o -> DOC_ID.equals(String.valueOf(((Map) o).get("id")))));

        // 7) 只读文档拒绝修改
        Map<String, Object> patch = new HashMap<>();
        patch.put("title", "篡改标题");
        BusinessException ex = assertThrows(BusinessException.class, () -> service.updateDoc(APPLICANT, DOC_ID, patch));
        assertEquals(403, ex.getCode());
    }

    @Test
    void reject_doesNotGrant() {
        Map<String, Object> body = new HashMap<>();
        body.put("docId", DOC_ID);
        service.addAccessRequest(APPLICANT, body, "3268252305");
        String reqId = String.valueOf(get(OWNER, "ACCESS_REQUESTS").get(0).get("id"));

        service.decideAccessRequest(OWNER, reqId, false, "1255542159");

        assertTrue(get(APPLICANT, "ACCESS_REQUESTS").stream().noneMatch(r -> "grant".equals(r.get("kind"))),
                "拒绝不应产生授权");
        assertThrows(BusinessException.class, () -> service.getDoc(APPLICANT, DOC_ID));
    }

    @Test
    void ownerCannotApplyToOwnDoc() {
        Map<String, Object> body = new HashMap<>();
        body.put("docId", DOC_ID);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.addAccessRequest(OWNER, body, "1255542159"));
        assertEquals(400, ex.getCode());
    }

    @Test
    void duplicatePendingRejected() {
        Map<String, Object> body = new HashMap<>();
        body.put("docId", DOC_ID);
        service.addAccessRequest(APPLICANT, body, "3268252305");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.addAccessRequest(APPLICANT, body, "3268252305"));
        assertEquals(400, ex.getCode());
    }
}
