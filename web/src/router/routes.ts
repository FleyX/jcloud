import { deviceView } from '@/utils/device'
import type { RouteRecordRaw } from 'vue-router'

export type RouteName =
  | 'Login'
  | 'Register'
  | 'NotFound'
  | 'Forbidden'
  | 'Init'
  | 'Files'
  | 'Trash'
  | 'Share'
  | 'RemoteMount'
  | 'UserManagement'
  | 'RoleManagement'
  | 'StorageSpaceManagement'
  | 'AdminMediaSettings'
  | 'PersonProfile'
  | 'PersonDevices'
  | 'PersonWebDav'
  | 'MediaHome'
  | 'MediaLibrary'
  | 'MediaDirectories'
  | 'MediaMovieDetail'
  | 'MediaSeriesDetail'
  | 'MediaPlay'

/**
 * 公开静态路由
 */
export const publicRoutes: RouteRecordRaw[] = [
  {
    path: '/',
    redirect: '/files',
  },
  {
    path: '/login',
    name: 'Login' as RouteName,
    component: deviceView('auth/Login'),
    meta: { public: true },
  },
  {
    path: '/register',
    name: 'Register' as RouteName,
    component: deviceView('auth/Register'),
    meta: { public: true },
  },
  {
    path: '/init',
    name: 'Init' as RouteName,
    component: deviceView('init/index'),
    meta: { init: true },
  },
  {
    path: '/404',
    name: 'NotFound' as RouteName,
    component: () => import('@/views/NotFound.vue'),
    meta: { public: true },
  },
  {
    path: '/403',
    name: 'Forbidden' as RouteName,
    component: () => import('@/views/Forbidden.vue'),
    meta: { public: true },
  },
  {
    path: '/s/:code',
    name: 'PublicShare' as RouteName,
    component: deviceView('share/index'),
    meta: { public: true },
  },
]

/**
 * 动态路由：登录后根据权限注入
 * - /files 为基础模块，无权限要求
 * - /admin/users 需要 VIEW:/admin/users 资源
 */
export const dynamicRoutes: RouteRecordRaw[] = [
  {
    path: '/files',
    name: 'Files' as RouteName,
    component: deviceView('files/index'),
    meta: { resource: 'VIEW:/files', title: '全部文件' },
  },
  {
    path: '/files/trash',
    name: 'Trash' as RouteName,
    component: deviceView('files/trash'),
    meta: { resource: 'VIEW:/files', title: '回收站' },
  },
  {
    path: '/files/share',
    name: 'Share' as RouteName,
    component: deviceView('files/share'),
    meta: { resource: 'VIEW:/files', title: '我的分享' },
  },
  {
    path: '/person',
    name: 'PersonProfile' as RouteName,
    component: deviceView('person/profile'),
    meta: { title: '个人资料' },
  },
  {
    path: '/person/devices',
    name: 'PersonDevices' as RouteName,
    component: deviceView('person/devices'),
    meta: { title: '登录设备' },
  },
  {
    path: '/person/remote-mounts',
    name: 'RemoteMount' as RouteName,
    component: deviceView('files/remote-mounts'),
    meta: { title: '远程挂载' },
  },
  {
    path: '/person/webdav',
    name: 'PersonWebDav' as RouteName,
    component: deviceView('person/webdav'),
    meta: { title: 'WebDAV共享' },
  },
  // 旧路径重定向，保持兼容
  {
    path: '/profile',
    redirect: '/person',
  },
  {
    path: '/files/remote-mounts',
    redirect: '/person/remote-mounts',
  },
  {
    path: '/media',
    name: 'MediaHome' as RouteName,
    component: deviceView('media/home'),
    meta: { resource: 'VIEW:/media', title: '影视' },
  },
  {
    path: '/media/libraries/:id',
    name: 'MediaLibrary' as RouteName,
    component: deviceView('media/library'),
    meta: { resource: 'VIEW:/media', title: '媒体库' },
  },
  {
    path: '/media/directories',
    name: 'MediaDirectories' as RouteName,
    component: deviceView('media/directories'),
    meta: { resource: 'VIEW:/media', title: '目录管理' },
  },
  {
    path: '/media/movies/:id',
    name: 'MediaMovieDetail' as RouteName,
    component: deviceView('media/movie-detail'),
    meta: { resource: 'VIEW:/media', title: '电影详情' },
  },
  {
    path: '/media/series/:id',
    name: 'MediaSeriesDetail' as RouteName,
    component: deviceView('media/series-detail'),
    meta: { resource: 'VIEW:/media', title: '电视剧详情' },
  },
  {
    path: '/media/play/:id',
    name: 'MediaPlay' as RouteName,
    component: deviceView('media/play'),
    meta: { resource: 'VIEW:/media', title: '播放', standalone: true },
  },
  // 旧路径重定向，保持兼容
  {
    path: '/media/movies',
    redirect: '/media',
  },
  {
    path: '/media/series',
    redirect: '/media',
  },
  {
    path: '/media/others',
    redirect: '/media',
  },
  {
    path: '/admin/users',
    name: 'UserManagement' as RouteName,
    component: deviceView('admin/Users'),
    meta: { resource: 'VIEW:/admin/users', title: '用户管理' },
  },
  {
    path: '/admin/roles',
    name: 'RoleManagement' as RouteName,
    component: deviceView('admin/Roles'),
    meta: { resource: 'VIEW:/admin/roles', title: '角色管理' },
  },
  {
    path: '/admin/storage-spaces',
    name: 'StorageSpaceManagement' as RouteName,
    component: deviceView('admin/StorageSpaces'),
    meta: { resource: 'VIEW:/admin/storage-spaces', title: '存储空间管理' },
  },
  {
    path: '/admin/media',
    name: 'AdminMediaSettings' as RouteName,
    component: deviceView('admin/MediaSettings'),
    meta: { resource: 'VIEW:/admin/media', title: '影视设置' },
  },
]