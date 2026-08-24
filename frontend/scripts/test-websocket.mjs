import { Client } from '@stomp/stompjs'
import SockJS from 'sockjs-client'

const [token, userId, datasetId] = process.argv.slice(2)
if (!token || !userId || !datasetId) {
  throw new Error('用法：npm run test:websocket -- <token> <userId> <datasetId>')
}

let createdTaskId = null
const notifications = []

await new Promise((resolve, reject) => {
  const timeout = setTimeout(() => {
    client.deactivate()
    reject(new Error('30秒内没有收到任务成功通知'))
  }, 30000)

  const client = new Client({
    webSocketFactory: () => new SockJS('http://127.0.0.1:8080/ws/tasks'),
    connectHeaders: { Authorization: `Bearer ${token}` },
    reconnectDelay: 0,
    onConnect: async () => {
      client.subscribe(`/topic/user/${userId}`, (frame) => {
        const notification = JSON.parse(frame.body)
        notifications.push(notification)
        if (notification.taskId === createdTaskId && notification.status === 'SUCCEEDED') {
          clearTimeout(timeout)
          void client.deactivate()
          resolve()
        }
      })

      try {
        const response = await fetch('http://127.0.0.1:8080/api/tasks', {
          method: 'POST',
          headers: {
            Authorization: `Bearer ${token}`,
            'Content-Type': 'application/json',
            'Idempotency-Key': crypto.randomUUID(),
          },
          body: JSON.stringify({
            taskType: 'PROFILE',
            taskName: 'WebSocket通知验证',
            datasetId: Number(datasetId),
            parameters: {},
          }),
        })
        const body = await response.json()
        if (!response.ok || !body.success) {
          throw new Error(body.message || '创建验证任务失败')
        }
        createdTaskId = body.data.id
        const completed = notifications.some(
          (notification) => notification.taskId === createdTaskId && notification.status === 'SUCCEEDED',
        )
        if (completed) {
          clearTimeout(timeout)
          void client.deactivate()
          resolve()
        }
      } catch (error) {
        clearTimeout(timeout)
        void client.deactivate()
        reject(error)
      }
    },
    onStompError: (frame) => {
      clearTimeout(timeout)
      reject(new Error(frame.headers.message || frame.body || 'STOMP连接失败'))
    },
    onWebSocketError: (error) => {
      clearTimeout(timeout)
      reject(error)
    },
  })

  client.activate()
})

console.log(`WebSocket验证成功，任务 ${createdTaskId} 已收到成功通知。`)
