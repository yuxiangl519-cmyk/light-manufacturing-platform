<script setup lang="ts">
import { statusLabel } from '../utils/format'
defineProps<{ value: string }>()
const types: Record<string, 'success'|'warning'|'info'|'primary'> = { DRAFT: 'info', CANCELLED: 'info', RECEIVED: 'success', EXECUTED: 'success', SHIPPED: 'success', COMPLETED: 'success', PENDING: 'warning', PENDING_INBOUND: 'warning', READY_TO_ISSUE: 'warning', IN_PRODUCTION: 'primary', CONFIRMED: 'primary' }
</script>
<template><el-tag :type="types[value] || 'info'" effect="light" size="small">{{ statusLabel(value) }}</el-tag></template>
