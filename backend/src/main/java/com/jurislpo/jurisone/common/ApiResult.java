package com.jurislpo.jurisone.common;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The response body of every JSON endpoint — the shape the web app expects:
 * {@code {ok:true, message?, data?}} or {@code {ok:false, error}}. Its own empty fields are omitted; nulls inside {@code data} are kept.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResult<T>(boolean ok, String message, T data, String error) {

    public static <T> ApiResult<T> success() { return new ApiResult<>(true, null, null, null); }
    public static <T> ApiResult<T> success(T data) { return new ApiResult<>(true, null, data, null); }
    public static <T> ApiResult<T> success(String message, T data) { return new ApiResult<>(true, message, data, null); }
    public static <T> ApiResult<T> message(String message) { return new ApiResult<>(true, message, null, null); }
    public static <T> ApiResult<T> fail(String error) { return new ApiResult<>(false, null, null, error); }
}
