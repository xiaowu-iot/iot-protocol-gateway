import { defineConfig } from 'vitepress'

export default defineConfig({
  lang: 'zh-CN',
  title: 'iot-protocol-gateway',
  description: '国标 IoT 协议接入网关：JT/T 808、JT/T 1078、HJ212',
  themeConfig: {
    nav: [
      { text: '快速开始', link: '/guide/getting-started' },
      { text: '协议详解', link: '/protocols/' },
      { text: '文章', link: '/articles/' }
    ],
    sidebar: {
      '/articles/': [
        {
          text: 'IoT 协议入门',
          items: [
            { text: '系列目录', link: '/articles/' },
            { text: '1. 物联网平台到底在做什么', link: '/articles/01-what-is-iot-platform' },
            { text: '2. 设备是怎么连上平台的', link: '/articles/02-how-devices-connect' },
            { text: '3. 为什么绕不开国标协议', link: '/articles/03-why-national-standards' },
            { text: '4. 二进制基础', link: '/articles/04-binary-basics' },
            { text: '5. 解析第一条 808 报文', link: '/articles/05-parse-first-jt808-message' },
            { text: '6. TCP 拆包粘包', link: '/articles/06-tcp-sticky-packets' }
          ]
        }
      ],
      '/protocols/': [
        {
          text: '协议详解',
          items: [
            { text: '总览', link: '/protocols/' }
          ]
        }
      ]
    },
    socialLinks: [
      { icon: 'github', link: 'https://github.com/xiaowu-iot/iot-protocol-gateway' }
    ],
    outline: { label: '本页目录' },
    docFooter: { prev: '上一篇', next: '下一篇' }
  }
})
