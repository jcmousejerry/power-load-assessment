package com.chen.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.chen.server.config.MinioConfig;
import com.chen.server.entity.DatasetInfo;
import com.chen.server.entity.User;
import com.chen.server.enums.UserType;
import com.chen.server.mapper.DatasetInfoMapper;
import com.chen.server.result.Result;
import com.chen.server.service.DatasetService;
import com.chen.server.utils.LoginUserHolder;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
public class DatasetServiceImpl extends ServiceImpl<DatasetInfoMapper, DatasetInfo> implements DatasetService {

    @Autowired
    private MinioClient minioClient;

    @Autowired
    private MinioConfig minioConfig;

    @Autowired
    private DatasetInfoMapper datasetInfoMapper;

    @Override
    public Result uploadDataset(MultipartFile file, String name, String startDateStr, String endDateStr) {
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

            // 检查是否存在同名数据集
            QueryWrapper<DatasetInfo> queryWrapper = new QueryWrapper<>();
            queryWrapper.eq("user_id", currentUser.getId());
            queryWrapper.eq("name", name);
            if (datasetInfoMapper.selectCount(queryWrapper) > 0) {
                return Result.fail("已存在同名数据集，请修改名称后重试");
            }

            // 解析日期参数
            LocalDate startDate = LocalDate.parse(startDateStr);
            LocalDate endDate = LocalDate.parse(endDateStr);

            // 生成唯一文件名
            String originalFilename = file.getOriginalFilename();
            String ext = originalFilename != null ?
                originalFilename.substring(originalFilename.lastIndexOf(".")) : "";
            String fileName = "datasets/" + UUID.randomUUID().toString().replace("-", "") + ext;

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

            // 保存数据集信息到数据库
            DatasetInfo datasetInfo = new DatasetInfo();
            datasetInfo.setName(name);
            datasetInfo.setPath(fileName);
            datasetInfo.setUserId(currentUser.getId());
            datasetInfo.setStartDate(startDate);
            datasetInfo.setEndDate(endDate);

            datasetInfoMapper.insert(datasetInfo);

            return Result.ok("数据集上传成功");
        } catch (Exception e) {
            return Result.fail("数据集上传失败: " + e.getMessage());
        }
    }

    @Override
    public Result getCurrentUserDatasets() {
        try {
            // 获取当前登录用户
            User currentUser = LoginUserHolder.getUser();
            if (currentUser == null) {
                return Result.fail("用户未登录");
            }

            // 查询当前用户的所有数据集
            QueryWrapper<DatasetInfo> queryWrapper = new QueryWrapper<>();
            queryWrapper.eq("user_id", currentUser.getId());
            List<DatasetInfo> datasets = datasetInfoMapper.selectList(queryWrapper);

            return Result.ok(datasets);
        } catch (Exception e) {
            return Result.fail("获取数据集列表失败: " + e.getMessage());
        }
    }

    @Override
    public Result deleteDatasetById(Long id) {
        try {
            // 获取当前登录用户
            User currentUser = LoginUserHolder.getUser();
            if (currentUser == null) {
                return Result.fail("用户未登录");
            }

            // 检查用户是否为管理员
            if (!UserType.ADMIN.getCode().equals(currentUser.getUserType())) {
                return Result.fail("权限不足，只有管理员可以删除数据集");
            }

            // 根据ID查询数据集信息
            DatasetInfo datasetInfo = datasetInfoMapper.selectById(id);
            if (datasetInfo == null) {
                return Result.fail("未找到指定的数据集");
            }

            // 从MinIO中删除文件
            try {
                minioClient.removeObject(
                        RemoveObjectArgs.builder()
                                .bucket(minioConfig.getBucketName())
                                .object(datasetInfo.getPath())
                                .build()
                );
            } catch (Exception e) {
                return Result.fail("删除MinIO文件失败: " + e.getMessage());
            }

            // 从数据库中删除记录
            datasetInfoMapper.deleteById(id);

            return Result.ok("数据集删除成功");
        } catch (Exception e) {
            return Result.fail("删除数据集失败: " + e.getMessage());
        }
    }

    @Override
    public Result getAllDatasets() {
        try {
            // 获取当前登录用户
            User currentUser = LoginUserHolder.getUser();
            if (currentUser == null) {
                return Result.fail("用户未登录");
            }

            // 检查用户是否为管理员
            if (!UserType.ADMIN.getCode().equals(currentUser.getUserType())) {
                return Result.fail("权限不足，只有管理员可以查询所有数据集信息");
            }

            // 查询所有数据集信息
            List<DatasetInfo> datasets = datasetInfoMapper.selectList(null);

            return Result.ok(datasets);
        } catch (Exception e) {
            return Result.fail("查询所有数据集信息失败: " + e.getMessage());
        }
    }


}
