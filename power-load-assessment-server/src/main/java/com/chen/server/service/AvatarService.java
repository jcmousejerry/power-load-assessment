package com.chen.server.service;

import com.chen.server.result.Result;
import org.springframework.web.multipart.MultipartFile;

public interface AvatarService {
    /**
     * 上传用户头像
     * @param file 头像文件
     * @return 上传结果
     */
    Result uploadAvatar(MultipartFile file);

    /**
     * 获取头像URL
     * @param avatarPath 头像在MinIO中的存储路径
     * @return 可访问的URL
     */
    Result getAvatarUrl(String avatarPath);

    /**
     * 获取当前用户头像URL
     * @return 可访问的URL
     */
    Result getCurrentUserAvatarUrl();
}
