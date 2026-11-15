package com.sora.evidence.application;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.sora.evidence.ai.AiClient;
import com.sora.evidence.application.Repositories.ApplicationRepository;
import com.sora.evidence.application.Repositories.AuditLogRepository;
import com.sora.evidence.application.Repositories.EvidenceFileRepository;
import com.sora.evidence.application.Repositories.ReviewRunRepository;
import com.sora.evidence.auth.User;
import com.sora.evidence.common.ApiException;
import com.sora.evidence.common.Maps;
import com.sora.evidence.policy.PolicyDefinition;
import com.sora.evidence.policy.PolicyService;
import com.sora.evidence.policy.PolicyVersion;
import com.sora.evidence.review.ReviewEngine;
import com.sora.evidence.review.ReviewInput;
import com.sora.evidence.review.ReviewInput.DocInput;
import com.sora.evidence.review.ValueComparator;
import com.sora.evidence.storage.StorageService;
import org.springframework.stereotype.Service;

/** 신청 접수 → 증빙 업로드·추출 → 검수 → 담당자 결정까지의 업무 흐름. */
@Service
public class ApplicationService {
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final Set<String> OPEN = Set.of(Application.AUTO_APPROVED, Application.NEEDS_REVIEW, Application.APPROVED, Application.SUPPLEMENT_REQUIRED);
    private static final Map<String, String> STATUS_LABEL = Map.of(
            Application.DRAFT, "작성 중", Application.AUTO_APPROVED, "승인(자동)", Application.SUPPLEMENT_REQUIRED, "반려 · 보완 필요",
            Application.NEEDS_REVIEW, "담당자 확인 중", Application.APPROVED, "승인(담당자)", Application.REJECTED, "반려 확정");

    private final ApplicationRepository apps;
    private final EvidenceFileRepository files;
    private final ReviewRunRepository runs;
    private final AuditLogRepository audits;
    private final AuditService audit;
    private final PolicyService policies;
    private final ReviewEngine engine;
    private final AiClient ai;
    private final StorageService storage;

    public ApplicationService(ApplicationRepository apps, EvidenceFileRepository files, ReviewRunRepository runs, AuditLogRepository audits,
                              AuditService audit, PolicyService policies, ReviewEngine engine, AiClient ai, StorageService storage) {
        this.apps = apps;
        this.files = files;
        this.runs = runs;
        this.audits = audits;
        this.audit = audit;
        this.policies = policies;
        this.engine = engine;
        this.ai = ai;
        this.storage = storage;
    }

    // ------------------------------------------------------------------ 조회 권한

    /** 구성원은 본인 신청만, HR·관리자는 전체를 볼 수 있다. 남의 건은 존재 여부도 알려 주지 않는다. */
    public Application load(User u, Long id) {
        Application a = apps.findById(id).orElseThrow(() -> ApiException.notFound("신청 건을 찾을 수 없습니다."));
        if (!u.isStaff() && !a.getApplicant().getId().equals(u.getId())) {
            throw ApiException.notFound("신청 건을 찾을 수 없습니다.");
        }
        return a;
    }

    private Application loadOwned(User u, Long id) {
        Application a = load(u, id);
        if (!a.getApplicant().getId().equals(u.getId())) {
            throw ApiException.forbidden("본인의 신청 건만 수정할 수 있습니다.");
        }
        return a;
    }

    // ------------------------------------------------------------------ 신청서

    public Application create(User u, String supportType, Map<String, Object> form) {
        PolicyVersion v = policies.active(supportType);
        String no = supportType.substring(0, 1).toUpperCase() + "-" + LocalDate.now(KST).format(DateTimeFormatter.BASIC_ISO_DATE)
                + "-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        Application a = apps.save(new Application(no, u, v, engine.coerceForm(policies.definition(v), form)));
        audit.log(a.getId(), u, "APPLICATION_CREATED", Maps.of("supportType", supportType, "policyVersion", v.getVersion()));
        return a;
    }

