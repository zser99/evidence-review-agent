package com.sora.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sora.evidence.ai.AiClient;
import com.sora.evidence.common.Maps;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** API 전체 흐름과 권한: 로그인 → 신청 → 증빙 업로드 → 제출 → HR 결정. AI 서버만 가짜로 바꾼다. */
@SpringBootTest(properties = "app.storage.local-dir=target/test-storage")
@AutoConfigureMockMvc
@ActiveProfiles("local")
class ApiSecurityTest {
    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @MockitoBean
    AiClient ai;

    private static Map<String, Object> field(Object v) {
        return Maps.of("value", v, "confidence", 0.99, "found", true, "bbox", List.of(1, 2, 3, 4), "page", 0);
    }

    @BeforeEach
    void stubAi() {
        Map<String, Object> fields = Maps.of("student_name", field("김민준"), "school_name", field("한빛대학교"), "course", field("학사과정"),
                "semester", field("2026-2"), "tuition_total", field(3_200_000), "paid_amount", field(3_200_000),
                "payment_date", field(java.time.LocalDate.now().minusDays(5).toString()), "issue_date", field("2026-09-01"),
                "issuer", field("한빛대학교 총장"), "seal", field("있음"));
        when(ai.extract(any(), anyString(), any(), anyBoolean())).thenAnswer(inv -> Maps.of(
                "doc_check", Maps.of("matched", true, "title", "등록금 납입증명서"), "quality", 0.98, "engine", "test", "fields", fields,
                "tables", Maps.of("payment_items", new ArrayList<>(List.of(Maps.of("name", "수업료", "amount", 3_200_000, "_confidence", 0.99)))),
                "pages", new ArrayList<>(List.of(Maps.of("index", 0, "width", 100, "height", 140, "image_jpeg_b64", "/9j/4AAQ")))));
        List<Map<String, Object>> results = new ArrayList<>();
        for (String id : List.of("J-INSTITUTION", "J-ITEMS", "J-FORMAL", "J-ELIGIBLE-COURSE")) {
            results.add(Maps.of("id", id, "status", "ok", "satisfied", true, "confidence", 0.97, "answer", "x", "answer_label", "x", "inputs", Maps.of(), "clauses", List.of()));
        }
        when(ai.judge(any())).thenReturn(Maps.of("provider", "test", "model", "test-1", "results", results));
        when(ai.indexPolicy(anyString(), anyString(), anyString(), anyString())).thenReturn(Maps.of("clauses", List.of(), "clause_count", 0));
    }

