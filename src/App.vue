<template>
  <router-view />
</template>

<style>
/* 全局重置，防止出现滚动条 */
html, body {
  margin: 0;
  padding: 0;
  width: 100%;
  height: 100%;
  overflow: hidden;
  background: transparent;
}
#app {
  width: 100%;
  height: 100%;
}
</style>
