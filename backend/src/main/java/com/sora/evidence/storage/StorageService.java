package com.sora.evidence.storage;

/** 증빙 원본·미리보기·규정집 원문을 보관하는 비공개 객체 저장소. */
public interface StorageService {
    void put(String key, byte[] data, String contentType);

    byte[] get(String key);

    void delete(String key);

    String describe();
}
