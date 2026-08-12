import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useTransferStore } from './transfer'

vi.mock('@/api/auth', () => ({
  login: vi.fn(),
  getCurrentUser: vi.fn(),
}))

beforeEach(() => {
  setActivePinia(createPinia())
})

describe('transfer store', () => {
  it('should add upload task with default status', () => {
    const store = useTransferStore()
    store.addUploadTask({ fileId: 'u1', fileName: 'a.txt' })

    expect(store.uploadQueue).toHaveLength(1)
    expect(store.uploadQueue[0].status).toBe('waiting')
    expect(store.uploadQueue[0].progress).toBe(0)
  })

  it('should update progress and switch status to uploading', () => {
    const store = useTransferStore()
    store.addUploadTask({ fileId: 'u1', fileName: 'a.txt' })
    store.updateProgress('u1', 30, '1 MB/s')

    const task = store.uploadQueue[0]
    expect(task.progress).toBe(30)
    expect(task.status).toBe('uploading')
    expect(task.speed).toBe('1 MB/s')
  })

  it('should abort controller when pausing an uploading task', () => {
    const store = useTransferStore()
    const controller = new AbortController()
    const abortSpy = vi.spyOn(controller, 'abort')

    store.addUploadTask({ fileId: 'u1', fileName: 'a.txt' })
    store.updateProgress('u1', 10)
    store.uploadQueue[0].controller = controller
    store.pauseTask('u1')

    expect(store.uploadQueue[0].status).toBe('paused')
    expect(abortSpy).toHaveBeenCalledOnce()
  })

  it('should resume a paused task', () => {
    const store = useTransferStore()
    store.addUploadTask({ fileId: 'u1', fileName: 'a.txt' })
    store.pauseTask('u1')
    store.pauseTask('u1')

    expect(store.uploadQueue[0].status).toBe('uploading')
  })

  it('should complete task', () => {
    const store = useTransferStore()
    store.addUploadTask({ fileId: 'u1', fileName: 'a.txt' })
    store.completeTask('u1')

    const task = store.uploadQueue[0]
    expect(task.status).toBe('success')
    expect(task.progress).toBe(100)
  })

  it('should mark task failed', () => {
    const store = useTransferStore()
    store.addUploadTask({ fileId: 'u1', fileName: 'a.txt' })
    store.failTask('u1')

    expect(store.uploadQueue[0].status).toBe('error')
  })

  it('should remove task', () => {
    const store = useTransferStore()
    store.addUploadTask({ fileId: 'u1', fileName: 'a.txt' })
    store.removeTask('u1')

    expect(store.uploadQueue).toHaveLength(0)
  })

  it('should detect running tasks', () => {
    const store = useTransferStore()
    expect(store.hasRunningTask).toBe(false)

    store.addUploadTask({ fileId: 'u1', fileName: 'a.txt' })
    expect(store.hasRunningTask).toBe(true)

    store.completeTask('u1')
    expect(store.hasRunningTask).toBe(false)
  })

  it('should debounce user info refresh after completeTask', async () => {
    const { getCurrentUser } = await import('@/api/auth')
    const getCurrentUserMock = vi.mocked(getCurrentUser)
    getCurrentUserMock.mockResolvedValue({
      token: 't',
      userInfo: { id: '1', username: 'u', status: 1, isAdmin: false, roles: [] },
      resources: [],
      initialized: true,
    })

    vi.useFakeTimers()
    try {
      const store = useTransferStore()
      store.addUploadTask({ fileId: 'u1', fileName: 'a.txt' })
      store.addUploadTask({ fileId: 'u2', fileName: 'b.txt' })
      store.completeTask('u1')
      store.completeTask('u2')

      expect(getCurrentUserMock).not.toHaveBeenCalled()
      await vi.advanceTimersByTimeAsync(800)
      expect(getCurrentUserMock).toHaveBeenCalledTimes(1)
    } finally {
      vi.useRealTimers()
    }
  })
})
