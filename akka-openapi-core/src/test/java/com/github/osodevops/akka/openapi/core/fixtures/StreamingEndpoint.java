package com.github.osodevops.akka.openapi.core.fixtures;

import com.github.osodevops.akka.openapi.annotations.OpenAPIResponse;
import com.github.osodevops.akka.openapi.annotations.OpenAPISummary;

/**
 * Test fixture: endpoint with custom media type responses.
 */
@MockHttpEndpoint("/api/v1/feeds")
public class StreamingEndpoint {

    @MockGet("/live")
    @OpenAPISummary("Subscribe to live feed")
    @OpenAPIResponse(status = "200", description = "Live feed established",
        mediaType = "text/event-stream", responseType = String.class)
    public String streamEvents() {
        return "";
    }

    @MockGet("/export")
    @OpenAPISummary("Export data as binary")
    @OpenAPIResponse(status = "200", description = "Binary payload",
        mediaType = "application/octet-stream", responseType = byte[].class)
    public byte[] downloadFile() {
        return new byte[0];
    }
}
