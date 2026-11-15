package com.sora.evidence.auth;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import com.sora.evidence.common.ApiException;
import com.sora.evidence.common.Maps;
import com.sora.evidence.config.AppProperties;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final JwtEncoder jwt;
    private final AppProperties props;
    private final CurrentUser current;

    public AuthController(UserRepository users, PasswordEncoder encoder, JwtEncoder jwt, AppProperties props, CurrentUser current) {
        this.users = users;
        this.encoder = encoder;
        this.jwt = jwt;
        this.props = props;
        this.current = current;
    }

    public record LoginRequest(String username, String password) {
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody LoginRequest req) {
        User u = users.findByUsername(req.username() == null ? "" : req.username().trim()).orElse(null);
        if (u == null || req.password() == null || !encoder.matches(req.password(), u.getPasswordHash())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "아이디 또는 비밀번호가 올바르지 않습니다.");
        }
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("evidence-agent").subject(u.getUsername()).issuedAt(now)
                .expiresAt(now.plus(props.jwtTtlMinutes(), ChronoUnit.MINUTES))
                .claim("role", u.getRole()).claim("name", u.getName()).build();
        String token = jwt.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        return Maps.of("token", token, "user", view(u));
    }

    @GetMapping("/me")
    public Map<String, Object> me() {
        return view(current.get());
    }

    public static Map<String, Object> view(User u) {
        return Maps.of("id", u.getId(), "username", u.getUsername(), "name", u.getName(), "role", u.getRole(),
                "employeeNo", u.getEmployeeNo(), "hireDate", u.getHireDate() == null ? null : u.getHireDate().toString(),
                "department", u.getDepartment());
    }
}
