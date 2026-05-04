package com.example;

import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import com.github.osodevops.akka.openapi.annotations.OpenAPIResponse;
import com.github.osodevops.akka.openapi.annotations.OpenAPISummary;

@HttpEndpoint("/widgets")
public class WidgetEndpoint {

    @Get("/{id}")
    @OpenAPISummary("Get a widget by id")
    @OpenAPIResponse(status = "200", description = "Widget found")
    public Widget getWidget(String id) {
        return new Widget(id, "Sample widget");
    }
}
