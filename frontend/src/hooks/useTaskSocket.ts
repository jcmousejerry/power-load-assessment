import { Client } from '@stomp/stompjs'
import { useQueryClient } from '@tanstack/react-query'
import { message } from 'antd'
import { useEffect } from 'react'
import SockJS from 'sockjs-client'

type TaskNotification = {
  taskId: number
  status: string
  message: string
}

export function useTaskSocket(userId: number, token: string) {
  const queryClient = useQueryClient()

  useEffect(() => {
    const client = new Client({
      webSocketFactory: () => new SockJS('/ws/tasks'),
      connectHeaders: { Authorization: `Bearer ${token}` },
      reconnectDelay: 3000,
      onConnect: () => {
        client.subscribe(`/topic/user/${userId}`, (frame) => {
          const notification = JSON.parse(frame.body) as TaskNotification
          void queryClient.invalidateQueries({ queryKey: ['tasks'] })
          void queryClient.invalidateQueries({ queryKey: ['results'] })
          if (notification.status === 'SUCCEEDED') {
            message.success(notification.message)
          } else if (notification.status === 'FAILED') {
            message.error(notification.message)
          }
        })
      },
    })
    client.activate()
    return () => {
      void client.deactivate()
    }
  }, [queryClient, token, userId])
}
