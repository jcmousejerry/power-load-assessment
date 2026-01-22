package com.chen.server.service;

import com.chen.server.result.Result;

public interface ModelService {

    /**
     * 获取当前用户的模型信息
     * @return 包含模型信息列表的 Result 对象
     */
    Result getCurrentUserModels();

    /**
     * 管理员获取平台全部用户的所有模型信息
     * @return 包含全部模型信息列表的 Result 对象
     */
    Result getAllUsersModels();
}