    public Application updateForm(User u, Long id, Map<String, Object> form) {
        Application a = loadOwned(u, id);
        if (!a.editable()) {
            throw ApiException.conflict("이미 처리된 신청은 수정할 수 없습니다.");
        }
        a.setForm(engine.coerceForm(policies.definition(a.getPolicyVersion()), form));
        a.touch();
        return apps.save(a);
    }

    public void deleteDraft(User u, Long id) {
        Application a = loadOwned(u, id);
        if (!Application.DRAFT.equals(a.getStatus())) {
            throw ApiException.conflict("작성 중인 신청만 삭제할 수 있습니다.");
        }
        for (EvidenceFile f : files.findByApplicationIdOrderByUploadedAtAsc(id)) {
            removeObjects(f);
            files.delete(f);
        }
        apps.delete(a);
    }

    // ------------------------------------------------------------------ 증빙

    public EvidenceFile upload(User u, Long id, String docId, String filename, String contentType, byte[] data) {
        Application a = loadOwned(u, id);
        if (!a.editable()) {
            throw ApiException.conflict("이미 처리된 신청에는 증빙을 올릴 수 없습니다.");
        }
        PolicyDefinition def = policies.definition(a.getPolicyVersion());
        Map<String, Object> docDef = def.document(docId);
        if (docDef == null) {
            throw ApiException.badRequest("이 지원 종류에서 받지 않는 서류입니다: " + docId);
        }
        String ext = sniff(data);
        String key = "applications/" + a.getApplicationNo() + "/" + docId + "/" + UUID.randomUUID();
        storage.put(key + "." + ext, data, "pdf".equals(ext) ? "application/pdf" : "image/" + ("jpg".equals(ext) ? "jpeg" : ext));
        Map<String, Object> x;
        try {
            x = ai.extract(data, ext, docDef, true);
        } catch (RuntimeException e) {
            storage.delete(key + "." + ext);
            throw e;
        }
        // 미리보기 페이지는 비공개 저장소에 따로 두고, DB에는 추출 결과와 위치 정보만 남긴다
        List<String> pageKeys = new ArrayList<>();
        for (Map<String, Object> pg : Maps.listOfMaps(x.get("pages"))) {
            Object b64 = pg.remove("image_jpeg_b64");
            if (b64 != null) {
                String pk = key + "_p" + pg.get("index") + ".jpg";
                storage.put(pk, Base64.getDecoder().decode(String.valueOf(b64)), "image/jpeg");
                pageKeys.add(pk);
            }
        }
        for (EvidenceFile old : files.findByApplicationIdAndActiveTrueOrderByUploadedAtAsc(id)) {
            if (old.getDocId().equals(docId)) {
                old.setActive(false);
                files.save(old);
            }
        }
        EvidenceFile f = new EvidenceFile(id, docId, safeName(filename), contentType, data.length, sha256(data), key + "." + ext);
        f.setPageKeys(pageKeys);
        f.setExtraction(x);
        f.setQuality(Maps.dbl(x.get("quality"), 0));
        f.setEngine(Maps.str(x.get("engine")));
        f.setTitleMatched(Maps.bool(Maps.map(x.get("doc_check")).get("matched"), false));
        f = files.save(f);
        a.touch();
        apps.save(a);
        audit.log(id, u, "EVIDENCE_UPLOADED", Maps.of("fileId", f.getId(), "docId", docId, "filename", f.getFilename(),
                "sha256", f.getSha256(), "engine", f.getEngine(), "quality", f.getQuality(), "titleMatched", f.isTitleMatched()));
        return f;
    }

