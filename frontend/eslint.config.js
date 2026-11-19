import { globalIgnores } from 'eslint/config'
import { defineConfigWithVueTs, vueTsConfigs } from '@vue/eslint-config-typescript'
import pluginVue from 'eslint-plugin-vue'

export default defineConfigWithVueTs(
  { files: ['**/*.{ts,vue}'] },
  globalIgnores(['dist/**', 'src/generated/**']),
  pluginVue.configs['flat/essential'],
  vueTsConfigs.recommended,
  { rules: { 'vue/no-v-html': 'error' } },
)
