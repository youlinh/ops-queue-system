import { mount } from '@vue/test-utils'
import { expect, it } from 'vitest'
import AppIcon from '../AppIcon.vue'

it('renders one typed 24-grid line icon without exposing decorative SVG', () => {
  const wrapper = mount(AppIcon, { props: { name: 'workspace', decorative: true } })

  expect(wrapper.get('svg').attributes('viewBox')).toBe('0 0 24 24')
  expect(wrapper.get('svg').attributes('aria-hidden')).toBe('true')
})
