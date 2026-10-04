package com.example.videolingo.ingest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

// Fetches admin-supplied URLs without letting them reach our own network:
// only http(s) on the default ports, every hop (redirects are followed by hand)
// must resolve to public addresses, small bodies, short timeouts.
@Component
public class SafeHttp {

    public static final String BROWSER_UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36";
    private static final int MAX_REDIRECTS = 5;

    public record Response(int status, URI finalUri, String contentType, long contentLength, byte[] body) {

        public String text() {
            Charset cs = StandardCharsets.UTF_8;
            if (contentType != null) {
                int i = contentType.toLowerCase(Locale.ROOT).indexOf("charset=");
                if (i >= 0) {
                    try {
                        cs = Charset.forName(contentType
                                .substring(i + 8)
                                .replaceAll("[\"';].*$", "")
                                .strip());
                    } catch (Exception ignored) {
                        // fall back to UTF-8
                    }
                }
            }
            return new String(body, cs);
        }

        public boolean ok() {
            return status >= 200 && status < 300;
        }
    }

    /** Thrown for a blocked or unreachable address — the message is safe to show. */
    public static class BlockedException extends IOException {
        public BlockedException(String message) {
            super(message);
        }
    }

    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(6))
            .build();

    public Response get(URI uri, Map<String, String> headers, int maxBytes) throws IOException {
        return send("GET", uri, headers, maxBytes);
    }

    /** HEAD, falling back to a 1-byte ranged GET for servers that don't answer HEAD. */
    public Response probe(URI uri, Map<String, String> headers) throws IOException {
        Response head = send("HEAD", uri, headers, 0);
        if (head.status() == 405 || head.status() == 403 || head.status() == 501) {
            Map<String, String> ranged = new java.util.HashMap<>(headers);
            ranged.put("Range", "bytes=0-0");
            return send("GET", uri, ranged, 1);
        }
        return head;
    }

    private Response send(String method, URI start, Map<String, String> headers, int maxBytes) throws IOException {
        URI uri = start;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            checkAllowed(uri);
            HttpRequest.Builder req = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(10))
                    .method(method, HttpRequest.BodyPublishers.noBody());
            headers.forEach(req::header);
            HttpResponse<InputStream> res;
            try {
                res = client.send(req.build(), HttpResponse.BodyHandlers.ofInputStream());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted");
            }
            int status = res.statusCode();
            if (status >= 300
                    && status < 400
                    && res.headers().firstValue("location").isPresent()) {
                res.body().close();
                uri = uri.resolve(res.headers().firstValue("location").get().replace(" ", "%20"));
                continue;
            }
            byte[] body = read(res.body(), maxBytes);
            String type = res.headers().firstValue("content-type").orElse(null);
            long length = contentLength(res);
            return new Response(status, uri, type, length, body);
        }
        throw new IOException("Too many redirects");
    }

    private static long contentLength(HttpResponse<?> res) {
        Optional<String> range = res.headers().firstValue("content-range"); // "bytes 0-0/12345"
        if (range.isPresent() && range.get().contains("/")) {
            try {
                return Long.parseLong(
                        range.get().substring(range.get().lastIndexOf('/') + 1).strip());
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return res.headers().firstValueAsLong("content-length").orElse(-1);
    }

    private static byte[] read(InputStream in, int maxBytes) throws IOException {
        try (in) {
            if (maxBytes <= 0) {
                return new byte[0];
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(maxBytes, 64 * 1024));
            byte[] buf = new byte[8192];
            int n;
            while (out.size() < maxBytes && (n = in.read(buf, 0, Math.min(buf.length, maxBytes - out.size()))) > 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    public static void checkAllowed(URI uri) throws BlockedException {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new BlockedException("Only http:// and https:// links can be fetched");
        }
        int port = uri.getPort();
        if (port != -1 && port != 80 && port != 443) {
            throw new BlockedException("Links on non-standard ports can't be fetched");
        }
        String host = uri.getHost();
        if (host == null) {
            throw new BlockedException("That link has no host");
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new BlockedException("The website " + host + " couldn't be found");
        }
        for (InetAddress a : addresses) {
            if (!isPublic(a)) {
                throw new BlockedException("Links to private or local network addresses aren't allowed");
            }
        }
    }

    static boolean isPublic(InetAddress a) {
        if (a.isAnyLocalAddress()
                || a.isLoopbackAddress()
                || a.isLinkLocalAddress()
                || a.isSiteLocalAddress()
                || a.isMulticastAddress()) {
            return false;
        }
        byte[] b = a.getAddress();
        if (a instanceof Inet4Address) {
            int first = b[0] & 0xff;
            int second = b[1] & 0xff;
            // 100.64/10 carrier-grade NAT, 0/8, 192.0.0/24, 198.18/15 benchmarking, 240/4 reserved
            return !(first == 0
                    || (first == 100 && second >= 64 && second <= 127)
                    || (first == 192 && second == 0 && (b[2] & 0xff) == 0)
                    || (first == 198 && (second == 18 || second == 19))
                    || first >= 240);
        }
        if (a instanceof Inet6Address) {
            // fc00::/7 unique-local
            return (b[0] & 0xfe) != 0xfc;
        }
        return true;
    }
}
