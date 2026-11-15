package com.sora.evidence.storage;

import java.net.URI;

import com.sora.evidence.common.ApiException;
import com.sora.evidence.config.AppProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;

/** Amazon S3(또는 MinIO 같은 S3 호환 저장소). 버킷은 비공개이며 객체는 항상 백엔드를 거쳐서만 내려간다. */
@Service
@ConditionalOnProperty(name = "app.storage.type", havingValue = "s3", matchIfMissing = true)
public class S3StorageService implements StorageService {
    private static final Logger log = LoggerFactory.getLogger(S3StorageService.class);
    private final S3Client s3;
    private final String bucket;
    private final boolean customEndpoint;

    public S3StorageService(AppProperties props) {
        AppProperties.Storage c = props.storage();
        this.bucket = c.bucket();
        this.customEndpoint = c.endpoint() != null && !c.endpoint().isBlank();
        S3ClientBuilder b = S3Client.builder().region(Region.of(c.region()));
        if (customEndpoint) {
            b.endpointOverride(URI.create(c.endpoint()));
        }
        if (c.pathStyle()) {
            b.forcePathStyle(true);
        }
        if (c.accessKey() != null && !c.accessKey().isBlank()) {
            b.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(c.accessKey(), c.secretKey())));
        } else {
            b.credentialsProvider(DefaultCredentialsProvider.builder().build());
        }
        this.s3 = b.build();
    }

    @PostConstruct
    void ensureBucket() {
        try {
            s3.headBucket(r -> r.bucket(bucket));
        } catch (NoSuchBucketException e) {
            if (customEndpoint) {
                s3.createBucket(r -> r.bucket(bucket));
                log.info("버킷 생성: {}", bucket);
            } else {
                log.warn("S3 버킷 '{}'이(가) 없습니다. 인프라에서 먼저 만들어 주세요.", bucket);
            }
        } catch (Exception e) {
            log.warn("S3 버킷 확인 실패({}): {}", bucket, e.getMessage());
            if (customEndpoint) {
                try {
                    s3.createBucket(r -> r.bucket(bucket));
                    log.info("버킷 생성: {}", bucket);
                } catch (Exception ignore) {
                    // 이미 있거나 아직 저장소가 뜨지 않았다 — 첫 업로드 때 다시 드러난다
                }
            }
        }
    }

    @Override
    public void put(String key, byte[] data, String contentType) {
        PutObjectRequest.Builder req = PutObjectRequest.builder().bucket(bucket).key(key)
                .contentType(contentType == null ? "application/octet-stream" : contentType);
        if (!customEndpoint) {
            req.serverSideEncryption(ServerSideEncryption.AES256);
        }
        s3.putObject(req.build(), RequestBody.fromBytes(data));
    }

    @Override
    public byte[] get(String key) {
        try {
            return s3.getObjectAsBytes(r -> r.bucket(bucket).key(key)).asByteArray();
        } catch (NoSuchKeyException e) {
            throw ApiException.notFound("보관된 파일을 찾을 수 없습니다.");
        }
    }

    @Override
    public void delete(String key) {
        s3.deleteObject(r -> r.bucket(bucket).key(key));
    }

    @Override
    public String describe() {
        return "s3://" + bucket;
    }
}
