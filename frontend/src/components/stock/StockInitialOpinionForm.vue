<script setup lang="ts">
import { reactive, ref } from 'vue'
import type { AddWorkspaceStockRequestDTO, OpinionHorizon, StockStance } from '@/api/stock'

type InitialOpinionDraft = Omit<AddWorkspaceStockRequestDTO, 'code'>

const props = defineProps<{
  stockName: string
  currency: 'CNY' | 'HKD'
  restarting?: boolean
  loading?: boolean
  error?: string
}>()

const emit = defineEmits<{
  submit: [draft: InitialOpinionDraft]
  cancel: []
}>()

const heading = ref<HTMLElement | null>(null)
const localError = ref('')
const opinion = reactive({
  stance: 'NEUTRAL' as StockStance,
  reason: '',
  observationNote: '',
  horizon: 'MEDIUM' as OpinionHorizon,
  targetPrice: '',
  stopLossPrice: '',
})

function optionalPrice(value: string | number) {
  if (value === '') return null
  const number = Number(value)
  return Number.isFinite(number) && number > 0 ? number : Number.NaN
}

function currencySymbol() {
  return props.currency === 'HKD' ? 'HK$' : '¥'
}

function submit() {
  if (props.loading) return
  const targetPrice = optionalPrice(opinion.targetPrice)
  const stopLossPrice = optionalPrice(opinion.stopLossPrice)
  if (!opinion.reason.trim()) {
    localError.value = '请填写判断理由'
    return
  }
  if (Number.isNaN(targetPrice) || Number.isNaN(stopLossPrice)) {
    localError.value = '目标价和止损参考价必须是大于 0 的数字'
    return
  }

  localError.value = ''
  emit('submit', {
    stance: opinion.stance,
    reason: opinion.reason.trim(),
    observationNote: opinion.observationNote.trim() || null,
    horizon: opinion.horizon,
    targetPrice,
    stopLossPrice,
  })
}

function focusHeading() {
  heading.value?.focus()
}

defineExpose({ focusHeading })
</script>

<template>
  <form
    class="opinion-form"
    novalidate
    aria-labelledby="initial-opinion-title"
    :aria-busy="loading"
    @submit.prevent="submit"
  >
    <div class="form-heading">
      <div>
        <p class="section-label">{{ restarting ? '重新开启研究' : '加入共享自选' }}</p>
        <h2 id="initial-opinion-title" ref="heading" tabindex="-1">
          记录对 {{ stockName }} 的初始观点
        </h2>
      </div>
      <button class="ui-text-action" type="button" :disabled="loading" @click="emit('cancel')">
        取消填写
      </button>
    </div>

    <fieldset class="opinion-fields" :disabled="loading">
      <div class="field-grid compact-fields">
        <label>
          <span>观点倾向（必填）</span>
          <select v-model="opinion.stance" required>
            <option value="BULLISH">看多</option>
            <option value="NEUTRAL">中性</option>
            <option value="BEARISH">看空</option>
          </select>
        </label>
        <label>
          <span>关注周期（必填）</span>
          <select v-model="opinion.horizon" required>
            <option value="SHORT">短期</option>
            <option value="MEDIUM">中期</option>
            <option value="LONG">长期</option>
          </select>
        </label>
      </div>

      <div class="field-grid text-fields">
        <label>
          <span>判断理由（必填）</span>
          <textarea
            v-model="opinion.reason"
            maxlength="1000"
            rows="5"
            required
            placeholder="为什么形成当前的看多、中性或看空判断？"
            :aria-describedby="localError || error ? 'initial-opinion-error' : undefined"
          ></textarea>
        </label>
        <label>
          <span>关键观察条件（选填）</span>
          <textarea
            v-model="opinion.observationNote"
            maxlength="1000"
            rows="5"
            placeholder="哪些变化可能支持、削弱或推翻当前判断？"
          ></textarea>
        </label>
      </div>

      <div class="field-grid price-fields">
        <label>
          <span>目标价（选填）</span>
          <div class="price-input">
            <span>{{ currencySymbol() }}</span>
            <input
              v-model="opinion.targetPrice"
              type="number"
              min="0.0001"
              step="0.0001"
              placeholder="不填写也可以"
              :aria-describedby="localError || error ? 'initial-opinion-error' : undefined"
            />
          </div>
        </label>
        <label>
          <span>止损参考价（选填）</span>
          <div class="price-input">
            <span>{{ currencySymbol() }}</span>
            <input
              v-model="opinion.stopLossPrice"
              type="number"
              min="0.0001"
              step="0.0001"
              placeholder="不填写也可以"
              :aria-describedby="localError || error ? 'initial-opinion-error' : undefined"
            />
          </div>
        </label>
      </div>
    </fieldset>
    <p v-if="localError || error" id="initial-opinion-error" class="inline-error" role="alert">
      {{ localError || error }}
    </p>
    <div class="submit-row">
      <span>关注价格将由后端重新读取，不采用页面当前显示值。</span>
      <button class="ui-button primary" type="submit" :disabled="loading">
        {{ loading ? '正在保存…' : restarting ? '重新关注并保存观点' : '加入关注并保存观点' }}
      </button>
    </div>
  </form>
