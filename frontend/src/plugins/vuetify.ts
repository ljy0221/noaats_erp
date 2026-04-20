import 'vuetify/styles'
import '@mdi/font/css/materialdesignicons.css'
import { createVuetify } from 'vuetify'
import { aliases, mdi } from 'vuetify/iconsets/mdi'

export const vuetify = createVuetify({
  theme: {
    defaultTheme: 'light',
    themes: {
      light: {
        colors: {
          primary: '#1E3A8A',
          secondary: '#475569',
          success: '#16A34A',
          error: '#DC2626',
          warning: '#D97706',
          info: '#2563EB',
        },
      },
    },
  },
  icons: {
    defaultSet: 'mdi',
    aliases,
    sets: { mdi },
  },
  defaults: {
    VBtn: { variant: 'flat' },
    VTextField: { variant: 'outlined', density: 'comfortable', hideDetails: 'auto' },
    VSelect: { variant: 'outlined', density: 'comfortable', hideDetails: 'auto' },
    VTextarea: { variant: 'outlined', density: 'comfortable', hideDetails: 'auto' },
  },
})
