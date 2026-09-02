package com.juriscore.identity.api;

import com.juriscore.common.api.ApiResponse;
import com.juriscore.common.security.CurrentUser;
import com.juriscore.identity.api.dto.AuthTokens;
import com.juriscore.identity.api.dto.ForgotPasswordRequest;
import com.juriscore.identity.api.dto.LoginRequest;
import com.juriscore.identity.api.dto.LogoutRequest;
import com.juriscore.identity.api.dto.RefreshTokenRequest;
import com.juriscore.identity.api.dto.RegisterRequest;
import com.juriscore.identity.api.dto.ResetPasswordRequest;
import com.juriscore.identity.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Tag(name = "Authentication", description = "Sign-up, sign-in, token refresh and password recovery")
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Register a law firm and its first administrator")
    public ApiResponse<AuthTokens> register(@Valid @RequestBody RegisterRequest request,
                                            HttpServletRequest httpRequest) {
        AuthTokens tokens = authService.register(request, contextOf(httpRequest));
        return ApiResponse.ok(tokens, "Firm registered successfully");
    }

    @PostMapping("/login")
    @Operation(summary = "Exchange credentials for an access and refresh token")
    public ApiResponse<AuthTokens> login(@Valid @RequestBody LoginRequest request,
                                         HttpServletRequest httpRequest) {
        return ApiResponse.ok(authService.login(request, contextOf(httpRequest)), "Signed in successfully");
    }

    @PostMapping("/refresh")
    @Operation(summary = "Rotate a refresh token for a new token pair")
    public ApiResponse<AuthTokens> refresh(@Valid @RequestBody RefreshTokenRequest request,
                                           HttpServletRequest httpRequest) {
        return ApiResponse.ok(authService.refresh(request.refreshToken(), contextOf(httpRequest)));
    }

    @PostMapping("/logout")
    @Operation(summary = "End this session, or every session when no token is supplied")
    public ApiResponse<Void> logout(@RequestBody(required = false) LogoutRequest request) {
        String refreshToken = request == null ? null : request.refreshToken();
        authService.logout(CurrentUser.requireUserId(), refreshToken);
        return ApiResponse.message("Signed out successfully");
    }

    @PostMapping("/forgot-password")
    @Operation(summary = "Request a password reset link",
            description = "Always reports success, whether or not the address is registered.")
    public ApiResponse<Void> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        authService.requestPasswordReset(request);
        return ApiResponse.message("If that address has an account, a reset link is on its way");
    }

    @PostMapping("/reset-password")
    @Operation(summary = "Set a new password using a reset or invitation token")
    public ApiResponse<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        authService.resetPassword(request);
        return ApiResponse.message("Password updated. Sign in with your new password.");
    }

    /**
     * The address recorded against the session, for the audit trail.
     *
     * <p>{@code getRemoteAddr()} only. This used to prefer the leftmost
     * {@code X-Forwarded-For} entry on the reasoning that the app sits behind an ALB — but
     * an ALB <em>appends</em> to that header rather than replacing it, so the leftmost
     * entry is whatever the caller sent, not the first hop. Every sign-in could therefore
     * be attributed to an address of the attacker's choosing, which is worse than useless
     * in the one record that exists to say where a session came from.
     *
     * <p>Whether a forwarding header may be believed is decided by
     * {@code server.tomcat.remoteip.internal-proxies}, before any application code runs;
     * Tomcat's RemoteIpValve resolves the real client address when the request came from a
     * trusted proxy and leaves the connection's address alone when it did not. The same
     * single source of truth as the rate limiter — see {@code RateLimitFilter}.
     */
    private AuthService.RequestContext contextOf(HttpServletRequest request) {
        return new AuthService.RequestContext(
                request.getRemoteAddr(), request.getHeader("User-Agent"));
    }
}
