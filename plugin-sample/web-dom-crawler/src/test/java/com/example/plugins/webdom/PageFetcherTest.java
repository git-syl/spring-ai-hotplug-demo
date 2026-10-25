package com.example.plugins.webdom;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PageFetcherTest {

    private static final String PAGE = "<html><head><title>Ünïcode</title></head><body><p>hi</p></body></html>";

    private HttpServer server;
    private String base;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        respond("/page", 200, "text/html; charset=utf-8", PAGE);
        respond("/json", 200, "application/json", "{}");
        respond("/big", 200, "text/html", "x".repeat(5000));
        redirect("/redirect", "/page");
        redirect("/loop", "/loop");
        redirect("/ftp", "ftp://example.com/file");
        server.createContext("/slow", exchange -> {
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void fetchesAPageAndFollowsRedirectsWhenPrivateNetworksAreAllowed() throws Exception {
        try (PageFetcher fetcher = fetcher(true, 1024)) {
            PageFetcher.Page page = fetcher.fetch(base + "/redirect");

            assertThat(page.uri().toString()).isEqualTo(base + "/page");
            assertThat(page.status()).isEqualTo(200);
            assertThat(new String(page.body(), StandardCharsets.UTF_8)).isEqualTo(PAGE);
            assertThat(PageFetcher.charset(page.contentType())).isEqualTo("utf-8");
        }
    }

    @Test
    void refusesHostsThatResolveToPrivateAddressesByDefault() {
        try (PageFetcher fetcher = fetcher(false, 1024)) {
            assertThatThrownBy(() -> fetcher.fetch(base + "/page"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("non-public address 127.0.0.1").hasMessageContaining("allowPrivateNetwork is false");
            assertThatThrownBy(() -> fetcher.fetch("http://localhost:" + server.getAddress().getPort() + "/page"))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("non-public address");
        }
    }

    @Test
    void checksEveryRedirectAndRefusesNonHtmlOversizedSlowOrEndlessResponses() {
        try (PageFetcher fetcher = fetcher(true, 1024)) {
            assertThatThrownBy(() -> fetcher.fetch(base + "/ftp"))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Only http and https");
            assertThatThrownBy(() -> fetcher.fetch(base + "/loop"))
                    .isInstanceOf(IOException.class).hasMessageContaining("More than 5 redirects");
            assertThatThrownBy(() -> fetcher.fetch(base + "/json"))
                    .isInstanceOf(IOException.class).hasMessageContaining("Not an HTML page (application/json)");
            assertThatThrownBy(() -> fetcher.fetch(base + "/big")).isInstanceOf(IOException.class);
            assertThatThrownBy(() -> fetcher.fetch(base + "/slow"))
                    .isInstanceOf(HttpTimeoutException.class).hasMessageContaining("No complete response within 1s");
        }
    }

    @Test
    void refusesMalformedAndUnsupportedUrls() {
        try (PageFetcher fetcher = fetcher(true, 1024)) {
            assertThatThrownBy(() -> fetcher.fetch(" ")).hasMessage("url is required");
            assertThatThrownBy(() -> fetcher.fetch("file:///etc/passwd")).hasMessageContaining("Only http and https");
            assertThatThrownBy(() -> fetcher.fetch("http://user:secret@example.com/")).hasMessageContaining("credentials");
            assertThatThrownBy(() -> fetcher.fetch("http://bad host/")).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @ParameterizedTest
    @CsvSource({"8.8.8.8,true", "1.1.1.1,true", "100.128.0.1,true", "172.32.0.1,true", "2001:4860:4860::8888,true",
            "127.0.0.1,false", "10.1.2.3,false", "172.16.0.1,false", "192.168.0.1,false", "169.254.169.254,false",
            "100.64.0.1,false", "0.0.0.0,false", "224.0.0.1,false", "240.0.0.1,false", "198.18.0.1,false",
            "192.0.0.8,false", "::1,false", "fe80::1,false", "fc00::1,false", "fd12:3456::1,false"})
    void classifiesAddresses(String address, boolean expected) throws Exception {
        assertThat(PageFetcher.isPublic(InetAddress.getByName(address))).isEqualTo(expected);
    }

    private static PageFetcher fetcher(boolean allowPrivateNetwork, long maxBytes) {
        return new PageFetcher(Duration.ofSeconds(1), maxBytes, allowPrivateNetwork);
    }

    private void respond(String path, int status, String contentType, String body) {
        server.createContext(path, exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(status, bytes.length);
            try (var out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
    }

    private void redirect(String path, String location) {
        server.createContext(path, exchange -> {
            exchange.getResponseHeaders().set("Location", location);
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
    }
}
