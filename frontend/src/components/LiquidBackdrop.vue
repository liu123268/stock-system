<script setup lang="ts">
import { onMounted, ref } from 'vue'

withDefaults(
  defineProps<{
    variant?: 'hero' | 'quiet'
  }>(),
  {
    variant: 'hero',
  },
)

const primaryVideoRef = ref<HTMLVideoElement>()
const secondaryVideoRef = ref<HTMLVideoElement>()

onMounted(() => {
  if (!window.matchMedia('(prefers-reduced-motion: reduce)').matches) return

  for (const video of [primaryVideoRef.value, secondaryVideoRef.value]) {
    if (!video) continue
    video.pause()
    video.currentTime = 0
  }
})
</script>

<template>
  <div class="liquid-backdrop" :class="`liquid-backdrop--${variant}`" aria-hidden="true">
    <div class="liquid-aura liquid-aura-cyan"></div>
    <div class="liquid-aura liquid-aura-rose"></div>

    <div class="liquid-lens liquid-lens-primary">
      <video
        ref="primaryVideoRef"
        muted
        loop
        autoplay
        playsinline
        preload="metadata"
        src="/media/liquid-glass.mp4"
      ></video>
    </div>

    <div class="liquid-lens liquid-lens-secondary">
      <video
        ref="secondaryVideoRef"
        muted
        loop
        autoplay
        playsinline
        preload="metadata"
        src="/media/liquid-glass.mp4"
      ></video>
    </div>

    <div class="liquid-highlight"></div>
  </div>
</template>

<style scoped>
.liquid-backdrop {
  position: absolute;
  inset: 0;
  overflow: hidden;
  background: var(--color-canvas);
  pointer-events: none;
}

.liquid-backdrop::before {
  position: absolute;
  inset: 0;
  background:
    radial-gradient(circle at 17% 22%, rgba(84, 222, 210, 0.055), transparent 26%),
    radial-gradient(circle at 82% 78%, rgba(231, 85, 171, 0.04), transparent 30%),
    radial-gradient(circle at 71% 16%, rgba(227, 225, 94, 0.035), transparent 24%);
  content: '';
}

.liquid-lens {
  position: absolute;
  overflow: hidden;
  mix-blend-mode: multiply;
  will-change: transform, clip-path;
}

.liquid-lens video {
  width: 100%;
  height: 100%;
  object-fit: cover;
  filter: grayscale(0.94) contrast(1.08) brightness(1.12) saturate(0.72);
}

.liquid-lens-primary {
  inset: -10% -8%;
  clip-path: polygon(0 29%, 100% 7%, 100% 71%, 0 91%);
  opacity: 0.62;
  animation: primary-flow 18s ease-in-out infinite alternate;
}

.liquid-lens-primary video {
  transform: scale(1.08);
}

.liquid-lens-secondary {
  top: 9%;
  left: 19%;
  width: 68%;
  height: 76%;
  border-radius: 48% 52% 44% 56% / 54% 43% 57% 46%;
  opacity: 0.2;
  mask-image: radial-gradient(ellipse at center, #000 34%, rgba(0, 0, 0, 0.9) 52%, transparent 76%);
  animation: secondary-float 22s ease-in-out infinite alternate;
}

.liquid-lens-secondary video {
  filter: grayscale(0.9) contrast(1.04) brightness(1.18) saturate(0.78);
  transform: scale(1.28) rotate(180deg);
}

.liquid-aura,
.liquid-highlight {
  position: absolute;
  border-radius: 50%;
  filter: blur(34px);
  will-change: transform, opacity;
}

.liquid-aura-cyan {
  top: 14%;
  right: 9%;
  width: min(34vw, 520px);
  aspect-ratio: 1;
  background: rgba(52, 204, 194, 0.055);
  animation: aura-drift 20s ease-in-out infinite alternate;
}

.liquid-aura-rose {
  bottom: 4%;
  left: 12%;
  width: min(29vw, 440px);
  aspect-ratio: 1;
  background: rgba(224, 74, 166, 0.035);
  animation: aura-drift 24s 1.5s ease-in-out infinite alternate-reverse;
}

.liquid-highlight {
  top: 18%;
  left: 34%;
  width: 32vw;
  height: 55vh;
  border: 1px solid rgba(255, 255, 255, 0.72);
  background: rgba(255, 255, 255, 0.12);
  filter: blur(12px);
  opacity: 0.48;
  transform: rotate(-11deg);
  animation: highlight-breathe 16s ease-in-out infinite alternate;
}

.liquid-backdrop--quiet .liquid-lens-primary {
  opacity: 0.28;
}

.liquid-backdrop--quiet .liquid-lens-secondary {
  opacity: 0.1;
}

.liquid-backdrop--quiet .liquid-aura,
.liquid-backdrop--quiet .liquid-highlight {
  opacity: 0.22;
}

@keyframes primary-flow {
  0% {
    clip-path: polygon(0 29%, 100% 7%, 100% 71%, 0 91%);
    transform: translate3d(-1.5%, -1%, 0) scale(1.02);
  }
  48% {
    clip-path: polygon(0 23%, 100% 15%, 100% 76%, 0 84%);
    transform: translate3d(1%, 1.5%, 0) scale(1.05);
  }
  100% {
    clip-path: polygon(0 35%, 100% 10%, 100% 66%, 0 88%);
    transform: translate3d(2%, -0.5%, 0) scale(1.03);
  }
}

@keyframes secondary-float {
  from {
    border-radius: 48% 52% 44% 56% / 54% 43% 57% 46%;
    transform: translate3d(-3%, 1%, 0) rotate(-4deg) scale(0.96);
  }
  to {
    border-radius: 56% 44% 53% 47% / 45% 57% 43% 55%;
    transform: translate3d(5%, -3%, 0) rotate(4deg) scale(1.05);
  }
}

@keyframes aura-drift {
  from {
    transform: translate3d(-8%, 4%, 0) scale(0.9);
  }
  to {
    transform: translate3d(11%, -7%, 0) scale(1.12);
  }
}

@keyframes highlight-breathe {
  from {
    opacity: 0.3;
    transform: translate3d(-5%, 2%, 0) rotate(-11deg) scale(0.92);
  }
  to {
    opacity: 0.55;
    transform: translate3d(8%, -3%, 0) rotate(-7deg) scale(1.08);
  }
}

@media (max-width: 700px) {
  .liquid-lens-primary {
    inset: -4% -45%;
    clip-path: polygon(0 18%, 100% 8%, 100% 78%, 0 91%);
  }

  .liquid-lens-secondary {
    top: 13%;
    left: -8%;
    width: 116%;
    height: 64%;
  }

  .liquid-highlight {
    top: 16%;
    left: 19%;
    width: 72vw;
    height: 52vh;
  }
}

@media (prefers-reduced-motion: reduce) {
  .liquid-lens,
  .liquid-aura,
  .liquid-highlight {
    animation: none;
  }
}
</style>
