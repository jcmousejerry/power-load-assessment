package com.chen.server.service;

import com.chen.server.result.Result;
import org.springframework.web.multipart.MultipartFile;

public interface DatasetService {

    Result uploadDataset(MultipartFile file, String name, String startDate, String endDate);

    Result getCurrentUserDatasets();
}