    public void removeEvidence(User u, Long id, Long fileId) {
        Application a = loadOwned(u, id);
        if (!a.editable()) {
            throw ApiException.conflict("이미 처리된 신청의 증빙은 삭제할 수 없습니다.");
        }
        EvidenceFile f = files.findByIdAndApplicationId(fileId, id).orElseThrow(() -> ApiException.notFound("증빙 파일을 찾을 수 없습니다."));
        f.setActive(false);   // 검수 이력이 가리킬 수 있으므로 지우지 않고 비활성으로 둔다
        files.save(f);
        audit.log(id, u, "EVIDENCE_REMOVED", Maps.of("fileId", fileId, "docId", f.getDocId()));
    }

    public byte[] page(User u, Long id, Long fileId, int page) {
        load(u, id);
        EvidenceFile f = files.findByIdAndApplicationId(fileId, id).orElseThrow(() -> ApiException.notFound("증빙 파일을 찾을 수 없습니다."));
        if (f.getPageKeys() == null || page < 0 || page >= f.getPageKeys().size()) {
            throw ApiException.notFound("페이지를 찾을 수 없습니다.");
        }
        return storage.get(f.getPageKeys().get(page));
    }

    public EvidenceFile original(User u, Long id, Long fileId) {
        Application a = load(u, id);
        EvidenceFile f = files.findByIdAndApplicationId(fileId, id).orElseThrow(() -> ApiException.notFound("증빙 파일을 찾을 수 없습니다."));
        audit.log(a.getId(), u, "EVIDENCE_ORIGINAL_VIEWED", Maps.of("fileId", fileId));
        return f;
    }

    public byte[] bytes(EvidenceFile f) {
        return storage.get(f.getStorageKey());
    }

    // ------------------------------------------------------------------ 검수

    private ReviewInput input(Application a, LocalDate appliedOn, boolean checkDuplicates) {
        User ap = a.getApplicant();
        Map<String, Object> emp = Maps.of("name", ap.getName(), "hire_date", ap.getHireDate() == null ? null : ap.getHireDate().toString(),
                "employee_no", ap.getEmployeeNo());
        List<DocInput> docs = new ArrayList<>();
        for (EvidenceFile f : files.findByApplicationIdAndActiveTrueOrderByUploadedAtAsc(a.getId())) {
            docs.add(new DocInput(f.getDocId(), f.getId(), f.getFilename(), f.getExtraction()));
        }
        return new ReviewInput(a.getPolicyVersion().getId(), policies.definition(a.getPolicyVersion()), a.getForm(), emp, appliedOn, docs,
                checkDuplicates ? key -> apps.existsByDedupKeyAndStatusInAndIdNot(key, OPEN, a.getId()) : null);
    }

