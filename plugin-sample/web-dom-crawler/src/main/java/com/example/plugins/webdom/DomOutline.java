package com.example.plugins.webdom;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.jsoup.select.Selector;

import java.util.List;
import java.util.Set;

/**
 * 把选中的元素渲染成缩进大纲，每个元素一行：标签名、{@code #id}、最多三个 {@code .class}、几个关键属性和元素自身的文字（截短）。
 * script、style 等元素只显示标签。输出达到节点上限就停止，让大页面对模型依然可读。
 */
final class DomOutline {

    private static final int MAX_MATCHES = 20;
    private static final int MAX_CLASSES = 3;
    private static final int MAX_TEXT = 80;
    private static final int MAX_ATTRIBUTE = 120;
    private static final List<String> ATTRIBUTES =
            List.of("href", "src", "action", "name", "type", "role", "aria-label", "alt", "title");
    private static final Set<String> URL_ATTRIBUTES = Set.of("href", "src", "action");
    private static final Set<String> OPAQUE =
            Set.of("script", "style", "noscript", "template", "svg", "math", "iframe", "canvas");

    private DomOutline() {
    }

    /**
     * @param maxDepth 每个匹配元素（第 0 层）向下展示的层数
     * @param maxNodes 总共最多展示的元素数
     */
    static String render(Document document, String selector, int maxDepth, int maxNodes) {
        Elements matches;
        try {
            matches = document.select(selector);
        } catch (Selector.SelectorParseException e) {
            throw new IllegalArgumentException("Invalid CSS selector '" + selector + "': " + e.getMessage());
        }
        if (matches.isEmpty()) return "Selector '" + selector + "' matched no element.\n";
        int shown = Math.min(matches.size(), MAX_MATCHES);
        StringBuilder out = new StringBuilder("Selector '").append(selector).append("' matched ").append(matches.size())
                .append(matches.size() == 1 ? " element" : " elements");
        if (shown < matches.size()) out.append("; showing the first ").append(shown);
        out.append(".\n");
        Budget budget = new Budget(maxNodes);
        for (int i = 0; i < shown && !budget.exhausted; i++) {
            out.append('\n');
            append(out, matches.get(i), 0, maxDepth, budget);
        }
        if (budget.exhausted) {
            out.append("\n... stopped after ").append(maxNodes).append(" elements; narrow the selector or lower maxDepth.\n");
        }
        return out.toString();
    }

    private static void append(StringBuilder out, Element element, int depth, int maxDepth, Budget budget) {
        if (!budget.take()) return;
        out.append("  ".repeat(depth)).append(label(element));
        if (OPAQUE.contains(element.normalName())) {
            out.append('\n');
            return;
        }
        String text = shorten(element.ownText(), MAX_TEXT);
        if (!text.isEmpty()) out.append(" \"").append(text).append('"');
        Elements children = element.children();
        if (depth == maxDepth && !children.isEmpty()) {
            out.append(" (+").append(children.size()).append(children.size() == 1 ? " child)" : " children)");
        }
        out.append('\n');
        if (depth < maxDepth) {
            for (Element child : children) {
                if (budget.exhausted) return;
                append(out, child, depth + 1, maxDepth, budget);
            }
        }
    }

    private static String label(Element element) {
        StringBuilder label = new StringBuilder(element.normalName());
        if (!element.id().isEmpty()) label.append('#').append(element.id());
        element.classNames().stream().limit(MAX_CLASSES).forEach(name -> label.append('.').append(name));
        for (String attribute : ATTRIBUTES) {
            if (!element.hasAttr(attribute)) continue;
            String value = URL_ATTRIBUTES.contains(attribute) ? element.absUrl(attribute) : "";
            if (value.isEmpty()) value = element.attr(attribute);
            label.append('[').append(attribute).append("=\"").append(shorten(value, MAX_ATTRIBUTE)).append("\"]");
        }
        return label.toString();
    }

    private static String shorten(String text, int max) {
        String collapsed = text.strip().replaceAll("\\s+", " ");
        return collapsed.length() <= max ? collapsed : collapsed.substring(0, max - 3) + "...";
    }

    /** 还能展示多少个元素；一旦有元素因此被省略，就标记为已耗尽。 */
    private static final class Budget {
        private int remaining;
        private boolean exhausted;

        Budget(int remaining) {
            this.remaining = remaining;
        }

        boolean take() {
            if (remaining == 0) {
                exhausted = true;
                return false;
            }
            remaining--;
            return true;
        }
    }
}
