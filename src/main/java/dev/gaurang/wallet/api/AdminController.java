package dev.gaurang.wallet.api;

import dev.gaurang.wallet.api.dto.InvariantsResponse;
import dev.gaurang.wallet.service.InvariantsService;
import dev.gaurang.wallet.web.ApiException;
import dev.gaurang.wallet.web.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin")
public class AdminController {

    private final InvariantsService invariantsService;
    private final String adminToken;

    public AdminController(InvariantsService invariantsService, @Value("${app.admin-token}") String adminToken) {
        this.invariantsService = invariantsService;
        this.adminToken = adminToken;
    }

    @GetMapping("/invariants")
    public InvariantsResponse invariants(@RequestHeader(value = "X-Admin-Token", required = false) String presented) {
        if (!adminToken.equals(presented)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Invalid admin token");
        }
        return invariantsService.snapshot();
    }
}
