package com.loadflex.server.service;

import com.loadflex.common.entity.Dataset;
import java.util.List;
import org.springframework.web.multipart.MultipartFile;

public interface DatasetService {
    List<Dataset> list();

    Dataset upload(MultipartFile file, String name) throws Exception;

    Dataset saveMapping(Long id, String mappingJson);

    byte[] download(Long id) throws Exception;

    void delete(Long id);

    Dataset accessible(Long id);
}
