import { Navigate, Route, Routes, BrowserRouter } from 'react-router-dom'
import { lazy, Suspense } from 'react'
import { Spin } from 'antd'
import { useAuthStore } from './store/auth'
import AppLayout from './components/AppLayout'
import LoginPage from './pages/LoginPage'

const DashboardPage = lazy(() => import('./pages/DashboardPage'))
const DatasetsPage = lazy(() => import('./pages/DatasetsPage'))
const TasksPage = lazy(() => import('./pages/TasksPage'))
const ResultsPage = lazy(() => import('./pages/ResultsPage'))
const GridMonitoringPage = lazy(() => import('./pages/GridMonitoringPage'))
const MaintenancePlanningPage = lazy(() => import('./pages/SimpleEmergencyPage'))
const PlanningAgentPage = lazy(() => import('./pages/SimpleFaultAgentPage'))

function ProtectedApp() {
  const user = useAuthStore((state) => state.user)
  if (!user) {
    return <Navigate to="/login" replace />
  }
  return <AppLayout />
}

export default function App() {
  return (
    <BrowserRouter>
      <Suspense fallback={<div className="page-loading"><Spin size="large" tip="页面加载中" /></div>}>
        <Routes>
          <Route path="/login" element={<LoginPage />} />
          <Route path="/" element={<ProtectedApp />}>
            <Route index element={<DashboardPage />} />
            <Route path="datasets" element={<DatasetsPage />} />
            <Route path="tasks" element={<TasksPage />} />
            <Route path="results" element={<ResultsPage />} />
            <Route path="grid-monitoring" element={<GridMonitoringPage />} />
            <Route path="maintenance-planning" element={<MaintenancePlanningPage />} />
            <Route path="planning-agent" element={<PlanningAgentPage />} />
          </Route>
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </Suspense>
    </BrowserRouter>
  )
}