    private String login(String user) throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + user + "\",\"password\":\"demo1234\"}")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return "Bearer " + json.readTree(body).get("token").asText();
    }

    @Test
    void rejectsUnauthenticatedAndBadCredentials() throws Exception {
        mvc.perform(get("/api/applications")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"employee1\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void employeeCannotUseStaffApis() throws Exception {
        String emp = login("employee1");
        mvc.perform(get("/api/review/queue").header("Authorization", emp)).andExpect(status().isForbidden());
        mvc.perform(post("/api/evaluations").header("Authorization", emp)).andExpect(status().isForbidden());
        mvc.perform(get("/api/policies").header("Authorization", emp)).andExpect(status().isForbidden());
        mvc.perform(get("/api/applications?scope=all").header("Authorization", emp)).andExpect(status().isForbidden());
        // HR은 규정을 볼 수 있지만 올리거나 시행할 수는 없다
        String hr = login("hr1");
        mvc.perform(get("/api/policies").header("Authorization", hr)).andExpect(status().isOk());
        mvc.perform(post("/api/policies").header("Authorization", hr).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
    }

    @Test
    void fullFlowWithOwnershipAndAudit() throws Exception {
        String emp = login("employee1");
        String form = "{\"supportType\":\"tuition\",\"form\":{\"target_name\":\"김민준\",\"relationship\":\"본인\",\"school_name\":\"한빛대학교\","
                + "\"semester\":\"2026-2\",\"requested_amount\":3200000}}";
        JsonNode created = json.readTree(mvc.perform(post("/api/applications").header("Authorization", emp).contentType(MediaType.APPLICATION_JSON).content(form))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DRAFT")).andReturn().getResponse().getContentAsString());
        long id = created.get("id").asLong();

        // 형식을 속인 파일은 받지 않는다
        mvc.perform(multipart("/api/applications/" + id + "/evidence").file(new MockMultipartFile("file", "a.pdf", "application/pdf", "not a pdf at all".getBytes()))
                .param("docId", "tuition_payment_certificate").header("Authorization", emp)).andExpect(status().isBadRequest());

        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0};
        mvc.perform(multipart("/api/applications/" + id + "/evidence").file(new MockMultipartFile("file", "증명서.png", "image/png", png))
                        .param("docId", "tuition_payment_certificate").header("Authorization", emp))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documents[0].file.fields[0].display").value("김민준"))
                .andExpect(jsonPath("$.precheck.comparisons[?(@.id=='C-AMOUNT')].status").value("MATCH"))
                .andExpect(jsonPath("$.documents[0].file.pages[0].image_jpeg_b64").doesNotExist());

        // 다른 구성원에게는 존재하지 않는 건으로 보인다
        String other = login("employee2");
        mvc.perform(get("/api/applications/" + id).header("Authorization", other)).andExpect(status().isNotFound());
        mvc.perform(post("/api/applications/" + id + "/submit").header("Authorization", other)).andExpect(status().isNotFound());

        mvc.perform(post("/api/applications/" + id + "/submit").header("Authorization", emp)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AUTO_APPROVED")).andExpect(jsonPath("$.review.verdict").value("APPROVE"))
                .andExpect(jsonPath("$.audit").doesNotExist());
        // 처리된 건은 다시 제출하거나 증빙을 바꿀 수 없다
        mvc.perform(post("/api/applications/" + id + "/submit").header("Authorization", emp)).andExpect(status().isConflict());

        // 같은 대상자·학기의 두 번째 신청은 중복 규칙에 걸려 반려된다
        long dup = json.readTree(mvc.perform(post("/api/applications").header("Authorization", emp).contentType(MediaType.APPLICATION_JSON).content(form))
                .andReturn().getResponse().getContentAsString()).get("id").asLong();
        mvc.perform(multipart("/api/applications/" + dup + "/evidence").file(new MockMultipartFile("file", "b.png", "image/png", png))
                .param("docId", "tuition_payment_certificate").header("Authorization", emp)).andExpect(status().isOk());
        mvc.perform(post("/api/applications/" + dup + "/submit").header("Authorization", emp)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUPPLEMENT_REQUIRED"))
                .andExpect(jsonPath("$.review.issues[?(@.code=='S-DUPLICATE')].severity").value("REJECT"));

        // HR: 대기 목록에서 보고, 자동 판정을 뒤집으려면 사유가 필요하다
        String hr = login("hr1");
        mvc.perform(get("/api/review/queue?kind=rejected").header("Authorization", hr)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(dup));
        mvc.perform(post("/api/review/applications/" + dup + "/decision").header("Authorization", hr).contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"APPROVE\"}")).andExpect(status().isBadRequest());
        String decided = mvc.perform(post("/api/review/applications/" + dup + "/decision").header("Authorization", hr).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\",\"comment\":\"앞선 건은 취소 예정으로 확인\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED")).andReturn().getResponse().getContentAsString();
        JsonNode audit = json.readTree(decided).get("audit");
        assertThat(audit.findValuesAsText("action")).contains("APPLICATION_CREATED", "EVIDENCE_UPLOADED", "REVIEW_COMPLETED", "HR_DECISION");
        // 검수 스냅샷은 그대로 남아 있다
        mvc.perform(get("/api/applications/" + dup + "/runs/1").header("Authorization", hr)).andExpect(status().isOk())
                .andExpect(jsonPath("$.verdict").value("REJECT")).andExpect(jsonPath("$.policy.version").value("2026.1"));
    }
}
