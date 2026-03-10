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
    smoothing: 0.86, // 更平滑的能量曲線，減少抖動感
    bands: {
      bass: { start: 0,  end: 4  },
      mid:  { start: 5,  end: 30 },
    },
    envelope: {
      // 更柔和的包絡，避免瞬時大幅度抖動
      bassAttack:  0.12,
      bassRelease: 0.05,
      midAttack:   0.10,
      midRelease:  0.07,
    },
    beatDetection: {
      // 提高觸發門檻 & 降低頻率，只在較明顯的鼓點時點頭
      threshold: 0.14,
      decay:     0.80,
      cooldown:  10,
    },
    body: {
      maxSway:        2.0,  // 身體左右最大擺幅（原 3.5）
      smoothness:     0.08, // 更平滑的插值
      sinSpeed:       0.025,
      bassMultiplier: 1.0,  // 對低頻反應略微收斂
    },
    head: {
      nodMaxAngle:   5.5,   // 點頭最大角度（原 8.0）
      swayMaxAngle:  2.0,   // 左右搖頭幅度（原 3.0）
      nodSmoothness: 0.16,
      returnSpeed:   0.07,
      microSway:     0.03,
    },
    breath: {
      baseRate:  0.48,
      amplitude: 0.14, // 稍微減小呼吸起伏
      speed:     0.22,
    },
    glitch: {
      // 將 glitch 降為偶爾的細微抖動，避免搶戲
      minInterval:  260,
      maxInterval:  520,
      maxBodyShift: 1.0,
      maxHeadShift: 1.8,
      decaySpeed:   0.40,
      burstCount:   2,
    },
    scan: {
      // 掃描脈衝變得更少、更輕
      interval:    420,
      duration:    18,
      peakAngle:   3.0,
      riseSpeed:   0.30,
      fallSpeed:   0.12,
    },
    beatEcho: {
      decay:       0.86,
      threshold:   0.012,
    },
    quantum: {
      noiseSpeed:  0.16,
      amplitude:   0.020,
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

    // bootState 不再包含 glitch 屬性，避免補間歸零導致黑閃
    const bootState = {
      bodyX:  -3.5,
      headZ:  -2.0,
      headY:   1.8,
      breath:  0.2,
    };

    const tl = gsap.timeline({
      onComplete() {
        state.bootDone = true;
        console.log("[律動] 開機序列完成，進入正常律動");
      },
    });

    // 通電抖動：固定幅度疊加在靜止位置（0）上，不補間到 0，不會黑閃
    // 使用 repeat + onRepeat 讓幅度逐漸縮小，模擬信號穩定感
    let burstStep = 0;
    const burstTotal = 6;
    tl.to(bootState, {
      duration: 0.07,
      repeat:   burstTotal - 1,
      ease:     "none",
      onRepeat() {
        burstStep++;
      },
      onUpdate() {
        try {
          // 幅度從 1.0 線性衰減到 0.2，始終有殘留偏移，不歸零
          const amp = 1.0 - (burstStep / burstTotal) * 0.8;
          core.setParameterValueById("ParamBodyAngleX", randSigned() * 2.5 * amp);
          core.setParameterValueById("ParamAngleZ",     randSigned() * 3.0 * amp);
          core.setParameterValueById("ParamAngleY",     randSigned() * 1.8 * amp);
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

  // ── 平滑重置（透明背景安全版，無黑閃） ───────────────────────────

  function smoothReset(core) {
    if (!core) return;

    const startBodyX  = core.getParameterValueById("ParamBodyAngleX") || 0;
    const startHeadZ  = core.getParameterValueById("ParamAngleZ")     || 0;
    const startHeadY  = core.getParameterValueById("ParamAngleY")     || 0;
    const startBreath = core.getParameterValueById("ParamBreath")     || 0.5;

    const s = {
      bodyX:  startBodyX,
      headZ:  startHeadZ,
      headY:  startHeadY,
      breath: startBreath,
      glitchAmp: 1.0,
    };

    const tl = gsap.timeline({
      onComplete() {
        console.log("[律動] 模型已平滑重置（透明背景安全斷電）");
      },
    });

    // 第一段：微抖動，幅度固定疊加在起始值上，不補間到 0，不黑閃
    tl.to(s, {
      duration:  0.08,
      glitchAmp: 0.3,
      yoyo:      true,
      repeat:    5,
      ease:      "none",
      onUpdate() {
        try {
          core.setParameterValueById("ParamBodyAngleX", startBodyX + randSigned() * 1.4 * s.glitchAmp);
          core.setParameterValueById("ParamAngleZ",     startHeadZ + randSigned() * 2.0 * s.glitchAmp);
          core.setParameterValueById("ParamAngleY",     startHeadY + randSigned() * 1.2 * s.glitchAmp);
          core.setParameterValueById("ParamBreath",     startBreath);
        } catch {}
      },
    });

    // 第二段：以起始值為基準做輕微相對過沖
    const overshootBodyX  = startBodyX  * 0.15;
    const overshootHeadZ  = startHeadZ  * 0.15;
    const overshootHeadY  = startHeadY  * (-0.2);
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

    // 第三段：緩緩歸位，透明背景下自然收斂
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

  // ── 系统音頻開關 ──────────────────────────────────────────────

  async function toggleSystemAudio(core, trackingEnabled) {
    if (showSystemAudioListening.value) {
      stopSystemAudioListening(core, trackingEnabled);
      return;
    }

    if (trackingEnabled.value) trackingEnabled.value = false;

    try {
      // 尝试获取系统音频，如果失败则尝试获取麦克风音频
      let stream;
      try {
        // 尝试使用getDisplayMedia获取系统音频（Chrome特有）
        if (navigator.mediaDevices && navigator.mediaDevices.getDisplayMedia) {
          stream = await navigator.mediaDevices.getDisplayMedia({
            video: false,  // 只获取音频
            audio: true
          });
        } else {
          // 如果getDisplayMedia不可用，则使用getUserMedia获取麦克风音频
          stream = await navigator.mediaDevices.getUserMedia({
            audio: true,
            video: false
          });
        }
      } catch (displayErr) {
        console.warn("[律動] 获取系统音频失败，尝试获取麦克风音频:", displayErr);
        // 如果系统音频获取失败，尝试获取麦克风音频
        stream = await navigator.mediaDevices.getUserMedia({
          audio: true,
          video: false
        });
      }

      state.currentStream = stream;

      if (!state.audioContext) {
        state.audioContext = new (window.AudioContext || window.webkitAudioContext)();
      }

      // 检查音频上下文状态，如果处于suspended状态则恢复
      if (state.audioContext.state === 'suspended') {
        await state.audioContext.resume();
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
      console.log("[律動] 已連接到音頻源");
    } catch (err) {
      console.error("[律動] 音頻初始化失敗:", err);
      alert("音频访问失败，请检查浏览器权限设置或尝试使用其他浏览器。");
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