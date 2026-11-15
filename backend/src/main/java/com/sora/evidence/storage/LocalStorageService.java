package com.sora.evidence.storage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.sora.evidence.common.ApiException;
import com.sora.evidence.config.AppProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** 로컬 개발·테스트용 파일 시스템 저장소. */
@Service
@ConditionalOnProperty(name = "app.storage.type", havingValue = "local")
public class LocalStorageService implements StorageService {
    private final Path root;

    public LocalStorageService(AppProperties props) {
        this.root = Path.of(props.storage().localDir()).toAbsolutePath().normalize();
    }

    private Path resolve(String key) {
        Path p = root.resolve(key).normalize();
        if (!p.startsWith(root)) {
            throw ApiException.badRequest("잘못된 저장 경로입니다.");
        }
        return p;
    }

    @Override
    public void put(String key, byte[] data, String contentType) {
        try {
            Path p = resolve(key);
            Files.createDirectories(p.getParent());
            Files.write(p, data);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public byte[] get(String key) {
        try {
            Path p = resolve(key);
            if (!Files.exists(p)) {
                throw ApiException.notFound("보관된 파일을 찾을 수 없습니다.");
            }
            return Files.readAllBytes(p);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public String describe() {
        return "file://" + root;
    }
}
