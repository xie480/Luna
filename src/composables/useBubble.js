import { ref, nextTick } from "vue";
import { gsap } from "gsap";

/**
 * 氣泡管理 composable
 * 負責聊天氣泡的創建、動畫、自動消失
 */
export function useBubble(messageBoxRef, showMessageBox) {
  const chatBubbles = ref([]);
  const bubbleAnchor = ref({ x: window.innerWidth / 2, y: window.innerHeight - 180 });
  const bubbleEls = new Map(); // id -> DOM element
  let bubbleId = 0;

  // 獲取氣泡錨點（輸入框上方 或 屏幕底部中間）
  // 僅在 messageBoxRef 或 showMessageBox 變化時重新計算，避免每次讀取 DOM
  function getBubbleAnchor() {
    if (showMessageBox.value && messageBoxRef.value) {
      try {
        const rect = messageBoxRef.value.getBoundingClientRect();
        return {
          x: rect.left + rect.width / 2,
          y: rect.top - 30, // 增加與輸入框的間距
        };
      } catch (e) {}
    }
    return {
      x: (window.innerWidth || 800) / 2,
      y: (window.innerHeight || 600) - 180, // 增加底部間距，避免與輸入框重疊
    };
  }

  // 記錄所有氣泡當前的頂部位置，用於 FLIP 動畫
  function recordBubblePositions() {
    const map = new Map();
    for (const [id, el] of bubbleEls.entries()) {
      try {
        map.set(id, el.getBoundingClientRect().top);
      } catch (e) {}
    }
    return map;
  }

  // 註冊氣泡 DOM 元素引用
  function registerBubble(el, id) {
    if (!el) {
      bubbleEls.delete(id);
      return;
    }
    bubbleEls.set(id, el);
  }

  /**
   * 顯示單個氣泡
   * @param {string} text - 氣泡文本
   * @param {number} duration - 顯示時長（ms）
   */
  async function showChatBubble(text, duration = 3000) {
    // 更新錨點
    bubbleAnchor.value = getBubbleAnchor();

    // 記錄舊氣泡位置（用於 FLIP 動畫）
    const prevPositions = recordBubblePositions();

    const id = bubbleId++;
    
    // [Fix] 使用展開運算符重新賦值，確保 Vue 100% 觸發響應式更新
    chatBubbles.value = [...chatBubbles.value, { id, text, leaving: false }];

    // 等待 DOM 更新
    await nextTick();

    // 對舊氣泡執行 FLIP 位移動畫
    for (const [bid, el] of bubbleEls.entries()) {
      if (!prevPositions.has(bid)) continue;
      try {
        const dy = prevPositions.get(bid) - el.getBoundingClientRect().top;
        if (Math.abs(dy) > 0.5) {
          gsap.fromTo(el, { y: dy }, { y: 0, duration: 0.22, ease: "power2.out" });
        }
      } catch (e) {
        console.warn("Bubble FLIP error", e);
      }
    }

    // 定時自動消失
    setTimeout(() => {
      const bubble = chatBubbles.value.find((b) => b.id === id);
      if (!bubble) return;
      bubble.leaving = true;
      
      // 觸發視圖更新以應用 leaving 動畫類
      chatBubbles.value = [...chatBubbles.value];

      setTimeout(() => {
        bubbleEls.delete(id);
        chatBubbles.value = chatBubbles.value.filter((b) => b.id !== id);
      }, 250); // 稍微大於 CSS 動畫時間 (0.2s)
    }, duration);
  }

  /**
   * 將長文本拆分為多個短句
   */
  function splitReplyIntoChunks(text) {
    if (!text) return [];
    text = String(text).replace(/\s+/g, " ").trim();
    if (!text) return [];

    const sentenceRe = /[^。！？!?~～…]+[。！？!?~～…]?/g;
    const sentences = text.match(sentenceRe) || [text];
    const parts = [];
    const commaRe = /[^，,、；;]+[，,、；;]?/g;

    for (let s of sentences) {
      s = s.trim();
      if (!s) continue;
      const subs = s.match(commaRe) || [s];
      for (let sub of subs) {
        sub = sub.replace(/[，,、；;]$/u, "").trim();
        if (sub) parts.push(sub);
      }
    }
    return parts;
  }

  /**
   * 將回复文本拆分後逐句顯示為氣泡
   * @param {string} reply - 完整回复文本
   * @param {object} opts - { interval: 間隔ms, duration: 每條顯示ms }
   */
  async function sendReplyAsBubbles(reply, opts = {}) {
    const interval = typeof opts.interval === "number" ? opts.interval : 450;
    const duration = typeof opts.duration === "number" ? opts.duration : 3500;
    const chunks = splitReplyIntoChunks(reply);
    if (!chunks.length) return;

    for (let i = 0; i < chunks.length; i++) {
      await showChatBubble(chunks[i], duration);
      if (i < chunks.length - 1) {
        await new Promise((r) => setTimeout(r, interval));
      }
    }
  }

  return {
    chatBubbles,
    bubbleAnchor,
    registerBubble,
    showChatBubble,
    sendReplyAsBubbles,
  };
}
