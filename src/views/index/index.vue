<template>
  <div class="app-root">

    <!-- ===== 背景装饰粒子（仅启动阶段使用） ===== -->
    <div v-if="bgParticlesVisible" class="bg-particles">
      <span v-for="i in 18" :key="i" class="particle" :style="particleStyle(i)"></span>
    </div>

    <!-- ===== 全局 CRT 扫描纹理 ===== -->
    <div class="crt-overlay" aria-hidden="true"></div>

    <!-- ===== 调试 UI ===== -->
    <div
      v-if="showDebugUI"
      ref="uiRef"
      class="debug-ui"
      @mouseenter="uiEnter"
      @mouseleave="uiLeave"
    >
      <div class="debug-topbar">
        <span class="debug-topbar-dot"></span>
        <span class="debug-topbar-label">SYS_CTRL</span>
        <span class="debug-topbar-line"></span>
      </div>
      <div class="debug-btns">
        <button class="cute-btn" @click="toggleTracking">
          <span class="btn-dot" :class="trackingEnabled ? 'green' : 'red'"></span>
          <span class="btn-code-prefix">&gt;_</span>
          {{ trackingEnabled ? "视线追踪 ON" : "视线追踪 OFF" }}
        </button>
        <button class="cute-btn" @click="startSetOrigin">
          <span class="btn-code-prefix">&gt;_</span>
          {{ isSettingOrigin ? "点击画布设置..." : "设置原点" }}
        </button>
        <button class="cute-btn" @click="clearOrigin">
          <span class="btn-code-prefix">&gt;_</span>
          清除原点
        </button>
      </div>
    </div>

    <!-- ===== 输入框 ===== -->
    <transition name="msgbox-fade">
      <div
        v-if="showMessageBox"
        class="messageBox"
        :class="[`emotion-${currentEmotion}`]"
        ref