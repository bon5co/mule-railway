import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Admin and gateway front for the Mule Community runtime, which has no deployment API of its own. */
public class Admin {
  static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{1,64}");
  static final Set<String> SKIP = Set.of("connection", "content-length", "host", "transfer-encoding", "upgrade", "expect");

  public static void main(String[] a) throws Exception {
    int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
    String token = System.getenv("MULE_ADMIN_TOKEN");
    if (token == null || token.isEmpty()) throw new IllegalStateException("MULE_ADMIN_TOKEN not set");
    Path data = Paths.get(System.getenv().getOrDefault("MULE_ADMIN_DATA", "/data"));
    Path apps = data.resolve("apps"), upload = data.resolve("upload");
    String version = System.getenv().getOrDefault("MULE_VERSION", "unknown");
    int muleHttp = Integer.parseInt(System.getenv().getOrDefault("MULE_HTTP_PORT", "8081"));
    HttpClient client = HttpClient.newHttpClient();

    HttpServer s = HttpServer.create(new InetSocketAddress(port), 0);
    s.setExecutor(Executors.newCachedThreadPool());
    s.createContext("/", ex -> {
      try {
        route(ex, token, apps, upload, version, muleHttp, client);
      } catch (Exception e) {
        send(ex, 500, "{\"error\":\"" + String.valueOf(e.getMessage()).replace("\"", "'") + "\"}");
      } finally { ex.close(); }
    });
    s.start();
    System.out.println("[admin] listening on " + port + ", Mule apps proxied from /gateway to 127.0.0.1:" + muleHttp);
  }

  static void route(HttpExchange ex, String token, Path apps, Path upload, String version, int muleHttp, HttpClient client) throws Exception {
    String path = ex.getRequestURI().getPath(), m = ex.getRequestMethod();
    if (path.equals("/health") && m.equals("GET")) { send(ex, 200, "{\"status\":\"UP\",\"runtime\":\"" + version + "\"}"); return; }
    if (path.equals("/gateway") || path.startsWith("/gateway/")) { proxy(ex, muleHttp, client); return; }
    if (path.startsWith("/admin/apps")) {
      if (!authorized(ex, token)) { send(ex, 401, "{\"error\":\"unauthorized\"}"); return; }
      String rest = path.substring("/admin/apps".length());
      if (rest.isEmpty() || rest.equals("/")) {
        if (!m.equals("GET")) { send(ex, 405, "{\"error\":\"method not allowed\"}"); return; }
        try (Stream<Path> l = Files.list(apps)) {
          String items = l.filter(Files::isDirectory).map(p -> p.getFileName().toString()).filter(n -> !n.startsWith(".")).sorted()
              .map(n -> "\"" + n + "\"").collect(Collectors.joining(","));
          send(ex, 200, "{\"apps\":[" + items + "]}");
        }
        return;
      }
      String name = rest.substring(1);
      if (!NAME.matcher(name).matches()) { send(ex, 400, "{\"error\":\"name must match [A-Za-z0-9_-]{1,64}\"}"); return; }
      if (m.equals("PUT")) {
        Files.createDirectories(upload);
        Path tmp = upload.resolve(name + ".jar.part");
        try (InputStream in = ex.getRequestBody()) { Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING); }
        Files.move(tmp, apps.resolve(name + ".jar"), StandardCopyOption.REPLACE_EXISTING);
        send(ex, 200, "{\"deployed\":\"" + name + "\"}");
      } else if (m.equals("DELETE")) {
        boolean gone = Files.isDirectory(apps.resolve(name)) && (Files.deleteIfExists(apps.resolve(name + "-anchor.txt")) || Files.deleteIfExists(apps.resolve(name + ".jar")));
        send(ex, gone ? 200 : 404, gone ? "{\"removing\":\"" + name + "\"}" : "{\"error\":\"not found\"}");
      } else send(ex, 405, "{\"error\":\"method not allowed\"}");
      return;
    }
    send(ex, 404, "{\"error\":\"not found\"}");
  }

  static boolean authorized(HttpExchange ex, String token) {
    String h = ex.getRequestHeaders().getFirst("Authorization");
    if (h == null) return false;
    return MessageDigest.isEqual(h.getBytes(StandardCharsets.UTF_8), ("Bearer " + token).getBytes(StandardCharsets.UTF_8));
  }

  static void proxy(HttpExchange ex, int muleHttp, HttpClient client) throws Exception {
    String p = ex.getRequestURI().getRawPath().substring("/gateway".length());
    if (p.isEmpty()) p = "/";
    String q = ex.getRequestURI().getRawQuery();
    URI target = URI.create("http://127.0.0.1:" + muleHttp + p + (q == null ? "" : "?" + q));
    byte[] body = ex.getRequestBody().readAllBytes();
    HttpRequest.Builder b = HttpRequest.newBuilder(target)
        .method(ex.getRequestMethod(), body.length == 0 ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
    ex.getRequestHeaders().forEach((k, v) -> { if (!SKIP.contains(k.toLowerCase())) v.forEach(x -> b.header(k, x)); });
    HttpResponse<byte[]> r;
    try { r = client.send(b.build(), HttpResponse.BodyHandlers.ofByteArray()); }
    catch (IOException e) { send(ex, 502, "{\"error\":\"no Mule application is listening on port " + muleHttp + "\"}"); return; }
    r.headers().map().forEach((k, v) -> { if (!SKIP.contains(k.toLowerCase())) v.forEach(x -> ex.getResponseHeaders().add(k, x)); });
    ex.sendResponseHeaders(r.statusCode(), r.body().length == 0 ? -1 : r.body().length);
    if (r.body().length > 0) try (OutputStream o = ex.getResponseBody()) { o.write(r.body()); }
  }

  static void send(HttpExchange ex, int code, String json) throws IOException {
    byte[] b = json.getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().set("Content-Type", "application/json");
    ex.sendResponseHeaders(code, b.length);
    try (OutputStream o = ex.getResponseBody()) { o.write(b); }
  }
}
