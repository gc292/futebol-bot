package com.caria.futebol;

import com.google.gson.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

interface FormsGateway {
    String create(String pollId, String playerName);
    VoteSnapshot read(String pollId, boolean close);
    default void delete(String pollId) { throw new FootballException("Google cleanup is unavailable. Update the Apps Script deployment."); }
}
record VoteSnapshot(List<Double> scores, boolean closed) {}

/** Credentials travel only in the server-to-Google POST body, never to the browser. */
final class GoogleFormsGateway implements FormsGateway {
    private final URI endpoint;
    private final String secret;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    GoogleFormsGateway(String url, String secret) {
        endpoint = URI.create(url);
        if (!"https".equals(endpoint.getScheme()) || !"script.google.com".equals(endpoint.getHost())
                || !endpoint.getPath().matches("/macros/s/[A-Za-z0-9_-]+/exec") || endpoint.getQuery() != null || endpoint.getUserInfo() != null)
            throw new IllegalArgumentException("FOOTBALL_FORMS_URL must be a deployed Google Apps Script /exec URL.");
        if (secret == null || secret.length() < 32) throw new IllegalArgumentException("FOOTBALL_FORMS_SECRET must contain at least 32 characters.");
        this.secret = secret;
    }
    public String create(String pollId, String playerName) {
        JsonObject result = call(Map.of("action","create","pollId",pollId,"name",playerName));
        String url = result.get("url").getAsString(); URI uri = URI.create(url);
        if (!"https".equals(uri.getScheme()) || !"docs.google.com".equals(uri.getHost()) || !uri.getPath().startsWith("/forms/") || uri.getUserInfo() != null)
            throw new FootballException("Google returned an invalid form link.");
        return url;
    }
    public VoteSnapshot read(String pollId, boolean close) {
        JsonObject result = call(Map.of("action",close ? "close" : "responses","pollId",pollId));
        List<Double> scores = new ArrayList<>();
        for (JsonElement value : result.getAsJsonArray("scores")) {
            double score = value.getAsDouble();
            if (!Double.isFinite(score) || score < 0 || score > 100) throw new FootballException("Google returned an invalid score.");
            scores.add(score);
        }
        return new VoteSnapshot(List.copyOf(scores),result.get("closed").getAsBoolean());
    }
    public void delete(String pollId) {
        JsonObject result = call(Map.of("action","cleanup","pollId",pollId));
        if (!result.has("deleted") || !result.get("deleted").getAsBoolean())
            throw new FootballException("Google did not confirm deletion. Cleanup will retry.");
    }
    private JsonObject call(Map<String,String> values) {
        Map<String,String> body = new HashMap<>(values); body.put("secret",secret);
        try {
            HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(15)).header("Content-Type","application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(new Gson().toJson(body))).build();
            HttpResponse<String> response = client.send(request,HttpResponse.BodyHandlers.ofString());
            // Apps Script ContentService serves its output from a one-time Google-hosted URL.
            if (response.statusCode() == 302 || response.statusCode() == 303) {
                URI redirect = URI.create(response.headers().firstValue("location").orElseThrow());
                if (!"https".equals(redirect.getScheme()) || !"script.googleusercontent.com".equals(redirect.getHost()) || redirect.getUserInfo() != null)
                    throw new FootballException("Check the Google Apps Script deployment and access settings.");
                response = client.send(HttpRequest.newBuilder(redirect).timeout(Duration.ofSeconds(10)).GET().build(),HttpResponse.BodyHandlers.ofString());
            }
            if (response.statusCode() != 200) throw new FootballException("Google Forms is unavailable. Retry shortly.");
            JsonObject result = JsonParser.parseString(response.body()).getAsJsonObject();
            if (!result.has("ok") || !result.get("ok").getAsBoolean()) {
                String code = result.has("errorCode") ? result.get("errorCode").getAsString() : "UNKNOWN";
                String message = switch (code) {
                    case "AUTH_FAILED" -> "The Google Script secret does not match. Set the same FOOTBALL_FORMS_SECRET in Script Properties and the website, then restart the website.";
                    case "CREATE_FORM" -> "Google could not create the form. Check Apps Script Executions for permissions or quota errors.";
                    case "OPEN_FORM" -> "Google could not open the saved form. Check Apps Script Executions and whether the form was deleted.";
                    case "CONFIGURE_FORM", "PUBLISH_FORM", "FORM_LINK" -> "Google could not finish setting up the form. Check Apps Script Executions for the error.";
                    case "CLEANUP" -> "The final rating is saved, but Google cleanup failed. Update the Apps Script manifest and authorize Drive access.";
                    case "READ_RESPONSES" -> "Google could not read or close the votes. Check Apps Script Executions for the error.";
                    default -> "Google could not complete the request. Check the secret and deployment, or update Code.gs for detailed errors.";
                };
                throw new FootballException(message);
            }
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new FootballException("Google request interrupted. Retry shortly.");
        } catch (FootballException e) { throw e; }
        catch (Exception e) { throw new FootballException("Could not contact Google Forms. Check the setup or retry shortly."); }
    }
}
