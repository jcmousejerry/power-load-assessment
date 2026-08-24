package com.loadflex.server.controller;

import com.loadflex.common.entity.Dataset;
import com.loadflex.server.api.ApiResponse;
import com.loadflex.server.service.DatasetService;
import java.util.List;
import lombok.Data;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/datasets")
public class DatasetController {
    private final DatasetService datasetService;

    public DatasetController(DatasetService datasetService) {
        this.datasetService = datasetService;
    }

    @GetMapping
    public ApiResponse<List<Dataset>> list() {
        return ApiResponse.ok(datasetService.list());
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<Dataset> upload(@RequestPart("file") MultipartFile file, @RequestParam String name)
            throws Exception {
        return ApiResponse.ok("上传成功", datasetService.upload(file, name));
    }

    @PutMapping("/{id}/mapping")
    public ApiResponse<Dataset> mapping(@PathVariable Long id, @RequestBody MappingDTO dto) {
        return ApiResponse.ok("字段映射已保存", datasetService.saveMapping(id, dto.mappingJson));
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<byte[]> download(@PathVariable Long id) throws Exception {
        Dataset dataset = datasetService.accessible(id);
        return ResponseEntity.ok()
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\""
                                + dataset.getOriginalFilename().replace("\"", "") + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(datasetService.download(id));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        datasetService.delete(id);
        return ApiResponse.ok("删除成功", null);
    }

    @Data
    public static class MappingDTO {
        private String mappingJson = "{}";
    }
}
