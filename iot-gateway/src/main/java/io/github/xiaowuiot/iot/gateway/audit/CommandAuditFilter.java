package io.github.xiaowuiot.iot.gateway.audit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;
import org.springframework.web.util.WebUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 记录通过接口下发给设备的指令：哪台设备、什么指令、参数、设备怎么应答、谁调的。
 * <p>
 * 指令接口是异步的（等设备应答），第一次进来时还没有结果。和 Spring 的 ShallowEtagHeaderFilter 一样，
 * 在异步结束后的那次分发里再取状态码和应答内容，记完把缓存的应答写回给调用方。
 */
public class CommandAuditFilter extends OncePerRequestFilter {

    private static final Pattern DEVICE_API = Pattern.compile("/api/(jt808|hj212|slp)/([^/]+)(/.*)?");
    private static final int MAX_TEXT = 1000;
    private static final String START = CommandAuditFilter.class.getName() + ".start";

    private record Name(String method, Pattern path, String name) {
    }

    private static final List<Name> NAMES = List.of(
            new Name("GET", Pattern.compile("/params"), "查询终端参数 0x8104"),
            new Name("PUT", Pattern.compile("/params"), "设置终端参数 0x8103"),
            new Name("GET", Pattern.compile("/params/select"), "查询指定终端参数 0x8106"),
            new Name("GET", Pattern.compile("/location"), "位置信息查询 0x8201"),
            new Name("POST", Pattern.compile("/text"), "文本信息下发 0x8300"),
            new Name("POST", Pattern.compile("/control"), "终端控制 0x8105"),
            new Name("POST", Pattern.compile("/passthrough"), "数据下行透传 0x8900"),
            new Name("GET", Pattern.compile("/attributes"), "查询终端属性 0x8107"),
            new Name("POST", Pattern.compile("/tracking"), "临时位置跟踪控制 0x8202"),
            new Name("POST", Pattern.compile("/alarm-confirm"), "人工确认报警 0x8203"),
            new Name("POST", Pattern.compile("/camera"), "摄像头立即拍摄 0x8801"),
            new Name("POST", Pattern.compile("/areas/circle"), "设置圆形区域 0x8600"),
            new Name("DELETE", Pattern.compile("/areas/circle"), "删除圆形区域 0x8601"),
            new Name("POST", Pattern.compile("/command/(\\d+)"), "命令 CN="),
            new Name("GET", Pattern.compile("/time"), "提取现场机时间 1011"),
            new Name("POST", Pattern.compile("/time"), "设置现场机时间 1012"),
            new Name("GET", Pattern.compile("/history/(\\d+)"), "提取历史数据 CN="),
            new Name("POST", Pattern.compile("/open"), "开门"));

    private final AuditLog audit;

    public CommandAuditFilter(AuditLog audit) {
        this.audit = audit;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !DEVICE_API.matcher(request.getRequestURI()).matches();
    }

    /** 异步结束后的那次分发也要进来，才能拿到最终结果 */
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
            throws ServletException, IOException {
        HttpServletRequest request = req;
        HttpServletResponse response = resp;
        if (!isAsyncDispatch(req)) {
            req.setAttribute(START, System.currentTimeMillis());
            request = new ContentCachingRequestWrapper(req);
            response = new ContentCachingResponseWrapper(resp);
        }
        try {
            chain.doFilter(request, response);
        } finally {
            if (!isAsyncStarted(request)) {
                ContentCachingRequestWrapper reqCache = WebUtils.getNativeRequest(request, ContentCachingRequestWrapper.class);
                ContentCachingResponseWrapper respCache = WebUtils.getNativeResponse(response, ContentCachingResponseWrapper.class);
                if (respCache != null) {
                    record(request, reqCache, respCache);
                    respCache.copyBodyToResponse();
                }
            }
        }
    }

    private void record(HttpServletRequest req, ContentCachingRequestWrapper reqCache, ContentCachingResponseWrapper resp) {
        Matcher m = DEVICE_API.matcher(req.getRequestURI());
        if (!m.matches()) {
            return;
        }
        String protocol = m.group(1);
        String sub = m.group(3) == null ? "" : m.group(3);
        String body = reqCache == null ? "" : new String(reqCache.getContentAsByteArray(), StandardCharsets.UTF_8);
        String request = join(req.getQueryString(), body);
        Object start = req.getAttribute(START);
        long duration = start instanceof Long t ? System.currentTimeMillis() - t : 0;
        audit.command(protocol, m.group(2), name(req.getMethod(), sub), req.getMethod(), req.getRequestURI(),
                cut(request), resp.getStatus(), cut(new String(resp.getContentAsByteArray(), StandardCharsets.UTF_8)),
                duration, req.getRemoteAddr());
    }

    static String name(String method, String sub) {
        for (Name n : NAMES) {
            Matcher m = n.path().matcher(sub);
            if (n.method().equals(method) && m.matches()) {
                return m.groupCount() > 0 ? n.name() + m.group(1) : n.name();
            }
        }
        return method + " " + sub;
    }

    private static String join(String query, String body) {
        boolean hasQuery = query != null && !query.isBlank();
        boolean hasBody = body != null && !body.isBlank();
        return hasQuery && hasBody ? query + " " + body : hasQuery ? query : hasBody ? body : null;
    }

    private static String cut(String s) {
        return s == null || s.length() <= MAX_TEXT ? s : s.substring(0, MAX_TEXT) + "…";
    }
}
