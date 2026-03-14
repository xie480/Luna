<template>
  <router-view />
</template>

<script>
import * as PIXI from "pixi.js";

// 全局挂载 PIXI，保证 pixi-live2d-display 可以使用
if (!window.PIXI) window.PIXI = PIXI;

export default {
  name: "App",
  mounted() {
    console.log("[App] PIXI 已挂载到 window.PIXI，可在各组件使用 Live2D");
    
    // 初始化主题
    const savedTheme = localStorage.getItem('luna_input_theme') || 'default';
    document.body.setAttribute('data-theme', savedTheme);
  },
};
</script>

<style>
/* 禁止滚动条显示 */
html, body {
  -ms-overflow-style: none;  /* IE/Edge */
  scrollbar-width: none;     /* Firefox */
  overflow: hidden !important;    
}

html::-webkit-scrollbar,
body::-webkit-scrollbar {
  display: none;  /* 隐藏 Chrome/Safari/Opera 滚动条 */
}

/* 防止 wrapper div 也出现滚动条 */
::-webkit-scrollbar {
  width: 0 !important;
}
::-webkit-scrollbar {
  width: 0 !important;height: 0;
}

/* =========================================
   全局样式：呼吸灯与情绪颜色
   供 ChatBox 组件使用
   ========================================= */

/* 1. 定义情绪颜色变量 */
:root {
  --emotion-color-neutral: #409eff; /* 默认蓝 */
  --emotion-color-happy:   #ff9f43; /* 开心橙 */
  --emotion-color-angry:   #ff6b6b; /* 生气红 */
  --emotion-color-sad:     #5f27cd; /* 悲伤紫 */
  --emotion-color-relaxed: #1dd1a1; /* 放松绿 */
  
  /* 默认呼吸频率 */
  --breathing-duration: 3s;
  --current-emotion-color: var(--emotion-color-neutral);
}

/* 2. 呼吸动画 Keyframes */
@keyframes breathe-glow {
  0% {
    box-shadow: 0 0 5px rgba(255, 255, 255, 0.1), 0 0 10px var(--current-emotion-color);
    border-color: rgba(255, 255, 255, 0.5);
  }
  50% {
    box-shadow: 0 0 20px rgba(255, 255, 255, 0.2), 0 0 30px var(--current-emotion-color);
    border-color: var(--current-emotion-color);
  }
  100% {
    box-shadow: 0 0 5px rgba(255, 255, 255, 0.1), 0 0 10px var(--current-emotion-color);
    border-color: rgba(255, 255, 255, 0.5);
  }
}

/* 3. 情绪类 (应用到输入框容器上) */
.emotion-neutral { --current-emotion-color: var(--emotion-color-neutral); --breathing-duration: 3s; }
.emotion-happy   { --current-emotion-color: var(--emotion-color-happy);   --breathing-duration: 1.5s; } /* 开心时呼吸变快 */
.emotion-angry   { --current-emotion-color: var(--emotion-color-angry);   --breathing-duration: 0.8s; } /* 生气时呼吸急促 */
.emotion-sad     { --current-emotion-color: var(--emotion-color-sad);     --breathing-duration: 4s; }   /* 悲伤时呼吸缓慢 */

/* 4. 主题样式 (配合 SettingsPanel 使用) */

/* 默认主题 */
[data-theme="default"] .chat-input-box {
  background: rgba(255, 255, 255, 0.9);
  border: 2px solid #eee;
  border-radius: 20px;
  color: #333;
  animation: breathe-glow var(--breathing-duration) infinite ease-in-out;
}

/* 霓虹主题 */
[data-theme="neon"] .chat-input-box {
  background: rgba(0, 0, 0, 0.7);
  border: 2px solid var(--current-emotion-color);
  border-radius: 4px;
  color: #fff;
  text-shadow: 0 0 5px var(--current-emotion-color);
  animation: breathe-glow var(--breathing-duration) infinite ease-in-out;
}

/* 毛玻璃主题 */
[data-theme="glass"] .chat-input-box {
  background: rgba(255, 255, 255, 0.2);
  backdrop-filter: blur(10px);
  border: 1px solid rgba(255, 255, 255, 0.5);
  border-radius: 12px;
  color: #000;
  animation: breathe-glow var(--breathing-duration) infinite ease-in-out;
}

/* 极简主题 (无呼吸灯) */
[data-theme="minimal"] .chat-input-box {
  background: transparent;
  border: none;
  border-bottom: 2px solid #333;
  border-radius: 0;
  color: #333;
  box-shadow: none !important;
  animation: none !important;
}
</style>
