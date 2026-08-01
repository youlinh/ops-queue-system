import { mount } from '@vue/test-utils'
import { afterEach, describe, expect, it } from 'vitest'
import AppDialog from '../AppDialog.vue'

afterEach(() => {
  document.body.innerHTML = ''
})

describe('AppDialog', () => {
  it('closes on Escape and restores focus to the trigger', async () => {
    const trigger = document.createElement('button')
    document.body.append(trigger)
    trigger.focus()
    const wrapper = mount(AppDialog, {
      attachTo: document.body,
      props: { open: true, labelledBy: 'dialog-title' },
      slots: { default: '<h2 id="dialog-title">确认操作</h2><button>确定</button>' },
    })

    const dialog = document.querySelector<HTMLElement>('[role="dialog"]')
    expect(dialog?.getAttribute('aria-modal')).toBe('true')
    expect(dialog?.getAttribute('aria-labelledby')).toBe('dialog-title')
    dialog?.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))
    expect(wrapper.emitted('close')).toHaveLength(1)
    await wrapper.setProps({ open: false })
    expect(document.activeElement).toBe(trigger)
    wrapper.unmount()
  })

  it('requests close from the scrim and keeps Tab focus inside the dialog', async () => {
    const trigger = document.createElement('button')
    document.body.append(trigger)
    trigger.focus()
    const wrapper = mount(AppDialog, {
      attachTo: document.body,
      props: { open: true, labelledBy: 'dialog-title' },
      slots: {
        default: '<h2 id="dialog-title">确认操作</h2><button>取消</button><button>确定</button>',
      },
    })

    await new Promise((resolve) => setTimeout(resolve))
    const dialog = document.querySelector<HTMLElement>('[role="dialog"]')
    const buttons = dialog?.querySelectorAll<HTMLButtonElement>('button')
    expect(document.activeElement).toBe(buttons?.[0])
    buttons?.[1].dispatchEvent(new KeyboardEvent('keydown', { key: 'Tab', bubbles: true }))
    expect(document.activeElement).toBe(buttons?.[0])
    document.querySelector<HTMLElement>('.ui-overlay')?.click()
    expect(wrapper.emitted('close')).toHaveLength(1)
    wrapper.unmount()
  })
})
