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
    glitchJitter:    0,
    glitchTimer:     0,
    scanPulseTimer:  0,
    scanPulseActive: false,
    scanPulseValue:  0,
    beatEcho:        0,
    bootDone:        false,
    quantumNoise:    0,
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
    glitch: {
      minInterval:  90,
      maxInterval:  240,
      maxBodyShift: 2.2,
      maxHeadShift: 3.5,
      decaySpeed:   0.55,
      burstCount:   3,
    },
    scan: {
      interval:    320,
      duration:    18,
      peakAngle:   5.5,
      riseSpeed:   0.38,
      fallSpeed:   0.10,
    },
    beatEcho: {
      decay:       0.82,
      threshold:   0.015,
    },
    quantum: {
      noiseSpeed:  0.18,
      amplitude:   0.028,
    },
  };

  // ── 工具函數 ──────────────────────────────────────────────────

  function lerp(a, b, t) {
    return a + (b - a) * t;
  }

  function randSigned() {
    return (Math.random() - 0.5) * 2;
  }

  function quantumNoiseSample(phase) {
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

    // 通電抖動
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

    // 身體從偏移平滑滑入
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
    let glitchBurst   = 0;

    state.glitchTimer    = RHYTHM_CFG.glitch.minInterval +
      Math.floor(Math.random() * (RHYTHM_CFG.glitch.maxInterval - RHYTHM_CFG.glitch.minInterval));
    state.scanPulseTimer = Math.floor(Math.random() * RHYTHM_CFG.scan.interval);

    state.bootDone = false;
    runBootSequence(core);

    function frame() {
      if (!state.isListening) {
        state.rafId = null;
        return;
      }

      analyser.getByteFrequencyData(dataArray);

      const { bass, mid } = RHYTHM_CFG.bands;
      let bassSum = 0, midSum = 0;

      for (let i = bass.start; i <= bass.end && i < binCount; i++) bassSum += dataArray[i];
      const bassEnergy = bassSum / ((bass.end - bass.start + 1) * 255);

      for (let i = mid.start; i <= mid.end && i < binCount; i++) midSum += dataArray[i];
      const midEnergy = midSum / ((mid.end - mid.start + 1) * 255);

      const { bassAttack, bassRelease, midAttack, midRelease } = RHYTHM_CFG.envelope;
      state.bassLevel += (bassEnergy - state.bassLevel) * (bassEnergy > state.bassLevel ? bassAttack  : bassRelease);
      state.midLevel  += (midEnergy  - state.midLevel)  * (midEnergy  > state.midLevel  ? midAttack   : midRelease);

      if (beatCooldown > 0) {
        beatCooldown--;
      } else {
        const beat = detectBeat(state.bassLevel, prevBass);
        if (beat.isBeat) {
          state.beatDetected  = true;
          state.beatIntensity = beat.intensity;
          beatCooldown        = RHYTHM_CFG.beatDetection.cooldown;
          state.beatEcho = Math.max(state.beatEcho, beat.intensity * 0.9);
        }
      }

      state.beatIntensity *= RHYTHM_CFG.beatDetection.decay;
      if (state.beatIntensity < 0.02) state.beatDetected = false;

      state.beatEcho *= RHYTHM_CFG.beatEcho.decay;
      if (state.beatEcho < RHYTHM_CFG.beatEcho.threshold) state.beatEcho = 0;

      if (state.bootDone) {
        if (glitchBurst > 0) {
          state.glitchJitter = randSigned() * RHYTHM_CFG.glitch.maxBodyShift;
          glitchBurst--;
        } else {
          state.glitchJitter *= RHYTHM_CFG.glitch.decaySpeed;
          if (Math.abs(state.glitchJitter) < 0.01) state.glitchJitter = 0;

          state.glitchTimer--;
          if (state.glitchTimer <= 0) {
            glitchBurst = RHYTHM_CFG.glitch.burstCount;
            state.glitchTimer = RHYTHM_CFG.glitch.minInterval +
              Math.floor(Math.random() * (RHYTHM_CFG.glitch.maxInterval - RHYTHM_CFG.glitch.minInterval));
          }
        }

        state.scanPulseTimer--;
        if (state.scanPulseTimer <= 0 && !state.scanPulseActive) {
          state.scanPulseActive = true;
          state.scanPulseTimer  = RHYTHM_CFG.scan.interval +
            Math.floor(Math.random() * 80);
        }

        if (state.scanPulseActive) {
          if (state.scanPulseValue < RHYTHM_CFG.scan.peakAngle) {
            state.scanPulseValue = lerp(state.scanPulseValue, RHYTHM_CFG.scan.peakAngle, RHYTHM_CFG.scan.riseSpeed);
          } else {
            state.scanPulseValue = lerp(state.scanPulseValue, 0, RHYTHM_CFG.scan.fallSpeed);
            if (state.scanPulseValue < 0.05) {
              state.scanPulseValue  = 0;
              state.scanPulseActive = false;
            }
          }
        }
      }

      const qNoise = quantumNoiseSample(quantumPhase) * RHYTHM_CFG.quantum.amplitude;
      const cfg = RHYTHM_CFG;

      const targetBodyX =
        Math.sin(bodyPhase) * (1 + state.bassLevel * cfg.body.bassMultiplier) * cfg.body.maxSway * 0.5
        + (state.bootDone ? state.glitchJitter * 0.8 : 0);

      let targetHeadZ;
      if (state.beatDetected) {
        targetHeadZ = state.beatIntensity * cfg.head.nodMaxAngle;
      } else {
        targetHeadZ =
          Math.sin(headSwayPhase * 0.7) * state.midLevel * cfg.head.nodMaxAngle * 0.35
          + state.beatEcho * cfg.head.nodMaxAngle * 0.45
          + (state.bootDone ? state.glitchJitter * 0.5 : 0);
      }

      if (state.bootDone && state.scanPulseActive) {
        targetHeadZ += state.scanPulseValue * 0.6;
      }

      let targetHeadY =
        Math.sin(headSwayPhase) * state.bassLevel * cfg.head.swayMaxAngle * 0.4;

      if (state.bootDone && state.scanPulseActive) {
        targetHeadY += Math.sin(state.scanPulseValue * 0.8) * 1.8;
      }

      const targetBreath =
        cfg.breath.baseRate
        + Math.sin(breathPhase) * cfg.breath.amplitude * (1 + state.midLevel * 0.5)
        + qNoise;

      const headSmooth = state.beatDetected ? cfg.head.nodSmoothness : cfg.head.returnSpeed;

      state.bodySway += (targetBodyX  - state.bodySway) * cfg.body.smoothness;
      state.headNod  += (targetHeadZ  - state.headNod)  * headSmooth;
      state.breath   += (targetBreath - state.breath)   * 0.07;

      bodyPhase     = (bodyPhase     + cfg.body.sinSpeed  * (1 + state.bassLevel * 0.6)) % (Math.PI * 2);
      headSwayPhase = (headSwayPhase + cfg.head.microSway * (1 + state.midLevel  * 0.4)) % (Math.PI * 2);
      breathPhase   = (breathPhase   + cfg.breath.speed   * 0.04)                         % (Math.PI * 2);
      quantumPhase  = (quantumPhase  + RHYTHM_CFG.quantum.noiseSpeed * 0.05)               % (Math.PI * 2);

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

  // ── 平滑重置（科技感斷電版，無黑閃） ─────────────────────────────
  //
  // 背景透明時不可出現參數硬跳或幅度歸零瞬間切斷。
  // 改動要點：
  //   第一段：抖動疊加在當前讀取值之上，強度固定不補間到 0，
  //           讓抖動本身有幅度但不會因 noise→0 造成硬跳。
  //   第二段：以當前值為基準做極小過沖（±0.5 以內），
  //           不硬編碼絕對角度。
  //   第三段：緩緩 ease 歸零，透明背景下自然消失。

  function smoothReset(core) {
    if (!core) return;

    // 記錄重置開始時的當前參數值
    const startBodyX  = core.getParameterValueById("ParamBodyAngleX") || 0;
    const startHeadZ  = core.getParameterValueById("ParamAngleZ")     || 0;
    const startHeadY  = core.getParameterValueById("ParamAngleY")     || 0;
    const startBreath = core.getParameterValueById("ParamBreath")     || 0.5;

    const s = {
      bodyX:  startBodyX,
      headZ:  startHeadZ,
      headY:  startHeadY,
      breath: startBreath,
      // glitchAmp 控制抖動疊加幅度，從 1 衰減到 0
      // 但我們用它乘以一個固定小值，不會造成大幅跳變
      glitchAmp: 1.0,
    };

    const tl = gsap.timeline({
      onComplete() {
        console.log("[律動] 模型已平滑重置（透明背景安全斷電）");
      },
    });

    // 第一段：微抖動（幅度小且固定，疊加在當前值上，不歸零）
    // glitchAmp 1→0.3，幅度受控，不出現硬跳
    tl.to(s, {
      duration:  0.08,
      glitchAmp: 0.3,
      yoyo:      true,
      repeat:    5,
      ease:      "none",
      onUpdate() {
        try {
          // 疊加偏移在起始值上，不替換整個值
          core.setParameterValueById("ParamBodyAngleX", startBodyX + randSigned() * 1.4 * s.glitchAmp);
          core.setParameterValueById("ParamAngleZ",     startHeadZ + randSigned() * 2.0 * s.glitchAmp);
          core.setParameterValueById("ParamAngleY",     startHeadY + randSigned() * 1.2 * s.glitchAmp);
          core.setParameterValueById("ParamBreath",     startBreath);
        } catch {}
      },
    });

    // 第二段：以當前值為基準做輕微過沖（相對偏移，非絕對角度）
    const overshootBodyX  = startBodyX  * 0.15;   // 往反方向 15% 的過沖
    const overshootHeadZ  = startHeadZ  * 0.15;
    const overshootHeadY  = startHeadY  * (-0.2); // 頭部輕微反彈
    const overshootBreath = startBreath > 0.5
      ? startBreath - 0.04
      : startBreath + 0.04;

    tl.to(s, {
      duration: 0.35,
      bodyX:    overshootBodyX,
      headZ:    overshootHeadZ,
      headY:    overshootHeadY,
      breath:   overshootBreath,
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

    // 第三段：最終優雅歸位至靜止，透明背景下自然收斂
    tl.to(s, {
      duration: 1.1,
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