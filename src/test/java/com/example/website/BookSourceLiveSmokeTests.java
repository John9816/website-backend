package com.example.website;

import com.example.website.module.reading.api.dto.BookSearchPageView;
import com.example.website.module.reading.application.ImportedBookSourceReader;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** 尽力而为的真实链路冒烟：用系统用户直接调内置标准源搜索。网络失败不判失败。 */
@SpringBootTest
class BookSourceLiveSmokeTests {

    @Autowired
    private ImportedBookSourceReader reader;

    @Test
    void searchBuiltinSources() {
        String[] ids = {"sys-b5200.net", "sys-biqubao.com", "sys-pinshuzhai", "sys-79wx.com", "sys-qxs.la"};
        int ok = 0;
        for (String id : ids) {
            if (!reader.supports(ImportedBookSourceReader.SYSTEM_USER_ID, id)) {
                System.out.println("[SMOKE] " + id + " -> 未就绪");
                continue;
            }
            try {
                BookSearchPageView view = reader.search(ImportedBookSourceReader.SYSTEM_USER_ID, id, "斗破苍穹", 1, 10);
                int n = view.getList() == null ? 0 : view.getList().size();
                System.out.println("[SMOKE] " + id + " -> 结果 " + n + (n > 0 ? " | 首条: " + view.getList().get(0).getName() : ""));
                if (n > 0) ok++;
            } catch (Exception ex) {
                System.out.println("[SMOKE] " + id + " -> 失败: " + ex.getClass().getSimpleName() + " " + ex.getMessage());
            }
        }
        System.out.println("[SMOKE] 可用源 " + ok + "/" + ids.length);
        // 不断言：第三方站点波动大，此处仅作诊断输出
    }
}
