package com.loadflex.server.config;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MinioConfig {
    @Bean
    MinioClient minioClient(
            @Value("${loadflex.minio.endpoint}") String endpoint,
            @Value("${loadflex.minio.access-key}") String access,
            @Value("${loadflex.minio.secret-key}") String secret,
            @Value("${loadflex.minio.bucket}") String bucket)
            throws Exception {
        MinioClient client = MinioClient.builder()
                .endpoint(endpoint)
                .credentials(access, secret)
                .build();
        if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
            client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        }
        return client;
    }
}
