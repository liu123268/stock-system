<template>
  <svg class="design-orbit" viewBox="0 0 520 360" aria-hidden="true" focusable="false">
    <defs>
      <radialGradient id="orbit-glow" cx="50%" cy="46%" r="56%">
        <stop offset="0%" stop-color="#ffffff" stop-opacity="0.88" />
        <stop offset="58%" stop-color="#dcebf5" stop-opacity="0.52" />
        <stop offset="100%" stop-color="#dcebf5" stop-opacity="0" />
      </radialGradient>
      <linearGradient id="orbit-core" x1="0" y1="0" x2="1" y2="1">
        <stop offset="0%" stop-color="#e1f5ef" />
        <stop offset="100%" stop-color="#cbdff5" />
      </linearGradient>
    </defs>

    <ellipse cx="260" cy="178" rx="208" ry="128" fill="url(#orbit-glow)" />
    <ellipse class="orbit-line orbit-line-wide" cx="260" cy="178" rx="196" ry="88" />
    <ellipse
      class="orbit-line orbit-line-tall"
      cx="260"
      cy="178"
      rx="118"
      ry="166"
      transform="rotate(-23 260 178)"
    />
    <ellipse
      class="orbit-line orbit-line-short"
      cx="260"
      cy="178"
      rx="157"
      ry="56"
      transform="rotate(28 260 178)"
    />

    <circle class="orbit-core" cx="260" cy="178" r="70" fill="url(#orbit-core)" />
    <path
      class="orbit-core-line"
      d="M218 181c18-38 49-56 83-48 25 6 43 24 51 51-15 29-39 48-72 52-30 3-51-16-62-55Z"
    />
    <path class="orbit-core-line" d="M235 131c-2 24 10 46 36 64 24 17 45 19 63 8" />

    <circle class="orbit-node orbit-node-a" cx="78" cy="148" r="10" />
    <circle class="orbit-node orbit-node-b" cx="422" cy="113" r="7" />
    <circle class="orbit-node orbit-node-c" cx="391" cy="288" r="12" />
    <circle class="orbit-node orbit-node-d" cx="153" cy="315" r="6" />
  </svg>
</template>

<style scoped>
.design-orbit {
  display: block;
  width: 100%;
  height: auto;
  overflow: visible;
}

.orbit-line {
  fill: none;
  stroke: color-mix(in srgb, var(--preview-ink) 19%, transparent);
  stroke-width: 1;
}

.orbit-line-wide {
  animation: orbit-sway 15s ease-in-out infinite alternate;
  transform-origin: center;
}

.orbit-line-tall {
  stroke: color-mix(in srgb, var(--preview-accent) 27%, transparent);
  animation: orbit-sway-reverse 18s ease-in-out infinite alternate;
  transform-origin: center;
}

.orbit-line-short {
  stroke: color-mix(in srgb, var(--preview-warm) 30%, transparent);
  animation: orbit-breathe 12s ease-in-out infinite alternate;
  transform-origin: center;
}

.orbit-core {
  animation: core-float 13s ease-in-out infinite alternate;
  transform-origin: center;
}

.orbit-core-line {
  fill: none;
  stroke: color-mix(in srgb, var(--preview-ink) 22%, transparent);
  stroke-width: 1;
}

.orbit-node {
  fill: var(--preview-surface);
  stroke: color-mix(in srgb, var(--preview-ink) 23%, transparent);
  stroke-width: 1;
}

.orbit-node-a {
  fill: var(--preview-accent-soft);
}

.orbit-node-b {
  fill: var(--preview-lilac);
}

.orbit-node-c {
  fill: var(--preview-warm-soft);
}

.orbit-node-d {
  fill: var(--preview-mint);
}

@keyframes orbit-sway {
  from {
    transform: rotate(-3deg) scale(0.98);
  }
  to {
    transform: rotate(3deg) scale(1.02);
  }
}

@keyframes orbit-sway-reverse {
  from {
    transform: rotate(-25deg) scale(0.98);
  }
  to {
    transform: rotate(-18deg) scale(1.03);
  }
}

@keyframes orbit-breathe {
  from {
    transform: rotate(25deg) scale(0.96);
    opacity: 0.62;
  }
  to {
    transform: rotate(31deg) scale(1.04);
    opacity: 1;
  }
}

@keyframes core-float {
  from {
    transform: translateY(2px) scale(0.98);
  }
  to {
    transform: translateY(-4px) scale(1.02);
  }
}

@media (prefers-reduced-motion: reduce) {
  .orbit-line,
  .orbit-core {
    animation: none;
  }
}
</style>