    public Map<String, Object> submit(User u, Long id) {
        Application a = loadOwned(u, id);
        if (!a.editable()) {
            throw ApiException.conflict("이미 처리된 신청입니다.");
        }
        if (Application.DRAFT.equals(a.getStatus())) {
            a.setPolicyVersion(policies.active(a.getSupportType()));   // 제출 시점에 시행 중인 규정을 적용
        }
        PolicyDefinition def = policies.definition(a.getPolicyVersion());
        List<String> missing = new ArrayList<>();
        for (Map<String, Object> f : def.applicationForm()) {
            if (Maps.bool(f.get("required"), false) && a.getForm().get(Maps.str(f.get("key"))) == null) {
                missing.add(Maps.str(f.get("label")));
            }
        }
        if (!missing.isEmpty()) {
            throw ApiException.badRequest("신청 내용을 모두 입력해 주세요: " + String.join(", ", missing));
        }
        boolean resubmit = a.getLastRunNo() > 0;
        LocalDate appliedOn = a.getAppliedOn() != null ? a.getAppliedOn() : LocalDate.now(KST);   // 보완 제출은 최초 신청일 기준
        Map<String, Object> result = engine.review(input(a, appliedOn, true));
        String verdict = Maps.str(result.get("verdict"));
        Map<String, Object> model = Maps.map(result.get("decisionModel"));
        int runNo = a.getLastRunNo() + 1;
        result.put("runNo", runNo);
        runs.save(new ReviewRun(id, runNo, a.getPolicyVersion().getId(), resubmit ? "RESUBMIT" : "SUBMIT", verdict,
                Maps.dbl(result.get("confidence"), 0), Maps.str(model.get("provider")), Maps.str(model.get("model")), result, u.getUsername()));
        a.setLastRunNo(runNo);
        a.setAppliedOn(appliedOn);
        if (a.getSubmittedAt() == null) {
            a.setSubmittedAt(Instant.now());
        }
        a.setAutoVerdict(verdict);
        a.setConfidence(Maps.dbl(result.get("confidence"), 0));
        a.setGuidance(Maps.str(result.get("guidance")));
        a.setDedupKey(Maps.str(result.get("dedupKey")));
        a.setFinalDecision(null);
        a.setDecidedBy(null);
        a.setDecidedAt(null);
        a.setDecisionComment(null);
        switch (verdict) {
            case "APPROVE" -> {
                if (def.autoFinalizeApproval()) {
                    a.setStatus(Application.AUTO_APPROVED);
                    a.setFinalDecision("APPROVE");
                    a.setDecidedBy("자동 검수");
                    a.setDecidedAt(Instant.now());
                } else {
                    a.setStatus(Application.NEEDS_REVIEW);
                }
            }
            case "REJECT" -> a.setStatus(Application.SUPPLEMENT_REQUIRED);
            default -> a.setStatus(Application.NEEDS_REVIEW);
        }
        a.touch();
        apps.save(a);
        audit.log(id, u, "REVIEW_COMPLETED", Maps.of("runNo", runNo, "verdict", verdict, "confidence", result.get("confidence"),
                "policyVersion", a.getPolicyVersion().getVersion(), "provider", model.get("provider"), "model", model.get("model"),
                "issues", Maps.listOfMaps(result.get("issues")).stream().map(i -> i.get("code")).toList()));
        return detail(u, id);
    }

    /** HR 담당자의 최종 결정. 에이전트 판정과 다르게 결정할 때는 사유를 남겨야 한다. */
    public Map<String, Object> decide(User hr, Long id, String decision, String comment, String guidanceText) {
        Application a = load(hr, id);
        if (Application.DRAFT.equals(a.getStatus())) {
            throw ApiException.conflict("아직 제출되지 않은 신청입니다.");
        }
        if (!"APPROVE".equals(decision) && !"REJECT".equals(decision)) {
            throw ApiException.badRequest("결정은 APPROVE 또는 REJECT여야 합니다.");
        }
        boolean overridden = a.getAutoVerdict() != null && !"REVIEW".equals(a.getAutoVerdict()) && !decision.equals(a.getAutoVerdict());
        if (overridden && (comment == null || comment.isBlank())) {
            throw ApiException.badRequest("자동 판정과 다르게 결정할 때는 사유를 입력해 주세요.");
        }
        a.setStatus("APPROVE".equals(decision) ? Application.APPROVED : Application.REJECTED);
        a.setFinalDecision(decision);
        a.setDecidedBy(hr.getName() + "(" + hr.getUsername() + ")");
        a.setDecidedAt(Instant.now());
        a.setDecisionComment(comment);
        if (guidanceText != null && !guidanceText.isBlank()) {
            a.setGuidance(guidanceText.trim());
        } else if ("APPROVE".equals(decision) && !"APPROVE".equals(a.getAutoVerdict())) {
            a.setGuidance("HR 담당자가 직접 확인하여 승인했습니다.");
        }
        a.touch();
        apps.save(a);
        audit.log(id, hr, "HR_DECISION", Maps.of("decision", decision, "autoVerdict", a.getAutoVerdict(), "overridden", overridden,
                "comment", comment, "runNo", a.getLastRunNo()));
        return detail(hr, id);
    }

