package com.loadflex.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.loadflex.common.entity.Dataset;
import com.loadflex.common.mapper.DatasetMapper;
import com.loadflex.server.security.AccessControlService;
import com.loadflex.server.security.LoginUser;
import com.loadflex.server.security.UserContext;
import com.loadflex.server.service.DatasetService;
import com.loadflex.server.service.ObjectStorageService;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class DatasetServiceImpl implements DatasetService {

    private static final String[] SUPPORTED_FILE_SUFFIXES = {".csv", ".xlsx", ".xls"};

    private final DatasetMapper datasetMapper;
    private final ObjectStorageService objectStorageService;
    private final AccessControlService accessControlService;

    public DatasetServiceImpl(
            DatasetMapper datasetMapper,
            ObjectStorageService objectStorageService,
            AccessControlService accessControlService) {
        this.datasetMapper = datasetMapper;
        this.objectStorageService = objectStorageService;
        this.accessControlService = accessControlService;
    }

    @Override
    public List<Dataset> list() {
        LoginUser loginUser = UserContext.get();
        LambdaQueryWrapper<Dataset> queryWrapper =
                new LambdaQueryWrapper<Dataset>().eq(Dataset::getDeleted, false).orderByDesc(Dataset::getCreatedAt);
        if (!isAdmin(loginUser)) {
            queryWrapper.eq(Dataset::getOwnerId, loginUser.getUserId());
        }
        return datasetMapper.selectList(queryWrapper);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Dataset upload(MultipartFile file, String name) throws Exception {
        accessControlService.requireAnalysisPermission();
        validateUpload(file, name);

        LoginUser loginUser = UserContext.get();
        String originalFilename =
                Optional.ofNullable(file.getOriginalFilename()).orElse("dataset.csv");
        String objectKey = buildObjectKey(loginUser.getUserId(), originalFilename);
        objectStorageService.put(objectKey, file);

        Dataset dataset = new Dataset();
        dataset.setOwnerId(loginUser.getUserId());
        dataset.setName(name.trim());
        dataset.setOriginalFilename(originalFilename);
        dataset.setObjectKey(objectKey);
        dataset.setContentType(file.getContentType());
        dataset.setFileSize(file.getSize());
        dataset.setSha256(calculateSha256(file));
        dataset.setStatus("UPLOADED");
        dataset.setDeleted(false);
        datasetMapper.insert(dataset);
        return dataset;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Dataset saveMapping(Long id, String mappingJson) {
        accessControlService.requireAnalysisPermission();
        Dataset dataset = accessible(id);
        dataset.setMappingJson(mappingJson == null ? "{}" : mappingJson);
        datasetMapper.updateById(dataset);
        return dataset;
    }

    @Override
    public byte[] download(Long id) throws Exception {
        Dataset dataset = accessible(id);
        try (InputStream inputStream = objectStorageService.get(dataset.getObjectKey())) {
            return inputStream.readAllBytes();
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        accessControlService.requireAnalysisPermission();
        Dataset dataset = accessible(id);
        dataset.setDeleted(true);
        dataset.setStatus("DELETED");
        datasetMapper.updateById(dataset);
    }

    @Override
    public Dataset accessible(Long id) {
        Dataset dataset = datasetMapper.selectById(id);
        LoginUser loginUser = UserContext.get();
        boolean inaccessible = dataset == null
                || Boolean.TRUE.equals(dataset.getDeleted())
                || (!isAdmin(loginUser) && !loginUser.getUserId().equals(dataset.getOwnerId()));
        if (inaccessible) {
            throw new IllegalArgumentException("数据集不存在或无权访问");
        }
        return dataset;
    }

    private void validateUpload(MultipartFile file, String name) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("上传文件不能为空");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("数据集名称不能为空");
        }

        String filename =
                Optional.ofNullable(file.getOriginalFilename()).orElse("").toLowerCase();
        boolean supported = false;
        for (String suffix : SUPPORTED_FILE_SUFFIXES) {
            if (filename.endsWith(suffix)) {
                supported = true;
                break;
            }
        }
        if (!supported) {
            throw new IllegalArgumentException("当前只支持CSV和Excel文件");
        }
    }

    private String buildObjectKey(Long userId, String originalFilename) {
        String safeFilename = originalFilename.replaceAll("[^a-zA-Z0-9._-]", "_");
        return "raw/" + userId + "/" + UUID.randomUUID() + "/" + safeFilename;
    }

    private String calculateSha256(MultipartFile file) throws Exception {
        MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
        try (InputStream inputStream = file.getInputStream()) {
            byte[] buffer = new byte[8192];
            int length;
            while ((length = inputStream.read(buffer)) > 0) {
                messageDigest.update(buffer, 0, length);
            }
        }
        return HexFormat.of().formatHex(messageDigest.digest());
    }

    private boolean isAdmin(LoginUser loginUser) {
        return "ADMIN".equals(loginUser.getRoleCode());
    }
}
