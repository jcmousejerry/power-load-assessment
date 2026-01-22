package com.chen.server.controller;

import com.chen.server.result.Result;
import com.chen.server.service.ModelService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/model")
public class ModelController {

    @Autowired
    private ModelService modelService;

    /**
     * 获取当前用户模型信息
     * @return 当前用户模型信息列表
     */
    @GetMapping("/list")
    public Result getCurrentUserModels() {
        return modelService.getCurrentUserModels();
    }

    /**
     * 管理员获取平台全部用户的所有模型信息
     * @return 平台全部用户的所有模型信息列表
     */
    @GetMapping("/all")
    public Result getAllUsersModels() {
        return modelService.getAllUsersModels();
    }
}
