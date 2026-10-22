<script setup lang="ts">
import { useRoute, useRouter } from 'vue-router';
const route=useRoute(),router=useRouter();
</script>
<template><el-result :icon="route.meta.error==='403'?'warning':'info'" :title="route.meta.error==='403'?'暂无访问权限':'页面不存在'" :sub-title="route.meta.error==='403'?'当前账号没有此模块权限，请联系管理员分配。':'请检查地址，或从左侧导航进入页面。'"><template #extra><el-button @click="router.back()">返回上一页</el-button><el-button type="primary" @click="router.push('/workbench')">返回首页</el-button></template></el-result></template>
