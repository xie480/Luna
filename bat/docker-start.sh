#!/bin/bash

VMX_PATH="/f/ubnutu/Ubuntu 64 位.vmx"
VM_IP="192.168.100.128"
VM_USER="yilena"

VMRUN="/f/VM/vmrun.exe"

echo "================================="
echo "🚀 启动虚拟机 (VMware)..."
echo "================================="

"$VMRUN" start "$VMX_PATH" nogui 2>/dev/null || true

echo "⏳ 等待 SSH 就绪..."

for i in {1..30}
do
  if ssh -o ConnectTimeout=2 ${VM_USER}@${VM_IP} "echo ok" 2>/dev/null; then
    echo "✅ SSH 已连接"
    break
  fi
  echo "⌛ 第 $i 次尝试..."
  sleep 2
done

echo "================================="
echo "🐳 操作 Docker 容器..."
echo "================================="

ssh ${VM_USER}@${VM_IP} "
docker start redis pg xxl-job rmqbroker rmqnamesrv 2>/dev/null || true
docker stop rmq-dashboard 2>/dev/null || true
"

echo "================================="
echo "🎉 所有服务启动完成！"
echo "================================="

read -p "按回车退出..."