/**
 * 分享视图对象
 */
export interface ShareVo {
  id: string
  name: string
  description?: string
  shareCode: string
  hasPassword: boolean
  expireAt?: string
  maxViews?: string
  viewCount: string
  status: number
  createTime: string
  updateTime: string
}

/**
 * 分享详情视图对象
 */
export interface ShareDetailVo extends ShareVo {
  items: ShareItemVo[]
}

/**
 * 分享项视图对象
 */
export interface ShareItemVo {
  id: string
  fileNodeId: string
  name: string
  type: 'file' | 'folder'
  size?: string
  mimeType?: string
  createTime: string
}

/**
 * 公开分享视图对象
 */
export interface PublicShareVo {
  id: string
  name: string
  description?: string
  hasPassword: boolean
  createTime: string
  items: ShareItemVo[]
}

/**
 * 创建分享请求
 */
export interface ShareCreateRequest {
  name: string
  description?: string
  fileNodeIds: string[]
  password?: string
  expireAt?: string
  maxViews?: number
}

/**
 * 更新分享请求
 */
export interface ShareUpdateRequest {
  name: string
  description?: string
  fileNodeIds: string[]
  password?: string
  expireAt?: string
  maxViews?: number
  status?: number
}

/**
 * 分享分页查询参数
 */
export interface SharePageQuery {
  name?: string
  status?: number
  pageNum?: number
  pageSize?: number
}

/**
 * 分享访问密码请求
 */
export interface ShareAccessRequest {
  password: string
}

/**
 * 公开分享项查询参数
 */
export interface PublicShareItemQuery {
  parentId?: string
  token?: string
}
