/** 补足触摸长按期间的视觉状态；不拦截默认滚动、点击或表单提交。 */
export function installPressFeedback(root: HTMLElement) {
  const pressed = new Map<number, HTMLElement>()
  const selector = '.ui-button, .period-switch button, .research-note'

  function release(event: PointerEvent) {
    const element = pressed.get(event.pointerId)
    pressed.delete(event.pointerId)
    if (element && ![...pressed.values()].includes(element))
      element.removeAttribute('data-touch-pressed')
  }
  function clear() {
    for (const element of pressed.values()) element.removeAttribute('data-touch-pressed')
    pressed.clear()
  }
  function press(event: PointerEvent) {
    if (event.pointerType !== 'touch' || !(event.target instanceof Element)) return
    const element = event.target.closest(selector)
    if (
      !(element instanceof HTMLElement) ||
      !root.contains(element) ||
      !element.closest('.research-theme') ||
      element.matches(':disabled, [aria-disabled="true"]')
    )
      return
    release(event)
    pressed.set(event.pointerId, element)
    element.setAttribute('data-touch-pressed', '')
  }

  root.addEventListener('pointerdown', press, { passive: true })
  document.addEventListener('pointerup', release, true)
  document.addEventListener('pointercancel', release, true)
  window.addEventListener('blur', clear)
  window.addEventListener('pagehide', clear)
  root.addEventListener('contextmenu', clear)

  return () => {
    clear()
    root.removeEventListener('pointerdown', press)
    document.removeEventListener('pointerup', release, true)
    document.removeEventListener('pointercancel', release, true)
    window.removeEventListener('blur', clear)
    window.removeEventListener('pagehide', clear)
    root.removeEventListener('contextmenu', clear)
  }
}
