const axios = require('axios');

// 设置基础URL
const BASE_URL = 'http://localhost:8081';

// 用户登录接口测试
async function testLogin() {
    try {
        const response = await axios.post(`${BASE_URL}/api/user/login`, {
            username: 'testadmin',
            password: 'password123'
        }, {
            headers: { 'Content-Type': 'application/json' }
        });
        console.log(response.data);
        return response.data.token; // 返回token
    } catch (error) {
        console.error(error.response?.data || error.message);
        return null;
    }
}

// 创建数据分析任务
async function createDataAnalysisTask(token) {
    try {
        const response = await axios.post(`${BASE_URL}/api/task/create`, {
            datasetId: 1,
            taskType: 0,
            clusterCount: 0,
            forecastSteps: 0
        }, {
            headers: {
                'Content-Type': 'application/json',
                'Authorization': token
            }
        });
        console.log(response.data);
    } catch (error) {
        console.error(error.response?.data || error.message);
    }
}

// 创建用户聚类任务
async function createUserClusteringTask(token) {
    try {
        const response = await axios.post(`${BASE_URL}/api/task/create`, {
            datasetId: 3,
            taskType: 1,
            clusterCount: 3,
            forecastSteps: 0
        }, {
            headers: {
                'Content-Type': 'application/json',
                'Authorization': token
            }
        });
        console.log(response.data);
    } catch (error) {
        console.error(error.response?.data || error.message);
    }
}

// 创建负荷预测任务
async function createLoadForecastTask(token) {
    try {
        const response = await axios.post(`${BASE_URL}/api/task/create`, {
            datasetId: 3,
            taskType: 2,
            clusterCount: 0,
            forecastSteps: 5
        }, {
            headers: {
                'Content-Type': 'application/json',
                'Authorization': token
            }
        });
        console.log(response.data);
    } catch (error) {
        console.error(error.response?.data || error.message);
    }
}

// 获取当前用户的所有任务信息
async function getUserTasks(token) {
    try {
        const response = await axios.get(`${BASE_URL}/api/task/list`, {
            headers: {
                'Authorization': token
            }
        });
        console.log(response.data);
    } catch (error) {
        console.error(error.response?.data || error.message);
    }
}

// 管理员获取平台全部用户的所有任务信息
async function getAllTasks(token) {
    try {
        const response = await axios.get(`${BASE_URL}/api/task/all`, {
            headers: {
                'Authorization': token
            }
        });
        console.log(response.data);
    } catch (error) {
        console.error(error.response?.data || error.message);
    }
}

// 管理员根据任务id删除指定任务
async function deleteTask(token, taskId = '2015418874387742721') {
    try {
        const response = await axios.delete(`${BASE_URL}/api/task/delete/${taskId}`, {
            headers: {
                'Authorization': token
            }
        });
        console.log(response.data);
    } catch (error) {
        console.error(error.response?.data || error.message);
    }
}

token = 'eyJhbGciOiJIUzUxMiJ9.eyJ1c2VySWQiOjQsInN1YiI6IjQiLCJpYXQiOjE3Njk1MDIyMTgsImV4cCI6MTc2OTU4ODYxOH0.6KU_Wmlz1t1jp5aH27Bys-bFrANwwRc4n37B4CvOxIBMgu14HtG23oW4UiFohgwj1176vJULNFGeiFPlZoT7Fw'
getAllTasks(token)
