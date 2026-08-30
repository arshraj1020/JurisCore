package com.juriscore.identity.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.juriscore.common.api.ApiErrorResponse;
import com.juriscore.common.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Unauthenticated requests get the same JSON envelope as everything else.
 * Without this, Spring Security returns an HTML error page and clients have to
 * special-case 401.
 */
@Component
@RequiredArgsConstructor
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        response.setStatus(ErrorCode.UNAUTHENTICATED.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(
                response.getOutputStream(),
                ApiErrorResponse.of(ErrorCode.UNAUTHENTICATED.name(),
                        ErrorCode.UNAUTHENTICATED.defaultMessage()));
    }
}
