import { createApp } from "vue";
import App from "./App.vue";
import router from "./router";
import * as PIXI from "pixi.js";

// 确保 PIXI 在全局可用，这是 pixi-live2d-display 正常工作的前提
// 必须在任何组件 import pixi-live2d-display 之前执行
window.PIXI = PIXI;

createApp(App)
  .use(router)   
  .mount("#app");
