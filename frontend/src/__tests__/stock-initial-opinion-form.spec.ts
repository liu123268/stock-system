import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import StockInitialOpinionForm from '@/components/stock/StockInitialOpinionForm.vue'

function mountForm() {
  return mount(StockInitialOpinionForm, {
    props: {
      stockName: '贵州茅台',
      currency: 'CNY',
    },
  })
}

describe('StockInitialOpinionForm', () => {
  it('does not emit again or allow editing while the parent is saving', async () => {
    const wrapper = mountForm()
    await wrapper.find('textarea').setValue('已经准备好的判断')
    await wrapper.setProps({ loading: true })
    await wrapper.find('form').trigger('submit')
    expect(wrapper.emitted('submit')).toBeUndefined()
    expect(wrapper.find('fieldset').attributes('disabled')).toBeDefined()
    expect(wrapper.find('button[type="button"]').attributes('disabled')).toBeDefined()
  })

  it('rejects a nonpositive optional price without submitting', async () => {
    const wrapper = mountForm()
    await wrapper.find('textarea').setValue('合理的判断理由')
    await wrapper.find('input[type="number"]').setValue('-1')
    await wrapper.find('form').trigger('submit')
    expect(wrapper.text()).toContain('必须是大于 0 的数字')
    expect(wrapper.emitted('submit')).toBeUndefined()
  })

  it('requires a judgment reason before emitting the initial opinion', async () => {
    const wrapper = mountForm()

    await wrapper.find('form').trigger('submit')

    expect(wrapper.text()).toContain('请填写判断理由')
    expect(wrapper.emitted('submit')).toBeUndefined()
  })

  it('normalizes optional fields and emits only the editable opinion fields', async () => {
    const wrapper = mountForm()
    const textareas = wrapper.findAll('textarea')
    const numberInputs = wrapper.findAll('input[type="number"]')

    await wrapper.find('select').setValue('BULLISH')
    await textareas[0]!.setValue('  看好长期现金流  ')
    await textareas[1]!.setValue('  观察收入增速  ')
    await numberInputs[0]!.setValue('1600.5')
    await wrapper.find('form').trigger('submit')

    expect(wrapper.emitted('submit')).toEqual([
      [
        {
          stance: 'BULLISH',
          reason: '看好长期现金流',
          observationNote: '观察收入增速',
          horizon: 'MEDIUM',
          targetPrice: 1600.5,
          stopLossPrice: null,
        },
      ],
    ])
  })
})
