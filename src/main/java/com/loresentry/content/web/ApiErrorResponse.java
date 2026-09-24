package com.loresentry.content.web;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiErrorResponse(String error, String message, String field) {

    static ApiErrorResponse of(ApiException exception) {
        return new ApiErrorResponse(exception.error(), exception.getMessage(), exception.field());
    }
}