    public Map<String, Object> polishGuidance(User hr, Long id, String draft) {
        Application a = load(hr, id);
        List<String> names = new ArrayList<>(List.of(a.getApplicant().getName()));
        Object target = a.getForm().get("target_name");
        if (target != null) {
            names.add(String.valueOf(target));
        }
        return ai.polish(draft == null || draft.isBlank() ? a.getGuidance() : draft, a.getAutoVerdict() == null ? "REVIEW" : a.getAutoVerdict(), names);
    }

    // ------------------------------------------------------------------ 화면용 조회

    public List<Map<String, Object>> listMine(User u) {
        return apps.findByApplicantIdOrderByCreatedAtDesc(u.getId()).stream().map(a -> row(a, false)).toList();
    }

    public List<Map<String, Object>> listAll(String status) {
        List<Application> list = status == null || status.isBlank() ? apps.findByStatusNotOrderByCreatedAtDesc(Application.DRAFT)
                : apps.findByStatusInOrderByCreatedAtDesc(List.of(status.split(",")));
        return list.stream().map(a -> row(a, false)).toList();
    }

    /** 검토 대기 목록: 담당자 확인이 필요한 건을 확신도가 낮은 순으로. */
    public List<Map<String, Object>> queue(String kind) {
        List<String> statuses = "rejected".equals(kind) ? List.of(Application.SUPPLEMENT_REQUIRED)
                : "all".equals(kind) ? List.of(Application.NEEDS_REVIEW, Application.SUPPLEMENT_REQUIRED) : List.of(Application.NEEDS_REVIEW);
        return apps.reviewQueue(statuses).stream().map(a -> row(a, true)).toList();
    }

