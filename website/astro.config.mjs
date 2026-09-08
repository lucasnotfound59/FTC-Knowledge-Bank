import {defineConfig} from 'astro/config';
import starlight from '@astrojs/starlight';
import {navigation} from './scripts/content-map.mjs';

export default defineConfig({
  site:'https://ftckb.lucasxl.com',
  integrations:[starlight({
    title:'FTC Knowledge Bank',
    description:'面向 FTC 队伍与编码 Agent 的知识库、确定性规则裁决与代码规范检查。',
    defaultLocale:'root',
    locales:{root:{label:'简体中文',lang:'zh-CN'}},
    social:[{icon:'github',label:'GitHub',href:'https://github.com/lucasnotfound59/FTC-Knowledge-Bank'}],
    customCss:['./src/styles/custom.css'],
    sidebar:navigation,
    favicon:'/favicon.svg',
  })],
});
