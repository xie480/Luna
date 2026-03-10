import { ref } from "vue";
import { gsap } from "gsap";

export function useRhythm() {
  const showSystemAudioListening = ref(false);

  const state = {
    isListening:   false,
    currentStream: null,
    audioContext:  null,
    analyser:      null,
    dataArray:     null,
    source:        null,
    bassLevel:     0,
    midLevel:      0,
    beatDetected:  false,
    beatIntensity: 0,
    bodySway:      0,
    headNod:       0,
    breath:        0.5,
    rafId:         null,

    // 科技感擴展狀態
    glitchJitter:    0,      // 當前抖動偏移量
    glitchTimer:     0,      // 距下次 glitch 的幀計數
    scanPulseTimer:  0,      // 掃描脈衝計時
    scanPulseActive: false,  // 是否正在執行掃描脈衝
    scanPulseValue:  0,      // 脈衝當前值
    beatEcho:        0,      // 節拍回響殘留值
    bootDone:        false,  // 開機序列是否完成
    quantumNoise:    0,      // 量子呼吸噪聲
  };

  const RHYTHM_CFG = {
    fftSize:   512,
    smoothing: 0.80,
    bands: {
      bass: { start: 0,  end: 4  },
      mid:  { start: 5,  end: 30 },
    },
    envelope: {
      bassAttack:  0.18,
      bassRelease: 0.04,
      midAttack:   0.12,
      midRelease:  0.06,
    },
    beatDetection: {
      threshold: 0.10,
      decay:     0.75,
      cooldown:  8,
    },
    body: {
      maxSway:        3.5,
      smoothness:     0.06,
      sinSpeed:       0.03,
      bassMultiplier: 1.2,
    },
    head: {
      nodMaxAngle:   8.0,
      swayMaxAngle:  3.0,
      nodSmoothness: 0.14,
      returnSpeed:   0.05,
      microSway:     0.04,
    },
    breath: {
      baseRate:  0.45,
      amplitude: 0.18,
      speed:     0.25,
    },
    // 科技感配置
    glitch: {
      minInterval:  90,    // 最短間隔幀數（约 1.5s @ 60fps）
      maxInterval:  240,   // 最長間隔幀數（约 4s @ 60fps）
      maxBodyShift: 2.2,   // 身體最大抖動角度
      maxHeadShift: 3.5,   // 頭部最大抖動角度
      decaySpeed:   0.55,  // 抖動衰減速度
      burstCount:   3,     // 每次 glitch 連發次數
    },
    scan: {
      interval:    320,    // 掃描週期（幀）
      duration:    18,     // 掃描持續幀數
      peakAngle:   5.5,    // 掃描峰值角度
      riseSpeed:   0.38,   // 上升速度
      fallSpeed:   0.10,   // 下降速度（慢落像雷達歸位）
    },
    beatEcho: {
      decay:       0.82,   // 回響衰減率（越高拖尾越長）
      threshold:   0.015,  // 低於此值清零
    },
    quantum: {
      noiseSpeed:  0.18,   // 量子噪聲更新速度
      amplitude:   0.028,  // 最大噪聲幅度
    },
  };

  // ── 工具函數 ──────────────────────────────────────────────────

  /** 線性插值 */
  function lerp(a, b, t) {
    return a + (b - a) * t;
  }

  /** 帶符號隨機值 [-1, 1] */
  function randSigned() {
    return (Math.random() - 0.5) * 2;
  }

  /** 偽隨機量子噪聲（基於相位的平滑噪聲） */
  function quantumNoiseSample(phase) {
    // 多個正弦疊加模擬非規則高頻微顫
    return (
      Math.sin(phase * 7.3)  * 0.4 +
      Math.sin(phase * 13.7) * 0.3 +
      Math.sin(phase * 23.1) * 0.2 +
      Math.sin(phase * 41.9) * 0.1
    );
  }

  function detectBeat(bassLevel, prevBass) {
    const rise   = bassLevel - prevBass;
    const isBeat = rise > RHYTHM_CFG.beatDetection.threshold && bassLevel > 0.06;
    return { isBeat, intensity: Math.min(1.0, rise * 2.0) };
  }

  // ── 開機序列 ──────────────────────────────────────────────────

  /**
   * 模型啟動時執行開機序列：
   * 1. 快速小幅抖動（通電感）
   * 2. 身體從輕微偏移平滑歸位
   * 3. 頭部做一次緩慢掃視後回正
   */
  function runBootSequence(core) {
    if (!core) {
      state.bootDone = true;
      return;
    }

    const bootState = {
      bodyX:  -3.5,
      headZ:  -2.0,
      headY:   1.8,
      breath:  0.2,
      glitch:  0,
    };

    const tl = gsap.timeline({
      onComplete() {
        state.bootDone = true;
        console.log("[律動] 開機序列完成，進入正常律動");
      },
    });

    // 通電抖動：快速連續小幅震顫
    tl.to(bootState, {
      duration: 0.06,
      glitch:    1,
      yoyo:      true,
      repeat:    5,
      ease:      "none",
      onUpdate() {
        try {
          const j = randSigned() * 2.8 * bootState.glitch;
          core.setParameterValueById("ParamBodyAngleX", j);
          core.setParameterValueById("ParamAngleZ",     randSigned() * 3.5 * bootState.glitch);
          core.setParameterValueById("ParamAngleY",     randSigned() * 2.0 * bootState.glitch);
          core.setParameterValueById("ParamBreath",     bootState.breath);
        } catch {}
      },
    });

    // 身體從偏移位置平滑滑入
    tl.to(bootState, {
      duration: 1.1,
      bodyX:    0,
      headZ:    0,
      headY:    0,
      breath:   0.5,
      ease:     "power3.out",
      onUpdate() {
        try {
          core.setParameterValueById("ParamBodyAngleX", bootState.bodyX);
          core.setParameterValueById("ParamAngleZ",     bootState.headZ);
          core.setParameterValueById("ParamAngleY",     bootState.headY);
          core.setParameterValueById("ParamBreath",     bootState.breath);
        } catch {}
      },
    });

    // 頭部緩慢掃視（像攝像頭初始化）
    tl.to(bootState, {
      duration: 0.7,
      headY:    4.5,
      ease:     "sine.inOut",
      onUpdate() {
        try {
          core.setParameterValueById("ParamAngleY", bootState.headY);
        } catch {}
      },
    });

    tl.to(bootState, {
      duration: 0.9,
      headY:    -3.5,
      ease:     "sine.inOut",
      onUpdate() {
        try {
          core.setParameterValueById("ParamAngleY", bootState.headY);
        } catch {}
      },
    });

    tl.to(bootState, {
      duration: 0.65,
      headY:    0,
      ease:     "power2.out",
      onUpdate() {
        try {
          core.setParameterValueById("ParamAngleY", bootState.headY);
        } catch {}
      },
    });
  }

  // ── 主律動循環 ────────────────────────────────────────────────

  function startNaturalRhythm(core) {
    if (!state.analyser) {
      console.warn("[律動] analyser 未初始化，無法啟動");
      return;
    }

    const { analyser, dataArray } = state;
    const binCount = analyser.frequencyBinCount;

    let bodyPhase     = Math.random() * Math.PI * 2;
    let headSwayPhase = Math.random() * Math.PI * 2;
    let breathPhase   = Math.random() * Math.PI * 2;
    let quantumPhase  = Math.random() * Math.PI * 2;
    let prevBass      = 0;
    let beatCooldown  = 0;
    let glitchBurst   = 0;  // 剩余連發次數

    // 隨機初始化下次 glitch 計時
    state.glitchTimer    = RHYTHM_CFG.glitch.minInterval +
      Math.floor(Math.random() * (RHYTHM_CFG.glitch.maxInterval - RHYTHM_CFG.glitch.minInterval));
    state.scanPulseTimer = Math.floor(Math.random() * RHYTHM_CFG.scan.interval);

    // 啟動開機序列，序列期間讓 bootDone 為 false
    state.bootDone = false;
    runBootSequence(core);

    function frame() {
      if (!state.isListening) {
        state.rafId = null;
        return;
      }

      analyser.getByteFrequencyData(dataArray);

      // —— 頻段能量 ——
      const { bass, mid } = RHYTHM_CFG.bands;
      let bassSum = 0, midSum = 0;

      for (let i = bass.start; i <= bass.end && i < binCount; i++) bassSum += dataArray[i];
      const bassEnergy = bassSum / ((bass.end - bass.start + 1) * 255);

      for (let i = mid.start; i <= mid.end && i < binCount; i++) midSum += dataArray[i];
      const midEnergy = midSum / ((mid.end - mid.start + 1) * 255);

      // —— 包絡跟蹤 ——
      const { bassAttack, bassRelease, midAttack, midRelease } = RHYTHM_CFG.envelope;
      state.bassLevel += (bassEnergy - state.bassLevel) * (bassEnergy > state.bassLevel ? bassAttack  : bassRelease);
      state.midLevel  += (midEnergy  - state.midLevel)  * (midEnergy  > state.midLevel  ? midAttack   : midRelease);

      // —— 節拍檢測 ——
      if (beatCooldown > 0) {
        beatCooldown--;
      } else {
        const beat = detectBeat(state.bassLevel, prevBass);
        if (beat.isBeat) {
          state.beatDetected  = true;
          state.beatIntensity = beat.intensity;
          beatCooldown        = RHYTHM_CFG.beatDetection.cooldown;
          // 節拍觸發時疊加回響初始值
          state.beatEcho = Math.max(state.beatEcho, beat.intensity * 0.9);
        }
      }

      state.beatIntensity *= RHYTHM_CFG.beatDetection.decay;
      if (state.beatIntensity < 0.02) state.beatDetected = false;

      // —— 節拍回響衰減 ——
      state.beatEcho *= RHYTHM_CFG.beatEcho.decay;
      if (state.beatEcho < RHYTHM_CFG.beatEcho.threshold) state.beatEcho = 0;

      // ════════════════════════════════════════
      //   科技感特效計算（開機序列期間跳過）
      // ════════════════════════════════════════

      // —— Glitch 抖動 ——
      if (state.bootDone) {
        if (glitchBurst > 0) {
          // 連發中：每幀施加新的隨機抖動
          state.glitchJitter = randSigned() * RHYTHM_CFG.glitch.maxBodyShift;
          glitchBurst--;
        } else {
          // 正常狀態：衰減抖動
          state.glitchJitter *= RHYTHM_CFG.glitch.decaySpeed;
          if (Math.abs(state.glitchJitter) < 0.01) state.glitchJitter = 0;

          // 計時觸發新一輪 glitch
          state.glitchTimer--;
          if (state.glitchTimer <= 0) {
            glitchBurst = RHYTHM_CFG.glitch.burstCount;
            state.glitchTimer = RHYTHM_CFG.glitch.minInterval +
              Math.floor(Math.random() * (RHYTHM_CFG.glitch.maxInterval - RHYTHM_CFG.glitch.minInterval));
          }
        }

        // —— 掃描脈衝 ——
        state.scanPulseTimer--;
        if (state.scanPulseTimer <= 0 && !state.scanPulseActive) {
          state.scanPulseActive = true;
          state.scanPulseTimer  = RHYTHM_CFG.scan.interval +
            Math.floor(Math.random() * 80);
        }

        if (state.scanPulseActive) {
          if (state.scanPulseValue < RHYTHM_CFG.scan.peakAngle) {
            state.scanPulseValue = lerp(state.scanPulseValue, RHYTHM_CFG.scan.peakAngle, RHYTHM_CFG.scan.riseSpeed);
            if (RHYTHM_CFG.scan.peakAngle - state.scanPulseValue < 0.1) {
              // 到達峰值，開始下降
            }
          } else {
            state.scanPulseValue = lerp(state.scanPulseValue, 0, RHYTHM_CFG.scan.fallSpeed);
            if (state.scanPulseValue < 0.05) {
              state.scanPulseValue  = 0;
              state.scanPulseActive = false;
            }
          }
        }
      }

      // —— 量子呼吸噪聲 ——
      const qNoise = quantumNoiseSample(quantumPhase) * RHYTHM_CFG.quantum.amplitude;

      const cfg = RHYTHM_CFG;

      // —— 身體極輕微左右晃動（X軸） + glitch 疊加 ——
      const targetBodyX =
        Math.sin(bodyPhase) * (1 + state.bassLevel * cfg.body.bassMultiplier) * cfg.body.maxSway * 0.5
        + (state.bootDone ? state.glitchJitter * 0.8 : 0);

      // —— 頭部點頭（Z軸）：節拍 + 回響 + 掃描脈衝 + glitch ——
      let targetHeadZ;
      if (state.beatDetected) {
        targetHeadZ = state.beatIntensity * cfg.head.nodMaxAngle;
      } else {
        targetHeadZ =
          Math.sin(headSwayPhase * 0.7) * state.midLevel * cfg.head.nodMaxAngle * 0.35
          + state.beatEcho * cfg.head.nodMaxAngle * 0.45   // 回響尾音
          + (state.bootDone ? state.glitchJitter * 0.5 : 0);
      }

      // 掃描脈衝疊加到頭部 Z（模擬快速點頭掃描）
      if (state.bootDone && state.scanPulseActive) {
        targetHeadZ += state.scanPulseValue * 0.6;
      }

      // —— 頭部左右（Y軸）：聯動 + 掃描脈衝橫向版 ——
      let targetHeadY =
        Math.sin(headSwayPhase) * state.bassLevel * cfg.head.swayMaxAngle * 0.4;

      if (state.bootDone && state.scanPulseActive) {
        // 掃描時 Y 軸做一個輕微偏移，模擬雷達橫掃
        targetHeadY += Math.sin(state.scanPulseValue * 0.8) * 1.8;
      }

      // —— 量子呼吸：基礎正弦 + 高頻噪聲疊加 ——
      const targetBreath =
        cfg.breath.baseRate
        + Math.sin(breathPhase) * cfg.breath.amplitude * (1 + state.midLevel * 0.5)
        + qNoise;

      // —— 平滑過渡（lerp） ——
      const headSmooth = state.beatDetected ? cfg.head.nodSmoothness : cfg.head.returnSpeed;

      state.bodySway += (targetBodyX  - state.bodySway) * cfg.body.smoothness;
      state.headNod  += (targetHeadZ  - state.headNod)  * headSmooth;
      state.breath   += (targetBreath - state.breath)   * 0.07;

      // —— 推進相位 ——
      bodyPhase     = (bodyPhase     + cfg.body.sinSpeed  * (1 + state.bassLevel * 0.6)) % (Math.PI * 2);
      headSwayPhase = (headSwayPhase + cfg.head.microSway * (1 + state.midLevel  * 0.4)) % (Math.PI * 2);
      breathPhase   = (breathPhase   + cfg.breath.speed   * 0.04)                         % (Math.PI * 2);
      quantumPhase  = (quantumPhase  + RHYTHM_CFG.quantum.noiseSpeed * 0.05)               % (Math.PI * 2);

      // —— 寫入模型參數（開機序列期間由 GSAP 接管，跳過直寫）——
      if (state.bootDone) {
        try {
          core.setParameterValueById("ParamBodyAngleX", state.bodySway);
          core.setParameterValueById("ParamAngleZ",     state.headNod);
          core.setParameterValueById("ParamAngleY",     targetHeadY);
          core.setParameterValueById("ParamBreath",     state.breath);
        } catch {}
      }

      prevBass    = state.bassLevel;
      state.rafId = requestAnimationFrame(frame);
    }

    state.rafId = requestAnimationFrame(frame);
    console.log("[律動] 自然律動循環啟動");
  }

  // ── 停止循環 ──────────────────────────────────────────────────

  function stopRhythmLoop() {
    if (state.rafId) {
      cancelAnimationFrame(state.rafId);
      state.rafId = null;
    }
  }

  // ── 平滑重置（科技感斷電版） ────────────────────────────────────

  /**
   * 重置分三段：
   * 1. 斷電抖動：快速小幅震顫，模擬系統關閉瞬間
   * 2. 信號衰減：參數快速滑向中間值，帶輕微過沖
   * 3. 最終歸位：緩慢、優雅地收斂到靜止狀態
   */
  function smoothReset(core) {
    if (!core) return;

    const s = {
      bodyX:  core.getParameterValueById("ParamBodyAngleX") || 0,
      headZ:  core.getParameterValueById("ParamAngleZ")     || 0,
      headY:  core.getParameterValueById("ParamAngleY")     || 0,
      breath: core.getParameterValueById("ParamBreath")     || 0.5,
      noise:  1,
    };

    const tl = gsap.timeline({
      onComplete() {
        console.log("[律動] 模型已平滑重置（科技感斷電）");
      },
    });

    // 第一段：斷電抖動（極快，模擬信號錯誤）
    tl.to(s, {
      duration: 0.05,
      noise:    0,
      yoyo:     true,
      repeat:   4,
      ease:     "none",
      onUpdate() {
        try {
          const j = randSigned() * 3.2 * s.noise;
          core.setParameterValueById("ParamBodyAngleX", s.bodyX + j);
          core.setParameterValueById("ParamAngleZ",     s.headZ + randSigned() * 4.0 * s.noise);
          core.setParameterValueById("ParamAngleY",     s.headY + randSigned() * 2.5 * s.noise);
          core.setParameterValueById("ParamBreath",     s.breath);
        } catch {}
      },
    });

    // 第二段：信號衰減（快速滑向初始值，略帶過沖）
    tl.to(s, {
      duration: 0.45,
      bodyX:    0.8,   // 略過沖
      headZ:    1.2,
      headY:   -0.6,
      breath:   0.55,
      ease:     "power2.out",
      onUpdate() {
        try {
          core.setParameterValueById("ParamBodyAngleX", s.bodyX);
          core.setParameterValueById("ParamAngleZ",     s.headZ);
          core.setParameterValueById("ParamAngleY",     s.headY);
          core.setParameterValueById("ParamBreath",     s.breath);
        } catch {}
      },
    });

    // 第三段：最終優雅歸位
    tl.to(s, {
      duration: 1.0,
      bodyX:    0,
      headZ:    0,
      headY:    0,
      breath:   0.5,
      ease:     "sine.out",
      onUpdate() {
        try {
          core.setParameterValueById("ParamBodyAngleX", s.bodyX);
          core.setParameterValueById("ParamAngleZ",     s.headZ);
          core.setParameterValueById("ParamAngleY",     s.headY);
          core.setParameterValueById("ParamBreath",     s.breath);
        } catch {}
      },
    });
  }

  // ── 系統音頻開關 ──────────────────────────────────────────────

  async function toggleSystemAudio(core, trackingEnabled) {
    if (showSystemAudioListening.value) {
      stopSystemAudioListening(core, trackingEnabled);
      return;
    }

    if (trackingEnabled.value) trackingEnabled.value = false;

    try {
      const stream = await navigator.mediaDevices.getUserMedia({
        audio: { mandatory: { chromeMediaSource: "desktop" } },
        video: { mandatory: { chromeMediaSource: "desktop" } },
      });

      state.currentStream = stream;

      if (!state.audioContext) {
        state.audioContext = new (window.AudioContext || window.webkitAudioContext)();
      }

      const analyser = state.audioContext.createAnalyser();
      analyser.fftSize               = RHYTHM_CFG.fftSize;
      analyser.smoothingTimeConstant = RHYTHM_CFG.smoothing;
      state.analyser  = analyser;
      state.dataArray = new Uint8Array(analyser.frequencyBinCount);

      state.source = state.audioContext.createMediaStreamSource(stream);
      state.source.connect(analyser);

      state.isListening              = true;
      showSystemAudioListening.value = true;

      startNaturalRhythm(core);
      console.log("[律動] 已連接到系統音頻");
    } catch (err) {
      console.error("[律動] 音頻初始化失敗:", err);
    }
  }

  function stopSystemAudioListening(core, trackingEnabled) {
    if (!trackingEnabled.value) trackingEnabled.value = true;

    state.isListening              = false;
    showSystemAudioListening.value = false;

    stopRhythmLoop();
    smoothReset(core);

    if (state.currentStream) {
      state.currentStream.getTracks().forEach((t) => t.stop());
      state.currentStream = null;
    }

    if (state.source) {
      try { state.source.disconnect(); } catch {}
      state.source = null;
    }

    if (state.audioContext) {
      state.audioContext.close().catch(() => {});
      state.audioContext = null;
      state.analyser     = null;
    }

    console.log("[律動] 已斷開音頻連接");
  }

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