    public Map<String, Object> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        for (String s : STATUS_LABEL.keySet()) {
            m.put(s, apps.countByStatus(s));
        }
        return m;
    }

    private Map<String, Object> row(Application a, boolean withReasons) {
        Map<String, Object> m = Maps.of("id", a.getId(), "applicationNo", a.getApplicationNo(), "supportType", a.getSupportType(),
                "supportName", a.getPolicyVersion().getName(), "policyVersion", a.getPolicyVersion().getVersion(),
                "applicantName", a.getApplicant().getName(), "department", a.getApplicant().getDepartment(),
                "targetName", a.getForm().get("target_name"), "requestedAmount", a.getForm().get("requested_amount"),
                "status", a.getStatus(), "statusLabel", STATUS_LABEL.get(a.getStatus()), "autoVerdict", a.getAutoVerdict(),
                "confidence", a.getConfidence(), "finalDecision", a.getFinalDecision(),
                "createdAt", a.getCreatedAt().toString(), "submittedAt", a.getSubmittedAt() == null ? null : a.getSubmittedAt().toString());
        if (withReasons) {
            m.put("reasons", runs.findFirstByApplicationIdOrderByRunNoDesc(a.getId())
                    .map(r -> Maps.listOfMaps(r.getResult().get("issues")).stream()
                            .map(i -> Maps.of("label", i.get("label"), "severity", i.get("severity"), "kind", i.get("kind"))).toList())
                    .orElse(List.of()));
        }
        return m;
    }

    public Map<String, Object> detail(User u, Long id) {
        Application a = load(u, id);
        PolicyVersion pv = a.getPolicyVersion();
        PolicyDefinition def = policies.definition(pv);
        LocalDate appliedOn = a.getAppliedOn() != null ? a.getAppliedOn() : LocalDate.now(KST);
        Map<String, Object> pre = engine.precheck(input(a, appliedOn, false));
        Map<String, Map<String, Object>> preDocs = new LinkedHashMap<>();
        for (Map<String, Object> d : Maps.listOfMaps(pre.get("documents"))) {
            preDocs.put(Maps.str(d.get("id")), d);
        }
        Map<String, EvidenceFile> active = new LinkedHashMap<>();
        for (EvidenceFile f : files.findByApplicationIdAndActiveTrueOrderByUploadedAtAsc(id)) {
            active.put(f.getDocId(), f);
        }
        List<Map<String, Object>> docs = new ArrayList<>();
        for (Map<String, Object> d : def.documents()) {
            String docId = Maps.str(d.get("id"));
            Map<String, Object> pd = preDocs.getOrDefault(docId, Map.of());
            Map<String, Object> doc = Maps.of("id", docId, "name", d.get("name"), "description", d.get("description"),
                    "required", pd.get("required"), "status", pd.get("status"), "reason", pd.get("reason"));
            EvidenceFile f = active.get(docId);
            doc.put("file", f == null ? null : fileView(def, d, f));
            docs.add(doc);
        }
        List<Map<String, Object>> formView = new ArrayList<>();
        for (Map<String, Object> f : def.applicationForm()) {
            Object v = a.getForm().get(Maps.str(f.get("key")));
            formView.add(Maps.of("key", f.get("key"), "label", f.get("label"), "type", f.get("type"), "value", v,
                    "display", ValueComparator.display(v, Maps.str(f, "type", "text"))));
        }
        List<ReviewRun> history = runs.findByApplicationIdOrderByRunNoDesc(id);
        User ap = a.getApplicant();
        Map<String, Object> out = Maps.of("id", a.getId(), "applicationNo", a.getApplicationNo(), "supportType", a.getSupportType(),
                "supportName", pv.getName(), "status", a.getStatus(), "statusLabel", STATUS_LABEL.get(a.getStatus()), "editable", a.editable(),
                "policy", Maps.of("id", pv.getId(), "version", pv.getVersion(), "status", pv.getStatus(),
                        "effectiveDate", pv.getEffectiveDate() == null ? null : pv.getEffectiveDate().toString()),
                "applicant", Maps.of("id", ap.getId(), "name", ap.getName(), "employeeNo", ap.getEmployeeNo(), "department", ap.getDepartment(),
                        "hireDate", ap.getHireDate() == null ? null : ap.getHireDate().toString()),
                "form", a.getForm(), "formView", formView, "definition", def.publicView(),
                "appliedOn", a.getAppliedOn() == null ? null : a.getAppliedOn().toString(), "createdAt", a.getCreatedAt().toString(),
                "submittedAt", a.getSubmittedAt() == null ? null : a.getSubmittedAt().toString(),
                "autoVerdict", a.getAutoVerdict(), "confidence", a.getConfidence(), "guidance", a.getGuidance(),
                "finalDecision", a.getFinalDecision(), "decidedBy", a.getDecidedBy(),
                "decidedAt", a.getDecidedAt() == null ? null : a.getDecidedAt().toString(), "decisionComment", a.getDecisionComment(),
                "documents", docs,
                "precheck", Maps.of("comparisons", pre.get("comparisons"), "missingDocuments", pre.get("missingDocuments")),
                "review", history.isEmpty() ? null : history.get(0).getResult(),
                "runs", history.stream().map(r -> Maps.of("runNo", r.getRunNo(), "verdict", r.getVerdict(), "confidence", r.getConfidence(),
                        "trigger", r.getTriggerType(), "provider", r.getProvider(), "model", r.getModel(),
                        "policyVersionId", r.getPolicyVersionId(), "createdAt", r.getCreatedAt().toString())).toList());
        if (u.isStaff()) {
            out.put("audit", audits.findByApplicationIdOrderByCreatedAtAsc(id).stream().map(l -> Maps.of("actor", l.getActor(),
                    "role", l.getActorRole(), "action", l.getAction(), "detail", l.getDetail(), "at", l.getCreatedAt().toString())).toList());
        }
        return out;
    }

    public Map<String, Object> run(User u, Long id, int runNo) {
        load(u, id);
        return runs.findByApplicationIdOrderByRunNoDesc(id).stream().filter(r -> r.getRunNo() == runNo).findFirst()
                .map(ReviewRun::getResult).orElseThrow(() -> ApiException.notFound("검수 이력을 찾을 수 없습니다."));
    }

    /** 원본 옆에 표시할 추출 항목(값·신뢰도·위치·상태). */
    private Map<String, Object> fileView(PolicyDefinition def, Map<String, Object> docDef, EvidenceFile f) {
        Map<String, Object> x = f.getExtraction() == null ? Map.of() : f.getExtraction();
        Map<String, Object> fields = Maps.map(x.get("fields"));
        double min = def.ocrMinConfidence();
        List<Map<String, Object>> fv = new ArrayList<>();
        for (Map<String, Object> fd : Maps.listOfMaps(docDef.get("fields"))) {
            Map<String, Object> r = Maps.map(fields.get(Maps.str(fd.get("key"))));
            boolean found = Maps.bool(r.get("found"), false) && r.get("value") != null;
            double conf = Maps.dbl(r.get("confidence"), 0);
            String type = Maps.str(fd, "type", "text");
            fv.add(Maps.of("key", fd.get("key"), "label", fd.get("label"), "type", type, "required", Maps.bool(fd.get("required"), false),
                    "value", r.get("value"), "display", ValueComparator.display(r.get("value"), type), "confidence", conf,
                    "status", !found ? "NOT_FOUND" : conf < min ? "LOW_CONFIDENCE" : "OK", "bbox", r.get("bbox"), "page", r.get("page")));
        }
        List<Map<String, Object>> tv = new ArrayList<>();
        Map<String, Object> tables = Maps.map(x.get("tables"));
        for (Map<String, Object> td : Maps.listOfMaps(docDef.get("tables"))) {
            tv.add(Maps.of("key", td.get("key"), "label", td.get("label"), "columns", Maps.listOfMaps(td.get("columns")).stream()
                    .map(c -> Maps.of("key", c.get("key"), "type", c.get("type"))).toList(), "rows", Maps.listOfMaps(tables.get(Maps.str(td.get("key"))))));
        }
        Map<String, Object> check = Maps.map(x.get("doc_check"));
        return Maps.of("id", f.getId(), "filename", f.getFilename(), "sizeBytes", f.getSizeBytes(), "uploadedAt", f.getUploadedAt().toString(),
                "sha256", f.getSha256(), "engine", f.getEngine(), "quality", f.getQuality(), "lowQuality", f.getQuality() != null && f.getQuality() < def.ocrMinDocQuality(),
                "titleMatched", f.isTitleMatched(), "title", check.get("title"), "titleReason", check.get("reason"),
                "pages", Maps.listOfMaps(x.get("pages")), "fields", fv, "tables", tv, "elapsedMs", x.get("elapsed_ms"));
    }

    // ------------------------------------------------------------------ 도우미

    private void removeObjects(EvidenceFile f) {
        try {
            storage.delete(f.getStorageKey());
            if (f.getPageKeys() != null) {
                f.getPageKeys().forEach(storage::delete);
            }
        } catch (RuntimeException ignore) {
            // 저장소 정리는 실패해도 업무 흐름을 막지 않는다
        }
    }

    /** 확장자·Content-Type을 믿지 않고 파일 앞부분으로 형식을 확인한다. */
    private static String sniff(byte[] d) {
        if (d == null || d.length < 8) {
            throw ApiException.badRequest("빈 파일입니다.");
        }
        if (d[0] == '%' && d[1] == 'P' && d[2] == 'D' && d[3] == 'F') {
            return "pdf";
        }
        if ((d[0] & 0xFF) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G') {
            return "png";
        }
        if ((d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xD8) {
            return "jpg";
        }
        throw ApiException.badRequest("PDF, PNG, JPG 파일만 올릴 수 있습니다.");
    }

    private static String safeName(String name) {
        String n = name == null ? "evidence" : name.replaceAll("[\\\\/\\r\\n\\t]", "_");
        return n.length() > 200 ? n.substring(n.length() - 200) : n;
    }

    private static String sha256(byte[] d) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(d));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
