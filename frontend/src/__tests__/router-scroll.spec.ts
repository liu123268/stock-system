import { describe, expect, it } from 'vitest'
import type { RouteLocationNormalized } from 'vue-router'
import router from '@/router'

function location(path: string): RouteLocationNormalized {
  const resolved = router.resolve(path)
  return { ...resolved, name: resolved.name ?? undefined }
}

describe('workspace scroll behavior', () => {
  const from = location('/workspaces')

  it('lets the asynchronous research page position its timeline anchor', () => {
    const to = location('/workspaces/12/stocks/21#research-timeline')
    expect(router.options.scrollBehavior?.(to, from, null)).toBe(false)
  })

  it('preserves the browser history position on ordinary pages', () => {
    const to = location('/workspaces/12/stocks')
    const saved = { top: 640, left: 0 }
    expect(router.options.scrollBehavior?.(to, from, saved)).toEqual(saved)
  })

  it('starts an ordinary new page at the top', () => {
    const to = location('/workspaces/12/stocks/21')
    expect(router.options.scrollBehavior?.(to, from, null)).toEqual({ top: 0 })
  })

  it('keeps the ordinary scroll policy when another page uses the same hash text', () => {
    const to = location('/workspaces/12/stocks#research-timeline')
    expect(router.options.scrollBehavior?.(to, from, null)).toEqual({ top: 0 })
  })

  it.each(['desk', 'features', 'room', 'workflow'])(
    'positions a direct homepage #%s anchor',
    (id) => {
      expect(router.options.scrollBehavior?.(location(`/#${id}`), from, null)).toEqual({
        el: `#${id}`,
        top: 24,
      })
    },
  )

  it('restores a saved homepage position before considering its section hash', () => {
    const saved = { top: 1200, left: 0 }
    expect(router.options.scrollBehavior?.(location('/#features'), from, saved)).toEqual(saved)
  })

  it('keeps unknown or other-page homepage hashes on the ordinary policy', () => {
    expect(router.options.scrollBehavior?.(location('/#unknown'), from, null)).toEqual({ top: 0 })
    expect(router.options.scrollBehavior?.(location('/workspaces#features'), from, null)).toEqual({
      top: 0,
    })
  })
})
