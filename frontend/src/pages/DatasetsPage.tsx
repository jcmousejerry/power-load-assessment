import { DeleteOutlined, LinkOutlined, PlusOutlined } from '@ant-design/icons'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Alert, Button, Card, Form, Input, InputNumber, Modal, Popconfirm, Select, Space, Table, Upload, message } from 'antd'
import type { UploadFile } from 'antd'
import { useState } from 'react'
import dayjs from 'dayjs'
import { deleteDataset, listDatasets, saveDatasetMapping, uploadDataset } from '../api/services'
import PageHeader from '../components/PageHeader'
import StatusTag from '../components/StatusTag'
import type { Dataset } from '../types'

type MappingForm = {
  userColumn?: string
  timeColumn?: string
  dateColumn?: string
  valueColumn?: string
  unit: string
  intervalMinutes?: number
}

export default function DatasetsPage() {
  const queryClient = useQueryClient()
  const query = useQuery({ queryKey: ['datasets'], queryFn: listDatasets })
  const [uploadOpen, setUploadOpen] = useState(false)
  const [mappingDataset, setMappingDataset] = useState<Dataset | null>(null)
  const [uploadFiles, setUploadFiles] = useState<UploadFile[]>([])
  const [uploadForm] = Form.useForm()
  const [mappingForm] = Form.useForm<MappingForm>()

  const refresh = () => queryClient.invalidateQueries({ queryKey: ['datasets'] })
  const uploadMutation = useMutation({
    mutationFn: ({ name, file }: { name: string; file: File }) => uploadDataset(name, file),
    onSuccess: () => {
      message.success('数据集上传成功')
      setUploadOpen(false)
      setUploadFiles([])
      uploadForm.resetFields()
      void refresh()
    },
    onError: (error) => message.error(error.message),
  })
  const mappingMutation = useMutation({
    mutationFn: ({ id, mapping }: { id: number; mapping: MappingForm }) => saveDatasetMapping(id, mapping),
    onSuccess: () => {
      message.success('字段映射已保存')
      setMappingDataset(null)
      void refresh()
    },
    onError: (error) => message.error(error.message),
  })

  const openMapping = (dataset: Dataset) => {
    setMappingDataset(dataset)
    try {
      mappingForm.setFieldsValue(dataset.mappingJson ? JSON.parse(dataset.mappingJson) : { unit: 'kW', intervalMinutes: 15 })
    } catch {
      mappingForm.setFieldsValue({ unit: 'kW', intervalMinutes: 15 })
    }
  }

  return (
    <>
      <PageHeader
        title="数据集中心"
        description="上传CSV或Excel数据，并确认用户、时间、负荷和单位字段。"
        extra={<Button type="primary" icon={<PlusOutlined />} onClick={() => setUploadOpen(true)}>上传数据</Button>}
      />
      <Card className="content-card">
        <Table
          rowKey="id"
          loading={query.isLoading}
          dataSource={query.data || []}
          columns={[
            { title: '名称', dataIndex: 'name' },
            { title: '原文件', dataIndex: 'originalFilename' },
            { title: '状态', dataIndex: 'status', width: 100, render: (status) => <StatusTag status={status} /> },
            { title: '用户数', dataIndex: 'userCount', width: 90, render: (value) => value ?? '-' },
            { title: '标准行数', dataIndex: 'rowCount', width: 110, render: (value) => value?.toLocaleString() ?? '-' },
            { title: '上传时间', dataIndex: 'createdAt', width: 180, render: (value) => dayjs(value).format('YYYY-MM-DD HH:mm') },
            {
              title: '操作',
              width: 190,
              render: (_, dataset) => (
                <Space>
                  <Button type="link" icon={<LinkOutlined />} onClick={() => openMapping(dataset)}>字段映射</Button>
                  <Popconfirm
                    title="确认删除这个数据集？"
                    onConfirm={async () => { await deleteDataset(dataset.id); message.success('已删除'); void refresh() }}
                  >
                    <Button type="link" danger icon={<DeleteOutlined />}>删除</Button>
                  </Popconfirm>
                </Space>
              ),
            },
          ]}
        />
      </Card>

      <Modal title="上传数据集" open={uploadOpen} onCancel={() => setUploadOpen(false)} onOk={() => uploadForm.submit()} confirmLoading={uploadMutation.isPending}>
        <Form form={uploadForm} layout="vertical" onFinish={(values) => {
          const file = uploadFiles[0]?.originFileObj
          if (!file) { message.warning('请选择文件'); return }
          uploadMutation.mutate({ name: values.name, file })
        }}>
          <Form.Item name="name" label="数据集名称" rules={[{ required: true }]}><Input /></Form.Item>
          <Form.Item label="CSV或Excel文件" required>
            <Upload beforeUpload={() => false} maxCount={1} fileList={uploadFiles} onChange={({ fileList }) => setUploadFiles(fileList)}>
              <Button>选择文件</Button>
            </Upload>
          </Form.Item>
        </Form>
      </Modal>

      <Modal title={`字段映射：${mappingDataset?.name || ''}`} open={Boolean(mappingDataset)} onCancel={() => setMappingDataset(null)} onOk={() => mappingForm.submit()} confirmLoading={mappingMutation.isPending}>
        <Form form={mappingForm} layout="vertical" initialValues={{ unit: 'kW', intervalMinutes: 15 }} onFinish={(values) => mappingDataset && mappingMutation.mutate({ id: mappingDataset.id, mapping: values })}>
          <Alert
            type="info"
            showIcon
            style={{ marginBottom: 16 }}
            message="这里填写的是文件中的原始列名"
            description="长表每行是一条采样记录，填写用户、时间和负荷字段；宽表每行是一个用电单元某一天，填写用户和日期字段，负荷点列使用 p1、p2…命名。用户编号字段必须能区分至少两个用电单元。"
          />
          <Form.Item name="userColumn" label="用电单元编号字段" rules={[{ required: true, message: '必须填写能够区分不同用电单元的列名' }]} extra="例如 user_id、meter_id 或 用电单元编码；同一个单元在所有历史记录中应保持相同编号。"><Input placeholder="例如 user_id" /></Form.Item>
          <Form.Item name="timeColumn" label="长表时间字段" extra="仅长表填写，内容应是完整采样时间；宽表留空。"><Input placeholder="例如 timestamp 或 采集时刻" /></Form.Item>
          <Form.Item name="dateColumn" label="宽表日期字段" extra="仅 p1、p2…形式的宽表填写；长表留空。"><Input placeholder="例如 data_date" /></Form.Item>
          <Form.Item name="valueColumn" label="长表负荷字段" extra="仅长表填写，内容是每个采样时刻的负荷数值；宽表留空。"><Input placeholder="例如 load_kw 或 有功功率_MW" /></Form.Item>
          <Form.Item name="unit" label="原始单位" rules={[{ required: true }]}><Select options={[{ value: 'kW' }, { value: 'MW' }, { value: 'kWh' }]} /></Form.Item>
          <Form.Item name="intervalMinutes" label="采样间隔（分钟）" extra="例如15表示每天96点，30表示每天48点；kWh数据会按这个间隔换算为kW。"><InputNumber min={1} max={1440} style={{ width: '100%' }} /></Form.Item>
        </Form>
      </Modal>
    </>
  )
}
