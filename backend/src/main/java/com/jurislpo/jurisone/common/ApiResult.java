package com.jurislpo.jurisone.common;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The response body of every JSON endpoint — the shape the web app expects:
 * {@code {ok:true, msg?, data?}} or {@code {ok:false, error}}. Its own empty fields are omitted; nulls inside {@code data} are kept.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResult<T>(boolean ok, String msg, T data, String error) {

    public static <T> ApiResult<T> success() { return new ApiResult<>(true, null, null, null); }
    public static <T> ApiResult<T> success(T data) { return new ApiResult<>(true, null, data, null); }
    public static <T> ApiResult<T> success(String msg, T data) { return new ApiResult<>(true, msg, data, null); }
    public static <T> ApiResult<T> message(String msg) { return new ApiResult<>(true, msg, null, null); }
    public static <T> ApiResult<T> fail(String error) { return new ApiResult<>(false, null, null, error); }
}
