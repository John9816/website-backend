package com.example.website.service.video;

import com.example.website.dto.video.VideoSourceCapability;

import java.util.Locale;

final class ProviderCapabilityDetector {
    private ProviderCapabilityDetector() { }

    static Detection detect(int type, String api, Object ext, String jar, String spider) {
        String normalizedApi = api == null ? "" : api.trim().toLowerCase(Locale.ROOT);
        String normalizedExt = ext == null ? "" : ext.toString().toLowerCase(Locale.ROOT);
        if ((type == 4 || normalizedApi.endsWith(".py")) && !normalizedApi.isEmpty()) {
            return new Detection(VideoSourceCapability.DRPY_PY, true, "Python Provider");
        }
        if (normalizedApi.startsWith("http://") || normalizedApi.startsWith("https://")) {
            if (normalizedApi.endsWith(".js") || normalizedApi.contains(".js?")) {
                return new Detection(VideoSourceCapability.TVBOX_JS, true, "功能型 TVBox JS Provider");
            }
            return new Detection(VideoSourceCapability.CMS_HTTP, true, "标准 HTTP CMS API");
        }
        if (normalizedApi.startsWith("csp_xbpq") || normalizedApi.startsWith("csp_xyqhiker")
                || normalizedExt.endsWith(".json") || normalizedExt.contains("\"class_url\"")) {
            return new Detection(VideoSourceCapability.DRPY_JS, true, "XBPQ/dr_py 规则 Provider");
        }
        if (normalizedApi.startsWith("csp_") || jar != null || spider != null) {
            return new Detection(VideoSourceCapability.ANDROID_DEX, false,
                    "该站点依赖 Android classes.dex Provider");
        }
        return new Detection(VideoSourceCapability.UNSUPPORTED, false, "无法识别 Provider 格式");
    }

    static final class Detection {
        final VideoSourceCapability capability;
        final boolean executable;
        final String reason;

        Detection(VideoSourceCapability capability, boolean executable, String reason) {
            this.capability = capability;
            this.executable = executable;
            this.reason = reason;
        }
    }
}
