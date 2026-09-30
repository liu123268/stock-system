// Canvas 与挂到 body 的提示框不会自动继承组件 CSS，显式读取宿主的语义变量。
export function readMarketChartTheme(host: HTMLElement) {
  const style = getComputedStyle(host)
  const token = (name: string, fallback: string) => style.getPropertyValue(name).trim() || fallback
  return {
    ink: token('--color-ink', '#191510'),
    muted: token('--color-muted', '#6f6759'),
    line: token('--color-border', '#e3ddd1'),
    surface: token('--color-surface', '#fffdf9'),
    secondary: token('--color-surface-secondary', '#f3efe7'),
    accent: token('--color-accent', '#6e9fc4'),
    focus: token('--color-focus', '#4e6e88'),
    mono: token('--font-mono', 'Consolas, monospace'),
    rise: '#B42318',
    fall: '#166D67',
    average: '#98621a',
    volume: '#a69e8d',
  }
}

export function marketTooltip(theme: ReturnType<typeof readMarketChartTheme>) {
  return {
    trigger: 'axis' as const,
    appendTo: 'body',
    confine: true,
    backgroundColor: theme.surface,
    borderColor: theme.line,
    padding: 10,
    textStyle: { color: theme.ink, fontFamily: theme.mono, fontSize: 12 },
    extraCssText: 'border-radius:4px;box-shadow:none;max-width:calc(100vw - 24px);',
    axisPointer: {
      type: 'cross' as const,
      lineStyle: { color: theme.muted },
      crossStyle: { color: theme.muted },
      label: { backgroundColor: theme.ink, color: theme.surface, fontFamily: theme.mono },
    },
  }
}
