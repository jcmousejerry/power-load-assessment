const WebSocket = require('ws');

class TaskNotificationDebugger {
    constructor(userId, serverUrl = 'ws://localhost:8080') {
        this.userId = userId;
        this.serverUrl = serverUrl.replace(/^http/, 'ws'); // 将HTTP URL转换为WebSocket URL
        this.ws = null;
        this.isConnected = false;

        console.log(`初始化任务通知调试器，用户ID: ${this.userId}`);
        console.log(`WebSocket 服务器地址: ${this.serverUrl}/websocket/task-notification/${this.userId}`);
    }

    // 连接到 WebSocket 服务器
    connect() {
        const wsUrl = `${this.serverUrl}/websocket/task-notification/${this.userId}`;

        try {
            this.ws = new WebSocket(wsUrl);

            this.ws.on('open', () => {
                console.log('WebSocket 连接已建立');
                this.isConnected = true;
            });

            this.ws.on('message', (data) => {
                console.log('收到服务器推送消息:', data.toString());

                try {
                    const notification = JSON.parse(data.toString());
                    this.displayNotification(notification);
                } catch (e) {
                    console.error('解析通知消息失败:', e);
                    console.log('原始消息:', data.toString());
                }
            });

            this.ws.on('error', (error) => {
                console.error('WebSocket 连接发生错误:', error);
            });

            this.ws.on('close', (code, reason) => {
                console.log('WebSocket 连接已关闭', code, reason ? reason.toString() : '');
                this.isConnected = false;
            });
        } catch (error) {
            console.error('创建 WebSocket 连接失败:', error);
        }
    }

    // 断开连接
    disconnect() {
        if (this.ws && this.isConnected) {
            this.ws.close();
            console.log('手动断开 WebSocket 连接');
        }
    }

    // 显示通知消息
    displayNotification(notification) {
        const now = new Date().toLocaleString();

        // 根据任务状态设置标识
        let statusText = '';
        if (notification.status === 1) {
            statusText = '已完成';
        } else if (notification.status === 0) {
            statusText = '未完成/失败';
        } else {
            statusText = '未知状态';
        }

        // 任务类型映射
        const taskTypes = {
            0: '基本统计特征提取',
            1: '用户聚类',
            2: '负荷预测'
        };
        const taskTypeName = taskTypes[notification.taskType] || '未知任务类型';

        console.log('\n   任务通知详情:');
        console.log(`   - 时间: ${now}`);
        console.log(`   - 任务ID: ${notification.taskId}`);
        console.log(`   - 任务类型: ${taskTypeName}`);
        console.log(`   - 状态: ${statusText}`);
        console.log(`   - 消息: ${notification.message}`);
        console.log(`   - 用户ID: ${notification.userId}`);
        console.log('='.repeat(50));
    }

    // 发送测试消息（如果需要的话）
    sendTestMessage(message) {
        if (this.ws && this.isConnected) {
            this.ws.send(message);
            console.log('发送测试消息:', message);
        } else {
            console.warn('WebSocket 未连接，无法发送消息');
        }
    }
}

// 主程序入口
function main() {
    // 从命令行参数获取用户ID和服务器URL
    const args = process.argv.slice(2);

    if (args.length < 1) {
        console.log('使用方法: node task-notify-ws.js <userId> [serverUrl]');
        console.log('示例: node task-notify-ws.js 1 ws://localhost:8081');
        process.exit(1);
    }

    const userId = parseInt(args[0]);
    if (isNaN(userId) || userId <= 0) {
        console.error('错误: 用户ID必须是正整数');
        process.exit(1);
    }

    const serverUrl = args[1] || 'ws://localhost:8080';

    // 创建调试器实例并连接
    const debuggerInstance = new TaskNotificationDebugger(userId, serverUrl);
    debuggerInstance.connect();

    // 设置优雅退出
    process.on('SIGINT', () => {
        console.log('\n正在断开连接...');
        debuggerInstance.disconnect();
        process.exit(0);
    });

    // 保持进程运行
    console.log('\n等待接收任务通知... 按 Ctrl+C 退出');
}

// 运行主程序
if (require.main === module) {
    main();
}

module.exports = TaskNotificationDebugger;
