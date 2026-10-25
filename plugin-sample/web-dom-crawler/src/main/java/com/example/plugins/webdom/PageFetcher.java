package com.example.plugins.webdom;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 下载一个 HTML 页面。URL 来自模型，因此不可信：只允许 http 和 https，拒绝带用户名密码的 URL，每一跳重定向都重新检查；
 * 除非管理员允许访问内网，否则拒绝解析到回环、内网、链路本地等非公网地址的主机。包括重定向在内的整个交换必须在超时内完成，
 * 页面必须小于 {@code maxBytes}。
 *
 * <p>地址检查和真正的连接各自解析一次域名，DNS 重绑定可能钻这个时间差。内网服务必须不可达时，还应在网络层限制出站访问。</p>
 */
final class PageFetcher implements AutoCloseable {

    record Page(URI uri, int status, String contentType, byte[] body) {}

    private static final int MAX_REDIRECTS = 5;
    private static final String USER_AGENT = "Mozilla/5.0 (compatible; AgentWebDom/1.0)";

    private final HttpClient client;
    private final Duration timeout;
    private final long maxBytes;
    private final boolean allowPrivateNetwork;

    PageFetcher(Duration timeout, long maxBytes, boolean allowPrivateNetwork) {
        this.client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(timeout)
                .build();
        this.timeout = timeout;
        this.maxBytes = maxBytes;
        this.allowPrivateNetwork = allowPrivateNetwork;
    }

    Page fetch(String url) throws IOException, InterruptedException {
        if (url == null || url.isBlank()) throw new IllegalArgumentException("url is required");
        URI uri = checked(URI.create(url.strip()));
        long deadline = System.nanoTime() + timeout.toNanos();
        for (int redirects = 0; ; redirects++) {
            HttpResponse<byte[]> response = send(uri, deadline);
            String location = response.headers().firstValue("Location").orElse(null);
            if (isRedirect(response.statusCode()) && location != null) {
                if (redirects == MAX_REDIRECTS) throw new IOException("More than " + MAX_REDIRECTS + " redirects from " + url);
                uri = checked(uri.resolve(location));
                continue;
            }
            String contentType = response.headers().firstValue("Content-Type").orElse("");
            if (!contentType.isEmpty() && !isHtml(contentType)) {
                throw new IOException("Not an HTML page (" + contentType + "): " + uri);
            }
            return new Page(uri, response.statusCode(), contentType, response.body());
        }
    }

    /** Content-Type 里的 {@code charset} 参数；没有时返回 null，交给解析器自行判断。 */
    static String charset(String contentType) {
        for (String parameter : contentType.split(";")) {
            String[] pair = parameter.strip().split("=", 2);
            if (pair.length == 2 && pair[0].equalsIgnoreCase("charset")) return pair[1].replace("\"", "").strip();
        }
        return null;
    }

    /** 回环、内网、链路本地、组播、运营商级 NAT 和保留地址都返回 false。 */
    static boolean isPublic(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return false;
        }
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address) {
            int first = bytes[0] & 0xff;
            int second = bytes[1] & 0xff;
            return first != 0                                         // 0.0.0.0/8
                    && !(first == 100 && second >= 64 && second <= 127) // 100.64.0.0/10 运营商级 NAT
                    && !(first == 192 && second == 0 && bytes[2] == 0)  // 192.0.0.0/24 协议分配
                    && !(first == 198 && (second == 18 || second == 19)) // 198.18.0.0/15 基准测试
                    && first < 240;                                    // 240.0.0.0/4 保留与广播
        }
        return (bytes[0] & 0xfe) != 0xfc; // fc00::/7 唯一本地地址
    }

    @Override
    public void close() {
        client.shutdownNow();
    }

    private URI checked(URI uri) throws UnknownHostException {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IllegalArgumentException("Only http and https URLs can be fetched: " + uri);
        }
        if (uri.getHost() == null) throw new IllegalArgumentException("The URL has no host: " + uri);
        if (uri.getRawUserInfo() != null) throw new IllegalArgumentException("URLs with credentials are not fetched");
        if (!allowPrivateNetwork) {
            for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
                if (!isPublic(address)) {
                    throw new IllegalArgumentException("Refusing " + uri.getHost() + ": it resolves to the non-public address "
                            + address.getHostAddress() + " (allowPrivateNetwork is false)");
                }
            }
        }
        return uri;
    }

    private HttpResponse<byte[]> send(URI uri, long deadline) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.1")
                .GET()
                .build();
        CompletableFuture<HttpResponse<byte[]>> pending = client.sendAsync(request,
                HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofByteArray(), maxBytes));
        try {
            return pending.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            throw new HttpTimeoutException("No complete response within " + timeout.toSeconds() + "s from " + uri);
        } catch (ExecutionException e) {
            throw e.getCause() instanceof IOException failure ? failure : new IOException(e.getCause());
        } finally {
            // 超时或被中断（请求取消）时放弃这次交换；已经完成时什么也不做。
            pending.cancel(true);
        }
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private static boolean isHtml(String contentType) {
        String type = contentType.toLowerCase(Locale.ROOT).strip();
        return type.startsWith("text/html") || type.startsWith("application/xhtml+xml");
    }
}
