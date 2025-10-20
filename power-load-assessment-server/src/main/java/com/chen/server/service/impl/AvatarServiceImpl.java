package com.chen.server.service.impl;

import com.chen.server.config.MinioConfig;
import com.chen.server.entity.User;
import com.chen.server.mapper.UserMapper;
import com.chen.server.result.Result;
import com.chen.server.service.AvatarService;
import com.chen.server.utils.LoginUserHolder;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.http.Method;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class AvatarServiceImpl implements AvatarService {

    @Autowired
    private MinioClient minioClient;

    @Autowired
    private MinioConfig minioConfig;

    @Autowired
    private UserMapper userMapper;

    private static final Logger logger = LoggerFactory.getLogger(AvatarServiceImpl.class);

    @Override
    public Result uploadAvatar(MultipartFile file) {
        try {
            // 获取当前登录用户
            User currentUser = LoginUserHolder.getUser();
            if (currentUser == null) {
                return Result.fail("用户未登录");
            }

            // 检查文件是否为空
            if (file.isEmpty()) {
                return Result.fail("上传文件不能为空");
            }

            // 获取用户当前的头像路径（旧头像）
            String oldAvatarPath = currentUser.getAvatar();

            // 生成唯一文件名
            String originalFilename = file.getOriginalFilename();
            String ext = originalFilename != null ?
                originalFilename.substring(originalFilename.lastIndexOf(".")) : "";
            String fileName = "avatar/" + UUID.randomUUID().toString().replace("-", "") + ext;

            // 上传到MinIO
            try (InputStream inputStream = file.getInputStream()) {
                minioClient.putObject(
                    PutObjectArgs.builder()
                        .bucket(minioConfig.getBucketName())
                        .object(fileName)
                        .stream(inputStream, file.getSize(), -1)
                        .contentType(file.getContentType())
                        .build()
                );
            }

            // 如果存在旧头像，则从MinIO中删除旧头像文件
            if (oldAvatarPath != null && !oldAvatarPath.isEmpty()) {
                try {
                    minioClient.removeObject(
                            RemoveObjectArgs.builder()
                                    .bucket(minioConfig.getBucketName())
                                    .object(oldAvatarPath)
                                    .build()
                    );
                } catch (Exception e) {
                    // 删除旧头像失败不应影响新头像上传，仅记录日志
                    logger.error("删除旧头像文件失败: {}", e.getMessage(), e);
                }
            }

            // 更新用户头像路径
            User user = new User();
            user.setId(currentUser.getId());
            user.setAvatar(fileName);
            userMapper.updateById(user);

            // 生成可访问的URL
            String avatarUrl = generateAvatarUrl(fileName);

            Map<String, String> data = new HashMap<>();
            data.put("avatarPath", fileName);
            data.put("avatarUrl", avatarUrl);

            return Result.ok(data);
        } catch (Exception e) {
            return Result.fail("头像上传失败: " + e.getMessage());
        }
    }

    @Override
    public Result getAvatarUrl(String avatarPath) {
        if (avatarPath == null || avatarPath.isEmpty()) {
            return Result.fail("头像路径为空");
        }

        try {
            String avatarUrl = generateAvatarUrl(avatarPath);
            Map<String, String> data = new HashMap<>();
            data.put("avatarPath", avatarPath);
            data.put("avatarUrl", avatarUrl);
            return Result.ok(data);
        } catch (Exception e) {
            return Result.fail("获取头像URL失败: " + e.getMessage());
        }
    }

    @Override
    public Result getCurrentUserAvatarUrl() {
        // 从ThreadLocal中获取当前用户信息
        User currentUser = LoginUserHolder.getUser();
        if (currentUser == null) {
            return Result.fail("用户未登录");
        }

        // 检查用户是否有头像
        if (currentUser.getAvatar() == null || currentUser.getAvatar().isEmpty()) {
            return Result.fail("用户未设置头像");
        }

        // 调用服务层获取头像URL
        return this.getAvatarUrl(currentUser.getAvatar());
    }

    /**
     * 生成头像访问URL
     * @param avatarPath 头像路径
     * @return 可访问的URL
     */
    private String generateAvatarUrl(String avatarPath) throws Exception {
        // 生成预签名URL（7天有效）
        String presignedUrl = minioClient.getPresignedObjectUrl(
            GetPresignedObjectUrlArgs.builder()
                .method(Method.GET)
                .bucket(minioConfig.getBucketName())
                .object(avatarPath)
                .expiry(7, TimeUnit.DAYS)
                .build()
        );

        return presignedUrl;
    }
}
