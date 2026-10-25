package com.example.plugins.webdom;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.ByteArrayInputStream;
import java.io.IOException;

/** 插件唯一的工具。一个实例被所有请求并发调用，所以它不保存任何请求状态。 */
final class WebDomTools {

    private static final int DEFAULT_DEPTH = 6;
    private static final int MAX_DEPTH = 12;

    private final PageFetcher fetcher;
    private final int maxNodes;

    WebDomTools(PageFetcher fetcher, int maxNodes) {
        this.fetcher = fetcher;
        this.maxNodes = maxNodes;
    }

    @Tool(name = "webdom_fetch_dom", description = """
            Fetch one public web page and return its DOM structure as an indented outline: one element per line \
            with its tag, id, classes, key attributes (href, src, action, name, type, role, aria-label, alt, title) \
            and its own text, shortened. Use it to understand how a page is laid out or to find the CSS selector \
            of some content. JavaScript is not run, so content that scripts render is missing.""")
    String fetchDom(@ToolParam(description = "Absolute http or https URL of the page") String url,
                    @ToolParam(description = "CSS selector of the elements to outline, for example main or table.prices; "
                            + "defaults to body", required = false) String selector,
                    @ToolParam(description = "How many levels below each selected element to show, 1 to 12; defaults to 6",
                            required = false) Integer maxDepth) throws IOException, InterruptedException {
        PageFetcher.Page page = fetcher.fetch(url);
        Document document = Jsoup.parse(new ByteArrayInputStream(page.body()),
                PageFetcher.charset(page.contentType()), page.uri().toString());
        String css = selector == null || selector.isBlank() ? "body" : selector.strip();
        int depth = maxDepth == null ? DEFAULT_DEPTH : Math.clamp(maxDepth, 1, MAX_DEPTH);
        return "URL: " + page.uri() + "\nStatus: " + page.status() + "\nTitle: " + document.title() + "\n\n"
                + DomOutline.render(document, css, depth, maxNodes);
    }
}
