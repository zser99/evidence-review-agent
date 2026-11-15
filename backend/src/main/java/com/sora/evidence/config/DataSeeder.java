package com.sora.evidence.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Stream;

import com.sora.evidence.auth.User;
import com.sora.evidence.auth.UserRepository;
import com.sora.evidence.evaluation.EvaluationService;
import com.sora.evidence.policy.PolicyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** 최초 기동 시 데모 계정과 초기 규정(seed/policies/*)을 넣는다. 이미 있으면 건드리지 않는다. */
@Component
public class DataSeeder implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);
    private final AppProperties props;
    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final PolicyService policies;
    private final EvaluationService evaluations;

    public DataSeeder(AppProperties props, UserRepository users, PasswordEncoder encoder, PolicyService policies, EvaluationService evaluations) {
        this.props = props;
        this.users = users;
        this.encoder = encoder;
        this.policies = policies;
        this.evaluations = evaluations;
    }

    private record Demo(String username, String name, String role, String employeeNo, String hireDate, String department) {
    }

    @Override
    public void run(ApplicationArguments args) {
        evaluations.failStaleRuns();
        if (props.seedDemoUsers()) {
            String hash = encoder.encode(props.demoPassword());
            for (Demo d : List.of(
                    new Demo("employee1", "김민준", "EMPLOYEE", "E2019031", "2019-03-04", "플랫폼개발팀"),
                    new Demo("employee2", "이서연", "EMPLOYEE", "E2015112", "2015-07-01", "재무팀"),
                    new Demo("employee3", "윤채원", "EMPLOYEE", "E2026044", "2026-06-01", "마케팅팀"),
                    new Demo("hr1", "박지훈", "HR", "E2011003", "2011-02-01", "HR서비스팀"),
                    new Demo("admin1", "최수아", "ADMIN", "E2009010", "2009-09-01", "HR기획팀"))) {
                try {
                    if (users.findByUsername(d.username()).isEmpty()) {
                        users.save(new User(d.username(), hash, d.name(), d.role(), d.employeeNo(), LocalDate.parse(d.hireDate()), d.department()));
                    }
                } catch (DataIntegrityViolationException e) {
                    log.debug("다른 파드가 먼저 만든 계정: {}", d.username());   // 여러 파드가 동시에 뜰 때
                }
            }
        }
        Path dir = Path.of(props.seedDir(), "policies");
        if (!Files.isDirectory(dir)) {
            log.warn("초기 규정 폴더가 없습니다: {}", dir.toAbsolutePath());
            return;
        }
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : s.filter(Files::isDirectory).sorted().toList()) {
                Path yaml = p.resolve("policy.yaml");
                Path reg = p.resolve("regulation.md");
                if (Files.exists(yaml) && Files.exists(reg)) {
                    try {
                        policies.seed(Files.readString(yaml, StandardCharsets.UTF_8), Files.readString(reg, StandardCharsets.UTF_8));
                    } catch (DataIntegrityViolationException e) {
                        log.debug("다른 파드가 먼저 등록한 규정: {}", p.getFileName());
                    }
                }
            }
        } catch (IOException e) {
            log.error("초기 규정 로드 실패", e);
        }
    }
}
