package com.loadflex.server.service;

import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import java.io.InputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class ObjectStorageService {
    private final MinioClient client;
    private final String bucket;

    public ObjectStorageService(MinioClient client, @Value("${loadflex.minio.bucket}") String bucket) {
        this.client = client;
        this.bucket = bucket;
    }

    public void put(String key, MultipartFile file) throws Exception {
        try (InputStream in = file.getInputStream()) {
            client.putObject(PutObjectArgs.builder().bucket(bucket).object(key).stream(in, file.getSize(), -1)
                    .contentType(file.getContentType())
                    .build());
        }
    }

    public InputStream get(String key) throws Exception {
        return client.getObject(
                GetObjectArgs.builder().bucket(bucket).object(key).build());
    }
}
