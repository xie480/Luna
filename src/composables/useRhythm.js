import { ref } from "vue";
import { gsap } from "gsap";

/**
 * 音頻律動 composable
 * 負責系統音頻捕獲、節奏分析、Live2D 模型律動驅動
 */
export function useRhythm() {
  const showSystemAudioListening = ref(false);

  // 內部狀態（非響應式，性能優先）
  const state = {
    isListening: false,
    currentStream: null,
    audioContext: null,
    analyser: null,
    dataArray: null,
    source: null,
    bassLevel: 0,
    midLevel: 0,
    beatDetected: false,
    beatIntensity: 0,
    bodySway: 0,
    headNod: 0,
    breath: 0.5,
    rafId: null,
  };

  // 律動參數配置
  const RHYTHM_CFG = {
    fftSize: 256,
    smoothing: 0.7,
    bands: {
      bass: { start: 0, end: 3 },
      lowMid: { start: 4, end: 12 },
      mid: { start: 13, end: 40 },
    },
    envelope: {
      bassAttack: 0.15,
      bassRelease: 0.05,
      midAttack: 0.1,
      midRelease: 0.08,
    },
    beatDetection: {
      threshold: 0.15,
      decay: 0.85,
      cooldown: 8,
    },
    body: {
      baseFrequency: 0.8,
      bassMultiplier: 2.5,
      maxSway: 6.0,
      smoothness: 0.15,
      leanFactor: 0.3,
    },
    head: {
      nodIntensity: 25,
      nodSmoothness: 0.3,
      returnSpeed: 0.05,
      microNods: 2,
    },
    breath: {
      baseRate: 0.4,
      amplitude: 0.15,
      speed: 0.3,
    },
    mouth: {
      enabled: true,
      sensitivity: 0.4,
      minOpen: 0.05,
      maxOpen: 100,
    },
  };

  /** 檢測節拍（基於能量上升幅度） */
  function detectBeat(bassLevel, prevBass) {
    const rise = bassLevel - prevBass;
    const isBeat = rise > RHYTHM_CFG.beatDetection.threshold && bassLevel > 0.1;
    return { isBeat, intensity: Math.min(1.0, rise * 2) };
  }

  /**
   * 啟動自然律動主循環
   * @param {object} core - Live2D coreModel 實例
   */
  function startNaturalRhythm(core) {
    if (`!state.analyser`) return;

    const { analyser, dataArray } = state;
    const binCount = analyser.frequencyBinCount;

    // 各正弦波相位（用於自然擺動）
    let bodyPhase = Math.random() * Math.PI * 2;
    let headPhase = Math.random() * Math.PI * 2;
    let breathPhase = Math.random() * Math.PI * 2;
    let prevBass = 0;
    let beatCooldown = 0;
    let lastBeatTime = 0;

    function frame() {
      if (!state.isListening) {
        state.rafId = null;
        return;
      }

      const now = performance.now();
      const time = now / 1000;

      // 讀取頻譜數據
      analyser.getByteFrequencyData(dataArray);

      // 計算各頻段能量（0-1 範圍）
      const { bass, lowMid, mid } = RHYTHM_CFG.bands;
      let bassSum = 0, midSum = 0;

      for (let i = bass.start; i <= bass.end && i < binCount; i++) bassSum += dataArray[i];
      const bassEnergy = bassSum / ((bass.end - bass.start + 1) * 255);

      for (let i = lowMid.start; i <= mid.end && i < binCount; i++) midSum += dataArray[i];
      const midEnergy = midSum / ((mid.end - lowMid.start + 1) * 255);

      // 包絡跟蹤（低頻快速 attack，慢速 release）
      if (bassEnergy > state.bassLevel) {
        state.bassLevel += (bassEnergy - state.bassLevel) * RHYTHM_CFG.envelope.bassAttack;
      } else {
        state.bassLevel += (bassEnergy - state.bassLevel) * RHYTHM_CFG.envelope.bassRelease;
      }
      if (midEnergy > state.midLevel) {
        state.midLevel += (midEnergy - state.midLevel) * RHYTHM_CFG.envelope.midAttack;
      } else {
        state.midLevel += (midEnergy - state.midLevel) * RHYTHM_CFG.envelope.midRelease;
      }

      // 節拍檢測（帶冷卻時間防抖）
      if (beatCooldown > 0) {
        beatCooldown--;
      } else {
        const beat = detectBeat(state.bassLevel, prevBass);
        if (beat.isBeat) {
          state.beatDetected = true;
          state.beatIntensity = beat.intensity;
          beatCooldown = RHYTHM_CFG.beatDetection.cooldown;
          lastBeatTime = time;
          bodyPhase += (Math.random() - 0.5) * 0.5;
          headPhase += (Math.random() - 0.5) * 0.3;
        }
      }

      // 衰減節拍強度
      state.beatIntensity *= RHYTHM_CFG.beatDetection.decay;
      if (state.beatIntensity < 0.05) state.beatDetected = false;

      // 計算身體搖擺目標值
      const baseSway = Math.sin(bodyPhase) * state.bassLevel * RHYTHM_CFG.body.maxSway;
      const beatSway = state.beatIntensity * RHYTHM_CFG.body.maxSway * Math.sin(bodyPhase * 3) * 0.5;
      const targetBodyX = baseSway + beatSway;
      const targetBodyZ = state.midLevel * RHYTHM_CFG.body.leanFactor;

      // 計算頭部點頭目標值
      let targetHeadZ = state.beatDetected
        ? -state.beatIntensity * RHYTHM_CFG.head.nodIntensity * 600
        : Math.sin(headPhase * 2) * state.midLevel * RHYTHM_CFG.head.microNods;

      // 計算呼吸目標值
      const targetBreath = RHYTHM_CFG.breath.baseRate + Math.sin(breathPhase) * RHYTHM_CFG.breath.amplitude;

      // 平滑過渡（lerp）
      state.bodySway += (targetBodyX - state.bodySway) * RHYTHM_CFG.body.smoothness;
      const headSmooth = state.beatDetected ? RHYTHM_CFG.head.nodSmoothness : RHYTHM_CFG.head.returnSpeed;
      state.headNod += (targetHeadZ - state.headNod) * headSmooth;
      state.breath += (targetBreath - state.breath) * 0.1;

      // 推進相位
      bodyPhase = (bodyPhase + 0.05 * (1 + state.bassLevel)) % (Math.PI * 2);
      headPhase = (headPhase + 0.03 * (1 + state.midLevel * 0.5)) % (Math.PI * 2);
      breathPhase = (breathPhase + RHYTHM_CFG.breath.speed * 0.05) % (Math.PI * 2);

      // 僅在參數變化超過閾值時才寫入模型，避免無意義的每幀寫入
      try {
        core.setParameterValueById("ParamBodyAngleX", state.bodySway);
        core.setParameterValueById("ParamBodyAngleZ", targetBodyZ);
        core.setParameterValueById("ParamAngleZ", state.headNod);
        core.setParameterValueById("ParamBreath", state.breath);

        if (RHYTHM_CFG.mouth.enabled) {
          const mouthOpen = RHYTHM_CFG.mouth.minOpen + state.midLevel * RHYTHM_CFG.mouth.sensitivity;
          core.setParameterValueById("ParamMouthOpenY", Math.min(RHYTHM_CFG.mouth.maxOpen, mouthOpen));
        }

        const headRotY = Math.sin(bodyPhase * 0.7) * state.bassLevel * 3;
        core.setParameterValueById("ParamAngleY", headRotY);
      } catch {}

      prevBass = state.bassLevel;
      state.rafId = requestAnimationFrame(frame);
    }

    state.rafId = requestAnimationFrame(frame);
    console.log("[律動] 自然律動循環啟動");
  }

  /** 停止律動循環 */
  function stopRhythmLoop() {
    if (state.rafId) {
      cancelAnimationFrame(state.rafId);
      state.rafId = null;
    }
  }

  /**
   * 平滑重置模型參數到初始狀態
   * @param {object} core - Live2D coreModel 實例
   */
  function smoothReset(core) {
    // 空值保護
    if (!core) return;

    const resetState = {
      bodyX: core.getParameterValueById("ParamBodyAngleX") || 0,
      bodyZ: core.getParameterValueById("ParamBodyAngleZ") || 0,
      headZ: core.getParameterValueById("ParamAngleZ") || 0,
      breath: core.getParameterValueById("ParamBreath") || 0.5,
    };

    gsap.to(resetState, {
      duration: 0.8,
      bodyX: 0,
      bodyZ: 0,
      headZ: 0,
      breath: 0.5,
      ease: "power2.out",
      onUpdate() {
        try {
          core.setParameterValueById("ParamBodyAngleX", resetState.bodyX);
          core.setParameterValueById("ParamBodyAngleZ", resetState.bodyZ);
          core.setParameterValueById("ParamAngleZ", resetState.headZ);
          core.setParameterValueById("ParamBreath", resetState.breath);
        } catch {}
      },
      onComplete() {
        console.log("[律動] 模型已重置");
      },
    });
  }

  /**
   * 切換系統音頻監聽
   * @param {object} core - Live2D coreModel 實例
   * @param {object} trackingEnabled - ref，視線追蹤開關
   */
  async function toggleSystemAudio(core, trackingEnabled) {
    if (showSystemAudioListening.value) {
      stopSystemAudioListening(core, trackingEnabled);
      return;
    }

    // 開啟律動時關閉視線追蹤，避免衝突
    if (trackingEnabled.value) trackingEnabled.value = false;

    try {
      const stream = await navigator.mediaDevices.getUserMedia({
        audio: { mandatory: { chromeMediaSource: "desktop" } },
        video: { mandatory: { chromeMediaSource: "desktop" } },
      });

      state.currentStream = stream;

      // 複用已有 AudioContext
      if (!state.audioContext) {
        state.audioContext = new (window.AudioContext || window.webkitAudioContext)();
      }

      const analyser = state.audioContext.createAnalyser();
      analyser.fftSize = RHYTHM_CFG.fftSize;
      analyser.smoothingTimeConstant = RHYTHM_CFG.smoothing;
      state.analyser = analyser;
      state.dataArray = new Uint8Array(analyser.frequencyBinCount);

      state.source = state.audioContext.createMediaStreamSource(stream);
      state.source.connect(analyser);

      state.isListening = true;
      showSystemAudioListening.value = true;

      startNaturalRhythm(core);
      console.log("[律動] 已連接到系統音頻");
    } catch (err) {
      console.error("[律動] 音頻初始化失敗:", err);
    }
  }

  /**
   * 停止系統音頻監聽並釋放所有資源
   * @param {object} core - Live2D coreModel 實例
   * @param {object} trackingEnabled - ref，視線追蹤開關
   */
  function stopSystemAudioListening(core, trackingEnabled) {
    // 恢復視線追蹤
    if (!trackingEnabled.value) trackingEnabled.value = true;

    state.isListening = false;
    showSystemAudioListening.value = false;

    stopRhythmLoop();
    smoothReset(core);

    // 釋放媒體流
    if (state.currentStream) {
      state.currentStream.getTracks().forEach((t) => t.stop());
      state.currentStream = null;
    }

    // 斷開音頻源
    if (state.source) {
      try { state.source.disconnect(); } catch {}
      state.source = null;
    }

    // 關閉 AudioContext，釋放系統資源
    if (state.audioContext) {
      state.audioContext.close().catch(() => {});
      state.audioContext = null;
      state.analyser = null;
    }

    console.log("[律動] 已斷開音頻連接");
  }

  /** 組件卸載時強制清理所有資源 */
  function dispose(core, trackingEnabled) {
    if (state.isListening) {
      stopSystemAudioListening(core, trackingEnabled);
    } else {
      stopRhythmLoop();
    }
  }

  return {
    showSystemAudioListening,
    toggleSystemAudio,
    dispose,
  };
}
