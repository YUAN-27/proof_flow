import vue from 'eslint-plugin-vue'

export default [
  { ignores: ['dist/'] },
  ...vue.configs['flat/essential'],
]