</template>

<style scoped>
.opinion-form {
  margin: 32px 0;
  padding: 26px;
  border: 1px solid var(--color-border);
  border-radius: 8px;
  background: var(--color-surface);
}
.form-heading,
.submit-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 16px;
}
.form-heading > div {
  min-width: 0;
}
.section-label {
  color: var(--color-muted);
  font-family: var(--font-mono);
  font-size: 11px;
}
.form-heading h2 {
  margin: 10px 0 0;
  font-family: var(--font-display);
  font-size: 22px;
  font-weight: 900;
  line-height: 1.5;
  text-wrap: balance;
}
.form-heading h2:focus {
  outline: none;
}
.form-heading button {
  min-height: 44px;
  padding: 4px 0;
  border: 0;
  background: transparent;
  color: var(--color-muted);
  text-decoration: underline;
  text-underline-offset: 5px;
  cursor: pointer;
  font-size: 12px;
}
button:disabled {
  cursor: wait;
  opacity: 0.6;
}
.opinion-fields {
  min-width: 0;
  margin: 0;
  padding: 0;
  border: 0;
}
.field-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 22px;
  margin-top: 24px;
}
label {
  min-width: 0;
}
label > span {
  display: block;
  margin-bottom: 8px;
  color: var(--color-text);
  font-size: 12px;
}
input,
select,
textarea {
  min-width: 0;
  width: 100%;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-control);
  background: var(--color-surface);
  color: var(--color-ink);
  font: inherit;
  font-size: 14px;
}
input,
select {
  min-height: 44px;
  padding: 10px 12px;
}
textarea {
  min-height: 132px;
  padding: 12px;
  resize: vertical;
  line-height: 1.7;
}
input::placeholder,
textarea::placeholder {
  color: var(--color-muted);
}
input:focus-visible,
select:focus-visible,
textarea:focus-visible {
  border-color: var(--color-focus);
  outline: 2px solid var(--color-focus);
  outline-offset: 2px;
}
input:disabled,
select:disabled,
textarea:disabled {
  background: var(--color-surface-secondary);
}
.price-input {
  display: flex;
  align-items: center;
  gap: 8px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-control);
  background: var(--color-surface);
  font-family: var(--font-mono);
}
.price-input > span {
  padding-left: 12px;
  color: var(--color-muted);
  font-size: 13px;
}
.price-input input {
  border: 0;
}
.price-input:focus-within {
  outline: 2px solid var(--color-focus);
  outline-offset: 2px;
}
.price-input input:focus-visible {
  outline: none;
}
.submit-row {
  margin-top: 26px;
  padding-top: 20px;
  border-top: 1px solid var(--color-border);
}
.submit-row > span {
  max-width: 48ch;
  color: var(--color-muted);
  font-size: 12px;
}
.primary {
  min-height: 44px;
  padding: 10px 20px;
  border: 1px solid var(--color-ink);
  border-radius: 999px;
  background: var(--color-ink);
  color: var(--color-surface);
  cursor: pointer;
  font-size: 12px;
}
.inline-error {
  margin-top: 18px;
  color: var(--color-danger);
  font-size: 13px;
}
@media (max-width: 640px) {
  .opinion-form {
    padding: 18px;
  }
  .field-grid {
    grid-template-columns: minmax(0, 1fr);
    gap: 18px;
  }
  .submit-row .primary {
    width: 100%;
  }
}
</style>
