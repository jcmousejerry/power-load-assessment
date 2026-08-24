import {
  AppstoreOutlined,
  AlertOutlined,
  BarChartOutlined,
  DatabaseOutlined,
  LogoutOutlined,
  MenuFoldOutlined,
  MenuUnfoldOutlined,
  ThunderboltOutlined,
  BranchesOutlined,
  RobotOutlined,
} from '@ant-design/icons'
import { Avatar, Button, Layout, Menu, Space, Typography } from 'antd'
import { useState } from 'react'
import { Outlet, useLocation, useNavigate } from 'react-router-dom'
import { useTaskSocket } from '../hooks/useTaskSocket'
import { useAuthStore } from '../store/auth'

const { Header, Sider, Content } = Layout

const menuItems = [
  { key: '/', icon: <AppstoreOutlined />, label: '运行总览' },
  { key: '/datasets', icon: <DatabaseOutlined />, label: '数据集中心' },
  { key: '/tasks', icon: <ThunderboltOutlined />, label: '任务中心' },
  { key: '/results', icon: <BarChartOutlined />, label: '分析结果' },
  { key: '/grid-monitoring', icon: <AlertOutlined />, label: '变压器实时预警' },
  { key: '/maintenance-planning', icon: <BranchesOutlined />, label: '异常影响与备用供电' },
  { key: '/planning-agent', icon: <RobotOutlined />, label: 'AI 异常处置 Agent' },
]

export default function AppLayout() {
  const [collapsed, setCollapsed] = useState(false)
  const location = useLocation()
  const navigate = useNavigate()
  const user = useAuthStore((state) => state.user)!
  const logout = useAuthStore((state) => state.logout)
  useTaskSocket(user.userId, user.token)

  return (
    <Layout className="app-layout">
      <Sider width={236} collapsed={collapsed} className="app-sider" trigger={null}>
        <div className="brand">
          <div className="brand-mark">LF</div>
          {!collapsed && (
            <div>
              <div className="brand-name">LoadFlex</div>
              <div className="brand-subtitle">负荷分析平台</div>
            </div>
          )}
        </div>
        <Menu
          theme="dark"
          mode="inline"
          selectedKeys={[location.pathname]}
          items={menuItems}
          onClick={({ key }) => navigate(key)}
        />
      </Sider>
      <Layout>
        <Header className="app-header">
          <Button
            type="text"
            icon={collapsed ? <MenuUnfoldOutlined /> : <MenuFoldOutlined />}
            onClick={() => setCollapsed((value) => !value)}
          />
          <Space size={12}>
            <Avatar>{user.displayName.slice(0, 1)}</Avatar>
            <div className="user-summary">
              <Typography.Text strong>{user.displayName}</Typography.Text>
              <Typography.Text type="secondary">{user.roleCode === 'ADMIN' ? '管理员' : '分析人员'}</Typography.Text>
            </div>
            <Button
              type="text"
              icon={<LogoutOutlined />}
              onClick={() => {
                logout()
                navigate('/login')
              }}
            >
              退出
            </Button>
          </Space>
        </Header>
        <Content className="app-content">
          <Outlet />
        </Content>
      </Layout>
    </Layout>
  )
}
