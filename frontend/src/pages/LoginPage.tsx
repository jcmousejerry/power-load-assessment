import { LockOutlined, UserOutlined } from '@ant-design/icons'
import { Button, Card, Form, Input, Typography, message } from 'antd'
import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { login } from '../api/services'
import { useAuthStore } from '../store/auth'

type LoginForm = {
  username: string
  password: string
}

export default function LoginPage() {
  const [loading, setLoading] = useState(false)
  const setUser = useAuthStore((state) => state.setUser)
  const navigate = useNavigate()

  const submit = async (values: LoginForm) => {
    setLoading(true)
    try {
      const user = await login(values.username, values.password)
      setUser(user)
      navigate('/')
    } catch (error) {
      message.error((error as Error).message)
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="login-page">
      <div className="login-intro">
        <div className="login-kicker">LOADFLEX HUB</div>
        <Typography.Title>异构负荷资源集群预测与调节潜力评估平台</Typography.Title>
        <div className="login-feature-grid">
          <span>数据质量检查</span>
          <span>动态排队进度</span>
          <span>预测与基线</span>
          <span>调节潜力评估</span>
        </div>
      </div>
      <Card className="login-card" variant="borderless">
        <Typography.Title level={2}>登录平台</Typography.Title>
        <Form<LoginForm>
          layout="vertical"
          onFinish={submit}
        >
          <Form.Item name="username" label="用户名" rules={[{ required: true, message: '请输入用户名' }]}>
            <Input size="large" prefix={<UserOutlined />} />
          </Form.Item>
          <Form.Item name="password" label="密码" rules={[{ required: true, message: '请输入密码' }]}>
            <Input.Password size="large" prefix={<LockOutlined />} />
          </Form.Item>
          <Button type="primary" htmlType="submit" size="large" block loading={loading}>
            登录
          </Button>
        </Form>
      </Card>
    </div>
  )
}
