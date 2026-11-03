package com.paicli.mcp.jsonrpc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.mcp.transport.McpTransport;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * JSON-RPC 2.0 协议客户端，MCP 通信的地基。
 * 管的是"发请求、等回复、按 id 对上号"这套流程：每个请求带一个自增 id，
 * 收到回复时按 id 找回等待中的调用方；server 主动发来的通知和请求（比如 ping）也在这里分发。
 * 走 stdio 子进程还是 HTTP、字节怎么收发，由 {@link McpTransport} 负责，这里只管协议。
 */
public class JsonRpcClient implements AutoCloseable {
    /** 构建 / 解析 JSON-RPC 消息的 Jackson 实例；无状态，全类共用。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** 调用方没指定超时时的默认请求超时（秒）。 */
    private static final long DEFAULT_TIMEOUT_SECONDS = 60;
    /** JSON-RPC 标准错误码 -32601（方法不存在）：server 请求我们不支持的方法时用它回绝。 */
    private static final int METHOD_NOT_FOUND = -32601;

    /** 底层传输：消息发出去靠它，收到的消息通过 onReceive 回调进来。 */
    private final McpTransport transport;
    /** 请求 id 生成器，从 1 开始自增；每个请求领一个唯一 id，回复靠它对号入座。 */
    private final AtomicLong ids = new AtomicLong(1);
    /** id → 等待回复的 future。发出请求时放入，收到回复 / 超时 / 断连时移除并给出结果。 */
    private final ConcurrentHashMap<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    /**
     * 单线程调度器，干两件事：到点把超时请求标记失败；把对 server 请求的回复
     * 挪出传输层的读线程再发（避免在读线程里阻塞写）。守护线程，不挡 JVM 退出。
     */
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "paicli-mcp-jsonrpc");
        thread.setDaemon(true);
        return thread;
    });
    /** server 主动发来的通知（没有 id 的消息，如 tools/list_changed）的监听者列表。 */
    private final List<Consumer<JsonNode>> notificationListeners = new java.util.concurrent.CopyOnWriteArrayList<>();
    /** 意外断开的原因；非 null 表示 transport 已断，新请求直接抛错，不再傻等超时。 */
    private volatile String disconnectReason;

    /** 注册"收到消息"和"意外断开"两个回调，之后收到的所有消息都走 handleMessage。 */
    public JsonRpcClient(McpTransport transport) {
        this.transport = transport;
        this.transport.onReceive(this::handleMessage);
        this.transport.onUnexpectedClose(this::failPending);
    }

    /** server 进程退出后，正在等回复的请求立即失败，不再干等超时。 */
    private void failPending(String reason) {
        disconnectReason = reason;
        IOException error = new IOException(reason);
        pending.keySet().forEach(id -> {
            CompletableFuture<JsonNode> future = pending.remove(id);
            if (future != null) {
                future.completeExceptionally(error);
            }
        });
    }

    /** 还有请求在等回复吗？关闭 server 前用来判断有没有会丢的在路上请求。 */
    public boolean hasPendingRequests() {
        return !pending.isEmpty();
    }

    /** 发一个请求并同步等回复，超时用 {@link #DEFAULT_TIMEOUT_SECONDS}。 */
    public JsonNode request(String method, JsonNode params) throws IOException {
        return request(method, params, DEFAULT_TIMEOUT_SECONDS);
    }

    /** 同上，超时按秒指定。 */
    public JsonNode request(String method, JsonNode params, long timeoutSeconds) throws IOException {
        return request(method, params, TimeUnit.SECONDS.toMillis(timeoutSeconds), TimeUnit.MILLISECONDS);
    }

    /**
     * 发一个请求并同步等回复：组装报文 → 登记 pending → 设超时 → 发送 → 阻塞等结果。
     * 失败分三种：server 回了 error（抛 {@link JsonRpcException}）、超时（抛 IOException）、
     * transport 断开（抛 IOException，原因就是断开原因）。
     */
    public JsonNode request(String method, JsonNode params, long timeout, TimeUnit unit) throws IOException {
        if (disconnectReason != null) {
            throw new IOException(disconnectReason);
        }
        long timeoutMillis = unit.toMillis(timeout);
        long id = ids.getAndIncrement();
        ObjectNode request = MAPPER.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", id);
        request.put("method", method);
        if (params != null) {
            request.set("params", params);
        }

        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pending.put(id, future);
        scheduler.schedule(() -> {
            CompletableFuture<JsonNode> removed = pending.remove(id);
            if (removed != null) {
                removed.completeExceptionally(new TimeoutException("JSON-RPC request timed out: " + method));
            }
        }, timeoutMillis, TimeUnit.MILLISECONDS);
        // 注册 pending 和进程退出之间有竞争窗口，放进去之后再检查一次
        if (disconnectReason != null) {
            pending.remove(id);
            throw new IOException(disconnectReason);
        }

        try {
            transport.send(request);
            // 多等 1 秒兜底：正常超时先由上面的 scheduler 触发（异常信息带方法名），get 自己的超时只是最后防线
            return future.get(timeoutMillis + 1000, TimeUnit.MILLISECONDS);
        } catch (JsonRpcException e) {
            throw e;
        } catch (Exception e) {
            pending.remove(id);
            if (e.getCause() instanceof JsonRpcException jsonRpcException) {
                throw jsonRpcException;
            }
            if (e.getCause() instanceof IOException ioException) {
                throw ioException;
            }
            throw new IOException(e.getMessage(), e);
        }
    }

    /** 发通知：不带 id、不等回复，比如 notifications/initialized。 */
    public void sendNotification(String method, JsonNode params) throws IOException {
        ObjectNode notification = MAPPER.createObjectNode();
        notification.put("jsonrpc", "2.0");
        notification.put("method", method);
        if (params != null) {
            notification.set("params", params);
        }
        transport.send(notification);
    }

    /** 注册一个通知监听者；server 发来的每条通知都会回调它。 */
    public void onNotification(Consumer<JsonNode> listener) {
        if (listener != null) {
            notificationListeners.add(listener);
        }
    }

    /**
     * 先按有没有 method 区分方向：带 method 的是 server 发来的请求或通知，不带的才是我们请求的回复。
     * 不能先拿 id 去 pending 里找：server 的请求 id 也从 1 开始，会和我们自己的请求撞号。
     */
    private void handleMessage(JsonNode message) {
        if (message.hasNonNull("method")) {
            JsonNode idNode = message.get("id");
            if (idNode == null || idNode.isNull()) {
                for (Consumer<JsonNode> listener : notificationListeners) {
                    listener.accept(message);
                }
            } else {
                // 回复放到别的线程发，避免在 stdout 读线程里阻塞写 stdin
                try {
                    scheduler.execute(() -> replyToServerRequest(idNode, message.path("method").asText()));
                } catch (RejectedExecutionException ignored) {
                    // client 已关闭，不再回复
                }
            }
            return;
        }
        Long id = responseId(message.get("id"));
        if (id == null) {
            return;
        }
        CompletableFuture<JsonNode> future = pending.remove(id);
        if (future == null) {
            return;
        }
        JsonNode error = message.get("error");
        if (error != null && !error.isNull()) {
            future.completeExceptionally(new JsonRpcException(
                    error.path("code").asInt(-32603),
                    error.path("message").asText("JSON-RPC error")));
            return;
        }
        future.complete(message.get("result"));
    }

    /** 我们发出的 id 都是数字；个别 server 会把它回成数字字符串，其他形式一律不认。 */
    private static Long responseId(JsonNode idNode) {
        if (idNode == null || idNode.isNull()) {
            return null;
        }
        if (idNode.isIntegralNumber()) {
            return idNode.asLong();
        }
        if (idNode.isTextual() && idNode.asText().matches("-?\\d{1,18}")) {
            return Long.parseLong(idNode.asText());
        }
        return null;
    }

    /** 回复 server 主动发来的请求：只认 ping（回空 result），其他方法回 -32601。 */
    private void replyToServerRequest(JsonNode idNode, String method) {
        ObjectNode response = MAPPER.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", idNode);
        if ("ping".equals(method)) {
            response.putObject("result");
        } else {
            ObjectNode error = response.putObject("error");
            error.put("code", METHOD_NOT_FOUND);
            error.put("message", "Method not found: " + method);
        }
        try {
            transport.send(response);
        } catch (IOException ignored) {
            // transport 已关闭或断开，server 那边会自己超时
        }
    }

    /** 关掉调度线程和 transport；等着的请求由各自的超时兜底，不在这里逐个唤醒。 */
    @Override
    public void close() {
        scheduler.shutdownNow();
        transport.close();
    }
}
