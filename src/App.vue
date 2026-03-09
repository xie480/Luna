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
</style>
