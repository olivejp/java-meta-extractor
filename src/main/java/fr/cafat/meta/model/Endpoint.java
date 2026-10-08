package fr.cafat.meta.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record Endpoint(
    @JsonProperty("id") String id,
    @JsonProperty("framework") String framework,
    @JsonProperty("method") String method,
    @JsonProperty("path") String path,
    @JsonProperty("handler") String handler,
    @JsonProperty("request_type") String requestType,
    @JsonProperty("response_type") String responseType,
    @JsonProperty("source") Source source) {

  public Endpoint withId(String newId) {
    return new Endpoint(newId, framework, method, path, handler, requestType, responseType, source);
  }
}
