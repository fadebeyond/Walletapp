package dev.gaurang.wallet.api;

import dev.gaurang.wallet.api.dto.IssueTokenRequest;
import dev.gaurang.wallet.api.dto.IssueTokenResponse;
import dev.gaurang.wallet.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth/tokens")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping
    public ResponseEntity<IssueTokenResponse> issue(@Valid @RequestBody IssueTokenRequest request) {
        String token = authService.issueToken(request.userId());
        return ResponseEntity.status(HttpStatus.CREATED).body(new IssueTokenResponse(request.userId(), token));
    }
}
