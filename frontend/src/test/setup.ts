import { config } from '@vue/test-utils'

config.global.stubs = {
  RouterLink: {
    props: ['to'],
    template: '<a :href=\"String(to)\"><slot /></a>',
  },
}
