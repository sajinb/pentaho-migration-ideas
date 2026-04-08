package com.pentaho.migration.step.impl.streaming;

import com.pentaho.migration.sort.Row;
import com.pentaho.migration.step.AbstractStreamingStep;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Makes an HTTP REST call per row, appending the response body and status code.
 * Equivalent to Pentaho's Rest step. Uses Java 11+ built-in HttpClient.
 *
 * <p>Params:
 * <ul>
 *   <li>{@code urlColumn}  — 0-based column containing the URL, OR
 *   <li>{@code url}        — static URL template (if urlColumn not given)
 *   <li>{@code method}     — HTTP method: "GET" (default), "POST", "PUT", "DELETE"
 *   <li>{@code bodyColumn} — 0-based column of request body (for POST/PUT)
 *   <li>{@code timeoutMs}  — request timeout in ms (default 5000)
 * </ul>
 */
public class RestStep extends AbstractStreamingStep {

    private int     urlColumn  = -1;
    private String  staticUrl  = null;
    private String  method     = "GET";
    private int     bodyColumn = -1;
    private int     timeoutMs  = 5000;
    private HttpClient client;

    @Override
    public void configure(Map<String, String> params) {
        if (params.containsKey("urlColumn")) urlColumn = Integer.parseInt(params.get("urlColumn"));
        if (params.containsKey("url"))        staticUrl = params.get("url");
        method     = params.getOrDefault("method", "GET").toUpperCase();
        if (params.containsKey("bodyColumn")) bodyColumn = Integer.parseInt(params.get("bodyColumn"));
        timeoutMs  = Integer.parseInt(params.getOrDefault("timeoutMs", "5000"));
        client     = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(timeoutMs)).build();
    }

    @Override
    protected Row transform(Row row) {
        String url  = (urlColumn >= 0 && urlColumn < row.fieldCount())
                ? row.getString(urlColumn) : staticUrl;
        String body = (bodyColumn >= 0 && bodyColumn < row.fieldCount())
                ? row.getString(bodyColumn) : "";

        String responseBody = null;
        String statusCode   = null;
        if (url != null) {
            try {
                HttpRequest.BodyPublisher publisher = body.isEmpty()
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body);
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofMillis(timeoutMs))
                        .method(method, publisher)
                        .build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                statusCode   = String.valueOf(response.statusCode());
                responseBody = response.body();
            } catch (Exception e) {
                statusCode   = "-1";
                responseBody = e.getMessage();
            }
        }
        String[] old = row.getValues();
        String[] nw  = new String[old.length + 2];
        System.arraycopy(old, 0, nw, 0, old.length);
        nw[old.length]     = responseBody;
        nw[old.length + 1] = statusCode;
        return new Row(nw);
    }
}
