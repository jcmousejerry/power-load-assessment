package com.chen.server.controller;

import com.chen.server.result.Result;
import com.chen.server.service.DatasetService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/dataset")
public class DatasetController {

    @Autowired
    private DatasetService datasetService;

    @PostMapping("/upload")
    public Result uploadDataset(@RequestParam("file") MultipartFile file,
                                @RequestParam("name") String name,
                                @RequestParam("startDate") String startDate,
                                @RequestParam("endDate") String endDate) {
        return datasetService.uploadDataset(file, name, startDate, endDate);
    }

    @GetMapping("/list")
    public Result listCurrentUserDatasets() {
        return datasetService.getCurrentUserDatasets();
    }

    @DeleteMapping("/delete/{id}")
    public Result deleteDatasetById(@PathVariable Long id) {
        return datasetService.deleteDatasetById(id);
    }

    @GetMapping("/admin/list-all")
    public Result listAllDatasets() {
        return datasetService.getAllDatasets();
    }
}
